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

class MainActivity : AppCompatActivity() {

    // UI Элементы
    private lateinit var statusText: TextView
    private lateinit var connectionStatusLabel: TextView
    private lateinit var connectButton: Button
    private lateinit var spinner: ProgressBar
    private lateinit var selectConfigButton: Button
    private lateinit var scrollView: ScrollView

    // Состояние
    private var selectedConfigContent: String? = null
    private var selectedConfigName: String = "Unknown"
    private var isVpnConnected = false

    // Enum для состояний UI
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }
    }

    private external fun initLogger()

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

    // --- BROADCAST RECEIVER ---

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status")
            status?.let { msg ->
                logToConsole(msg)

                // Логика переключения состояний на основе сообщений от Rust
                when {
                    msg.contains("Starting", ignoreCase = true) ||
                            msg.contains("Authenticating", ignoreCase = true) ||
                            msg.contains("Connecting", ignoreCase = true) -> {
                        setUiState(State.CONNECTING)
                    }

                    msg.contains("VPN Tunnel UP", ignoreCase = true) -> {
                        setUiState(State.CONNECTED)
                    }

                    msg.contains("VPN Stopped", ignoreCase = true) ||
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