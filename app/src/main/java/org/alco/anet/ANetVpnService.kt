package org.alco.anet

import android.content.Intent
import android.net.VpnService
import android.net.IpPrefix // API 33+
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import java.net.InetAddress

class ANetVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }
        const val ACTION_CONNECT = "org.alco.anet.CONNECT"
        const val ACTION_STOP = "org.alco.anet.STOP"
    }

    // Native methods
    private external fun initLogger()
    private external fun connectVpn(config: String)
    private external fun stopVpn()

    override fun onCreate() {
        super.onCreate()
        initLogger()
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
        startForeground(1337, notification)

        val config = intent?.getStringExtra("CONFIG") ?: return START_NOT_STICKY
        Thread { connectVpn(config) }.start()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "ANetChannel", "ANet VPN Status", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
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

        if (vpnInterface != null) {
            try { vpnInterface!!.close() } catch (e: Exception) {}
        }

        val builder = Builder()
        builder.addAddress(ip, prefix)
        builder.setMtu(mtu)
        builder.setSession("ANet VPN")
        try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

        // DNS
        if (dnsServers.isNotEmpty()) {
            dnsServers.split(",").forEach {
                if (it.isNotBlank()) try { builder.addDnsServer(it.trim()) } catch(e: Exception){
                    Log.e("ANet", e.toString())
                }
            }
        } else {
            builder.addDnsServer("1.1.1.1")
        }

        // ROUTING LOGIC
        if (includeRoutes.isNotEmpty()) {
            // Mode 1: Whitelist
            Log.i("ANet", "Mode: INCLUDE (Split Tunneling)")
            includeRoutes.split(",").forEach { addRouteSafely(builder, it) }
        } else {
            // Mode 2: Global / Blacklist
            Log.i("ANet", "Mode: GLOBAL/EXCLUDE")

            if (excludeRoutes.isNotEmpty()) {
                // Пытаемся использовать нативный Exclude (Android 13+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    Log.i("ANet", "Using Native Android 13 Exclusions")
                    // Добавляем весь мир
                    builder.addRoute("0.0.0.0", 0)
                    // Исключаем ненужное
                    excludeRoutes.split(",").forEach { excludeRouteSafely(builder, it) }
                    builder.addRoute("128.0.0.0", 1)
                } else {
                    // Эмуляция для старых Android
                    Log.i("ANet", "Legacy Android: Using Calculated Fallback Routes")
                    if (fallbackRoutes.isNotEmpty()) {
                        // Rust уже посчитал "Весь мир минус Исключения"
                        fallbackRoutes.split(",").forEach { addRouteSafely(builder, it) }
                    } else {
                        // Если Rust не смог посчитать (или что-то пошло не так), фоллбек на Full VPN
                        Log.w("ANet", "Fallback empty! Defaulting to Full VPN.")
                        builder.addRoute("0.0.0.0", 0)
                    }
                }
            } else {
                // Чистый Global VPN без исключений
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

    // Доступно только на API 33+ (проверка вызова делается снаружи или через аннотацию,
    // но в Kotlin можно просто проверить SDK_INT выше)
    private fun excludeRouteSafely(builder: Builder, routeStr: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
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
        val intent = Intent("org.alco.anet.VPN_STATUS")
        intent.putExtra("status", status)
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
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }
}
