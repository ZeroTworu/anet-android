package org.alco.anet

import android.content.Intent
import android.net.VpnService
import android.net.IpPrefix
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.os.Build
import android.os.Build.*
import java.net.InetAddress
import android.content.pm.ServiceInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler      // <-- Добавьте этот импорт
import android.os.Looper       // <-- Добавьте этот импорт

class ANetVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var allowedAppsCache: List<String> = emptyList()

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ТРЕБОВАНИЕ: Создаем Handler для перенаправления тяжелых вызовов на главный поток Android
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }
        const val ACTION_CONNECT = "org.alco.anet.CONNECT"
        const val ACTION_STOP = "org.alco.anet.STOP"
    }

    // Native-методы
    private external fun initLogger()
    private external fun connectVpn(config: String, selectedServer: String)
    private external fun stopVpn()

    override fun onCreate() {
        super.onCreate()
        initLogger()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            Log.i("ANet", "Received STOP Intent")
            Thread {
                stopVpn()
                stopVpnInternal()
            }.start()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "ANetChannel")
            .setContentTitle("ANet VPN")
            .setContentText("Connecting...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
            startForeground(1337, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1337, notification)
        }

        val config = intent?.getStringExtra("CONFIG") ?: return START_NOT_STICKY
        val selectedServer = intent?.getStringExtra("SELECTED_SERVER") ?: ""

        allowedAppsCache = intent?.getStringArrayListExtra("ALLOWED_APPS") ?: emptyList()

        registerNetworkCallback()

        Thread { connectVpn(config, selectedServer) }.start()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "ANetChannel", "ANet VPN Status", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback == null) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.i("ANet", "Active physical network switched to: $network")

                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        Log.i("ANet", "Ignoring VPN network callback to prevent infinite routing loop")
                        return
                    }

                    try {
                        setUnderlyingNetworks(arrayOf(network))
                    } catch (e: Exception) {
                        Log.e("ANet", "Failed to set underlying networks: ${e.message}")
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.i("ANet", "Physical network lost: $network")
                    try {
                        setUnderlyingNetworks(null)
                    } catch (e: Exception) {
                        Log.e("ANet", "Failed to clear underlying networks: ${e.message}")
                    }
                }
            }
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
            } catch (e: Exception) {
                Log.e("ANet", "Failed to register default network callback", e)
            }
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e("ANet", "Failed to unregister network callback", e)
            }
            networkCallback = null
        }
    }

    private fun closeTun() {
        if (vpnInterface != null) {
            Log.i("ANet", "Closing TUN to prevent routing blackhole during reconnect...")
            try {
                vpnInterface!!.close()
            } catch (e: Exception) {
                Log.e("ANet", "Failed to close vpnInterface", e)
            }
            vpnInterface = null
        }
    }

    // --- Метод вызываемый из RUST ---
    fun configureTun(
        ip: String,
        prefix: Int,
        mtu: Int,
        includeRoutes: String,
        excludeRoutes: String,
        fallbackRoutes: String,
        dnsServers: String
    ): Int {
        Log.i("ANet", "Configuring TUN...")

        closeTun()

        val builder = Builder()
        builder.addAddress(ip, prefix)
        builder.setMtu(mtu)
        builder.setSession("ANet VPN")

        if (allowedAppsCache.isNotEmpty()) {
            Log.i("ANet", "Per-App Mode ON. Allowing ${allowedAppsCache.size} specific apps.")
            onStatusChanged("App Split: Only specific apps use VPN")
            for (pkg in allowedAppsCache) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: Exception) {
                    Log.e("ANet", "Package $pkg not found, skipping", e)
                }
            }
        } else {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) { Log.e("ANet", "$e", e)}
        }

        if (dnsServers.isNotEmpty()) {
            dnsServers.split(",").forEach {
                if (it.isNotBlank()) try { builder.addDnsServer(it.trim()) } catch(e: Exception){
                    Log.e("ANet", e.toString())
                }
            }
        } else {
            builder.addDnsServer("1.1.1.1")
        }

        if (includeRoutes.isNotEmpty()) {
            Log.i("ANet", "Mode: INCLUDE (Split Tunneling)")
            includeRoutes.split(",").forEach { addRouteSafely(builder, it) }
        } else {
            Log.i("ANet", "Mode: GLOBAL/EXCLUDE")

            if (excludeRoutes.isNotEmpty()) {
                if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
                    Log.i("ANet", "Using Native Android 13 Exclusions")
                    builder.addRoute("0.0.0.0", 0)
                    excludeRoutes.split(",").forEach { excludeRouteSafely(builder, it) }
                    builder.addRoute("128.0.0.0", 1)
                } else {
                    Log.i("ANet", "Legacy Android: Using Calculated Fallback Routes")
                    if (fallbackRoutes.isNotEmpty()) {
                        fallbackRoutes.split(",").forEach { addRouteSafely(builder, it) }
                    } else {
                        Log.w("ANet", "Fallback empty! Defaulting to Full VPN.")
                        builder.addRoute("0.0.0.0", 0)
                    }
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
            }
        }

        try {
            vpnInterface = builder.establish()
            return vpnInterface?.fd ?: -1
        } catch (e: Exception) {
            Log.e("ANet", "Establish failed", e)
            return -1
        }
    }

    private fun addRouteSafely(builder: Builder, routeStr: String) {
        try {
            val parts = routeStr.trim().split("/")
            if (parts.size == 2) {
                builder.addRoute(parts[0], parts[1].toInt())
            }
        } catch (e: Exception) {
            Log.e("ANet", "Failed to add route: $routeStr", e)
        }
    }

    private fun excludeRouteSafely(builder: Builder, routeStr: String) {
        if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            try {
                val parts = routeStr.trim().split("/")
                if (parts.size == 2) {
                    val ip = InetAddress.getByName(parts[0])
                    val prefix = parts[1].toInt()
                    builder.excludeRoute(IpPrefix(ip, prefix))
                    Log.i("ANet", "Excluded: $routeStr")
                }
            } catch (e: Exception) {
                Log.e("ANet", "Failed to exclude route: $routeStr", e)
            }
        } else {
            Log.e("ANet", "excludeRoute Not Supported")
            onStatusChanged("excludeRoute Not Supported")
        }
    }

    fun onStatusChanged(status: String) {
        Log.d("ANet", "Status: $status")
        updateNotification(status)

        val isDisconnect = status.contains("Connection lost", ignoreCase = true) ||
                status.contains("Reconnecting", ignoreCase = true) ||
                status.contains("Stopped", ignoreCase = true) ||
                status.contains("Error", ignoreCase = true) ||
                status.contains("Failed", ignoreCase = true)

        if (isDisconnect) {
            // ТРЕБОВАНИЕ: Асинхронно отправляем закрытие TUN на главный поток Android.
            // Это мгновенно освобождает рабочий поток Rust (Tokio) от ожидания JNI,
            // предотвращая дедлоки во время фазы очистки сессии.
            mainHandler.post {
                closeTun()
            }
        }

        val intent = Intent("org.alco.anet.VPN_STATUS")
        intent.putExtra("status", status)

        if (status.contains("ERROR", ignoreCase = true) ||
            status.contains("[CORE AUTH]", ignoreCase = true)) {
            intent.putExtra("is_error", true)
        }

        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, "ANetChannel")
            .setContentTitle("ANet VPN")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        try { nm?.notify(1337, n) } catch (e: SecurityException) {}
    }

    private fun stopVpnInternal() {
        unregisterNetworkCallback()
        closeTun()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        Log.i("ANet", "VPN Service Revoked by System")
        stopVpn()
        stopVpnInternal()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }
}
