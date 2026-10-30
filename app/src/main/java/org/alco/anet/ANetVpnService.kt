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
import android.os.PowerManager
import android.net.wifi.WifiManager
import java.util.concurrent.atomic.AtomicInteger;

class ANetVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile private var allowedAppsCache: List<String> = emptyList()
    @Volatile private var isShuttingDown = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var lastNetwork: Network? = null
    @Volatile private var hadActiveNetwork = false
    @Volatile private var lastWakeLockRenewMs: Long = 0L

    // Поколение TUN-интерфейса. Защищает от гонки: отложенный closeTun(),
    // запощенный по статусу "Reconnecting", не должен закрыть УЖЕ НОВЫЙ
    // интерфейс, который Rust успел поднять через configureTun().
    private val tunGeneration = AtomicInteger(0)
    private val sessionGeneration = AtomicInteger(0)

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Создаем Handler для перенаправления тяжелых вызовов на главный поток Android
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }

        @Volatile
        var isServiceRunning: Boolean = false

        @Volatile
        private var lastConfigCache: String? = null

        @Volatile
        private var lastSelectedServerCache: String? = null

        const val ACTION_CONNECT = "org.alco.anet.CONNECT"
        const val ACTION_STOP = "org.alco.anet.STOP"

        const val EXTRA_VPN_STATE = "vpn_state"
        const val EXTRA_VPN_MESSAGE = "vpn_message"
        const val EXTRA_SERVER_NAME = "server_name"

        const val EXTRA_IS_STATS = "is_stats"
        const val EXTRA_STATS_RX = "stats_rx"
        const val EXTRA_STATS_TX = "stats_tx"
        const val EXTRA_STATS_RTT = "stats_rtt"
        const val EXTRA_STATS_RXM = "stats_rxm"
        const val EXTRA_STATS_TXM = "stats_txm"
        const val EXTRA_IS_CONNECTIVITY = "is_connectivity"
        const val EXTRA_CONN_CONDITION = "conn_condition"
        const val EXTRA_CONN_PULSE_COLOR = "conn_pulse_color"
        const val EXTRA_CONN_RTT_MS = "conn_rtt_ms"
        const val EXTRA_CONN_VPN_CONNECTED = "conn_vpn_connected"
        const val EXTRA_CONN_TITLE = "conn_title"
        const val EXTRA_CONN_SUBTITLE = "conn_subtitle"
        const val EXTRA_CONN_GLOBAL_OK = "conn_global_ok"
        const val EXTRA_CONN_GLOBAL_TOTAL = "conn_global_total"
        const val EXTRA_CONN_DOMESTIC_OK = "conn_domestic_ok"
        const val EXTRA_CONN_DOMESTIC_TOTAL = "conn_domestic_total"
        const val EXTRA_IS_TELEMETRY = "is_telemetry"
        const val EXTRA_TEL_RX_BPS = "tel_rx_bps"
        const val EXTRA_TEL_TX_BPS = "tel_tx_bps"
        const val EXTRA_TEL_TOTAL_RX = "tel_total_rx"
        const val EXTRA_TEL_TOTAL_TX = "tel_total_tx"
        const val EXTRA_TEL_RTT_MS = "tel_rtt_ms"
        const val EXTRA_TEL_RX_SPEED_STR = "tel_rx_speed_str"
        const val EXTRA_TEL_TX_SPEED_STR = "tel_tx_speed_str"
        const val EXTRA_TEL_TOTAL_RX_STR = "tel_total_rx_str"
        const val EXTRA_TEL_TOTAL_TX_STR = "tel_total_tx_str"
                const val EXTRA_TEL_RX_CURVE = "tel_rx_curve"
        const val EXTRA_TEL_TX_CURVE = "tel_tx_curve"
        const val EXTRA_IS_NODE_PROBE = "is_node_probe"
        const val EXTRA_PROBE_IDS = "probe_ids"
        const val EXTRA_PROBE_NAMES = "probe_names"
        const val EXTRA_PROBE_STATUSES = "probe_statuses"
        const val EXTRA_PROBE_RTTS = "probe_rtts"
        const val EXTRA_PROBE_FULL_RTTS = "probe_full_rtts"
        const val EXTRA_PROBE_REACHABLES = "probe_reachables"
        const val EXTRA_PROBE_TOTALS = "probe_totals"
        const val EXTRA_PROBE_PROTOS = "probe_protos"
        const val EXTRA_PROBE_IS_CHECKING = "probe_is_checking"
        const val EXTRA_PROBE_COMPLETED = "probe_completed"
        const val EXTRA_PROBE_TOTAL_NODES = "probe_total_nodes"

        @Volatile var cachedAccountGroup: String = ""
        @Volatile var cachedAccountExpires: String = ""
        @Volatile var cachedAccountTraffic: String = ""
        @Volatile var cachedAccountSpeed: String = ""
        @Volatile var cachedAccountSessions: String = ""

        @Volatile var cachedRxCurve: FloatArray = FloatArray(0)
        @Volatile var cachedTxCurve: FloatArray = FloatArray(0)
        @Volatile var cachedRxSpeedStr: String = ""
        @Volatile var cachedTxSpeedStr: String = ""
        @Volatile var cachedTotalRxStr: String = ""
        @Volatile var cachedTotalTxStr: String = ""
        @Volatile var cachedRttMs: Long = -1L

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
    private external fun reconnectVpn()
    private external fun stopVpn()
    private external fun clearVpnCallback()

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
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

        val connectGen = sessionGeneration.incrementAndGet()
        isShuttingDown = false
        isServiceRunning = true

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

        val config = intent?.getStringExtra("CONFIG") ?: lastConfigCache
        if (config == null) {
            // START_STICKY: система может перезапустить сервис с intent == null
            // (после убийства процесса). Если и в кэше нет — корректно останавливаемся.
            Log.w("ANet", "Restarted without intent/config and no cache, stopping service")
            val stopIntent = Intent("org.alco.anet.VPN_STATUS").apply {
                putExtra(EXTRA_VPN_STATE, STATE_STOPPED)
                putExtra(EXTRA_VPN_MESSAGE, "")
                putExtra(EXTRA_SERVER_NAME, "")
                setPackage(packageName)
            }
            sendBroadcast(stopIntent)
            stopVpnInternal()
            return START_NOT_STICKY
        }

        val selectedServer = intent?.getStringExtra("SELECTED_SERVER") ?: lastSelectedServerCache ?: ""

        // Сохраняем в кэш для авто-восстановления при перезапуске сервиса системой
        lastConfigCache = config
        lastSelectedServerCache = selectedServer

        acquireLocks()
        allowedAppsCache = intent?.getStringArrayListExtra("ALLOWED_APPS") ?: emptyList()
        registerNetworkCallback()

        Thread {
            if (sessionGeneration.get() == connectGen && !isShuttingDown) {
                connectVpn(config, selectedServer)
            }
        }.start()
        return START_STICKY
    }

    private fun renewWakeLock(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWakeLockRenewMs < 60_000L) return
        try {
            wakeLock?.let {
                it.acquire(10 * 60 * 1000L)
                lastWakeLockRenewMs = now
            }
        } catch (e: Exception) {
            Log.e("ANet", "Failed to renew WakeLock: ${e.message}")
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ANet:VpnWakeLock").apply {
                    setReferenceCounted(false)
                }
                Log.i("ANet", "WakeLock initialized")
            }
            renewWakeLock(force = true)
        } catch (e: Exception) {
            Log.e("ANet", "Failed to acquire WakeLock: ${e.message}")
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifiManager.createWifiLock(
                    if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    },
                    "ANet:VpnWifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i("ANet", "WifiLock acquired")
            }
        } catch (e: Exception) {
            Log.e("ANet", "Failed to acquire WifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i("ANet", "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e("ANet", "Failed to release WakeLock: ${e.message}")
        }
        wakeLock = null

        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i("ANet", "WifiLock released")
                }
            }
        } catch (e: Exception) {
            Log.e("ANet", "Failed to release WifiLock: ${e.message}")
        }
        wifiLock = null
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
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        Log.i("ANet", "Ignoring VPN network callback to prevent infinite routing loop")
                        return
                    }

                    val isNetworkSwitch =
                        (lastNetwork != null && lastNetwork != network) ||
                        (lastNetwork == null && hadActiveNetwork)
                    lastNetwork = network
                    hadActiveNetwork = true
                    renewWakeLock(force = true)
                    Log.i("ANet", "Active physical network: $network (switch=$isNetworkSwitch)")

                    try {
                        setUnderlyingNetworks(arrayOf(network))
                    } catch (e: Exception) {
                        Log.e("ANet", "Failed to set underlying networks: ${e.message}")
                    }

                    if (isNetworkSwitch && !isShuttingDown) {
                        Log.i("ANet", "Physical network switched or restored after sleep! Triggering reconnect...")
                        onStatusChanged("Сеть изменилась, переподключение...")
                        mainHandler.postDelayed({
                            if (!isShuttingDown) {
                                Thread { reconnectVpn() }.start()
                            }
                        }, 500)
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.i("ANet", "Physical network lost: $network")
                    if (lastNetwork == network) {
                        lastNetwork = null
                        try {
                            setUnderlyingNetworks(null)
                        } catch (e: Exception) {
                            Log.e("ANet", "Failed to clear underlying networks: ${e.message}")
                        }
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
        lastNetwork = null
        hadActiveNetwork = false
    }

    @androidx.annotation.Keep
    @Synchronized
    fun closeTun() {
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
        if (isShuttingDown || !isServiceRunning) {
            Log.w("ANet", "configureTun aborted: service is shutting down or not running")
            return -1
        }
        Log.i("ANet", "Configuring TUN...")
        tunGeneration.incrementAndGet()
        val oldInterface = vpnInterface

        val builder = Builder()
        builder.addAddress(ip, prefix)
        builder.setMtu(mtu)
        builder.setSession("ANet VPN")

        renewWakeLock(force = true)

        if (allowedAppsCache.isNotEmpty()) {
            Log.i("ANet", "Per-App Mode ON. Allowing ${allowedAppsCache.size} specific apps.")
            onStatusChanged("App Split: Only specific apps use VPN")
            for (pkg in allowedAppsCache) {
                if (pkg == packageName) continue
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: Exception) {
                    Log.e("ANet", "Package $pkg not found, skipping", e)
                }
            }
        } else {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) { Log.e("ANet", "$e", e) }
        }

        if (dnsServers.isNotEmpty()) {
            dnsServers.split(",").forEach {
                if (it.isNotBlank()) try { builder.addDnsServer(it.trim()) } catch (e: Exception) {
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
                    val count = excludeRoutes.split(",").filter { it.isNotBlank() }.size
                    Log.i("ANet", "Using Native Android 13 Exclusions ($count routes)")
                    builder.addRoute("0.0.0.0", 0)
                    excludeRoutes.split(",").forEach { excludeRouteSafely(builder, it) }
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

        return try {
            val newInterface = builder.establish()
            if (isShuttingDown || !isServiceRunning) {
                try { newInterface?.close() } catch (_: Exception) {}
                try { oldInterface?.close() } catch (_: Exception) {}
                vpnInterface = null
                return -1
            }
            if (oldInterface != null && oldInterface !== newInterface) {
                try {
                    oldInterface.close()
                } catch (e: Exception) {
                    Log.e("ANet", "Failed to close previous vpnInterface", e)
                }
            }
            vpnInterface = newInterface
            vpnInterface?.fd ?: -1
        } catch (e: Exception) {
            Log.e("ANet", "Establish failed", e)
            -1
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
        if (isShuttingDown && state != STATE_STOPPED && state != STATE_DISCONNECTED) {
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

    @androidx.annotation.Keep
    fun onAccountInfo(
        billing: String,
        group: String,
        sessions: String,
        speed: String,
        consumed: String,
        limit: String,
        expires: String
    ) {
        cachedAccountGroup = "$billing • $group"
        cachedAccountExpires = expires
        cachedAccountTraffic = "$consumed\n/ $limit"
        cachedAccountSpeed = speed
        cachedAccountSessions = sessions
        try {
            getSharedPreferences("anet_prefs", Context.MODE_PRIVATE).edit()
                .putString("acc_group_cached", cachedAccountGroup)
                .putString("acc_expires_cached", cachedAccountExpires)
                .putString("acc_traffic_cached", cachedAccountTraffic)
                .putString("acc_speed_cached", cachedAccountSpeed)
                .putString("acc_sessions_cached", cachedAccountSessions)
                .apply()
        } catch (_: Exception) {}

        val intent = Intent("org.alco.anet.VPN_STATUS").apply {
            putExtra("is_account_info", true)
            putExtra("billing", billing)
            putExtra("group", group)
            putExtra("sessions", sessions)
            putExtra("speed", speed)
            putExtra("consumed", consumed)
            putExtra("limit", limit)
            putExtra("expires", expires)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    @androidx.annotation.Keep
    fun onTrafficStats(rx: String, tx: String, rtt: String, rxm: String, txm: String) {
        renewWakeLock(force = false)
        val intent = Intent("org.alco.anet.VPN_STATUS").apply {
            putExtra(EXTRA_IS_STATS, true)
            putExtra(EXTRA_STATS_RX, rx)
            putExtra(EXTRA_STATS_TX, tx)
            putExtra(EXTRA_STATS_RTT, rtt)
            putExtra(EXTRA_STATS_RXM, rxm)
            putExtra(EXTRA_STATS_TXM, txm)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    @androidx.annotation.Keep
    fun onConnectivityUpdated(
        conditionCode: Int,
        pulseColorCode: Int,
        rttMs: Long,
        vpnConnected: Boolean,
        title: String,
        subtitle: String,
        globalOk: Int,
        globalTotal: Int,
        domesticOk: Int,
        domesticTotal: Int
    ) {
        val intent = Intent("org.alco.anet.VPN_STATUS").apply {
            putExtra(EXTRA_IS_CONNECTIVITY, true)
            putExtra(EXTRA_CONN_CONDITION, conditionCode)
            putExtra(EXTRA_CONN_PULSE_COLOR, pulseColorCode)
            putExtra(EXTRA_CONN_RTT_MS, rttMs)
            putExtra(EXTRA_CONN_VPN_CONNECTED, vpnConnected)
            putExtra(EXTRA_CONN_TITLE, title)
            putExtra(EXTRA_CONN_SUBTITLE, subtitle)
            putExtra(EXTRA_CONN_GLOBAL_OK, globalOk)
            putExtra(EXTRA_CONN_GLOBAL_TOTAL, globalTotal)
            putExtra(EXTRA_CONN_DOMESTIC_OK, domesticOk)
            putExtra(EXTRA_CONN_DOMESTIC_TOTAL, domesticTotal)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    @androidx.annotation.Keep
    fun onTelemetryUpdated(
        rxBps: Long,
        txBps: Long,
        totalRxBytes: Long,
        totalTxBytes: Long,
        rttMs: Long,
        rxSpeedStr: String,
        txSpeedStr: String,
        totalRxStr: String,
        totalTxStr: String,
        rxCurve: FloatArray,
        txCurve: FloatArray
    ) {
        renewWakeLock(force = false)
        cachedRxCurve = rxCurve
        cachedTxCurve = txCurve
        cachedRxSpeedStr = rxSpeedStr
        cachedTxSpeedStr = txSpeedStr
        cachedTotalRxStr = totalRxStr
        cachedTotalTxStr = totalTxStr
        if (rttMs > 0L) cachedRttMs = rttMs

        val intent = Intent("org.alco.anet.VPN_STATUS").apply {
            putExtra(EXTRA_IS_TELEMETRY, true)
            putExtra(EXTRA_TEL_RX_BPS, rxBps)
            putExtra(EXTRA_TEL_TX_BPS, txBps)
            putExtra(EXTRA_TEL_TOTAL_RX, totalRxBytes)
            putExtra(EXTRA_TEL_TOTAL_TX, totalTxBytes)
            putExtra(EXTRA_TEL_RTT_MS, rttMs)
            putExtra(EXTRA_TEL_RX_SPEED_STR, rxSpeedStr)
            putExtra(EXTRA_TEL_TX_SPEED_STR, txSpeedStr)
            putExtra(EXTRA_TEL_TOTAL_RX_STR, totalRxStr)
            putExtra(EXTRA_TEL_TOTAL_TX_STR, totalTxStr)
            putExtra(EXTRA_TEL_RX_CURVE, rxCurve)
            putExtra(EXTRA_TEL_TX_CURVE, txCurve)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    @androidx.annotation.Keep
    fun onNodeProbeUpdated(
        ids: Array<String>,
        names: Array<String>,
        statuses: IntArray,
        rtts: IntArray,
        fullRtts: IntArray,
        reachables: IntArray,
        totals: IntArray,
        protos: Array<String>,
        isChecking: Boolean,
        completedNodes: Int,
        totalNodes: Int
    ) {
        val intent = Intent("org.alco.anet.VPN_STATUS").apply {
            putExtra(EXTRA_IS_NODE_PROBE, true)
            putExtra(EXTRA_PROBE_IDS, ids)
            putExtra(EXTRA_PROBE_NAMES, names)
            putExtra(EXTRA_PROBE_STATUSES, statuses)
            putExtra(EXTRA_PROBE_RTTS, rtts)
            putExtra(EXTRA_PROBE_FULL_RTTS, fullRtts)
            putExtra(EXTRA_PROBE_REACHABLES, reachables)
            putExtra(EXTRA_PROBE_TOTALS, totals)
            putExtra(EXTRA_PROBE_PROTOS, protos)
            putExtra(EXTRA_PROBE_IS_CHECKING, isChecking)
            putExtra(EXTRA_PROBE_COMPLETED, completedNodes)
            putExtra(EXTRA_PROBE_TOTAL_NODES, totalNodes)
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
            .setOnlyAlertOnce(true)
            .build()
        try { nm?.notify(1337, n) } catch (e: SecurityException) {}
    }

    private fun stopVpnInternal() {
        isServiceRunning = false
        cachedRxCurve = FloatArray(0)
        cachedTxCurve = FloatArray(0)
        releaseLocks()
        unregisterNetworkCallback()
        closeTun()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestStop() {
        if (isShuttingDown) {
            if (!isServiceRunning) {
                val intent = Intent("org.alco.anet.VPN_STATUS").apply {
                    putExtra(EXTRA_VPN_STATE, STATE_STOPPED)
                    putExtra(EXTRA_VPN_MESSAGE, "")
                    putExtra(EXTRA_SERVER_NAME, "")
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            return
        }
        isShuttingDown = true
        val stopGen = sessionGeneration.incrementAndGet()

        val stoppingIntent = Intent("org.alco.anet.VPN_STATUS").apply {
            putExtra(EXTRA_VPN_STATE, STATE_STOPPING)
            putExtra(EXTRA_VPN_MESSAGE, "Stopping VPN...")
            putExtra(EXTRA_SERVER_NAME, "")
            setPackage(packageName)
        }
        sendBroadcast(stoppingIntent)

        // Закрываем TUN-дескриптор синхронно до stopVpn(), чтобы мгновенно разблокировать нативный read/write в ядре
        closeTun()
        releaseLocks()
        unregisterNetworkCallback()

        Thread {
            val nativeStopWorker = Thread {
                try {
                    stopVpn()
                } catch (e: Throwable) {
                    Log.e("ANet", "stopVpn failed", e)
                }
            }
            nativeStopWorker.start()
            try {
                nativeStopWorker.join(4500L)
            } catch (_: InterruptedException) {}

            mainHandler.post {
                if (sessionGeneration.get() == stopGen) {
                    val intent = Intent("org.alco.anet.VPN_STATUS").apply {
                        putExtra(EXTRA_VPN_STATE, STATE_STOPPED)
                        putExtra(EXTRA_VPN_MESSAGE, "")
                        putExtra(EXTRA_SERVER_NAME, "")
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                    stopVpnInternal()
                }
            }
        }.start()
    }

    override fun onRevoke() {
        Log.i("ANet", "VPN Service Revoked by System")
        val intent = Intent("org.alco.anet.VPN_STATUS").apply {
            putExtra(EXTRA_VPN_STATE, STATE_STOPPED)
            putExtra(EXTRA_VPN_MESSAGE, "")
            putExtra(EXTRA_SERVER_NAME, "")
            setPackage(packageName)
        }
        sendBroadcast(intent)
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (!isShuttingDown) {
            isShuttingDown = true
            isServiceRunning = false
            sessionGeneration.incrementAndGet()
            closeTun()
            releaseLocks()
            unregisterNetworkCallback()
            val intent = Intent("org.alco.anet.VPN_STATUS").apply {
                putExtra(EXTRA_VPN_STATE, STATE_STOPPED)
                putExtra(EXTRA_VPN_MESSAGE, "")
                putExtra(EXTRA_SERVER_NAME, "")
                setPackage(packageName)
            }
            sendBroadcast(intent)
            Thread { stopVpn() }.start()
        } else {
            isServiceRunning = false
            releaseLocks()
            unregisterNetworkCallback()
            closeTun()
        }
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
