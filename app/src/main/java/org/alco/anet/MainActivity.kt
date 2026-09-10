package org.alco.anet

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// Вспомогательная структура данных для парсинга нод в Kotlin
data class ServerModel(val name: String) {
    fun getFormattedName() = name
}

// Модель для хранения списка конфигураций
data class ConfigItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val content: String
)

class MainActivity : AppCompatActivity() {

    // UI Элементы
    private lateinit var tvRtt: TextView
    private lateinit var tvRx: TextView
    private lateinit var tvTx: TextView
    private lateinit var tvRxm: TextView
    private lateinit var tvTxm: TextView

    private lateinit var connectionStatusLabel: TextView
    private lateinit var connectButton: Button
    private lateinit var spinner: ImageView
    private lateinit var selectConfigButton: Button
    private lateinit var btnScanQr: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnShowLogs: Button

    private lateinit var serverSelectContainer: LinearLayout
    private lateinit var serverSelectTextView: TextView
    private lateinit var serverSelectIcon: ImageView

    private lateinit var selectAppsButton: Button
    private var activeErrorDialog: AlertDialog? = null

    // Буфер и управление окном логов
    private val logBuffer = SpannableStringBuilder("> System ready...")
    private var activeLogTextView: TextView? = null
    private var activeLogScrollView: ScrollView? = null

    // Управление окном списка конфигов
    private var activeConfigDialog: AlertDialog? = null
    private var refreshConfigListRunnable: Runnable? = null

    // Состояние
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

    // Список распарсенных нод из активного конфига
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

    // Enum для состояний UI
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }
    }

    private external fun initLogger()

    // --- УПРАВЛЕНИЕ СПИСКОМ КОНФИГУРАЦИЙ В PREFS ---

    private fun getSavedConfigs(): MutableList<ConfigItem> {
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
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
                        content = obj.getString("content")
                    )
                )
            }
        } catch (e: Exception) {
            logToConsole("Ошибка чтения списка конфигов: ${e.message}")
        }
        return list
    }

    private fun saveConfigsToPrefs(configs: List<ConfigItem>, activeId: String? = null) {
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val array = JSONArray()
        for (item in configs) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("content", item.content)
            }
            array.put(obj)
        }
        val editor = prefs.edit().putString("saved_configs_list", array.toString())
        if (activeId != null) {
            editor.putString("active_config_id", activeId)
        }
        editor.apply()
    }

    private fun addAndActivateConfig(name: String, content: String) {
        val configs = getSavedConfigs()
        val newItem = ConfigItem(name = name, content = content)
        configs.add(newItem)

        selectedConfigContent = content
        selectedConfigName = name

        saveConfigsToPrefs(configs, newItem.id)
        saveConfigToPrefs(content, name)

        setupServerSelector()
        refreshConfigListRunnable?.run()
    }

    // Метод установки скачанного APK
    private fun installApk() {
        val apkFile = File(cacheDir, "update.apk")
        val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // Модалка обновления
    private fun showUpdateModal(version: String, body: String) {
        runOnUiThread {
            if (updateDialog?.isShowing == true) return@runOnUiThread

            val builder = AlertDialog.Builder(this)
            val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)

            val versionView = dialogView.findViewById<TextView>(R.id.updateVersion)
            val changelogView = dialogView.findViewById<TextView>(R.id.updateChangelog)
            val btnUpdate = dialogView.findViewById<Button>(R.id.btnUpdateNow)
            val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelUpdate)

            progressBar = dialogView.findViewById<ProgressBar>(R.id.updateProgress)

            versionView.text = "Version: $version"
            changelogView.text = body

            builder.setView(dialogView)
            builder.setCancelable(false)

            updateDialog = builder.create()
            updateDialog?.show()

            btnCancel.setOnClickListener {
                updateDialog?.dismiss()
            }

            btnUpdate.setOnClickListener {
                btnUpdate.isEnabled = false
                btnCancel.isEnabled = false
                progressBar?.visibility = View.VISIBLE

                logToConsole("Starting APK download...")
                val destination = File(cacheDir, "update.apk").absolutePath
                startDownload(destination)
            }
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
        return result.drop(1).filter { it.isNotBlank() }.map(::ServerModel)
    }

    private fun onServerSelected(position: Int) {
        if (position !in availableServers.indices) return
        val name = availableServers[position].getFormattedName()

        selectedServerName = name
        serverSelectTextView.text = name

        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("selected_server_${selectedConfigName}", name).apply()

        logToConsole("Выбран сервер/группа: $name")
    }

    private fun setupServerSelector() {
        val content = selectedConfigContent ?: return
        availableServers.clear()
        val servers = inspectServers(content) ?: return
        availableServers.addAll(servers)

        logToConsole("Найдено серверов/групп в конфиге: ${availableServers.size}")

        if (availableServers.isEmpty()) {
            serverSelectContainer.visibility = View.GONE
            return
        }

        serverSelectContainer.visibility = View.VISIBLE

        val formattedNames = availableServers.map { it.getFormattedName() }

        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val lastSelected = prefs.getString("selected_server_${selectedConfigName}", "") ?: ""
        val index = formattedNames.indexOf(lastSelected)

        if (index >= 0) {
            selectedServerName = lastSelected
        } else {
            selectedServerName = formattedNames.first()
            prefs.edit().putString("selected_server_${selectedConfigName}", selectedServerName).apply()
        }

        serverSelectTextView.text = selectedServerName

        val listPopupWindow = ListPopupWindow(this, null, androidx.appcompat.R.attr.listPopupWindowStyle)
        listPopupWindow.anchorView = serverSelectContainer

        val backgroundDrawable = ContextCompat.getDrawable(this, R.drawable.popup_bg)
        if (backgroundDrawable != null) {
            listPopupWindow.setBackgroundDrawable(backgroundDrawable)
        }

        listPopupWindow.verticalOffset = 0

        val adapter = ArrayAdapter(
            this,
            R.layout.spinner_dropdown_item,
            formattedNames
        )
        listPopupWindow.setAdapter(adapter)

        listPopupWindow.setOnItemClickListener { _, _, position, _ ->
            onServerSelected(position)
            listPopupWindow.dismiss()
        }

        var popupDismissTime = 0L

        listPopupWindow.setOnDismissListener {
            popupDismissTime = System.currentTimeMillis()
            serverSelectIcon.animate().rotation(0f).setDuration(200).start()
        }

        serverSelectContainer.setOnClickListener {
            if (isVpnConnected) return@setOnClickListener

            if (System.currentTimeMillis() - popupDismissTime < 250) {
                return@setOnClickListener
            }

            if (listPopupWindow.isShowing) {
                listPopupWindow.dismiss()
            } else {
                serverSelectIcon.animate().rotation(180f).setDuration(200).start()
                listPopupWindow.show()
            }
        }
    }

    // --- Google Barcode Scanner (ML Kit) ---
    private fun startQrScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val url = barcode.rawValue
                if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    downloadConfigFromUrl(url)
                } else {
                    logToConsole("Неверный формат ссылки: $url")
                }
            }
            .addOnFailureListener { e ->
                logToConsole("QR Сканирование отменено или ошибка: ${e.message}")
            }
    }

    private fun downloadConfigFromUrl(url: String) {
        logToConsole("Загрузка конфигурации...")
        spinner.visibility = View.VISIBLE

        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.requestMethod = "GET"

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val content = connection.inputStream.bufferedReader().use { it.readText() }

                    if (inspectServers(content, reportError = false) != null) {
                        runOnUiThread {
                            spinner.visibility = View.INVISIBLE
                            addAndActivateConfig("QR-Imported", content)
                            logToConsole("Профиль импортирован по QR-коду!")

                            logToConsole(">>> Автозапуск соединения...")
                            checkPermissionsAndStart()
                        }
                    } else {
                        runOnUiThread {
                            spinner.visibility = View.INVISIBLE
                            logToConsole("Ошибка: файл по ссылке не является TOML-конфигом ANet")
                        }
                    }
                } else {
                    runOnUiThread {
                        spinner.visibility = View.INVISIBLE
                        logToConsole("Сервер вернул ошибку: ${connection.responseCode}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    spinner.visibility = View.INVISIBLE
                    logToConsole("Ошибка скачивания: ${e.message}")
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    // --- LAUNCHERS ---

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val content = readTextFromUri(it)
            val name = getFileName(it)

            if (content.isNotEmpty() && inspectServers(content) != null) {
                addAndActivateConfig(name, content)
                logToConsole("Loaded config: $name (${content.length} bytes)")
            } else {
                logToConsole("Failed to read config file")
            }
        }
    }

    private fun checkBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                logToConsole("Запрос на отключение оптимизации батареи...")
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
    ) { isGranted ->
        if (!isGranted) {
            logToConsole("Notification permission denied. Running silently.")
        }
        attemptVpnConnection()
    }

    private fun showErrorDialog(message: String) {
        runOnUiThread {
            if (activeErrorDialog?.isShowing == true) {
                return@runOnUiThread
            }

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

    // --- BROADCAST RECEIVER ---

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
                
                runOnUiThread {
                    findViewById<View>(R.id.accountInfoContainer)?.visibility = View.VISIBLE
                    findViewById<TextView>(R.id.tvAccountGroup)?.text = "$billing • $group"
                    findViewById<TextView>(R.id.tvAccountExpires)?.text = expires
                    findViewById<TextView>(R.id.tvAccountTraffic)?.text = "$consumed\n/ $limit"
                    findViewById<TextView>(R.id.tvAccountSpeed)?.text = speed
                    findViewById<TextView>(R.id.tvAccountSessions)?.text = sessions
                }
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
                    intent.getIntExtra(
                        ANetVpnService.EXTRA_VPN_STATE,
                        ANetVpnService.STATE_DISCONNECTED
                    ),
                    intent.getStringExtra(ANetVpnService.EXTRA_VPN_MESSAGE).orEmpty(),
                    intent.getStringExtra(ANetVpnService.EXTRA_SERVER_NAME).orEmpty()
                )
                return
            }

            val status = intent?.getStringExtra("status")

            if (status == null) return

            if (status.contains("Найдено обновление") ||
                status.contains("актуальная версия") ||
                status.contains("Ошибка обновления")) {
                isCheckingUpdates = false
                btnCheckUpdate.isEnabled = currentUiState == State.DISCONNECTED
                btnCheckUpdate.alpha = if (btnCheckUpdate.isEnabled) 1.0f else 0.3f
            }

            status.let { msg ->
                if (msg.startsWith("PROGRESS:")) {
                    val progressValue = msg.substringAfter("PROGRESS:").toFloatOrNull() ?: 0f
                    runOnUiThread {
                        progressBar?.isIndeterminate = false
                        progressBar?.progress = (progressValue * 100).toInt()
                    }
                    return
                }

                if (msg.equals("VPN Stopped", ignoreCase = true)) {
                    return
                }

                logToConsole(msg)

                val isAuthError = msg.contains("сессий", ignoreCase = true) ||
                        msg.contains("истекло", ignoreCase = true) ||
                        msg.contains("denied", ignoreCase = true)

                when {
                    msg.contains("Active node:") -> {
                        val activeName = msg.substringAfter("Active node:").trim()
                        runOnUiThread {
                            val index = availableServers.indexOfFirst { it.getFormattedName() == activeName }
                            if (index >= 0) {
                                serverSelectTextView.text = activeName
                                selectedServerName = activeName

                                val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
                                prefs.edit().putString("selected_server_${selectedConfigName}", activeName).apply()
                            }
                        }
                    }

                    msg.contains("Найдено обновление", ignoreCase = true) -> {
                        val tag = getPendingTag()
                        val body = getPendingBody()
                        showUpdateModal(tag, body)
                    }

                    msg.contains("Update downloaded to cache", ignoreCase = true) -> {
                        updateDialog?.dismiss()
                        installApk()
                    }

                    isAuthError -> {
                        stopVpnService()
                        setUiState(State.DISCONNECTED)
                        showErrorDialog(msg)
                    }
                }
            }
        }
    }

    private fun handleVpnState(state: Int, message: String, serverName: String) {
        if (state == ANetVpnService.STATE_FAILED && message.isNotBlank()) {
            logToConsole(message)
        }

        if (serverName.isNotBlank()) {
            val index = availableServers.indexOfFirst { it.getFormattedName() == serverName }
            if (index >= 0) {
                serverSelectTextView.text = serverName
                selectedServerName = serverName
                getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("selected_server_${selectedConfigName}", serverName)
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
            if (hasFocus) {
                view.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .translationZ(8f)
                    .setDuration(150)
                    .start()
            } else {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationZ(0f)
                    .setDuration(150)
                    .start()
            }
        }
    }

    // --- LIFECYCLE ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvRtt = findViewById(R.id.tvRtt)
        tvRx = findViewById(R.id.tvRx)
        tvTx = findViewById(R.id.tvTx)
        tvRxm = findViewById(R.id.tvRxm)
        tvTxm = findViewById(R.id.tvTxm)

        connectionStatusLabel = findViewById(R.id.connectionStatus)
        connectButton = findViewById(R.id.connect)
        spinner = findViewById(R.id.connectSpinner)
        selectConfigButton = findViewById(R.id.selectConfig)
        btnScanQr = findViewById(R.id.btnScanQr)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnShowLogs = findViewById(R.id.btnShowLogs)

        serverSelectContainer = findViewById(R.id.serverSelectContainer)
        serverSelectTextView = findViewById(R.id.serverSelectTextView)
        serverSelectIcon = findViewById(R.id.serverSelectIcon)

        selectAppsButton = findViewById(R.id.selectApps)

        selectAppsButton.setOnClickListener {
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }

        btnShowLogs.setOnClickListener {
            showLogsDialog()
        }

        initLogger()

        loadConfigFromPrefs()
        if (selectedConfigContent != null) {
            logToConsole("Config loaded: $selectedConfigName")
            setupServerSelector()
            checkBatteryOptimizations()
        } else {
            logToConsole("Welcome. Please select config file.")
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

        // Вызов менеджера конфигураций вместо прямого выбора файла
        selectConfigButton.setOnClickListener {
            showConfigManagerDialog()
        }

        btnScanQr.setOnClickListener {
            startQrScanner()
        }

        setUiState(State.DISCONNECTED)

        findViewById<TextView>(R.id.versionLabel).text = getAppVersion()
        findViewById<TextView>(R.id.buildDetailLabel).text = getBuildInfo()

        connectButton.setupTvFocusAnimator()
        selectConfigButton.setupTvFocusAnimator()
        btnScanQr.setupTvFocusAnimator()
        btnCheckUpdate.setupTvFocusAnimator()
        serverSelectContainer.setupTvFocusAnimator()
        selectAppsButton.setupTvFocusAnimator()
        btnShowLogs.setupTvFocusAnimator()

        btnCheckUpdate.setOnClickListener {
            if (isCheckingUpdates) return@setOnClickListener

            isCheckingUpdates = true
            btnCheckUpdate.isEnabled = false
            btnCheckUpdate.alpha = 0.3f

            logToConsole("Checking for system updates...")

            Thread {
                try {
                    checkUpdates(selectedConfigContent)
                } catch (e: Exception) {
                    runOnUiThread {
                        isCheckingUpdates = false
                        btnCheckUpdate.isEnabled = currentUiState == State.DISCONNECTED
                        btnCheckUpdate.alpha = if (btnCheckUpdate.isEnabled) 1.0f else 0.3f
                        logToConsole("Update check error: ${e.message}")
                    }
                }
            }.start()
        }

        handleIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val currentFocus = currentFocus
            if (currentFocus != null) {
                currentFocus.performClick()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val data = intent?.data

        if (Intent.ACTION_VIEW == action && data != null) {
            logToConsole("Импорт конфигурации из внешнего источника...")
            val content = readTextFromUri(data)
            val name = getFileName(data)

            if (content.isNotEmpty() && inspectServers(content) != null) {
                addAndActivateConfig(name, content)
                logToConsole("Конфигурация успешно импортирована: $name")
                logToConsole(">>> Автозапуск соединения...")
                checkPermissionsAndStart()
            } else {
                logToConsole("Ошибка импорта: Некорректный файл .toml")
            }
        }
    }

    @Keep
    fun onStatusChanged(status: String) {
        val intent = Intent("org.alco.anet.VPN_STATUS")
        intent.putExtra("status", status)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    @Keep
    fun onTrafficStats(rx: String, tx: String, rtt: String, rxm: String, txm: String) {
        updateTrafficStats(rx, tx, rtt, rxm, txm)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        unregisterReceiver(statusReceiver)
        clearUiCallback()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val stateCode = if (ANetVpnService.isServiceRunning) getVpnStateCode() else ANetVpnService.STATE_DISCONNECTED
        handleVpnState(stateCode, "", getVpnServerName())
    }

    // --- LOGIC ---

    private fun checkPermissionsAndStart() {
        if (selectedConfigContent == null) {
            logToConsole("Error: No config selected!")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
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

        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
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

    fun TextView.setLeftIcon(iconRes: Int, text: String) {
        val icon = ContextCompat.getDrawable(context, iconRes)
        this.text = text
        setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
    }

    fun TextView.setLeftIcon(
        @DrawableRes iconRes: Int,
        text: String,
        offsetX: Int = 0,
        offsetY: Int = 0,
        @ColorInt color: Int? = null
    ) {
        this.text = text
        val original = ContextCompat.getDrawable(context, iconRes) ?: return

        if (color != null) {
            original.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }

        val finalDrawable = if (offsetX == 0 && offsetY == 0) {
            original.apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) }
        } else {
            object : Drawable() {
                override fun draw(canvas: Canvas) {
                    canvas.save()
                    canvas.translate(offsetX.toFloat(), offsetY.toFloat())
                    original.draw(canvas)
                    canvas.restore()
                }

                override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
                    super.setBounds(left, top, right, bottom)
                    original.setBounds(left, top, right, bottom)
                }

                override fun getIntrinsicWidth() = original.intrinsicWidth
                override fun getIntrinsicHeight() = original.intrinsicHeight
                override fun setAlpha(alpha: Int) { original.alpha = alpha }
                override fun setColorFilter(filter: ColorFilter?) { original.colorFilter = filter }
                override fun getOpacity() = original.opacity
            }.apply {
                setBounds(0, 0, original.intrinsicWidth, original.intrinsicHeight)
            }
        }

        setCompoundDrawables(finalDrawable, null, null, null)
    }

    fun TextView.removeLeftIcon() {
        setCompoundDrawables(null, null, null, null)
    }

    // --- UI HELPERS ---

    private fun setUiState(state: State, customStatusText: String? = null) {
        runOnUiThread {
            currentUiState = state
            val controlsEnabled = state == State.DISCONNECTED
            btnScanQr.isEnabled = controlsEnabled
            btnScanQr.alpha = if (controlsEnabled) 1.0f else 0.3f
            selectAppsButton.isEnabled = controlsEnabled
            selectAppsButton.alpha = if (controlsEnabled) 1.0f else 0.3f
            selectConfigButton.isEnabled = controlsEnabled
            selectConfigButton.alpha = if (controlsEnabled) 1.0f else 0.3f
            btnCheckUpdate.isEnabled = controlsEnabled && !isCheckingUpdates
            btnCheckUpdate.alpha = if (btnCheckUpdate.isEnabled) 1.0f else 0.3f

            when (state) {
                State.DISCONNECTED -> {
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    isVpnConnected = false
                    spinner.visibility = View.INVISIBLE
                    spinner.clearAnimation()

                    tvRtt.text = "0 ms"
                    tvRx.text = "0 B/s"
                    tvTx.text = "0 B/s"
                    tvRxm.text = "0 B"
                    tvTxm.text = "0 B"
                    
                    findViewById<View>(R.id.accountInfoContainer)?.visibility = View.GONE

                    connectButton.text = "CONNECT"
                    connectButton.isEnabled = true

                    val readyColors = intArrayOf(
                        Color.parseColor("#669D29"),
                        Color.parseColor("#3AA34B"), // ярко-зелёный
                        Color.parseColor("#1C7C3A"), // тёмный лесной
                        Color.parseColor("#1C7C3A"),
                        Color.parseColor("#3AA34B"),
                        Color.parseColor("#669D29")
                    )
                    connectButton.background = createNeonRingDrawable(readyColors)

                    connectionStatusLabel.setLeftIcon(R.drawable.block, "DISCONNECTED", offsetX = 0, offsetY = -5, color = (0xFFFF5252.toInt()))
                    connectionStatusLabel.setTextColor(0xFFFF5252.toInt())

                    serverSelectContainer.isEnabled = true
                    serverSelectContainer.alpha = 1.0f
                    serverSelectIcon.setImageResource(R.drawable.chevron_down)
                }
                State.CONNECTING -> {
                    val statusText = customStatusText ?: "CONNECTING..."
                    val isStopping = statusText.startsWith("STOPPING")
                    if (!isStopping) {
                        mainHandler.removeCallbacks(connectTimeoutRunnable)
                        mainHandler.postDelayed(connectTimeoutRunnable, 30_000L)
                    } else {
                        mainHandler.removeCallbacks(connectTimeoutRunnable)
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

                    if (isStopping) {
                        connectButton.text = "STOPPING"
                        connectButton.isEnabled = false
                    } else {
                        connectButton.text = "CANCEL"
                        connectButton.isEnabled = true
                    }

                    val workingColors = intArrayOf(
                        Color.parseColor("#669D29"),
                        Color.parseColor("#C4B12B"), // золотисто-жёлтый
                        Color.parseColor("#E67E22"), // яркий оранжевый
                        Color.parseColor("#E67E22"),
                        Color.parseColor("#C4B12B"),
                        Color.parseColor("#669D29")
                    )
                    connectButton.background = createNeonRingDrawable(workingColors)

                    connectionStatusLabel.setLeftIcon(R.drawable.check, statusText, offsetX = 0, offsetY = -5, color = Color.TRANSPARENT)
                    connectionStatusLabel.setTextColor(0xFFFFC107.toInt())

                    serverSelectContainer.isEnabled = false
                    serverSelectContainer.alpha = 0.6f
                }
                State.CONNECTED -> {
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    isVpnConnected = true
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

                    connectionStatusLabel.setLeftIcon(R.drawable.check, "CONNECTED", offsetX = 0, offsetY = -5, color = (0xFF4CAF50.toInt()))
                    connectionStatusLabel.setTextColor(0xFF4CAF50.toInt())

                    serverSelectContainer.isEnabled = false
                    serverSelectContainer.alpha = 0.6f
                    serverSelectIcon.setImageResource(R.drawable.block)
                }
            }
        }
    }

    private fun logToConsole(msg: String) {
        runOnUiThread {
            val start = logBuffer.length
            val prefix = if (start == 0) "> " else "\n> "
            logBuffer.append(prefix).append(msg)
            val lineStart = if (start == 0) 0 else start + 1
            val lineEnd = logBuffer.length

            val color = when {
                msg.contains("Config loaded", ignoreCase = true) ||
                    msg.contains("dead session", ignoreCase = true) -> Color.parseColor("#FF9800")
                msg.contains("Connected", ignoreCase = true) -> Color.parseColor("#4CAF50")
                msg.contains("Stopped", ignoreCase = true) ||
                    msg.contains("Error", ignoreCase = true) ||
                    msg.contains("Ошибка", ignoreCase = true) -> Color.parseColor("#F44336")
                else -> null
            }

            if (color != null) {
                logBuffer.setSpan(
                    ForegroundColorSpan(color),
                    lineStart,
                    lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            activeLogTextView?.text = SpannableStringBuilder(logBuffer)
            activeLogScrollView?.post {
                activeLogScrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    // --- ДИАЛОГ МЕНЕДЖЕРА КОНФИГУРАЦИЙ ---

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

        // 1. Шапка: Кнопка "Назад" + Заголовок
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

        // 2. Кнопка "+ Добавить конфигурацию"
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
                setMargins(0, 0, 0, 20.dpToPx())
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
            text = "Добавить конфигурацию"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
        }

        btnAddConfigLayout.addView(iconPlus)
        btnAddConfigLayout.addView(textPlus)

        // 3. Заголовок раздела "ВАШИ КОНФИГУРАЦИИ"
        val sectionTitle = TextView(this).apply {
            text = "ВАШИ КОНФИГУРАЦИИ"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#7E7E7E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8.dpToPx(), 0, 12.dpToPx())
            }
        }

        // 4. Прокручиваемый список карточек
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
        rootLayout.addView(sectionTitle)
        rootLayout.addView(scrollView)

        fun populateList() {
            listContainer.removeAllViews()
            val configs = getSavedConfigs()
            val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
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
                    setPadding(8.dpToPx(), 12.dpToPx(), 8.dpToPx(), 12.dpToPx())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14 * resources.displayMetrics.density
                        // Серо-зеленый цвет для активного и тёмный #1C1C1E для неактивного
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

                // Иконка состояния selection
                val ivIndicator = ImageView(this).apply {
                    setImageResource(if (isActive) R.drawable.leftchev else R.drawable.uncheck)
                    setColorFilter(if (isActive) Color.parseColor("#669D29") else Color.parseColor("#3b3b3b"))
                    val iconSize = 22.dpToPx()
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        setMargins(0, 0, 8.dpToPx(), 0)
                    }
                }

                // Название конфигурации
                val nameTv = TextView(this).apply {
                    text = item.name
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }

                // Выбор конфигурации по клику на карточку
                itemLayout.setOnClickListener {
                    if (isVpnConnected) {
                        logToConsole("Нельзя менять конфигурацию во время активного подключения")
                        return@setOnClickListener
                    }
                    selectedConfigContent = item.content
                    selectedConfigName = item.name
                    saveConfigToPrefs(item.content, item.name)
                    prefs.edit().putString("active_config_id", item.id).apply()
                    setupServerSelector()
                    logToConsole("Выбрана конфигурация: ${item.name}")
                    populateList()
                }

                // Кнопка редактирования (pen.xml)
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

                // Кнопка удаления (delete.xml)
                val btnDelete = android.widget.ImageButton(this).apply {
                    setImageResource(R.drawable.delete)
                    setColorFilter(Color.parseColor("#AAAAAA"))
                    setBackgroundColor(Color.TRANSPARENT)
                    val iconSize = 22.dpToPx()
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    setOnClickListener {
                        if (isVpnConnected && isActive) {
                            logToConsole("Нельзя удалить активную конфигурацию во время подключения")
                            return@setOnClickListener
                        }
                        showDeleteConfirmation(item) {
                            populateList()
                        }
                    }
                }

                itemLayout.addView(ivIndicator)
                itemLayout.addView(nameTv)
                itemLayout.addView(btnRename)
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
            }
            .create()

        activeConfigDialog = dialog

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.setContentView(rootLayout)

        // 2. Настраиваем окно диалога ПОСЛЕ вызова show()
        dialog.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Убираем системный фон диалога, который оставляет стандартные рамки по бокам
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
                    val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
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

            val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
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
                    saveConfigToPrefs("", "Unknown")
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

    // --- ДИАЛОГ ЛОГОВ ---

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

        headerLayout.addView(btnClose)
        headerLayout.addView(titleView)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        val textView = TextView(this).apply {
            text = SpannableStringBuilder(logBuffer)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.buttons_icon_color))
        }

        scrollView.addView(textView)

        rootLayout.addView(headerLayout)
        rootLayout.addView(scrollView)

        activeLogTextView = textView
        activeLogScrollView = scrollView


        val dialog = AlertDialog.Builder(this)
            .setOnDismissListener {
                activeLogTextView = null
                activeLogScrollView = null
            }
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.setContentView(rootLayout)
        // 2. Настраиваем окно диалога ПОСЛЕ вызова show()
        dialog.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Убираем системный фон диалога, который оставляет стандартные рамки по бокам
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    // --- FILE IO & PREFS ---

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
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("config_content", content)
            .putString("config_name", name)
            .apply()
    }

    private fun loadConfigFromPrefs() {
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val configs = getSavedConfigs()

        // Миграция старых данных, если список пуст
        if (configs.isEmpty()) {
            val oldContent = prefs.getString("config_content", null)
            val oldName = prefs.getString("config_name", "Unknown")
            if (oldContent != null) {
                val item = ConfigItem(name = oldName ?: "Config 1", content = oldContent)
                configs.add(item)
                saveConfigsToPrefs(configs, item.id)
            }
        }

        val activeId = prefs.getString("active_config_id", null)
        val activeItem = configs.find { it.id == activeId } ?: configs.firstOrNull()

        if (activeItem != null) {
            selectedConfigContent = activeItem.content
            selectedConfigName = activeItem.name
            prefs.edit().putString("active_config_id", activeItem.id).apply()
        }
    }

    private fun formatSpeed(speedStr: String): String {
        val mbps = speedStr.toDoubleOrNull()
        if (mbps != null) {
            return when {
                mbps >= 1000.0 -> String.format(java.util.Locale.US, "%.2f Gbps", mbps / 1000.0)
                mbps >= 1.0 -> String.format(java.util.Locale.US, "%.2f Mbps", mbps)
                mbps >= 0.001 -> String.format(java.util.Locale.US, "%.1f Kbps", mbps * 1000.0)
                else -> "0 Kbps"
            }
        }
        return if (speedStr.isNotBlank()) speedStr else "0 B/s"
    }

    fun updateTrafficStats(rxTotal: String, txTotal: String, rtt: String, rxSpeedRaw: String, txSpeedRaw: String) {
        val rxSpeed = formatSpeed(rxSpeedRaw)
        val txSpeed = formatSpeed(txSpeedRaw)

        runOnUiThread {
            tvRtt.text = if (rtt.isNotBlank()) rtt else "0 ms"
            tvRx.text = rxSpeed     // Скорость загрузки (напр. "18.47 Mbps" или "1.85 MiB/s")
            tvTx.text = txSpeed     // Скорость отдачи (напр. "1.86 Mbps" или "200 KiB/s")
            tvRxm.text = if (rxTotal.isNotBlank()) rxTotal else "0 B"   // Всего получено (напр. "2.20 MiB")
            tvTxm.text = if (txTotal.isNotBlank()) txTotal else "0 B"   // Всего отправлено (напр. "227.52 KiB")
        }
    }

    // --- GRAPHICS & ANIMATION DRAWABLES ---

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
        val rippleColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))

        return android.graphics.drawable.RippleDrawable(rippleColor, layerDrawable, null)
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
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

                val colors = intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor("#80FF7043"),
                    Color.parseColor("#FFFFCA28")
                )
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
}