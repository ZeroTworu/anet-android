package org.alco.anet

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.os.Build.*
import java.net.InetAddress
import android.content.pm.ServiceInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger;

class ANetVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var allowedAppsCache: List<String> = emptyList()
    @Volatile private var isShuttingDown = false

    // Поколение TUN-интерфейса. Защищает от гонки: отложенный closeTun(),
    // запощенный по статусу "Reconnecting", не должен закрыть УЖЕ НОВЫЙ
    // интерфейс, который Rust успел поднять через configureTun().
    private val tunGeneration = AtomicInteger(0)

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Создаем Handler для перенаправления тяжелых вызовов на главный поток Android
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }
        const val ACTION_CONNECT = "org.alco.anet.CONNECT"
        const val ACTION_STOP = "org.alco.anet.STOP"
        const val EXTRA_VPN_STATE = "vpn_state"
        const val EXTRA_VPN_MESSAGE = "vpn_message"
        const val EXTRA_SERVER_NAME = "server_name"

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        const val STATE_RECONNECTING = 3
        const val STATE_STOPPING = 4
        const val STATE_STOPPED = 5
        const val STATE_FAILED = 6
    }

    // Native-методы
    private external fun initLogger()
    private external fun connectVpn(config: String, selectedServer: String)
    private external fun stopVpn()
    private external fun clearVpnCallback()

    override fun onCreate() {
        super.onCreate()
        initLogger()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            Log.i("ANet", "Received STOP Intent")
            requestStop()
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
            // Вызываем через хелпер для API 34
            Api34Helper.startForegroundSpecial(this, 1337, notification)
        } else {
            startForeground(1337, notification)
        }

        val config = intent?.getStringExtra("CONFIG")
        if (config == null) {
            // START_STICKY: система может перезапустить сервис с intent == null
            // (после убийства процесса). Конфига нет — молча висеть в форграунде
            // с вечным "Connecting..." нельзя, корректно останавливаемся.
            Log.w("ANet", "Restarted without intent/config, stopping service")
            stopVpnInternal()
            return START_NOT_STICKY
        }
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

    @Synchronized
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
    @androidx.annotation.Keep
    @Synchronized
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

        tunGeneration.incrementAndGet()
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
                    // Вызываем через хелпер, чтобы избежать VerifyError
                    Api33Helper.excludeRoute(builder, parts[0], parts[1].toInt())
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

    @androidx.annotation.Keep
    fun onStatusChanged(status: String) {
        Log.d("ANet", "Status: $status")
        updateNotification(status)

        val intent = Intent("org.alco.anet.VPN_STATUS")
        intent.putExtra("status", status)

        if (status.contains("ERROR", ignoreCase = true) ||
            status.contains("[CORE AUTH]", ignoreCase = true)) {
            intent.putExtra("is_error", true)
        }

        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    @androidx.annotation.Keep
    fun onVpnStateChanged(state: Int, message: String, serverName: String) {
        if (isShuttingDown) {
            Log.d("ANet", "Ignoring queued VPN state during shutdown: $state")
            return
        }
        Log.d("ANet", "VPN state=$state, message=$message, server=$serverName")
        updateNotification(message)

        if (state == STATE_RECONNECTING || state == STATE_STOPPING ||
            state == STATE_STOPPED || state == STATE_FAILED) {
            // Не даём отложенному закрытию уничтожить новый TUN, который Rust
            // мог успеть создать во время реконнекта.
            val generation = tunGeneration.get()
            mainHandler.post {
                if (tunGeneration.get() == generation) {
                    closeTun()
                } else {
                    Log.i("ANet", "Skip stale closeTun: TUN was re-established")
                }
            }
        }

        val intent = Intent("org.alco.anet.VPN_STATUS").apply {
            putExtra(EXTRA_VPN_STATE, state)
            putExtra(EXTRA_VPN_MESSAGE, message)
            putExtra(EXTRA_SERVER_NAME, serverName)
            setPackage(packageName)
        }
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

    private fun requestStop() {
        if (isShuttingDown) return
        isShuttingDown = true
        Thread {
            stopVpn()
            mainHandler.post {
                val intent = Intent("org.alco.anet.VPN_STATUS").apply {
                    putExtra(EXTRA_VPN_STATE, STATE_STOPPED)
                    putExtra(EXTRA_VPN_MESSAGE, "VPN stopped")
                    putExtra(EXTRA_SERVER_NAME, "")
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                stopVpnInternal()
            }
        }.start()
    }

    override fun onRevoke() {
        Log.i("ANet", "VPN Service Revoked by System")
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        // Останавливаем и Rust-часть: иначе tokio-рантайм продолжает жить
        // в процессе после смерти сервиса — клиент бесконечно реконнектится
        // в фоне (батарея), а колбэки летят в мертвый Service через GlobalRef.
        if (!isShuttingDown) {
            isShuttingDown = true
            Thread { stopVpn() }.start()
        }
        unregisterNetworkCallback()
        closeTun()
        clearVpnCallback()
        super.onDestroy()
    }
}

// Вспомогательный класс для изоляции API 33 (Android 13) от старых систем
@androidx.annotation.RequiresApi(VERSION_CODES.TIRAMISU)
object Api33Helper {
    fun excludeRoute(builder: VpnService.Builder, ipStr: String, prefix: Int) {
        val ip = java.net.InetAddress.getByName(ipStr)
        builder.excludeRoute(android.net.IpPrefix(ip, prefix))
    }
}

// Вспомогательный класс для изоляции API 34 (Android 14) от старых систем
@androidx.annotation.RequiresApi(VERSION_CODES.UPSIDE_DOWN_CAKE)
object Api34Helper {
    fun startForegroundSpecial(
        service: android.app.Service,
        id: Int,
        notification: android.app.Notification
    ) {
        service.startForeground(
            id,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }
}
