package org.alco.anet

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class ServerModel(val id: String, val name: String) {
    fun getFormattedName() = name
}

data class ConfigItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var content: String,
    val isServerConfig: Boolean = false
)

data class RoutingOverrides(
    val block: MutableList<String> = mutableListOf(),
    val direct: MutableList<String> = mutableListOf(),
    val proxy: MutableList<String> = mutableListOf()
)

enum class OverrideTab { DIRECT, BLOCK, PROXY }

class MainActivity : AppCompatActivity() {

    private lateinit var tvRtt: TextView
    private lateinit var tvRx: TextView
    private lateinit var tvTx: TextView
    private lateinit var tvRxm: TextView
    private lateinit var tvTxm: TextView

    private lateinit var connectivityShieldCard: LinearLayout
    private lateinit var connectivityBadgeDot: ImageView
    private lateinit var tvConnectivityTitle: TextView
    private lateinit var tvConnectivitySubtitle: TextView
    private lateinit var tvConnectivityNodes: TextView
    private lateinit var btnRecheckConnectivity: TextView
    private lateinit var connectivitySegmentsView: ConnectivitySegmentsView
    private lateinit var pulseLineView: PulseLineView
    private lateinit var tvPulseTarget: TextView
    private lateinit var telemetrySparklineView: TelemetrySparklineView

    private var lastConnCondition: Int = 1
    private var lastConnPulseColor: Int = PulseLineView.PULSE_YELLOW
    private var lastConnRttMs: Long = -1L
    private var lastConnTitle: String = "Метеосводка: проверка связи…"
    private var lastConnSubtitle: String = "Опрашиваем ближние и дальние маяки…"
    private var lastConnGlobalOk: Int = 0
    private var lastConnGlobalTotal: Int = 4
    private var lastConnDomesticOk: Int = 0
    private var lastConnDomesticTotal: Int = 5
    private var useKotlinFallbackProber: Boolean = false

    private var isScanningQr = false
    private lateinit var connectionStatusLabel: TextView
    private lateinit var connectButton: Button
    private lateinit var spinner: ImageView
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnShowLogs: Button
    private lateinit var btnSettings: Button

    private lateinit var serverSelectContainer: LinearLayout
    private lateinit var serverSelectTextView: TextView
    private lateinit var serverIndicator: ImageView
    private lateinit var serverSelectIcon: ImageView
    private var activeErrorDialog: AlertDialog? = null

    private lateinit var versionInfoContainer: LinearLayout
    private lateinit var tvVpnEventStatus: TextView

    private var lastAccountGroup: String = ""
    private var lastAccountExpires: String = ""
    private var lastAccountTraffic: String = ""
    private var lastAccountSpeed: String = ""
    private var lastAccountSessions: String = ""
    private var lastVpnEventText: String = ""
    private var lastVpnEventColor: Int = Color.WHITE

    private val logLines = ArrayDeque<CharSequence>().apply {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        add("[$time] > System ready...")
    }
    private var activeLogAdapter: ArrayAdapter<CharSequence>? = null
    private var activeLogListView: ListView? = null

    private var activeConfigDialog: AlertDialog? = null
    private var refreshConfigListRunnable: Runnable? = null

    private var selectedConfigContent: String? = null
    private var selectedConfigName: String = "Unknown"
    private var isCheckingUpdates = false
    private var isVpnConnected = false
    private var currentUiState = State.DISCONNECTED
    private var updateDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectTimeoutRunnable = Runnable {
        if (currentUiState == State.CONNECTING) {
            logToConsole("Connection timeout: stopping VPN attempt")
            stopVpnService()
            showErrorDialog("Connection timed out. Please check your network or try a different server.")
        }
    }

    private val availableServers = mutableListOf<ServerModel>()
    private var selectedServerName: String = ""

    private external fun getAppVersion(): String
    private external fun getBuildInfo(): String
    private external fun checkUpdates(config: String?)
    private external fun startDownload(path: String)
    private external fun getPendingTag(): String
    private external fun getPendingBody(): String
    private external fun inspectConfig(config: String): String
    private external fun getVpnStateCode(): Int
    private external fun getVpnServerName(): String
    private external fun clearUiCallback()
    private external fun initLogger()
    private external fun startConnectivityMonitor(config: String)
    private external fun triggerConnectivityCheck()
    private external fun getConnectivitySnapshotJson(): String

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }

        private const val MAX_LOG_LINES = 20000
        private const val PREF_SERVER_CONFIG_URL = "server_config_url"
        private const val PREF_SERVER_ROUTING_OVERRIDES = "server_routing_overrides_json"
        const val SERVER_CONFIG_NAME = "Server config"
        const val SERVER_CONFIG_ID = "__SERVER_CONFIG__"
    }

    // --- PREFERENCES & STORAGE ---

    private fun getAppPrefs(): SharedPreferences {
        return getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
    }

    private fun getSavedServerUrl(): String? {
        val url = getAppPrefs().getString(PREF_SERVER_CONFIG_URL, null)
        return if (url.isNullOrBlank()) null else url
    }

    private fun saveServerUrl(url: String?) {
        val editor = getAppPrefs().edit()
        if (url.isNullOrBlank()) {
            editor.remove(PREF_SERVER_CONFIG_URL)
        } else {
            editor.putString(PREF_SERVER_CONFIG_URL, url.trim())
        }
        editor.apply()
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
    }

    private fun getSavedConfigs(): MutableList<ConfigItem> {
        val prefs = getAppPrefs()
        val jsonStr = prefs.getString("saved_configs_list", null) ?: return mutableListOf()
        val list = mutableListOf<ConfigItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ConfigItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.getString("name"),
                        content = obj.getString("content"),
                        isServerConfig = obj.optBoolean("isServerConfig", false)
                    )
                )
            }
        } catch (e: Exception) {
            logToConsole("Ошибка чтения списка конфигов: ${e.message}")
        }
        return list
    }

    private fun saveConfigsToPrefs(configs: List<ConfigItem>, activeId: String? = null) {
        val prefs = getAppPrefs()
        val array = JSONArray()
        for (item in configs) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("content", item.content)
                put("isServerConfig", item.isServerConfig)
            }
            array.put(obj)
        }
        val editor = prefs.edit().putString("saved_configs_list", array.toString())
        if (activeId != null) {
            editor.putString("active_config_id", activeId)
        }
        editor.apply()
    }

    private fun setServerConfig(content: String) {
        val configs = getSavedConfigs()
        val existingIndex = configs.indexOfFirst { it.id == SERVER_CONFIG_ID || it.isServerConfig }

        val item = if (existingIndex >= 0) {
            configs[existingIndex].apply {
                this.content = content
                this.name = SERVER_CONFIG_NAME
            }
        } else {
            val newItem = ConfigItem(
                id = SERVER_CONFIG_ID,
                name = SERVER_CONFIG_NAME,
                content = content,
                isServerConfig = true
            )
            configs.add(0, newItem)
            newItem
        }

        selectedConfigContent = content
        selectedConfigName = SERVER_CONFIG_NAME

        saveConfigsToPrefs(configs, item.id)
        saveConfigToPrefs(content, SERVER_CONFIG_NAME)

        setupServerSelector()
        safeStartConnectivityMonitor()
        refreshConfigListRunnable?.run()
    }

    private fun addAndActivateConfig(name: String, content: String) {
        val configs = getSavedConfigs()
        val newItem = ConfigItem(name = name, content = content, isServerConfig = false)
        configs.add(newItem)

        selectedConfigContent = content
        selectedConfigName = name

        saveConfigsToPrefs(configs, newItem.id)
        saveConfigToPrefs(content, name)

        setupServerSelector()
        safeStartConnectivityMonitor()
        refreshConfigListRunnable?.run()
    }

    // --- ЛОКАЛЬНЫЕ ПРАВИЛА (ROUTING OVERRIDES: BLOCK, DIRECT, PROXY) ---

    private fun parseRoutingOverridesFromToml(toml: String): RoutingOverrides {
        val result = RoutingOverrides()
        val lines = toml.lines()
        var inOverridesSection = false
        var currentList: MutableList<String>? = null

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inOverridesSection = line.equals("[routing.overrides]", ignoreCase = true)
                currentList = null
                continue
            }

            if (!inOverridesSection) continue

            if (line.startsWith("block", ignoreCase = true)) {
                currentList = result.block
            } else if (line.startsWith("direct", ignoreCase = true)) {
                currentList = result.direct
            } else if (line.startsWith("proxy", ignoreCase = true)) {
                currentList = result.proxy
            }

            if (currentList != null) {
                val regex = Regex("\"([^\"]+)\"")
                val matches = regex.findAll(line)
                for (m in matches) {
                    val value = m.groupValues[1].trim()
                    if (value.isNotEmpty() && !currentList.contains(value)) {
                        currentList.add(value)
                    }
                }
                if (line.contains("]")) {
                    currentList = null
                }
            }
        }

        return result
    }

    private fun injectRoutingOverridesToToml(toml: String, overrides: RoutingOverrides): String {
        val lines = toml.lines().toMutableList()
        var startIdx = -1
        var endIdx = -1

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.equals("[routing.overrides]", ignoreCase = true)) {
                startIdx = i
                endIdx = lines.size
                for (j in (i + 1) until lines.size) {
                    val nextLine = lines[j].trim()
                    if (nextLine.startsWith("[") && nextLine.endsWith("]")) {
                        endIdx = j
                        break
                    }
                }
                break
            }
        }

        val blockFormatted = overrides.block.joinToString(",\n") { "    \"$it\"" }
        val directFormatted = overrides.direct.joinToString(",\n") { "    \"$it\"" }
        val proxyFormatted = overrides.proxy.joinToString(",\n") { "    \"$it\"" }

        val newSection = buildString {
            appendLine("[routing.overrides]")
            appendLine("block = [")
            if (blockFormatted.isNotEmpty()) appendLine(blockFormatted)
            appendLine("]")
            appendLine("direct = [")
            if (directFormatted.isNotEmpty()) appendLine(directFormatted)
            appendLine("]")
            appendLine("proxy = [")
            if (proxyFormatted.isNotEmpty()) appendLine(proxyFormatted)
            appendLine("]")
        }

        return if (startIdx != -1) {
            val before = lines.subList(0, startIdx).joinToString("\n")
            val after = lines.subList(endIdx, lines.size).joinToString("\n")
            listOf(before.trimEnd(), newSection.trim(), after.trimStart())
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
        } else {
            toml.trimEnd() + "\n\n" + newSection.trim()
        }
    }

    private fun loadActiveRoutingOverrides(): RoutingOverrides {
        val isServerCfg = selectedConfigName == SERVER_CONFIG_NAME
        if (isServerCfg) {
            val jsonStr = getAppPrefs().getString(PREF_SERVER_ROUTING_OVERRIDES, null)
            if (!jsonStr.isNullOrEmpty()) {
                try {
                    val obj = JSONObject(jsonStr)
                    val ovr = RoutingOverrides()
                    obj.optJSONArray("block")?.let { arr ->
                        for (i in 0 until arr.length()) ovr.block.add(arr.getString(i))
                    }
                    obj.optJSONArray("direct")?.let { arr ->
                        for (i in 0 until arr.length()) ovr.direct.add(arr.getString(i))
                    }
                    obj.optJSONArray("proxy")?.let { arr ->
                        for (i in 0 until arr.length()) ovr.proxy.add(arr.getString(i))
                    }
                    return ovr
                } catch (_: Exception) {}
            }
        }

        return parseRoutingOverridesFromToml(selectedConfigContent ?: "")
    }

    private fun saveActiveRoutingOverrides(overrides: RoutingOverrides) {
        val currentContent = selectedConfigContent ?: return
        val updatedToml = injectRoutingOverridesToToml(currentContent, overrides)
        val isServerCfg = selectedConfigName == SERVER_CONFIG_NAME

        if (isServerCfg) {
            val obj = JSONObject().apply {
                put("block", JSONArray(overrides.block))
                put("direct", JSONArray(overrides.direct))
                put("proxy", JSONArray(overrides.proxy))
            }
            getAppPrefs().edit().putString(PREF_SERVER_ROUTING_OVERRIDES, obj.toString()).apply()
            setServerConfig(updatedToml)
        } else {
            selectedConfigContent = updatedToml
            saveConfigToPrefs(updatedToml, selectedConfigName)

            val configs = getSavedConfigs()
            val prefs = getAppPrefs()
            val activeId = prefs.getString("active_config_id", null)
            val target = configs.find { it.id == activeId || it.name == selectedConfigName }
            if (target != null) {
                target.content = updatedToml
                saveConfigsToPrefs(configs, activeId)
            }
        }

        logToConsole("Правила [routing.overrides] успешно сохранены")
    }

    // --- СКАЧИВАНИЕ И АВТООБНОВЛЕНИЕ СЕРВЕРНОГО КОНФИГА ---

    private fun fetchServerConfigFromUrl(
        url: String,
        showToastOnSuccess: Boolean = true,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val normalized = normalizeUrl(url)
        logToConsole("Запрос серверного конфига: $normalized")

        Thread {
            var currentUrl = normalized
            var redirectsCount = 0
            val maxRedirects = 5
            var content: String? = null

            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })

            try {
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, trustAllCerts, SecureRandom())
                HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
                HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            } catch (e: Exception) {
                Log.e("ANet", "SSL TrustAll setup failed: ${e.message}")
            }

            while (redirectsCount < maxRedirects) {
                var connection: HttpURLConnection? = null
                try {
                    val urlObj = URL(currentUrl)
                    connection = urlObj.openConnection() as HttpURLConnection
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000
                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ANet-Client"
                    )
                    connection.setRequestProperty("Accept", "*/*")

                    val status = connection.responseCode

                    if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                        status == HttpURLConnection.HTTP_MOVED_TEMP ||
                        status == HttpURLConnection.HTTP_SEE_OTHER ||
                        status == 307 || status == 308
                    ) {
                        val newUrl = connection.getHeaderField("Location")
                        if (!newUrl.isNullOrBlank()) {
                            currentUrl = normalizeUrl(if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                                newUrl
                            } else {
                                URL(urlObj, newUrl).toString()
                            })
                            redirectsCount++
                            continue
                        }
                    }

                    if (status == HttpURLConnection.HTTP_OK) {
                        content = connection.inputStream.bufferedReader().use { it.readText() }
                        break
                    } else {
                        val err = "Сервер вернул код: HTTP $status"
                        runOnUiThread {
                            logToConsole("Ошибка загрузки: $err")
                            onError?.invoke(err)
                        }
                        return@Thread
                    }
                } catch (e: Exception) {
                    val errorMsg = "Ошибка сети: ${e.localizedMessage ?: e.message}"
                    runOnUiThread {
                        logToConsole(errorMsg)
                        onError?.invoke(errorMsg)
                    }
                    return@Thread
                } finally {
                    connection?.disconnect()
                }
            }

            if (content.isNullOrBlank()) {
                runOnUiThread {
                    val err = "Получен пустой ответ от сервера"
                    logToConsole(err)
                    onError?.invoke(err)
                }
                return@Thread
            }

            val servers = inspectServers(content, reportError = false)
            if (servers != null && servers.isNotEmpty()) {
                runOnUiThread {
                    saveServerUrl(normalized)

                    // Накладываем сохраненные пользователем оверлеи [routing.overrides]
                    val savedOverrides = loadActiveRoutingOverrides()
                    val finalContent = injectRoutingOverridesToToml(content, savedOverrides)

                    setServerConfig(finalContent)
                    logToConsole("Серверный конфиг успешно обновлен (нод: ${servers.size})")

                    if (showToastOnSuccess) {
                        Toast.makeText(
                            this@MainActivity,
                            "✔ Серверный конфиг успешно обновлен",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    onSuccess?.invoke()
                }
            } else {
                runOnUiThread {
                    val err = "Файл не является валидным TOML-конфигом"
                    logToConsole("Ошибка: $err")
                    onError?.invoke(err)
                }
            }
        }.start()
    }

    // --- LIFECYCLE & STARTUP ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvRtt = findViewById(R.id.tvRtt)
        tvRx = findViewById(R.id.tvRx)
        tvTx = findViewById(R.id.tvTx)
        tvRxm = findViewById(R.id.tvRxm)
        tvTxm = findViewById(R.id.tvTxm)

        connectivityShieldCard = findViewById(R.id.connectivityShieldCard)
        connectivityBadgeDot = findViewById(R.id.connectivityBadgeDot)
        tvConnectivityTitle = findViewById(R.id.tvConnectivityTitle)
        tvConnectivitySubtitle = findViewById(R.id.tvConnectivitySubtitle)
        tvConnectivityNodes = findViewById(R.id.tvConnectivityNodes)
        btnRecheckConnectivity = findViewById(R.id.btnRecheckConnectivity)
        connectivitySegmentsView = findViewById(R.id.connectivitySegmentsView)
        pulseLineView = findViewById(R.id.pulseLineView)
        tvPulseTarget = findViewById(R.id.tvPulseTarget)
        telemetrySparklineView = findViewById(R.id.telemetrySparklineView)

        connectivityShieldCard.setupTvFocusAnimator()
        connectivityShieldCard.setOnClickListener {
            requestConnectivityRecheck(silent = false)
        }
        btnRecheckConnectivity.setOnClickListener {
            requestConnectivityRecheck(silent = false)
        }

        connectionStatusLabel = findViewById(R.id.connectionStatus)
        connectButton = findViewById(R.id.connect)
        spinner = findViewById(R.id.connectSpinner)

        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnShowLogs = findViewById(R.id.btnShowLogs)

        btnSettings = findViewById(R.id.btnSettings)
        btnSettings.setupTvFocusAnimator()

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        serverSelectContainer = findViewById(R.id.serverSelectContainer)
        serverSelectTextView = findViewById(R.id.serverSelectTextView)
        serverIndicator = findViewById(R.id.serverIndicator)
        serverSelectIcon = findViewById(R.id.serverSelectIcon)

        versionInfoContainer = findViewById(R.id.versionInfoContainer)
        tvVpnEventStatus = findViewById(R.id.tvVpnEventStatus)

        btnShowLogs.setOnClickListener {
            showLogsDialog()
        }

        initLogger()
        loadConfigFromPrefs()

        val savedServerUrl = getSavedServerUrl()
        val hasConfigs = getSavedConfigs().isNotEmpty()

        if (!savedServerUrl.isNullOrBlank()) {
            logToConsole("Автообновление серверного конфига...")
            fetchServerConfigFromUrl(savedServerUrl, showToastOnSuccess = false)
        } else if (!hasConfigs) {
            showEnterUrlDialog()
        }

        if (selectedConfigContent != null) {
            logToConsole("Config loaded: $selectedConfigName")
            setupServerSelector()
            checkBatteryOptimizations()
        }

        val filter = IntentFilter("org.alco.anet.VPN_STATUS")
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        connectButton.setOnClickListener {
            when (currentUiState) {
                State.CONNECTED, State.CONNECTING -> {
                    stopVpnService()
                }
                State.DISCONNECTED -> {
                    checkPermissionsAndStart()
                }
            }
        }

        setUiState(State.DISCONNECTED)

        findViewById<TextView>(R.id.versionLabel).text = getAppVersion()
        findViewById<TextView>(R.id.buildDetailLabel).text = getBuildInfo()

        if (savedInstanceState != null) {
            lastAccountGroup = savedInstanceState.getString("acc_group", "")
            lastAccountExpires = savedInstanceState.getString("acc_expires", "")
            lastAccountTraffic = savedInstanceState.getString("acc_traffic", "")
            lastAccountSpeed = savedInstanceState.getString("acc_speed", "")
            lastAccountSessions = savedInstanceState.getString("acc_sessions", "")
            lastVpnEventText = savedInstanceState.getString("vpn_event_text", "")
            lastVpnEventColor = savedInstanceState.getInt("vpn_event_color", Color.WHITE)
            tvRtt.text = savedInstanceState.getString("stat_rtt", "— мс")
            tvRx.text = savedInstanceState.getString("stat_rx", "0 B/s")
            tvTx.text = savedInstanceState.getString("stat_tx", "0 B/s")
            tvRxm.text = savedInstanceState.getString("stat_rxm", "0 B")
            tvTxm.text = savedInstanceState.getString("stat_txm", "0 B")

            lastConnCondition = savedInstanceState.getInt("conn_condition", lastConnCondition)
            lastConnPulseColor = savedInstanceState.getInt("conn_pulse_color", lastConnPulseColor)
            lastConnRttMs = savedInstanceState.getLong("conn_rtt_ms", lastConnRttMs)
            lastConnTitle = savedInstanceState.getString("conn_title", lastConnTitle)
            lastConnSubtitle = savedInstanceState.getString("conn_subtitle", lastConnSubtitle)
            lastConnGlobalOk = savedInstanceState.getInt("conn_glob_ok", lastConnGlobalOk)
            lastConnGlobalTotal = savedInstanceState.getInt("conn_glob_total", lastConnGlobalTotal)
            lastConnDomesticOk = savedInstanceState.getInt("conn_dom_ok", lastConnDomesticOk)
            lastConnDomesticTotal = savedInstanceState.getInt("conn_dom_total", lastConnDomesticTotal)
        }
        applyConnectivityState(
            conditionCode = lastConnCondition,
            pulseColorCode = lastConnPulseColor,
            rttMs = lastConnRttMs,
            vpnConnected = isVpnConnected,
            title = lastConnTitle,
            subtitle = lastConnSubtitle,
            globalOk = lastConnGlobalOk,
            globalTotal = lastConnGlobalTotal,
            domesticOk = lastConnDomesticOk,
            domesticTotal = lastConnDomesticTotal
        )
        safeStartConnectivityMonitor()

        if (lastAccountGroup.isNotEmpty()) {
            findViewById<TextView>(R.id.tvAccountGroup)?.text = lastAccountGroup
            findViewById<TextView>(R.id.tvAccountExpires)?.text = lastAccountExpires
            findViewById<TextView>(R.id.tvAccountTraffic)?.text = lastAccountTraffic
            findViewById<TextView>(R.id.tvAccountSpeed)?.text = lastAccountSpeed
            findViewById<TextView>(R.id.tvAccountSessions)?.text = lastAccountSessions
        }

        if (lastVpnEventText.isNotEmpty()) {
            tvVpnEventStatus.text = lastVpnEventText
            tvVpnEventStatus.setTextColor(lastVpnEventColor)
        }

        connectButton.setupTvFocusAnimator()
        btnCheckUpdate.setupTvFocusAnimator()
        serverSelectContainer.setupTvFocusAnimator()
        btnShowLogs.setupTvFocusAnimator()

        btnCheckUpdate.setOnClickListener {
            if (isCheckingUpdates) return@setOnClickListener

            isCheckingUpdates = true
            btnCheckUpdate.isEnabled = false
            btnSettings.isEnabled = false
            btnCheckUpdate.alpha = 0.3f

            logToConsole("Checking for system updates...")

            Thread {
                try {
                    checkUpdates(selectedConfigContent)
                } catch (e: Exception) {
                    runOnUiThread {
                        isCheckingUpdates = false
                        btnCheckUpdate.isEnabled = currentUiState == State.DISCONNECTED
                        btnSettings.isEnabled = currentUiState == State.DISCONNECTED
                        btnCheckUpdate.alpha = if (btnCheckUpdate.isEnabled) 1.0f else 0.3f
                        btnSettings.alpha = if (btnSettings.isEnabled) 1.0f else 0.3f
                        logToConsole("Update check error: ${e.message}")
                    }
                }
            }.start()
        }

        handleIntent(intent)
    }

    // --- ДИАЛОГ ВВОДА ССЫЛКИ НА СЕРВЕРНЫЙ КОНФИГ ---

    private fun showEnterUrlDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "Серверный конфиг"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 6.dpToPx())
        }

        val hintTv = TextView(this).apply {
            text = "Введите адрес сервера конфигурации или выберите локальный файл:"
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, 16.dpToPx())
        }

        val input = EditText(this).apply {
            hint = "example.com/config или https://..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7E7E7E"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(Color.parseColor("#2C2C2E"))
            }
        }

        val errorTv = TextView(this).apply {
            setTextColor(Color.parseColor("#FF5252"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
            setPadding(4.dpToPx(), 6.dpToPx(), 4.dpToPx(), 0)
        }

        val loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8.dpToPx(), 0, 0)
            }
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 14.dpToPx(), 0, 0)
            }
        }

        val btnCancel = Button(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnQr = Button(this).apply {
            text = "QR"
            setTextColor(Color.parseColor("#EEBC7A"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnFileConfig = Button(this).apply {
            text = "Конфиг"
            setTextColor(Color.parseColor("#EEBC7A"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnOk = Button(this).apply {
            text = "ОК"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        buttonBar.addView(btnCancel)
        buttonBar.addView(btnQr)
        buttonBar.addView(btnFileConfig)
        buttonBar.addView(btnOk)

        container.addView(titleTv)
        container.addView(hintTv)
        container.addView(input)
        container.addView(errorTv)
        container.addView(loadingBar)
        container.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
            finishAffinity()
        }

        btnQr.setOnClickListener {
            dialog.dismiss()
            startQrScanner()
        }

        btnFileConfig.setOnClickListener {
            dialog.dismiss()
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        btnOk.setOnClickListener {
            val rawUrl = input.text.toString().trim()
            if (rawUrl.isEmpty()) {
                errorTv.text = "Поле ссылки не может быть пустым"
                errorTv.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (rawUrl.contains(" ")) {
                errorTv.text = "Ссылка не должна содержать пробелов"
                errorTv.visibility = View.VISIBLE
                return@setOnClickListener
            }

            btnOk.isEnabled = false
            btnCancel.isEnabled = false
            btnQr.isEnabled = false
            btnFileConfig.isEnabled = false
            errorTv.visibility = View.GONE
            loadingBar.visibility = View.VISIBLE

            fetchServerConfigFromUrl(
                url = rawUrl,
                showToastOnSuccess = true,
                onSuccess = {
                    dialog.dismiss()
                },
                onError = { errMsg ->
                    btnOk.isEnabled = true
                    btnCancel.isEnabled = true
                    btnQr.isEnabled = true
                    btnFileConfig.isEnabled = true
                    loadingBar.visibility = View.GONE
                    errorTv.text = errMsg
                    errorTv.visibility = View.VISIBLE
                }
            )
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    // --- ДИАЛОГ РЕДАКТИРОВАНИЯ ССЫЛКИ В НАСТРОЙКАХ ---

    private fun showEditSubscriptionUrlDialog() {
        val currentUrl = getSavedServerUrl() ?: ""

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "Ссылка на конфигурацию"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 6.dpToPx())
        }

        val subtitleTv = TextView(this).apply {
            text = "Используется для автоматического обновления профиля с сервера"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, 0, 0, 16.dpToPx())
        }

        val input = EditText(this).apply {
            setText(currentUrl)
            setSelection(currentUrl.length)
            hint = "example.com/config или https://..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7E7E7E"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(Color.parseColor("#2C2C2E"))
            }
        }

        val errorTv = TextView(this).apply {
            setTextColor(Color.parseColor("#FF5252"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
            setPadding(4.dpToPx(), 6.dpToPx(), 4.dpToPx(), 0)
        }

        val loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8.dpToPx(), 0, 0)
            }
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 14.dpToPx(), 0, 0)
            }
        }

        val btnClear = Button(this).apply {
            text = "Удалить"
            setTextColor(Color.parseColor("#FF5252"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnCancel = Button(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnSave = Button(this).apply {
            text = "Сохранить"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        if (currentUrl.isNotEmpty()) {
            buttonBar.addView(btnClear)
        }
        buttonBar.addView(btnCancel)
        buttonBar.addView(btnSave)

        container.addView(titleTv)
        container.addView(subtitleTv)
        container.addView(input)
        container.addView(errorTv)
        container.addView(loadingBar)
        container.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnClear.setOnClickListener {
            saveServerUrl(null)
            val configs = getSavedConfigs()
            configs.removeAll { it.isServerConfig || it.id == SERVER_CONFIG_ID }
            saveConfigsToPrefs(configs)
            logToConsole("Ссылка на Server config удалена")
            Toast.makeText(this, "Ссылка удалена", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newUrl = input.text.toString().trim()

            if (newUrl.isEmpty()) {
                errorTv.text = "Ссылка не может быть пустой"
                errorTv.visibility = View.VISIBLE
                return@setOnClickListener
            }

            btnSave.isEnabled = false
            btnCancel.isEnabled = false
            btnClear.isEnabled = false
            errorTv.visibility = View.GONE
            loadingBar.visibility = View.VISIBLE

            fetchServerConfigFromUrl(
                url = newUrl,
                showToastOnSuccess = true,
                onSuccess = {
                    dialog.dismiss()
                },
                onError = { error ->
                    btnSave.isEnabled = true
                    btnCancel.isEnabled = true
                    btnClear.isEnabled = true
                    loadingBar.visibility = View.GONE
                    errorTv.text = error
                    errorTv.visibility = View.VISIBLE
                }
            )
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    // --- МЕНЮ КОНФИГУРАЦИЙ ---

    private fun showConfigManagerDialog() {
        val rootLayout = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 20.dpToPx())
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24.dpToPx())
            }
        }

        val btnClose = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#262626"))
            }
            val buttonSize = 40.dpToPx()
            val paddingSize = 10.dpToPx()
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(0, 0, 16.dpToPx(), 0)
            }
            setPadding(paddingSize, paddingSize, paddingSize, paddingSize)
            setupTvFocusAnimator()
        }

        val titleView = TextView(this).apply {
            text = "Конфигурации"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        headerLayout.addView(btnClose)
        headerLayout.addView(titleView)

        val btnAddConfigLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
                setStroke(1.dpToPx(), Color.parseColor("#2C2C2E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10.dpToPx())
            }
            isClickable = true
            isFocusable = true
            setupTvFocusAnimator()
            setOnClickListener {
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        }

        val iconPlus = ImageView(this).apply {
            setImageResource(R.drawable.ic_pluse)
            setColorFilter(Color.parseColor("#669D29"))
            val iconSize = 18.dpToPx()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                setMargins(0, 0, 8.dpToPx(), 0)
            }
        }

        val textPlus = TextView(this).apply {
            text = "Добавить .toml файл"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
        }

        btnAddConfigLayout.addView(iconPlus)
        btnAddConfigLayout.addView(textPlus)

        val btnAddQrLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
                setStroke(1.dpToPx(), Color.parseColor("#2C2C2E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 20.dpToPx())
            }
            isClickable = true
            isFocusable = true
            setupTvFocusAnimator()
            setOnClickListener {
                startQrScanner()
            }
        }

        val iconQr = ImageView(this).apply {
            setImageResource(R.drawable.qr)
            setColorFilter(Color.parseColor("#EEBC7A"))
            val iconSize = 18.dpToPx()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                setMargins(0, 0, 8.dpToPx(), 0)
            }
        }

        val textQr = TextView(this).apply {
            text = "Импорт по QR"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
        }

        btnAddQrLayout.addView(iconQr)
        btnAddQrLayout.addView(textQr)

        val sectionTitle = TextView(this).apply {
            text = "ВАШИ КОНФИГУРАЦИИ"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#7E7E7E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4.dpToPx(), 0, 12.dpToPx())
            }
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(listContainer)

        rootLayout.addView(headerLayout)
        rootLayout.addView(btnAddConfigLayout)
        rootLayout.addView(btnAddQrLayout)
        rootLayout.addView(sectionTitle)
        rootLayout.addView(scrollView)

        fun populateList() {
            listContainer.removeAllViews()
            val configs = getSavedConfigs()
            val prefs = getAppPrefs()
            val activeId = prefs.getString("active_config_id", null)

            if (configs.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "Список конфигураций пуст"
                    setTextColor(Color.GRAY)
                    setPadding(16.dpToPx(), 32.dpToPx(), 16.dpToPx(), 32.dpToPx())
                    gravity = android.view.Gravity.CENTER
                }
                listContainer.addView(emptyTv)
                return
            }

            for (item in configs) {
                val isActive = item.id == activeId || (activeId == null && item.content == selectedConfigContent)

                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14 * resources.displayMetrics.density
                        setColor(if (isActive) Color.parseColor("#375417") else Color.parseColor("#1C1C1E"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 6.dpToPx(), 0, 6.dpToPx())
                    }
                    setupTvFocusAnimator()
                }

                val ivIndicator = ImageView(this).apply {
                    setImageResource(if (isActive) R.drawable.leftchev else R.drawable.uncheck)
                    setColorFilter(if (isActive) Color.parseColor("#669D29") else Color.parseColor("#3b3b3b"))
                    val iconSize = 22.dpToPx()
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        setMargins(0, 0, 8.dpToPx(), 0)
                    }
                }

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }

                val nameTv = TextView(this).apply {
                    text = item.name
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.WHITE)
                }
                textCol.addView(nameTv)

                if (item.isServerConfig) {
                    val badgeTv = TextView(this).apply {
                        text = "СЕРВЕРНЫЙ КОНФИГ"
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                        setTextColor(Color.parseColor("#EEBC7A"))
                    }
                    textCol.addView(badgeTv)
                }

                itemLayout.setOnClickListener {
                    if (isVpnConnected) {
                        logToConsole("Нельзя менять конфигурацию во время активного подключения")
                        Toast.makeText(this, "Сначала отключите VPN", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    selectedConfigContent = item.content
                    selectedConfigName = item.name
                    saveConfigToPrefs(item.content, item.name)
                    prefs.edit().putString("active_config_id", item.id).apply()
                    setupServerSelector()
                    safeStartConnectivityMonitor()
                    logToConsole("Выбрана конфигурация: ${item.name}")
                    populateList()
                }

                itemLayout.addView(ivIndicator)
                itemLayout.addView(textCol)

                if (item.isServerConfig) {
                    val btnRefresh = android.widget.ImageButton(this).apply {
                        setImageResource(R.drawable.update)
                        setColorFilter(Color.parseColor("#EEBC7A"))
                        setBackgroundColor(Color.TRANSPARENT)
                        val iconSize = 22.dpToPx()
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                            setMargins(12.dpToPx(), 0, 12.dpToPx(), 0)
                        }
                        setOnClickListener {
                            val savedUrl = getSavedServerUrl()
                            if (!savedUrl.isNullOrBlank()) {
                                Toast.makeText(this@MainActivity, "Запрос обновления...", Toast.LENGTH_SHORT).show()
                                fetchServerConfigFromUrl(savedUrl, showToastOnSuccess = true)
                            }
                        }
                    }
                    itemLayout.addView(btnRefresh)
                } else {
                    val btnRename = android.widget.ImageButton(this).apply {
                        setImageResource(R.drawable.pen)
                        setColorFilter(Color.parseColor("#AAAAAA"))
                        setBackgroundColor(Color.TRANSPARENT)
                        val iconSize = 22.dpToPx()
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                            setMargins(12.dpToPx(), 0, 12.dpToPx(), 0)
                        }
                        setOnClickListener {
                            showRenameDialog(item) {
                                populateList()
                            }
                        }
                    }
                    itemLayout.addView(btnRename)
                }

                val btnDelete = android.widget.ImageButton(this).apply {
                    setImageResource(R.drawable.delete)
                    setColorFilter(Color.parseColor("#AAAAAA"))
                    setBackgroundColor(Color.TRANSPARENT)
                    val iconSize = 22.dpToPx()
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    setOnClickListener {
                        if (isVpnConnected && isActive) {
                            Toast.makeText(this@MainActivity, "Нельзя удалить активный конфиг", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        showDeleteConfirmation(item) {
                            populateList()
                        }
                    }
                }
                itemLayout.addView(btnDelete)

                listContainer.addView(itemLayout)
            }
        }

        refreshConfigListRunnable = Runnable { populateList() }
        populateList()

        val dialog = AlertDialog.Builder(this)
            .setOnDismissListener {
                activeConfigDialog = null
                refreshConfigListRunnable = null
                if (getSavedConfigs().isEmpty()) {
                    showEnterUrlDialog()
                }
            }
            .create()

        activeConfigDialog = dialog

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.setContentView(rootLayout)

        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun showRenameDialog(item: ConfigItem, onUpdated: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "Переименовать"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16.dpToPx())
        }

        val input = EditText(this).apply {
            setText(item.name)
            setSelection(item.name.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7E7E7E"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(Color.parseColor("#2C2C2E"))
            }
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10.dpToPx(), 0, 0)
            }
        }

        val btnCancel = Button(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnSave = Button(this).apply {
            text = "Сохранить"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        buttonBar.addView(btnCancel)
        buttonBar.addView(btnSave)

        container.addView(titleTv)
        container.addView(input)
        container.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isNotEmpty()) {
                val configs = getSavedConfigs()
                val target = configs.find { it.id == item.id }
                if (target != null) {
                    target.name = newName
                    val prefs = getAppPrefs()
                    val activeId = prefs.getString("active_config_id", null)
                    saveConfigsToPrefs(configs, activeId)

                    if (item.id == activeId || selectedConfigName == item.name) {
                        selectedConfigName = newName
                        saveConfigToPrefs(item.content, newName)
                    }
                    logToConsole("Конфигурация переименована в: $newName")
                    onUpdated()
                }
            }
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    private fun showDeleteConfirmation(item: ConfigItem, onUpdated: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "Удаление"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12.dpToPx())
        }

        val messageTv = TextView(this).apply {
            text = "Удалить конфигурацию \"${item.name}\"?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#CCCCCC"))
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 20.dpToPx(), 0, 0)
            }
        }

        val btnCancel = Button(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnDelete = Button(this).apply {
            text = "Удалить"
            setTextColor(Color.parseColor("#FF5252"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        buttonBar.addView(btnCancel)
        buttonBar.addView(btnDelete)

        container.addView(titleTv)
        container.addView(messageTv)
        container.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnDelete.setOnClickListener {
            val configs = getSavedConfigs()
            configs.removeAll { it.id == item.id }

            if (item.isServerConfig || item.id == SERVER_CONFIG_ID) {
                saveServerUrl(null)
            }

            val prefs = getAppPrefs()
            var activeId = prefs.getString("active_config_id", null)

            if (activeId == item.id) {
                val nextActive = configs.firstOrNull()
                if (nextActive != null) {
                    activeId = nextActive.id
                    selectedConfigContent = nextActive.content
                    selectedConfigName = nextActive.name
                    saveConfigToPrefs(nextActive.content, nextActive.name)
                    setupServerSelector()
                } else {
                    activeId = null
                    selectedConfigContent = null
                    selectedConfigName = "Unknown"
                    prefs.edit()
                        .remove("config_content")
                        .remove("config_name")
                        .remove("active_config_id")
                        .apply()
                    availableServers.clear()
                    serverSelectContainer.visibility = View.GONE
                }
            }

            saveConfigsToPrefs(configs, activeId)
            logToConsole("Конфигурация \"${item.name}\" удалена")
            onUpdated()
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    // --- НАСТРОЙКИ (SETTINGS) ---

    data class SettingItem(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val onClick: () -> Unit
    )

    private fun showSettingsDialog() {
        var dialogInstance: AlertDialog? = null
        val currentSubUrl = getSavedServerUrl()

        val settingsList = listOf(
            SettingItem(
                title = "Конфигурации",
                subtitle = "Управление профилями, добавление файлов и QR",
                iconRes = R.drawable.file
            ) {
                showConfigManagerDialog()
            },
            SettingItem(
                title = "Ссылка на конфигурацию",
                subtitle = if (!currentSubUrl.isNullOrBlank()) currentSubUrl else "Не указана (нажмите для ввода)",
                iconRes = R.drawable.qr
            ) {
                showEditSubscriptionUrlDialog()
            },
            SettingItem(
                title = "Раздельное туннелирование",
                subtitle = "Выбор приложений, работающих через VPN",
                iconRes = R.drawable.apps_white
            ) {
                startActivity(Intent(this, AppSelectionActivity::class.java))
            },
            SettingItem(
                title = "Локальные правила (Overrides)",
                subtitle = "Списки прямого доступа (Direct), блокировки (Block) и туннеля (Proxy)",
                iconRes = R.drawable.dot
            ) {
                showRoutingOverridesDialog()
            }
        )

        val rootLayout = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 20.dpToPx())
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24.dpToPx())
            }
        }

        val btnClose = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#262626"))
            }
            val buttonSize = 40.dpToPx()
            val paddingSize = 10.dpToPx()
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(0, 0, 16.dpToPx(), 0)
            }
            setPadding(paddingSize, paddingSize, paddingSize, paddingSize)
            setupTvFocusAnimator()
        }

        val titleView = TextView(this).apply {
            text = "Настройки"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        headerLayout.addView(btnClose)
        headerLayout.addView(titleView)

        val sectionTitle = TextView(this).apply {
            text = "ПАРАМЕТРЫ ПРИЛОЖЕНИЯ"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#7E7E7E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4.dpToPx(), 0, 12.dpToPx())
            }
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        for (item in settingsList) {
            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * resources.displayMetrics.density
                    setColor(Color.parseColor("#1C1C1E"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 6.dpToPx(), 0, 6.dpToPx())
                }
                isClickable = true
                isFocusable = true
                setupTvFocusAnimator()
                setOnClickListener {
                    item.onClick()
                }
            }

            val iconView = ImageView(this).apply {
                setImageResource(item.iconRes)
                setColorFilter(Color.parseColor("#EEBC7A"))
                val iconSize = 22.dpToPx()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    setMargins(0, 0, 14.dpToPx(), 0)
                }
            }

            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val tvTitle = TextView(this).apply {
                text = item.title
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            }

            val tvSubtitle = TextView(this).apply {
                text = item.subtitle
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#8E8E93"))
                setPadding(0, 2.dpToPx(), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            textContainer.addView(tvTitle)
            textContainer.addView(tvSubtitle)

            val chevron = ImageView(this).apply {
                setImageResource(R.drawable.chevron_down)
                setColorFilter(Color.parseColor("#5A5A5E"))
                rotation = 270f
                val iconSize = 18.dpToPx()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }

            itemCard.addView(iconView)
            itemCard.addView(textContainer)
            itemCard.addView(chevron)

            listContainer.addView(itemCard)
        }

        scrollView.addView(listContainer)
        rootLayout.addView(headerLayout)
        rootLayout.addView(sectionTitle)
        rootLayout.addView(scrollView)

        dialogInstance = AlertDialog.Builder(this).create()

        btnClose.setOnClickListener {
            dialogInstance.dismiss()
        }

        dialogInstance.show()
        dialogInstance.setContentView(rootLayout)
        dialogInstance.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }

    // --- ДИАЛОГ РЕДАКТИРОВАНИЯ [ROUTING.OVERRIDES] ---

    private fun showRoutingOverridesDialog() {
        val overrides = loadActiveRoutingOverrides()
        var currentTab = OverrideTab.DIRECT
        var dialogInstance: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 20.dpToPx())
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16.dpToPx())
            }
        }

        val btnClose = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#262626"))
            }
            val buttonSize = 40.dpToPx()
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(0, 0, 16.dpToPx(), 0)
            }
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 10.dpToPx())
            setupTvFocusAnimator()
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val titleView = TextView(this).apply {
            text = "Локальные правила (Overrides)"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        val subTitleView = TextView(this).apply {
            text = "Профиль: $selectedConfigName"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#EEBC7A"))
        }

        titleCol.addView(titleView)
        titleCol.addView(subTitleView)
        headerLayout.addView(btnClose)
        headerLayout.addView(titleCol)

        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10.dpToPx())
            }
        }

        val btnTabDirect = Button(this)
        val btnTabBlock = Button(this)
        val btnTabProxy = Button(this)

        val tvHint = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 10.dpToPx())
        }

        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 12.dpToPx())
            }
        }

        val etInput = EditText(this).apply {
            hint = "*.domain, 192.168.1.0/24"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7E7E7E"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, 8.dpToPx(), 0)
            }
        }

        val btnAdd = Button(this).apply {
            text = "ДОБАВИТЬ"
            setTextColor(Color.BLACK)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(Color.parseColor("#EEBC7A"))
            }
            setupTvFocusAnimator()
        }

        inputLayout.addView(etInput)
        inputLayout.addView(btnAdd)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(listContainer)

        val btnSaveApply = Button(this).apply {
            text = "СОХРАНИТЬ И ПРИМЕНИТЬ"
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.parseColor("#375417"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dpToPx()
            ).apply {
                setMargins(0, 12.dpToPx(), 0, 0)
            }
            setupTvFocusAnimator()
        }

        fun updateTabsAndList() {
            val styleTab = { btn: Button, title: String, count: Int, isSel: Boolean, activeColor: Int ->
                btn.text = "$title ($count)"
                btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                btn.setTypeface(null, android.graphics.Typeface.BOLD)
                btn.setTextColor(if (isSel) Color.BLACK else activeColor)
                btn.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8f * resources.displayMetrics.density
                    setColor(if (isSel) activeColor else Color.parseColor("#1C1C1E"))
                }
                btn.layoutParams = LinearLayout.LayoutParams(0, 38.dpToPx(), 1.0f).apply {
                    setMargins(3.dpToPx(), 0, 3.dpToPx(), 0)
                }
            }

            styleTab(btnTabDirect, "Direct", overrides.direct.size, currentTab == OverrideTab.DIRECT, Color.parseColor("#4CAF50"))
            styleTab(btnTabBlock, "Block", overrides.block.size, currentTab == OverrideTab.BLOCK, Color.parseColor("#FF5252"))
            styleTab(btnTabProxy, "Proxy", overrides.proxy.size, currentTab == OverrideTab.PROXY, Color.parseColor("#EEBC7A"))

            when (currentTab) {
                OverrideTab.DIRECT -> {
                    tvHint.text = "⚡ Direct Split: трафик идёт напрямую мимо VPN через физическую сеть."
                    tvHint.setTextColor(Color.parseColor("#4CAF50"))
                    etInput.hint = "*.home.arpa, 192.168.1.0/24"
                }
                OverrideTab.BLOCK -> {
                    tvHint.text = "🚫 Block: запросы блокируются локально внутри DNS (0.0.0.0)."
                    tvHint.setTextColor(Color.parseColor("#FF5252"))
                    etInput.hint = "metrics.telemetry.com, 198.51.100.0/24"
                }
                OverrideTab.PROXY -> {
                    tvHint.text = "🛡️ Proxy: трафик принудительно направляется в шифрованный VPN-туннель."
                    tvHint.setTextColor(Color.parseColor("#EEBC7A"))
                    etInput.hint = "blocked-service.dev, internal-api.io"
                }
            }

            listContainer.removeAllViews()
            val currentItems = when (currentTab) {
                OverrideTab.DIRECT -> overrides.direct
                OverrideTab.BLOCK -> overrides.block
                OverrideTab.PROXY -> overrides.proxy
            }

            if (currentItems.isEmpty()) {
                val emptyTv = TextView(this@MainActivity).apply {
                    text = "Список пуст"
                    setTextColor(Color.GRAY)
                    setPadding(16.dpToPx(), 32.dpToPx(), 16.dpToPx(), 32.dpToPx())
                    gravity = android.view.Gravity.CENTER
                }
                listContainer.addView(emptyTv)
            } else {
                for ((index, item) in currentItems.withIndex()) {
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 8f * resources.displayMetrics.density
                            setColor(Color.parseColor("#1C1C1E"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 4.dpToPx(), 0, 4.dpToPx())
                        }
                    }

                    val bulletColor = when (currentTab) {
                        OverrideTab.DIRECT -> Color.parseColor("#4CAF50")
                        OverrideTab.BLOCK -> Color.parseColor("#FF5252")
                        OverrideTab.PROXY -> Color.parseColor("#EEBC7A")
                    }

                    val bullet = TextView(this@MainActivity).apply {
                        text = "• "
                        setTextColor(bulletColor)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                    }

                    val tvItem = TextView(this@MainActivity).apply {
                        text = item
                        setTextColor(Color.WHITE)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = android.graphics.Typeface.MONOSPACE
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                    }

                    val btnDelete = android.widget.ImageButton(this@MainActivity).apply {
                        setImageResource(R.drawable.delete)
                        setColorFilter(Color.parseColor("#FF5252"))
                        setBackgroundColor(Color.TRANSPARENT)
                        val iconSize = 20.dpToPx()
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        setOnClickListener {
                            currentItems.removeAt(index)
                            updateTabsAndList()
                        }
                    }

                    row.addView(bullet)
                    row.addView(tvItem)
                    row.addView(btnDelete)
                    listContainer.addView(row)
                }
            }
        }

        btnTabDirect.setOnClickListener { currentTab = OverrideTab.DIRECT; updateTabsAndList() }
        btnTabBlock.setOnClickListener { currentTab = OverrideTab.BLOCK; updateTabsAndList() }
        btnTabProxy.setOnClickListener { currentTab = OverrideTab.PROXY; updateTabsAndList() }

        tabsLayout.addView(btnTabDirect)
        tabsLayout.addView(btnTabBlock)
        tabsLayout.addView(btnTabProxy)

        btnAdd.setOnClickListener {
            val target = etInput.text.toString().trim()
            if (target.isEmpty() || target.contains(" ")) {
                Toast.makeText(this, "Укажите корректный домен, IP или CIDR", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentList = when (currentTab) {
                OverrideTab.DIRECT -> overrides.direct
                OverrideTab.BLOCK -> overrides.block
                OverrideTab.PROXY -> overrides.proxy
            }

            if (currentList.contains(target)) {
                Toast.makeText(this, "Правило уже существует в этом списке", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            currentList.add(target)
            etInput.setText("")
            updateTabsAndList()
        }

        btnSaveApply.setOnClickListener {
            saveActiveRoutingOverrides(overrides)
            Toast.makeText(this, "Правила сохранены", Toast.LENGTH_SHORT).show()

            if (isVpnConnected) {
                logToConsole("Переподключение VPN с обновленными правилами...")
                stopVpnService()
                mainHandler.postDelayed({
                    attemptVpnConnection()
                }, 600)
            }
            dialogInstance?.dismiss()
        }

        btnClose.setOnClickListener { dialogInstance?.dismiss() }

        rootLayout.addView(headerLayout)
        rootLayout.addView(tabsLayout)
        rootLayout.addView(tvHint)
        rootLayout.addView(inputLayout)
        rootLayout.addView(scrollView)
        rootLayout.addView(btnSaveApply)

        updateTabsAndList()

        dialogInstance = AlertDialog.Builder(this).create()
        dialogInstance.show()
        dialogInstance.setContentView(rootLayout)
        dialogInstance.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ UI, ЛОГОВ И СЛУЖБЫ VPN ---

    private fun showLogsDialog() {
        val rootLayout = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(20, 20, 20, 20)
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        val btnClose = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#262626"))
            }
            val buttonSize = 40.dpToPx()
            val paddingSize = 10.dpToPx()
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(0, 0, 16.dpToPx(), 0)
            }
            setPadding(paddingSize, paddingSize, paddingSize, paddingSize)
            setupTvFocusAnimator()
        }

        val titleView = TextView(this).apply {
            text = "Системные логи"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val btnSave = Button(this).apply {
            text = "Сохранить"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 10.dpToPx().toFloat()
                setColor(Color.parseColor("#262626"))
            }
            setPadding(20.dpToPx(), 0, 20.dpToPx(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                40.dpToPx()
            ).apply {
                setMargins(16.dpToPx(), 0, 0, 0)
            }
            setupTvFocusAnimator()
        }

        headerLayout.addView(btnClose)
        headerLayout.addView(titleView)
        headerLayout.addView(btnSave)

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            isStackFromBottom = false
        }

        val paddingV = 6.dpToPx()
        val adapter = object : ArrayAdapter<CharSequence>(this, 0, logLines) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(ContextCompat.getColor(context, R.color.white))
                    setPadding(0, paddingV, 0, paddingV)
                    setTextIsSelectable(true)
                }
                view.text = getItem(position)
                return view
            }
        }
        listView.adapter = adapter

        rootLayout.addView(headerLayout)
        rootLayout.addView(listView)

        activeLogAdapter = adapter
        activeLogListView = listView

        val dialog = AlertDialog.Builder(this)
            .setOnDismissListener {
                activeLogAdapter = null
                activeLogListView = null
            }
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            showSaveLogDialog()
        }

        dialog.show()
        dialog.setContentView(rootLayout)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        if (logLines.isNotEmpty()) {
            listView.post {
                listView.setSelection(logLines.size - 1)
            }
        }
    }

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { writeLogToUri(it) }
    }

    private fun launchSaveLog(extension: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        try {
            saveLogLauncher.launch("anet_log_$timestamp.$extension")
        } catch (e: Exception) {
            logToConsole("Не удалось открыть диалог сохранения: ${e.message}")
        }
    }

    private fun writeLogToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    for ((index, line) in logLines.withIndex()) {
                        if (index > 0) writer.write("\n")
                        writer.write(line.toString())
                    }
                }
                Toast.makeText(this, "Лог сохранён: ${getFileName(uri)}", Toast.LENGTH_SHORT).show()
                logToConsole("Лог сохранён: ${getFileName(uri)}")
            } ?: logToConsole("Не удалось открыть файл для записи лога")
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения лога: ${e.message}", Toast.LENGTH_SHORT).show()
            logToConsole("Ошибка сохранения лога: ${e.message}")
        }
    }

    private fun showSaveLogDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "В каком формате сохранить лог?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16.dpToPx())
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10.dpToPx(), 0, 0)
            }
        }

        val btnCancel = Button(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnSaveTxt = Button(this).apply {
            text = ".txt"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnSaveLog = Button(this).apply {
            text = ".log"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        buttonBar.addView(btnCancel)
        buttonBar.addView(btnSaveTxt)
        buttonBar.addView(btnSaveLog)

        container.addView(titleTv)
        container.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSaveTxt.setOnClickListener {
            launchSaveLog("txt")
            dialog.dismiss()
        }

        btnSaveLog.setOnClickListener {
            launchSaveLog("log")
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    private fun startQrScanner() {
        isScanningQr = true
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                isScanningQr = false
                val rawUrl = barcode.rawValue
                if (!rawUrl.isNullOrBlank()) {
                    Toast.makeText(this, "QR распознан, загрузка профиля...", Toast.LENGTH_SHORT).show()
                    fetchServerConfigFromUrl(rawUrl, showToastOnSuccess = true)
                } else {
                    Toast.makeText(this, "QR-код не содержит валидную ссылку", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnCanceledListener {
                isScanningQr = false
            }
            .addOnFailureListener { e ->
                isScanningQr = false
                Toast.makeText(this, "Ошибка QR-сканера: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val content = readTextFromUri(uri)
            val name = getFileName(uri)

            if (content.isNotEmpty() && inspectServers(content) != null) {
                addAndActivateConfig(name, content)
                logToConsole("Loaded config: $name (${content.length} bytes)")
                Toast.makeText(this, "Конфигурация \"$name\" добавлена", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Ошибка: некорректный .toml конфиг", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            } ?: ""
        } catch (e: Exception) {
            logToConsole("IO Error: ${e.message}")
            ""
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "config.toml"
    }

    private fun saveConfigToPrefs(content: String, name: String) {
        val prefs = getAppPrefs()
        prefs.edit()
            .putString("config_content", content)
            .putString("config_name", name)
            .apply()
    }

    private fun loadConfigFromPrefs() {
        val prefs = getAppPrefs()
        val configs = getSavedConfigs()

        val activeId = prefs.getString("active_config_id", null)
        val activeItem = configs.find { it.id == activeId } ?: configs.firstOrNull()

        if (activeItem != null) {
            selectedConfigContent = activeItem.content
            selectedConfigName = activeItem.name
            prefs.edit().putString("active_config_id", activeItem.id).apply()
        } else {
            selectedConfigContent = null
            selectedConfigName = "Unknown"
            prefs.edit()
                .remove("active_config_id")
                .remove("config_content")
                .remove("config_name")
                .apply()
        }
    }

    private fun checkBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    logToConsole("Не удалось открыть настройки батареи: ${e.message}")
                }
            }
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            logToConsole("VPN permission denied")
            setUiState(State.DISCONNECTED)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        attemptVpnConnection()
    }

    private fun checkPermissionsAndStart() {
        if (selectedConfigContent == null) {
            logToConsole("Error: No config selected!")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                attemptVpnConnection()
            }
        } else {
            attemptVpnConnection()
        }
    }

    private fun attemptVpnConnection() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        setUiState(State.CONNECTING)

        val intent = Intent(this, ANetVpnService::class.java)
        intent.action = ANetVpnService.ACTION_CONNECT
        intent.putExtra("CONFIG", selectedConfigContent)
        intent.putExtra("SELECTED_SERVER", selectedServerName)

        val prefs = getAppPrefs()
        val appsSet = prefs.getStringSet("allowed_apps", emptySet())
        if (!appsSet.isNullOrEmpty()) {
            intent.putStringArrayListExtra("ALLOWED_APPS", ArrayList(appsSet))
        }

        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        val intent = Intent(this, ANetVpnService::class.java)
        intent.action = ANetVpnService.ACTION_STOP
        startService(intent)
        setUiState(State.CONNECTING, "STOPPING...")
    }

    private fun showErrorDialog(message: String) {
        runOnUiThread {
            if (activeErrorDialog?.isShowing == true) return@runOnUiThread

            val builder = AlertDialog.Builder(this)
            builder.setTitle("ОШИБКА ДОСТУПА")

            val cleanMsg = message
                .replace("ERROR:", "")
                .replace("WARN:", "")
                .replace("[CORE AUTH]", "")
                .replace(Regex("\\[AUTH\\].*failed:"), "")
                .trim()

            builder.setMessage(cleanMsg)
            builder.setPositiveButton("ПОНЯТНО") { dialog, _ ->
                dialog.dismiss()
                activeErrorDialog = null
            }

            builder.setOnCancelListener { activeErrorDialog = null }

            activeErrorDialog = builder.create()
            activeErrorDialog?.show()
            activeErrorDialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(Color.parseColor("#FF6400"))
        }
    }

    private fun inspectServers(toml: String, reportError: Boolean = true): List<ServerModel>? {
        val result = inspectConfig(toml).lineSequence().toList()
        if (result.firstOrNull() != "OK") {
            val error = result.drop(1).joinToString("\n").ifBlank { "Invalid configuration" }
            logToConsole("Ошибка конфигурации: $error")
            if (reportError) showErrorDialog(error)
            return null
        }
        return result.drop(1).filter { it.isNotBlank() }.map { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2) {
                ServerModel(parts[0], parts[1])
            } else {
                ServerModel(parts[0], parts[0])
            }
        }
    }

    private fun setupServerSelector() {
        val content = selectedConfigContent ?: return
        availableServers.clear()
        val servers = inspectServers(content) ?: return
        availableServers.addAll(servers)

        if (availableServers.isEmpty()) {
            serverSelectContainer.visibility = View.GONE
            return
        }

        serverSelectContainer.visibility = View.VISIBLE

        val formattedNames = availableServers.map { it.getFormattedName() }

        val prefs = getAppPrefs()
        val lastSelected = prefs.getString("selected_server_${selectedConfigName}", "") ?: ""
        val index = availableServers.indexOfFirst { it.id == lastSelected }

        if (index >= 0) {
            selectedServerName = availableServers[index].id
            serverSelectTextView.text = availableServers[index].name
        } else {
            selectedServerName = availableServers.first().id
            serverSelectTextView.text = availableServers.first().name
            prefs.edit().putString("selected_server_${selectedConfigName}", selectedServerName).apply()
        }

        val listPopupWindow = ListPopupWindow(this, null, androidx.appcompat.R.attr.listPopupWindowStyle)
        listPopupWindow.anchorView = serverSelectContainer
        listPopupWindow.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.popup_bg))

        val adapter = ArrayAdapter(this, R.layout.spinner_dropdown_item, formattedNames)
        listPopupWindow.setAdapter(adapter)

        listPopupWindow.setOnItemClickListener { _, _, position, _ ->
            if (position in availableServers.indices) {
                val server = availableServers[position]
                selectedServerName = server.id
                serverSelectTextView.text = server.name
                prefs.edit().putString("selected_server_${selectedConfigName}", server.id).apply()
            }
            listPopupWindow.dismiss()
        }

        serverSelectContainer.setOnClickListener {
            if (isVpnConnected) {
                Toast.makeText(this, "Соединение активно, выбор ноды заблокирован", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            listPopupWindow.show()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getBooleanExtra("is_account_info", false) == true) {
                val billing = intent.getStringExtra("billing") ?: "—"
                val group = intent.getStringExtra("group") ?: "—"
                val sessions = intent.getStringExtra("sessions") ?: "—"
                val speed = intent.getStringExtra("speed") ?: "—"
                val consumed = intent.getStringExtra("consumed") ?: "—"
                val limit = intent.getStringExtra("limit") ?: "—"
                val expires = intent.getStringExtra("expires") ?: "—"

                lastAccountGroup = "$billing • $group"
                lastAccountExpires = expires
                lastAccountTraffic = "$consumed\n/ $limit"
                lastAccountSpeed = speed
                lastAccountSessions = sessions

                runOnUiThread {
                    findViewById<TextView>(R.id.tvAccountGroup)?.text = lastAccountGroup
                    findViewById<TextView>(R.id.tvAccountExpires)?.text = lastAccountExpires
                    findViewById<TextView>(R.id.tvAccountTraffic)?.text = lastAccountTraffic
                    findViewById<TextView>(R.id.tvAccountSpeed)?.text = lastAccountSpeed
                    findViewById<TextView>(R.id.tvAccountSessions)?.text = lastAccountSessions
                }
                return
            }

            if (intent?.getBooleanExtra(ANetVpnService.EXTRA_IS_CONNECTIVITY, false) == true) {
                applyConnectivityState(
                    conditionCode = intent.getIntExtra(ANetVpnService.EXTRA_CONN_CONDITION, 1),
                    pulseColorCode = intent.getIntExtra(ANetVpnService.EXTRA_CONN_PULSE_COLOR, PulseLineView.PULSE_YELLOW),
                    rttMs = intent.getLongExtra(ANetVpnService.EXTRA_CONN_RTT_MS, -1L),
                    vpnConnected = intent.getBooleanExtra(ANetVpnService.EXTRA_CONN_VPN_CONNECTED, isVpnConnected),
                    title = intent.getStringExtra(ANetVpnService.EXTRA_CONN_TITLE).orEmpty(),
                    subtitle = intent.getStringExtra(ANetVpnService.EXTRA_CONN_SUBTITLE).orEmpty(),
                    globalOk = intent.getIntExtra(ANetVpnService.EXTRA_CONN_GLOBAL_OK, 0),
                    globalTotal = intent.getIntExtra(ANetVpnService.EXTRA_CONN_GLOBAL_TOTAL, 4),
                    domesticOk = intent.getIntExtra(ANetVpnService.EXTRA_CONN_DOMESTIC_OK, 0),
                    domesticTotal = intent.getIntExtra(ANetVpnService.EXTRA_CONN_DOMESTIC_TOTAL, 5)
                )
                return
            }

            if (intent?.getBooleanExtra(ANetVpnService.EXTRA_IS_TELEMETRY, false) == true) {
                applyTelemetryState(
                    rxBps = intent.getLongExtra(ANetVpnService.EXTRA_TEL_RX_BPS, 0L),
                    txBps = intent.getLongExtra(ANetVpnService.EXTRA_TEL_TX_BPS, 0L),
                    totalRxBytes = intent.getLongExtra(ANetVpnService.EXTRA_TEL_TOTAL_RX, 0L),
                    totalTxBytes = intent.getLongExtra(ANetVpnService.EXTRA_TEL_TOTAL_TX, 0L),
                    rttMs = intent.getLongExtra(ANetVpnService.EXTRA_TEL_RTT_MS, -1L),
                    rxSpeedStr = intent.getStringExtra(ANetVpnService.EXTRA_TEL_RX_SPEED_STR).orEmpty(),
                    txSpeedStr = intent.getStringExtra(ANetVpnService.EXTRA_TEL_TX_SPEED_STR).orEmpty(),
                    totalRxStr = intent.getStringExtra(ANetVpnService.EXTRA_TEL_TOTAL_RX_STR).orEmpty(),
                    totalTxStr = intent.getStringExtra(ANetVpnService.EXTRA_TEL_TOTAL_TX_STR).orEmpty(),
                    rxCurve = intent.getFloatArrayExtra(ANetVpnService.EXTRA_TEL_RX_CURVE) ?: FloatArray(0),
                    txCurve = intent.getFloatArrayExtra(ANetVpnService.EXTRA_TEL_TX_CURVE) ?: FloatArray(0)
                )
                return
            }

            if (intent?.getBooleanExtra(ANetVpnService.EXTRA_IS_STATS, false) == true) {
                val rx = intent.getStringExtra(ANetVpnService.EXTRA_STATS_RX).orEmpty()
                val tx = intent.getStringExtra(ANetVpnService.EXTRA_STATS_TX).orEmpty()
                val rtt = intent.getStringExtra(ANetVpnService.EXTRA_STATS_RTT).orEmpty()
                val rxm = intent.getStringExtra(ANetVpnService.EXTRA_STATS_RXM).orEmpty()
                val txm = intent.getStringExtra(ANetVpnService.EXTRA_STATS_TXM).orEmpty()
                updateTrafficStats(rx, tx, rtt, rxm, txm)
                return
            }

            if (intent?.hasExtra(ANetVpnService.EXTRA_VPN_STATE) == true) {
                handleVpnState(
                    intent.getIntExtra(ANetVpnService.EXTRA_VPN_STATE, ANetVpnService.STATE_DISCONNECTED),
                    intent.getStringExtra(ANetVpnService.EXTRA_VPN_MESSAGE).orEmpty(),
                    intent.getStringExtra(ANetVpnService.EXTRA_SERVER_NAME).orEmpty()
                )
                return
            }

            val status = intent?.getStringExtra("status") ?: return
            logToConsole(status)

            if (status.contains("Starting", ignoreCase = true) ||
                status.contains("Connecting", ignoreCase = true) ||
                status.contains("Phase", ignoreCase = true) ||
                status.contains("Authentication successful", ignoreCase = true) ||
                status.contains("Connected", ignoreCase = true) ||
                status.contains("Tunnel UP", ignoreCase = true) ||
                status.contains("Stopping", ignoreCase = true) ||
                status.contains("Stopped", ignoreCase = true) ||
                status.contains("Restoring", ignoreCase = true) ||
                status.contains("Routing restored", ignoreCase = true) ||
                status.contains("Cleaning up", ignoreCase = true) ||
                status.contains("Error", ignoreCase = true) ||
                status.contains("Failed", ignoreCase = true)
            ) {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val cleanMsg = status.replace("[Core]", "").replace("[AUTH]", "").trim()
                updateEventStatus("[$time] $cleanMsg")
            }
        }
    }

    private fun updateEventStatus(msg: String) {
        val color = when {
            msg.contains("Error", ignoreCase = true) ||
                    msg.contains("Failed", ignoreCase = true) ||
                    msg.contains("Ошибка", ignoreCase = true) ||
                    msg.contains("Connection lost", ignoreCase = true) -> Color.parseColor("#F44336")

            msg.contains("Connected", ignoreCase = true) ||
                    msg.contains("Tunnel UP", ignoreCase = true) -> Color.parseColor("#4CAF50")

            msg.contains("Stopping", ignoreCase = true) ||
                    msg.contains("Stopped", ignoreCase = true) ||
                    msg.contains("Cleaning up", ignoreCase = true) -> Color.parseColor("#FF9800")

            else -> Color.parseColor("#EEBC7A")
        }

        lastVpnEventText = msg
        lastVpnEventColor = color

        runOnUiThread {
            tvVpnEventStatus.text = msg
            tvVpnEventStatus.setTextColor(color)
        }
    }

    private fun handleVpnState(state: Int, message: String, serverName: String) {
        if (state == ANetVpnService.STATE_FAILED && message.isNotBlank()) {
            logToConsole(message)
        }

        if (serverName.isNotBlank()) {
            val index = availableServers.indexOfFirst { it.name == serverName }
            if (index >= 0) {
                val server = availableServers[index]
                serverSelectTextView.text = server.name
                selectedServerName = server.id
                getAppPrefs()
                    .edit()
                    .putString("selected_server_${selectedConfigName}", server.id)
                    .apply()
            }
        }

        when (state) {
            ANetVpnService.STATE_CONNECTING -> setUiState(State.CONNECTING, "CONNECTING...")
            ANetVpnService.STATE_RECONNECTING -> setUiState(State.CONNECTING, "RECONNECTING...")
            ANetVpnService.STATE_STOPPING -> setUiState(State.CONNECTING, "STOPPING...")
            ANetVpnService.STATE_CONNECTED -> setUiState(State.CONNECTED)
            ANetVpnService.STATE_DISCONNECTED,
            ANetVpnService.STATE_STOPPED -> setUiState(State.DISCONNECTED)
            ANetVpnService.STATE_FAILED -> {
                setUiState(State.DISCONNECTED)
                if (message.isNotBlank()) showErrorDialog(message)
            }
        }
    }

    private fun View.setupTvFocusAnimator() {
        this.isFocusable = true
        this.isClickable = true
        this.setOnFocusChangeListener { view, hasFocus ->
            view.animate()
                .scaleX(if (hasFocus) 1.08f else 1.0f)
                .scaleY(if (hasFocus) 1.08f else 1.0f)
                .translationZ(if (hasFocus) 8f else 0f)
                .setDuration(150)
                .start()
        }
    }

    private fun setUiState(state: State, customStatusText: String? = null) {
        runOnUiThread {
            currentUiState = state
            val controlsEnabled = state == State.DISCONNECTED
            btnCheckUpdate.isEnabled = controlsEnabled && !isCheckingUpdates
            btnSettings.isEnabled = controlsEnabled && !isCheckingUpdates
            btnCheckUpdate.alpha = if (btnCheckUpdate.isEnabled) 1.0f else 0.3f
            btnSettings.alpha = if (btnSettings.isEnabled) 1.0f else 0.3f

            if (state == State.DISCONNECTED) {
                versionInfoContainer.visibility = View.VISIBLE
                tvVpnEventStatus.visibility = View.GONE
            } else {
                versionInfoContainer.visibility = View.GONE
                tvVpnEventStatus.visibility = View.VISIBLE
            }

            when (state) {
                State.DISCONNECTED -> {
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    val wasConnected = isVpnConnected
                    isVpnConnected = false
                    spinner.visibility = View.INVISIBLE
                    spinner.clearAnimation()

                    tvPulseTarget.text = "Сеть"
                    if (lastConnRttMs <= 0L) {
                        tvRtt.text = "— мс"
                    }
                    tvRx.text = "0 B/s"
                    tvTx.text = "0 B/s"
                    tvRxm.text = "0 B"
                    tvTxm.text = "0 B"
                    telemetrySparklineView.clearGraph()
                    if (wasConnected) {
                        requestConnectivityRecheck(silent = true)
                    }

                    connectButton.text = "CONNECT"
                    connectButton.isEnabled = true

                    val readyColors = intArrayOf(
                        Color.parseColor("#669D29"),
                        Color.parseColor("#3AA34B"),
                        Color.parseColor("#1C7C3A"),
                        Color.parseColor("#1C7C3A"),
                        Color.parseColor("#3AA34B"),
                        Color.parseColor("#669D29")
                    )
                    connectButton.background = createNeonRingDrawable(readyColors)

                    connectionStatusLabel.setLeftIcon(
                        R.drawable.block,
                        "DISCONNECTED",
                        offsetX = -2,
                        offsetY = 0,
                        color = (0xFFFF5252.toInt()),
                        widthDp = 12,
                        heightDp = 12
                    )
                    connectionStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.grey_light))
                    serverSelectContainer.isEnabled = true
                    serverIndicator.imageTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
                    serverSelectIcon.setImageResource(R.drawable.chevron_down)
                }

                State.CONNECTING -> {
                    val statusText = customStatusText ?: "CONNECTING..."
                    val isStopping = statusText.startsWith("STOPPING")
                    if (!isStopping) {
                        mainHandler.removeCallbacks(connectTimeoutRunnable)
                        mainHandler.postDelayed(connectTimeoutRunnable, 30_000L)
                    }

                    spinner.visibility = View.VISIBLE
                    spinner.setImageDrawable(createAaaSpinnerDrawable())

                    if (spinner.animation == null) {
                        val animator = android.animation.ObjectAnimator.ofFloat(spinner, View.ROTATION, 0f, 360f)
                        animator.duration = 1200
                        animator.repeatCount = android.animation.ValueAnimator.INFINITE
                        animator.interpolator = android.view.animation.LinearInterpolator()
                        animator.start()
                    }

                    connectButton.text = if (isStopping) "STOPPING" else "CANCEL"
                    connectButton.isEnabled = !isStopping

                    val workingColors = intArrayOf(
                        Color.parseColor("#669D29"),
                        Color.parseColor("#C4B12B"),
                        Color.parseColor("#E67E22"),
                        Color.parseColor("#E67E22"),
                        Color.parseColor("#C4B12B"),
                        Color.parseColor("#669D29")
                    )
                    connectButton.background = createNeonRingDrawable(workingColors)

                    connectionStatusLabel.setLeftIcon(
                        R.drawable.update,
                        "CONNECTING",
                        offsetX = -2,
                        offsetY = 0,
                        color = (0xFFFE6102.toInt()),
                        widthDp = 12,
                        heightDp = 12
                    )
                    connectionStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.grey_light))
                    serverSelectContainer.isEnabled = true
                    serverIndicator.imageTintList = ColorStateList.valueOf(Color.parseColor("#669D29"))
                }

                State.CONNECTED -> {
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    val wasDisconnected = !isVpnConnected
                    isVpnConnected = true
                    val activeNodeLabel = serverSelectTextView.text?.toString()?.takeIf { it.isNotBlank() } ?: "Туннель"
                    tvPulseTarget.text = activeNodeLabel
                    if (wasDisconnected) {
                        requestConnectivityRecheck(silent = true)
                    }
                    spinner.visibility = View.INVISIBLE
                    spinner.clearAnimation()

                    connectButton.text = "STOP"
                    connectButton.isEnabled = true

                    val neonColors = intArrayOf(
                        Color.parseColor("#6A1B9A"),
                        Color.parseColor("#9C27B0"),
                        Color.parseColor("#E91E63"),
                        Color.parseColor("#D32F2F"),
                        Color.parseColor("#E91E63"),
                        Color.parseColor("#6A1B9A")
                    )
                    connectButton.background = createNeonRingDrawable(neonColors)

                    connectionStatusLabel.setLeftIcon(
                        R.drawable.dot,
                        "CONNECTED",
                        offsetX = -2,
                        offsetY = 0,
                        color = (0xFF4CAF50.toInt()),
                        widthDp = 12,
                        heightDp = 12
                    )
                    connectionStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.grey_light))
                    serverSelectContainer.isEnabled = true
                    serverSelectIcon.setImageResource(R.drawable.block)
                }
            }
        }
    }

    private fun logToConsole(msg: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val formattedMsg = if (msg.startsWith("[") && msg.length >= 10 && msg[9] == ']') {
                msg
            } else {
                "[$timestamp] $msg"
            }

            val color = when {
                formattedMsg.contains("Config loaded", ignoreCase = true) ||
                        formattedMsg.contains("dead session", ignoreCase = true) -> Color.parseColor("#FF9800")
                formattedMsg.contains("Connected", ignoreCase = true) -> Color.parseColor("#4CAF50")
                formattedMsg.contains("Stopped", ignoreCase = true) ||
                        formattedMsg.contains("Error", ignoreCase = true) ||
                        formattedMsg.contains("Ошибка", ignoreCase = true) -> Color.parseColor("#F44336")
                else -> null
            }

            val entry: CharSequence = if (color != null) {
                SpannableString(formattedMsg).apply {
                    setSpan(ForegroundColorSpan(color), 0, formattedMsg.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                formattedMsg
            }

            logLines.addLast(entry)
            if (logLines.size > MAX_LOG_LINES) {
                logLines.removeFirst()
            }

            activeLogAdapter?.notifyDataSetChanged()
            activeLogListView?.let { lv ->
                lv.post {
                    if (logLines.isNotEmpty()) {
                        lv.setSelection(logLines.size - 1)
                    }
                }
            }
        }
    }

    private fun formatSpeed(speedStr: String): String {
        val mbps = speedStr.toDoubleOrNull()
        if (mbps != null) {
            return when {
                mbps >= 1000.0 -> String.format(Locale.US, "%.2f Gbps", mbps / 1000.0)
                mbps >= 1.0 -> String.format(Locale.US, "%.2f Mbps", mbps)
                mbps >= 0.001 -> String.format(Locale.US, "%.1f Kbps", mbps * 1000.0)
                else -> "0 Kbps"
            }
        }
        return if (speedStr.isNotBlank()) speedStr else "0 B/s"
    }

    private fun parseRateToBps(raw: String): Double {
        val clean = raw.trim()
        val numberRegex = Regex("([0-9]+(?:\\.[0-9]+)?)")
        val match = numberRegex.find(clean) ?: return 0.0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        val upper = clean.uppercase(Locale.US)
        return when {
            upper.contains("GIB") || upper.contains("GB") -> value * 1024.0 * 1024.0 * 1024.0
            upper.contains("MIB") || upper.contains("MB") -> value * 1024.0 * 1024.0
            upper.contains("KIB") || upper.contains("KB") -> value * 1024.0
            else -> value
        }
    }

    private fun parseRttStringMs(rtt: String): Long {
        val digits = rtt.filter { it.isDigit() }
        return digits.toLongOrNull() ?: -1L
    }

    fun updateTrafficStats(
        rxTotal: String,
        txTotal: String,
        rtt: String,
        rxSpeedRaw: String,
        txSpeedRaw: String
    ) {
        val rxSpeed = formatSpeed(rxSpeedRaw)
        val txSpeed = formatSpeed(txSpeedRaw)
        val parsedRtt = parseRttStringMs(rtt)
        val rxBps = parseRateToBps(rxSpeedRaw)
        val txBps = parseRateToBps(txSpeedRaw)

        runOnUiThread {
            if (parsedRtt > 0L) {
                lastConnRttMs = parsedRtt
                tvRtt.text = "$parsedRtt мс"
                pulseLineView.updateState(lastConnPulseColor, parsedRtt)
            } else if (rtt.isNotBlank() && lastConnRttMs <= 0L) {
                tvRtt.text = rtt.replace("ms", " мс")
            }
            tvRx.text = rxSpeed
            tvTx.text = txSpeed
            tvRxm.text = if (rxTotal.isNotBlank()) rxTotal else "0 B"
            tvTxm.text = if (txTotal.isNotBlank()) txTotal else "0 B"
            telemetrySparklineView.pushFallbackSpeedSample(rxBps, txBps)
        }
    }

    @Keep
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
        applyConnectivityState(
            conditionCode = conditionCode,
            pulseColorCode = pulseColorCode,
            rttMs = rttMs,
            vpnConnected = vpnConnected,
            title = title,
            subtitle = subtitle,
            globalOk = globalOk,
            globalTotal = globalTotal,
            domesticOk = domesticOk,
            domesticTotal = domesticTotal
        )
    }

    @Keep
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
        applyTelemetryState(
            rxBps = rxBps,
            txBps = txBps,
            totalRxBytes = totalRxBytes,
            totalTxBytes = totalTxBytes,
            rttMs = rttMs,
            rxSpeedStr = rxSpeedStr,
            txSpeedStr = txSpeedStr,
            totalRxStr = totalRxStr,
            totalTxStr = totalTxStr,
            rxCurve = rxCurve,
            txCurve = txCurve
        )
    }

    private fun applyConnectivityState(
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
        lastConnCondition = conditionCode
        lastConnPulseColor = pulseColorCode
        if (rttMs > 0L || !isVpnConnected) {
            lastConnRttMs = rttMs
        }
        if (title.isNotBlank()) lastConnTitle = title
        if (subtitle.isNotBlank()) lastConnSubtitle = subtitle
        lastConnGlobalOk = globalOk
        lastConnGlobalTotal = globalTotal.coerceAtLeast(1)
        lastConnDomesticOk = domesticOk
        lastConnDomesticTotal = domesticTotal.coerceAtLeast(1)

        runOnUiThread {
            val activeColor = when (pulseColorCode) {
                PulseLineView.PULSE_RED -> PulseLineView.COLOR_RED
                PulseLineView.PULSE_YELLOW -> PulseLineView.COLOR_YELLOW
                else -> PulseLineView.COLOR_GREEN
            }
            connectivityBadgeDot.imageTintList = ColorStateList.valueOf(activeColor)
            tvConnectivityTitle.text = lastConnTitle
            tvConnectivitySubtitle.text = lastConnSubtitle
            tvConnectivityNodes.text = "РФ $domesticOk/${lastConnDomesticTotal} • Мир $globalOk/${lastConnGlobalTotal}"
            connectivitySegmentsView.setProbes(
                domOk = domesticOk,
                domTotal = lastConnDomesticTotal,
                globOk = globalOk,
                globTotal = lastConnGlobalTotal,
                colorCode = pulseColorCode
            )

            val displayRtt = if (rttMs > 0L) rttMs else lastConnRttMs
            pulseLineView.updateState(pulseColorCode, displayRtt)
            tvRtt.setTextColor(activeColor)
            tvRtt.text = if (displayRtt > 0L) "$displayRtt мс" else "— мс"

            tvPulseTarget.text = if (isVpnConnected || vpnConnected) {
                serverSelectTextView.text?.toString()?.takeIf { it.isNotBlank() } ?: "Туннель"
            } else {
                "Сеть"
            }
        }
    }

    private fun applyTelemetryState(
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
        runOnUiThread {
            if (rxSpeedStr.isNotBlank()) tvRx.text = rxSpeedStr
            if (txSpeedStr.isNotBlank()) tvTx.text = txSpeedStr
            if (totalRxStr.isNotBlank()) tvRxm.text = totalRxStr
            if (totalTxStr.isNotBlank()) tvTxm.text = totalTxStr

            if (rttMs > 0L) {
                lastConnRttMs = rttMs
                tvRtt.text = "$rttMs мс"
                pulseLineView.updateState(lastConnPulseColor, rttMs)
            }

            if (rxCurve.isNotEmpty() || txCurve.isNotEmpty()) {
                telemetrySparklineView.setCurves(rxCurve, txCurve)
            } else {
                telemetrySparklineView.pushFallbackSpeedSample(rxBps.toDouble(), txBps.toDouble())
            }
        }
    }

    private fun safeStartConnectivityMonitor() {
        try {
            startConnectivityMonitor(selectedConfigContent ?: "")
            useKotlinFallbackProber = false
            val snapJson = getConnectivitySnapshotJson()
            if (snapJson.isNotBlank() && snapJson != "{}") {
                parseAndApplySnapshotJson(snapJson)
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.w("ANet", "Native startConnectivityMonitor unavailable, using Kotlin fallback prober")
            useKotlinFallbackProber = true
            startKotlinFallbackProber()
        } catch (e: Exception) {
            Log.e("ANet", "Failed to start connectivity monitor: ${e.message}")
        }
    }

    private fun parseAndApplySnapshotJson(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            val cond = when (obj.optString("condition", "")) {
                "Offline" -> 0
                "DomesticOnly" -> 1
                "DirectOnline" -> 2
                "TunnelLost" -> 3
                "TunnelOnlyNoWorld" -> 4
                "TunnelFullOnline" -> 5
                else -> obj.optInt("condition", lastConnCondition)
            }
            val color = when (obj.optString("pulse_color", "")) {
                "Red" -> PulseLineView.PULSE_RED
                "Yellow" -> PulseLineView.PULSE_YELLOW
                "Green" -> PulseLineView.PULSE_GREEN
                else -> obj.optInt("pulse_color", lastConnPulseColor)
            }
            val rtt = if (obj.isNull("display_rtt_ms")) -1L else obj.optLong("display_rtt_ms", -1L)
            val vpnConn = obj.optBoolean("vpn_connected", isVpnConnected)
            val title = obj.optString("title", lastConnTitle)
            val subtitle = obj.optString("subtitle", lastConnSubtitle)
            val gOk = obj.optInt("global_ok", lastConnGlobalOk)
            val gTot = obj.optInt("global_total", lastConnGlobalTotal)
            val dOk = obj.optInt("domestic_ok", lastConnDomesticOk)
            val dTot = obj.optInt("domestic_total", lastConnDomesticTotal)
            applyConnectivityState(cond, color, rtt, vpnConn, title, subtitle, gOk, gTot, dOk, dTot)
        } catch (_: Exception) {}
    }

    private fun requestConnectivityRecheck(silent: Boolean) {
        if (!silent) {
            btnRecheckConnectivity.animate().rotationBy(360f).setDuration(450).start()
            tvConnectivityTitle.text = "Сверяем показания приборов…"
        }
        try {
            if (!useKotlinFallbackProber) {
                triggerConnectivityCheck()
            } else {
                runSingleKotlinFallbackProbe()
            }
        } catch (e: UnsatisfiedLinkError) {
            useKotlinFallbackProber = true
            runSingleKotlinFallbackProbe()
        }
    }

    private val fallbackProbeRunnable = object : Runnable {
        override fun run() {
            if (!useKotlinFallbackProber || isFinishing) return
            runSingleKotlinFallbackProbe()
            mainHandler.postDelayed(this, 4000L)
        }
    }

    private fun startKotlinFallbackProber() {
        mainHandler.removeCallbacks(fallbackProbeRunnable)
        mainHandler.post(fallbackProbeRunnable)
    }

    private fun runSingleKotlinFallbackProbe() {
        Thread {
            val domesticHosts = listOf("https://ya.ru", "https://vk.com", "https://gosuslugi.ru", "https://mail.ru", "https://www.sberbank.ru")
            val globalHosts = listOf("https://www.google.com/generate_204", "https://cp.cloudflare.com/generate_204", "https://github.com", "https://firefox.com")
            var domOk = 0
            var globOk = 0
            var bestRtt = Long.MAX_VALUE

            for (urlStr in domesticHosts) {
                val rtt = probeHttpHead(urlStr, 2200)
                if (rtt > 0L) {
                    domOk++
                    if (rtt < bestRtt) bestRtt = rtt
                }
            }
            for (urlStr in globalHosts) {
                val rtt = probeHttpHead(urlStr, 2200)
                if (rtt > 0L) {
                    globOk++
                    if (rtt < bestRtt) bestRtt = rtt
                }
            }

            val rttMs = if (bestRtt != Long.MAX_VALUE) bestRtt else lastConnRttMs
            val vpnOn = isVpnConnected
            val (cond, color, title, subtitle) = when {
                !vpnOn && domOk == 0 && globOk == 0 -> Quad(
                    0, PulseLineView.PULSE_RED,
                    "Полный штиль",
                    "Ни свои, ни чужие берега не отвечают. Проверьте Wi-Fi, баланс или антенну."
                )
                !vpnOn && domOk > 0 && globOk == 0 -> Quad(
                    1, PulseLineView.PULSE_YELLOW,
                    "Метеосводка: локальная облачность",
                    "Домашние гавани открыты, дальние берега в тумане. Включите VPN для улучшения погоды."
                )
                !vpnOn -> Quad(
                    2, PulseLineView.PULSE_GREEN,
                    "Погода лётная",
                    "Основной интернет работает штатно. Туннель нужен только для особых маршрутов."
                )
                vpnOn && domOk == 0 && globOk == 0 -> Quad(
                    3, PulseLineView.PULSE_RED,
                    "Потеряна связь с бортом",
                    "Туннель поднят, но эхо до сервера не доходит. Попробуйте сменить узел."
                )
                vpnOn && globOk == 0 -> Quad(
                    4, PulseLineView.PULSE_YELLOW,
                    "Туннель в тупике",
                    "Связь с узлом есть, но внешний мир из этой точки не отвечает. Смените локацию."
                )
                else -> Quad(
                    5, PulseLineView.PULSE_GREEN,
                    "Полный вперёд",
                    "Туннель стабилен, дальние маршруты открыты и работают без помех."
                )
            }
            applyConnectivityState(
                conditionCode = cond,
                pulseColorCode = color,
                rttMs = rttMs,
                vpnConnected = vpnOn,
                title = title,
                subtitle = subtitle,
                globalOk = globOk,
                globalTotal = globalHosts.size,
                domesticOk = domOk,
                domesticTotal = domesticHosts.size
            )
        }.start()
    }

    private data class Quad(val cond: Int, val color: Int, val title: String, val subtitle: String)

    private fun probeHttpHead(urlStr: String, timeoutMs: Int): Long {
        var conn: HttpURLConnection? = null
        return try {
            val start = System.currentTimeMillis()
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 ANet-ConnectivityCheck")
            }
            val code = conn.responseCode
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            if (code in 100..599) elapsed else -1L
        } catch (_: Exception) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }

    private fun TextView.setLeftIcon(
        @DrawableRes iconRes: Int,
        text: String,
        offsetX: Int = 0,
        offsetY: Int = 0,
        @ColorInt color: Int? = null,
        widthDp: Int = 12,
        heightDp: Int = 12
    ) {
        this.text = text
        val original = ContextCompat.getDrawable(context, iconRes) ?: return

        if (color != null) {
            original.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }

        val targetWidth = widthDp.dpToPx()
        val targetHeight = heightDp.dpToPx()

        original.setBounds(0, 0, targetWidth, targetHeight)
        setCompoundDrawables(original, null, null, null)
    }

    private fun createNeonRingDrawable(gradientColors: IntArray): Drawable {
        val strokeWidthPx = 3.dpToPx().toFloat()
        val solidBackground = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#121212"))
        }

        val strokeDrawable = object : Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = strokeWidthPx
            }

            override fun draw(canvas: Canvas) {
                val rect = android.graphics.RectF(
                    strokeWidthPx / 2f,
                    strokeWidthPx / 2f,
                    bounds.width() - strokeWidthPx / 2f,
                    bounds.height() - strokeWidthPx / 2f
                )
                if (paint.shader == null) {
                    paint.shader = android.graphics.SweepGradient(
                        bounds.exactCenterX(),
                        bounds.exactCenterY(),
                        gradientColors,
                        null
                    )
                }
                canvas.drawOval(rect, paint)
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: ColorFilter?) {}
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }

        val layerDrawable = android.graphics.drawable.LayerDrawable(arrayOf(solidBackground, strokeDrawable))
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
        return android.graphics.drawable.RippleDrawable(rippleColor, layerDrawable, null)
    }

    private fun createAaaSpinnerDrawable(): Drawable {
        return object : Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 4.dpToPx().toFloat()
                strokeCap = android.graphics.Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val inset = paint.strokeWidth
                val rect = android.graphics.RectF(
                    inset, inset,
                    bounds.width().toFloat() - inset,
                    bounds.height().toFloat() - inset
                )

                val centerX = bounds.exactCenterX()
                val centerY = bounds.exactCenterY()
                val colors = intArrayOf(Color.TRANSPARENT, Color.parseColor("#80FF7043"), Color.parseColor("#FFFFCA28"))
                val positions = floatArrayOf(0f, 0.6f, 1f)
                val sweepGradient = android.graphics.SweepGradient(centerX, centerY, colors, positions)

                val matrix = android.graphics.Matrix()
                matrix.setRotate(-90f, centerX, centerY)
                sweepGradient.setLocalMatrix(matrix)
                paint.shader = sweepGradient
                canvas.drawArc(rect, -90f, 300f, false, paint)
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: ColorFilter?) {}
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val data = intent?.data

        if (Intent.ACTION_VIEW == action && data != null) {
            logToConsole("Импорт конфигурации...")
            val content = readTextFromUri(data)
            val name = getFileName(data)

            if (content.isNotEmpty() && inspectServers(content) != null) {
                addAndActivateConfig(name, content)
                logToConsole("Конфигурация успешно импортирована: $name")
                checkPermissionsAndStart()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("acc_group", findViewById<TextView>(R.id.tvAccountGroup)?.text?.toString().orEmpty())
        outState.putString("acc_expires", findViewById<TextView>(R.id.tvAccountExpires)?.text?.toString().orEmpty())
        outState.putString("acc_traffic", findViewById<TextView>(R.id.tvAccountTraffic)?.text?.toString().orEmpty())
        outState.putString("acc_speed", findViewById<TextView>(R.id.tvAccountSpeed)?.text?.toString().orEmpty())
        outState.putString("acc_sessions", findViewById<TextView>(R.id.tvAccountSessions)?.text?.toString().orEmpty())
        outState.putString("stat_rtt", tvRtt.text?.toString().orEmpty())
        outState.putString("stat_rx", tvRx.text?.toString().orEmpty())
        outState.putString("stat_tx", tvTx.text?.toString().orEmpty())
        outState.putString("stat_rxm", tvRxm.text?.toString().orEmpty())
        outState.putString("stat_txm", tvTxm.text?.toString().orEmpty())
        outState.putInt("conn_condition", lastConnCondition)
        outState.putInt("conn_pulse_color", lastConnPulseColor)
        outState.putLong("conn_rtt_ms", lastConnRttMs)
        outState.putString("conn_title", lastConnTitle)
        outState.putString("conn_subtitle", lastConnSubtitle)
        outState.putInt("conn_glob_ok", lastConnGlobalOk)
        outState.putInt("conn_glob_total", lastConnGlobalTotal)
        outState.putInt("conn_dom_ok", lastConnDomesticOk)
        outState.putInt("conn_dom_total", lastConnDomesticTotal)

        outState.putString("vpn_event_text", tvVpnEventStatus.text?.toString().orEmpty())
        outState.putInt("vpn_event_color", tvVpnEventStatus.currentTextColor)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.removeCallbacks(fallbackProbeRunnable)
        unregisterReceiver(statusReceiver)
        clearUiCallback()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        safeStartConnectivityMonitor()
        val stateCode = if (ANetVpnService.isServiceRunning) getVpnStateCode() else ANetVpnService.STATE_DISCONNECTED
        handleVpnState(stateCode, "", getVpnServerName())

        if (isScanningQr) {
            isScanningQr = false
            if (getSavedConfigs().isEmpty()) {
                mainHandler.postDelayed({
                    val savedUrl = getSavedServerUrl()
                    if (savedUrl.isNullOrBlank()) showEnterUrlDialog()
                }, 150)
            }
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}