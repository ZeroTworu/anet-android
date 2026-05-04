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
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import androidx.core.content.FileProvider
import androidx.annotation.Keep
import java.io.File

class MainActivity : AppCompatActivity() {

    // UI Элементы
    private lateinit var statusText: TextView
    private lateinit var connectionStatusLabel: TextView
    private lateinit var connectButton: Button
    private lateinit var spinner: ProgressBar
    private lateinit var selectConfigButton: Button
    private lateinit var scrollView: ScrollView

    private lateinit var selectAppsButton: Button
    private var activeErrorDialog: androidx.appcompat.app.AlertDialog? = null

    // Состояние
    private var selectedConfigContent: String? = null
    private var selectedConfigName: String = "Unknown"
    private var isCheckingUpdates = false
    private var isVpnConnected = false
    private var updateDialog: androidx.appcompat.app.AlertDialog? = null
    private var progressBar: ProgressBar? = null

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


    //  Метод установки
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

    //  Красивая модалка обновления
    private fun showUpdateModal(version: String, body: String) {
        runOnUiThread {
            // Если диалог уже открыт — не плодим новые
            if (updateDialog?.isShowing == true) return@runOnUiThread

            val builder = androidx.appcompat.app.AlertDialog.Builder(this)

            // Надуваем нашу разметку
            val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)

            // Ищем элементы ВНУТРИ dialogView
            val titleView = dialogView.findViewById<TextView>(R.id.updateTitle)
            val versionView = dialogView.findViewById<TextView>(R.id.updateVersion)
            val changelogView = dialogView.findViewById<TextView>(R.id.updateChangelog)
            val btnUpdate = dialogView.findViewById<Button>(R.id.btnUpdateNow)
            val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelUpdate)

            // Сохраняем ссылку на прогресс-бар в поле класса MainActivity,
            // чтобы обновлять его из statusReceiver
            progressBar = dialogView.findViewById<ProgressBar>(R.id.updateProgress)

            versionView.text = "Version: $version"
            changelogView.text = body

            builder.setView(dialogView)
            builder.setCancelable(false) // Чтобы идиот не закрыл случайно

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
                // Скачиваем во внутренний кэш приложения
                val destination = File(cacheDir, "update.apk").absolutePath
                startDownload(destination)
            }
        }
    }


    // --- LAUNCHERS ---

    // Выбор файла
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
            } else {
                logToConsole("Failed to read config file")
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
            // Если диалог уже показан — ничего не делаем
            if (activeErrorDialog?.isShowing == true) {
                return@runOnUiThread
            }

            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            builder.setTitle("ОШИБКА ДОСТУПА")

            val cleanMsg = message
                .replace("ERROR:", "")
                .replace("WARN:", "")
                .replace("[CORE AUTH]", "")
                .replace(Regex("\\[AUTH\\].*failed:"), "") // Убираем инфу про попытку (attempt X)
                .trim()

            builder.setMessage(cleanMsg)
            builder.setPositiveButton("ПОНЯТНО") { dialog, _ ->
                dialog.dismiss()
                activeErrorDialog = null // Очищаем ссылку при закрытии
            }

            // Если юзер ткнул мимо окна — тоже очищаем
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
                // 1. Ловим прогресс обновления (чтобы не спамить в логи, обрабатываем отдельно)
                if (msg.startsWith("PROGRESS:")) {
                    val progressValue = msg.substringAfter("PROGRESS:").toFloatOrNull() ?: 0f
                    runOnUiThread {
                        progressBar?.isIndeterminate = false
                        progressBar?.progress = (progressValue * 100).toInt()
                    }
                    return // Выходим из функции, прогресс в консоль логов не пишем
                }

                // 2. Печатаем все остальные сообщения в консоль логов
                logToConsole(msg)

                // Проверяем на критические ошибки тарифа
                val isAuthError = msg.contains("сессий", ignoreCase = true) ||
                        msg.contains("истекло", ignoreCase = true) ||
                        msg.contains("denied", ignoreCase = true)

                when {
                    // --- ОБНОВЛЕНИЯ ---
                    msg.contains("Найдено обновление", ignoreCase = true) -> {
                        // Показываем модалку (текст можно распарсить из msg, если нужно)
                        val tag = getPendingTag()
                        val body = getPendingBody()
                        showUpdateModal(tag, body)
                    }

                    msg.contains("Update downloaded to cache", ignoreCase = true) -> {
                        // Когда Rust сказал, что файл на диске
                        updateDialog?.dismiss()
                        installApk()
                    }

                    // --- ОШИБКИ ТАРИФА ---
                    isAuthError -> {
                        stopVpnService()
                        setUiState(State.DISCONNECTED)
                        showErrorDialog(msg)
                    }

                    // --- СОСТОЯНИЯ ПОДКЛЮЧЕНИЯ ---
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

        // Инициализация UI
        statusText = findViewById(R.id.statusText)
        connectionStatusLabel = findViewById(R.id.connectionStatus)
        connectButton = findViewById(R.id.connect)
        spinner = findViewById(R.id.connectSpinner)
        selectConfigButton = findViewById(R.id.selectConfig)
        scrollView = findViewById(R.id.scrollView)

        selectAppsButton = findViewById(R.id.selectApps)
        selectAppsButton.setOnClickListener {
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }

        initLogger()

        // Загрузка сохраненного конфига
        loadConfigFromPrefs()
        if (selectedConfigContent != null) {
            logToConsole("Config loaded: $selectedConfigName")
        } else {
            logToConsole("Welcome. Please select config file.")
        }

        // Регистрация ресивера
        val filter = IntentFilter("org.alco.anet.VPN_STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        // Обработчики кнопок
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

        // Начальное состояние
        setUiState(State.DISCONNECTED)

        findViewById<TextView>(R.id.versionLabel).text = getAppVersion()
        findViewById<TextView>(R.id.buildDetailLabel).text = getBuildInfo()

        val btnUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        btnUpdate.setOnClickListener {
            if (isCheckingUpdates) return@setOnClickListener

            isCheckingUpdates = true
            btnUpdate.isEnabled = false
            btnUpdate.alpha = 0.5f

            logToConsole("Checking for system updates...")

            Thread {
                try {
                    // Передаем текущий контент конфига в Rust
                    checkUpdates(selectedConfigContent)
                } catch (e: Exception) {
                    runOnUiThread {
                        isCheckingUpdates = false
                        btnUpdate.isEnabled = true
                        btnUpdate.alpha = 1.0f
                        logToConsole("Update check error: ${e.message}")
                    }
                }
            }.start()
        }
    }

    @Keep
    fun onStatusChanged(status: String) {
        // Мы просто пересылаем это сообщение самим себе через ту же систему Broadcast
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

    private fun showAccessDeniedDialog(message: String) {
        runOnUiThread {
            // Чтобы диалог выглядел в стиле HL (черный/оранжевый),
            // мы используем MaterialAlertDialogBuilder или кастомную тему
            val builder = androidx.appcompat.app.AlertDialog.Builder(this)

            // Настраиваем заголовок и текст (можешь добавить иконку радиации в ресурсы)
            builder.setTitle("ОШИБКА ДОСТУПА")
            builder.setMessage(message)

            builder.setPositiveButton("ПОНЯТНО (OK)") { dialog, _ ->
                dialog.dismiss()
            }

            val dialog = builder.create()
            dialog.show()

            // Стилизация кнопок программно (чтобы не ковырять XML)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#FF6400"))
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

        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val appsSet = prefs.getStringSet("allowed_apps", emptySet())
        if (!appsSet.isNullOrEmpty()) {
            intent.putStringArrayListExtra("ALLOWED_APPS", ArrayList(appsSet))
        }

        startForegroundService(intent)
    }

    private fun stopVpnService() {
        logToConsole(">>> Stopping Service...")
        val intent = Intent(this, ANetVpnService::class.java)
        intent.action = ANetVpnService.ACTION_STOP
        startService(intent)
        // Состояние переключится в DISCONNECTED, когда придет подтверждение от Rust
        // Но для отзывчивости можно сразу переключить UI в промежуточное состояние
        setUiState(State.CONNECTING) // Показываем спиннер пока останавливается
    }

    // --- UI HELPERS ---

    private fun setUiState(state: State) {
        runOnUiThread {
            when (state) {
                State.DISCONNECTED -> {
                    isVpnConnected = false
                    spinner.visibility = View.INVISIBLE

                    connectButton.text = "CONNECT"
                    connectButton.backgroundTintList = ColorStateList.valueOf(0xFF4CAF50.toInt()) // Green
                    connectButton.isEnabled = true

                    connectionStatusLabel.text = "Now you are Disconnected to VPN"
                    connectionStatusLabel.setTextColor(0xFFFF5252.toInt()) // Red
                }
                State.CONNECTING -> {
                    // isVpnConnected не меняем, ждем результата
                    spinner.visibility = View.VISIBLE

                    connectButton.text = "" // Убираем текст, чтобы было видно спиннер (или можно оставить)
                    connectButton.backgroundTintList = ColorStateList.valueOf(0xFF555555.toInt()) // Gray
                    connectButton.isEnabled = false

                    connectionStatusLabel.text = "WORKING..."
                    connectionStatusLabel.setTextColor(0xFFFFC107.toInt()) // Yellow
                }
                State.CONNECTED -> {
                    isVpnConnected = true
                    spinner.visibility = View.INVISIBLE

                    connectButton.text = "STOP"
                    connectButton.backgroundTintList = ColorStateList.valueOf(0xFFF44336.toInt()) // Red
                    connectButton.isEnabled = true

                    connectionStatusLabel.text = "Now you are connected to VPN"
                    connectionStatusLabel.setTextColor(0xFF4CAF50.toInt()) // Green
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