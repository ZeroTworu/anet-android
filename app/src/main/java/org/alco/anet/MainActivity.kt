package org.alco.anet

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import androidx.core.content.FileProvider
import androidx.annotation.Keep
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri

// Вспомогательная структура данных для парсинга нод в Kotlin
data class ServerModel(val name: String?, val address: String, val mode: String) {
    fun getFormattedName(): String {
        if (!name.isNullOrBlank()) return name
        val ip = address.split(":").firstOrNull() ?: address
        return "$ip:${mode.uppercase()}"
    }
}

class MainActivity : AppCompatActivity() {

    // UI Элементы
    private lateinit var statusText: TextView
    private lateinit var connectionStatusLabel: TextView
    private lateinit var connectButton: Button
    private lateinit var spinner: ProgressBar
    private lateinit var selectConfigButton: Button
    private lateinit var scrollView: ScrollView
    private lateinit var btnScanQr: Button
    private lateinit var serverSelectSpinner: Spinner

    private lateinit var selectAppsButton: Button
    private var activeErrorDialog: androidx.appcompat.app.AlertDialog? = null

    // Состояние
    private var selectedConfigContent: String? = null
    private var selectedConfigName: String = "Unknown"
    private var isCheckingUpdates = false
    private var isVpnConnected = false
    private var updateDialog: androidx.appcompat.app.AlertDialog? = null
    private var progressBar: ProgressBar? = null

    // Список распарсенных нод из активного конфига
    private val availableServers = mutableListOf<ServerModel>()
    private var selectedServerName: String = ""

    private external fun getAppVersion(): String
    private external fun getBuildInfo(): String
    private external fun checkUpdates(config: String?)
    private external fun startDownload(path: String)
    private external fun getPendingTag(): String
    private external fun getPendingBody(): String

    // Enum для состояний UI
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }
    }

    private external fun initLogger()

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

    // Красивая модалка обновления
    private fun showUpdateModal(version: String, body: String) {
        runOnUiThread {
            if (updateDialog?.isShowing == true) return@runOnUiThread

            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
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

    // --- БЕЗОПАСНЫЙ TOML-ПАРСЕР СЕРВЕРОВ ---
    private fun parseServers(toml: String): List<ServerModel> {
        val servers = mutableListOf<ServerModel>()
        val parts = toml.split("[[servers]]")
        if (parts.size <= 1) return emptyList()

        for (i in 1 until parts.size) {
            var sName: String? = null
            var sAddr: String? = null
            var sMode: String? = null

            // Читаем блок построчно, игнорируя брэкеты внутри строковых литералов
            for (line in parts[i].lines()) {
                val trimmed = line.trim()

                if (trimmed.startsWith("[") && !trimmed.startsWith("[[servers")) {
                    break
                }

                if (trimmed.startsWith("name")) {
                    sName = trimmed.substringAfter("=").replace("\"", "").trim()
                } else if (trimmed.startsWith("address")) {
                    sAddr = trimmed.substringAfter("=").replace("\"", "").trim()
                } else if (trimmed.startsWith("mode")) {
                    sMode = trimmed.substringAfter("=").replace("\"", "").trim()
                }
            }

            if (sAddr != null && sMode != null) {
                servers.add(ServerModel(sName, sAddr, sMode))
            }
        }
        return servers
    }

    // Слушатель выбора элементов в выпадающем списке
    private fun restoreSpinnerListener() {
        serverSelectSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val name = availableServers[position].getFormattedName()
                selectedServerName = name
                val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("selected_server_${selectedConfigName}", name).apply()
                logToConsole("Приоритетный сервер: $name")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // Инициализация выпадающего меню серверов с кастомной оранжевой разметкой
    private fun setupServerSpinner() {
        val content = selectedConfigContent ?: return
        availableServers.clear()
        availableServers.addAll(parseServers(content))

        logToConsole("Найдено серверов в конфиге: ${availableServers.size}")

        if (availableServers.isEmpty()) {
            serverSelectSpinner.visibility = View.GONE
            return
        }

        serverSelectSpinner.visibility = View.VISIBLE
        val adapter = ArrayAdapter(
            this,
            R.layout.spinner_dropdown_item,
            availableServers.map { it.getFormattedName() }
        )
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        serverSelectSpinner.adapter = adapter

        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val lastSelected = prefs.getString("selected_server_${selectedConfigName}", "") ?: ""
        val index = availableServers.indexOfFirst { it.getFormattedName() == lastSelected }
        if (index >= 0) {
            serverSelectSpinner.setSelection(index)
            selectedServerName = lastSelected
        } else {
            selectedServerName = availableServers.first().getFormattedName()
        }

        // Подключаем слушатель событий выбора
        restoreSpinnerListener()
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
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.requestMethod = "GET"

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val content = connection.inputStream.bufferedReader().use { it.readText() }

                    if (content.contains("[main]") && content.contains("[keys]")) {
                        runOnUiThread {
                            selectedConfigContent = content
                            selectedConfigName = "QR-Imported"
                            saveConfigToPrefs(content, "QR-Imported")
                            spinner.visibility = View.INVISIBLE
                            logToConsole("Профиль импортирован по QR-коду!")

                            setupServerSpinner()

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
            }
        }.start()
    }

    // --- LAUNCHERS ---

    // Выбор файла через проводник
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val content = readTextFromUri(it)
            val name = getFileName(it)

            if (content.isNotEmpty()) {
                selectedConfigContent = content
                selectedConfigName = name
                logToConsole("Loaded config: $name (${content.length} bytes)")
                saveConfigToPrefs(content, name)
                setupServerSpinner()
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
    // Права VPN
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

    // Права Уведомлений
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

            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
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
            val status = intent?.getStringExtra("status")

            if (status == null) return

            if (status.contains("Найдено обновление") ||
                status.contains("актуальная версия") ||
                status.contains("Ошибка обновления")) {
                isCheckingUpdates = false
                val btnUpdate = findViewById<Button>(R.id.btnCheckUpdate)
                btnUpdate.isEnabled = true
                btnUpdate.alpha = 1.0f
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

                logToConsole(msg)

                val isAuthError = msg.contains("сессий", ignoreCase = true) ||
                        msg.contains("истекло", ignoreCase = true) ||
                        msg.contains("denied", ignoreCase = true)

                when {
                    // Синхронизируем Spinner при авто-переключении нод из Rust
                    msg.contains("Active node:") -> {
                        val activeName = msg.substringAfter("Active node:").trim()
                        runOnUiThread {
                            val index = availableServers.indexOfFirst { it.getFormattedName() == activeName }
                            if (index >= 0) {
                                // Принудительно отключаем слушатель событий на время программного сдвига
                                serverSelectSpinner.onItemSelectedListener = null

                                serverSelectSpinner.setSelection(index)
                                selectedServerName = activeName

                                val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
                                prefs.edit().putString("selected_server_${selectedConfigName}", activeName).apply()

                                // Возвращаем слушатель назад
                                restoreSpinnerListener()
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

                    msg.contains("Starting", ignoreCase = true) ||
                            msg.contains("Authenticating", ignoreCase = true) ||
                            msg.contains("Connecting", ignoreCase = true) -> {
                        setUiState(State.CONNECTING)
                    }

                    msg.contains("VPN Tunnel UP", ignoreCase = true) -> {
                        setUiState(State.CONNECTED)
                    }

                    msg.contains("Stopped", ignoreCase = true) ||
                            msg.contains("Error", ignoreCase = true) ||
                            msg.contains("Failed", ignoreCase = true) -> {
                        setUiState(State.DISCONNECTED)
                    }
                }
            }
        }
    }

    // --- LIFECYCLE ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        connectionStatusLabel = findViewById(R.id.connectionStatus)
        connectButton = findViewById(R.id.connect)
        spinner = findViewById(R.id.connectSpinner)
        selectConfigButton = findViewById(R.id.selectConfig)
        scrollView = findViewById(R.id.scrollView)
        btnScanQr = findViewById(R.id.btnScanQr)
        serverSelectSpinner = findViewById(R.id.serverSelectSpinner)

        selectAppsButton = findViewById(R.id.selectApps)
        selectAppsButton.setOnClickListener {
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }

        initLogger()

        loadConfigFromPrefs()
        if (selectedConfigContent != null) {
            logToConsole("Config loaded: $selectedConfigName")
            setupServerSpinner()
            checkBatteryOptimizations()
        } else {
            logToConsole("Welcome. Please select config file.")
        }

        // Регистрация ресивера с помощью безопасного метода Jetpack ContextCompat
        val filter = IntentFilter("org.alco.anet.VPN_STATUS")
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        connectButton.setOnClickListener {
            if (isVpnConnected) {
                stopVpnService()
            } else {
                checkPermissionsAndStart()
            }
        }

        selectConfigButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        btnScanQr.setOnClickListener {
            startQrScanner()
        }

        setUiState(State.DISCONNECTED)

        findViewById<TextView>(R.id.versionLabel).text = getAppVersion()
        findViewById<TextView>(R.id.buildDetailLabel).text = getBuildInfo()

        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        btnCheckUpdate.setOnClickListener {
            if (isCheckingUpdates) return@setOnClickListener

            isCheckingUpdates = true
            btnCheckUpdate.isEnabled = false
            btnCheckUpdate.alpha = 0.5f

            logToConsole("Checking for system updates...")

            Thread {
                try {
                    checkUpdates(selectedConfigContent)
                } catch (e: Exception) {
                    runOnUiThread {
                        isCheckingUpdates = false
                        btnCheckUpdate.isEnabled = true
                        btnCheckUpdate.alpha = 1.0f
                        logToConsole("Update check error: ${e.message}")
                    }
                }
            }.start()
        }

        // Проверяем, было ли запущено приложение через открытие .toml файла извне
        handleIntent(intent)
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

            if (content.isNotEmpty() && content.contains("[main]") && content.contains("[keys]")) {
                selectedConfigContent = content
                selectedConfigName = name
                saveConfigToPrefs(content, name)

                logToConsole("Конфигурация успешно импортирована: $name")
                setupServerSpinner()

                // ТРЕБОВАНИЕ: Мгновенный автоматический запуск туннеля при импорте из файла
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

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
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
        logToConsole(">>> Launching Service...")

        val intent = Intent(this, ANetVpnService::class.java)
        intent.action = ANetVpnService.ACTION_CONNECT
        intent.putExtra("CONFIG", selectedConfigContent)
        intent.putExtra("SELECTED_SERVER", selectedServerName) // Передаем приоритетную ноду

        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val appsSet = prefs.getStringSet("allowed_apps", emptySet())
        if (!appsSet.isNullOrEmpty()) {
            intent.putStringArrayListExtra("ALLOWED_APPS", ArrayList(appsSet))
        }

        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        logToConsole(">>> Stopping Service...")
        val intent = Intent(this, ANetVpnService::class.java)
        intent.action = ANetVpnService.ACTION_STOP
        startService(intent)
        setUiState(State.CONNECTING)
    }

    // --- UI HELPERS ---

    private fun setUiState(state: State) {
        runOnUiThread {
            when (state) {
                State.DISCONNECTED -> {
                    isVpnConnected = false
                    spinner.visibility = View.INVISIBLE

                    connectButton.text = "CONNECT"
                    connectButton.backgroundTintList = ColorStateList.valueOf(0xFF4CAF50.toInt())
                    connectButton.isEnabled = true

                    connectionStatusLabel.text = "Now you are Disconnected to VPN"
                    connectionStatusLabel.setTextColor(0xFFFF5252.toInt())

                    serverSelectSpinner.isEnabled = true
                }
                State.CONNECTING -> {
                    spinner.visibility = View.VISIBLE

                    connectButton.text = ""
                    connectButton.backgroundTintList = ColorStateList.valueOf(0xFF555555.toInt())
                    connectButton.isEnabled = false

                    connectionStatusLabel.text = "WORKING..."
                    connectionStatusLabel.setTextColor(0xFFFFC107.toInt())

                    serverSelectSpinner.isEnabled = false
                }
                State.CONNECTED -> {
                    isVpnConnected = true
                    spinner.visibility = View.INVISIBLE

                    connectButton.text = "STOP"
                    connectButton.backgroundTintList = ColorStateList.valueOf(0xFFF44336.toInt())
                    connectButton.isEnabled = true

                    connectionStatusLabel.text = "Now you are connected to VPN"
                    connectionStatusLabel.setTextColor(0xFF4CAF50.toInt())

                    serverSelectSpinner.isEnabled = false
                }
            }
        }
    }

    private fun logToConsole(msg: String) {
        runOnUiThread {
            statusText.append("\n> $msg")
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // --- FILE IO & PREFS ---

    private fun readTextFromUri(uri: android.net.Uri): String {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            } ?: ""
        } catch (e: Exception) {
            logToConsole("IO Error: ${e.message}")
            ""
        }
    }

    private fun getFileName(uri: android.net.Uri): String {
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
        val content = prefs.getString("config_content", null)
        val name = prefs.getString("config_name", "Unknown")
        if (content != null) {
            selectedConfigContent = content
            selectedConfigName = name ?: "Unknown"
        }
    }
}
