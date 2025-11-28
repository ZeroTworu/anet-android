package org.alco.anet

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import android.graphics.Color
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var connectButton: Button

    // Config
    private var selectedConfigContent: String? = null
    private var selectedConfigName: String = "Unknown"

    // Флаг текущего состояния
    private var isVpnConnected = false


    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnService()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) attemptVpnConnection()
        else {
            statusText.append("\nNotification permission denied.")
            attemptVpnConnection()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status")
            status?.let {
                statusText.append("\n$it")
                val scroll = findViewById<ScrollView>(R.id.scrollView)
                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }

                // АВТОМАТИЧЕСКОЕ ПЕРЕКЛЮЧЕНИЕ СОСТОЯНИЯ ПО ЛОГАМ ИЗ RUST
                when {
                    it.contains("VPN Tunnel UP") -> setConnectedState(true)
                    it.contains("VPN Stopped") -> setConnectedState(false)
                    it.contains("Fatal Error") -> setConnectedState(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val btnConnect = findViewById<Button>(R.id.connect)
        val btnSelect = findViewById<Button>(R.id.selectConfig)

        loadConfigFromPrefs()

        if (selectedConfigContent != null) {
            statusText.text = "Ready. Config loaded: $selectedConfigName"
        } else {
            statusText.text = "Welcome. Please select config file."
        }

        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connect)


        // Ресивер
        val filter = IntentFilter("org.alco.anet.VPN_STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        btnConnect.setOnClickListener {
            if (isVpnConnected) {
                // Если подключено -> жмем Stop
                stopVpnService()
            } else {
                // Если отключено -> жмем Connect (с проверками)
                checkPermissionsAndStart()
            }
        }
        btnSelect.setOnClickListener {
            // Открываем пикер (ищем .toml или любые файлы)
            filePickerLauncher.launch(arrayOf("text/plain", "application/toml", "application/octet-stream"))
        }

    }

    // UI Свитчер
    private fun setConnectedState(connected: Boolean) {
        isVpnConnected = connected
        runOnUiThread {
            if (connected) {
                connectButton.text = "Disconnect"
                connectButton.backgroundTintList = ColorStateList.valueOf(Color.RED)
            } else {
                connectButton.text = "Connect"
                connectButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50")) // Green
            }
        }
    }

    private fun checkPermissionsAndStart() {
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
        if (intent != null) vpnPermissionLauncher.launch(intent)
        else startVpnService()
    }


    // Лаунчер выбора файла
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val content = readTextFromUri(it)
            val name = getFileName(it)

            if (content.isNotEmpty()) {
                selectedConfigContent = content
                selectedConfigName = name

                statusText.append("\nLoaded: $name (${content.length} bytes)")

                // Сохраняем
                saveConfigToPrefs(content, name)
            } else {
                statusText.append("\nFailed to read file")
            }
        }
    }

    // SharedPreferences Helpers
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
        val name = prefs.getString("config_name", "client.toml")

        if (content != null) {
            selectedConfigContent = content
            selectedConfigName = name ?: "Unknown"
        }
    }

    // Чтение текста из URI
    private fun readTextFromUri(uri: android.net.Uri): String {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            } ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // Получение имени файла из URI
    private fun getFileName(uri: android.net.Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    // Колонка DISPLAY_NAME
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "config.toml"
    }

    // ЗАПУСК
    private fun startVpnService() {
        val config = selectedConfigContent
        if (config == null) {
            statusText.append("\nError: No config selected!")
            return
        }
        statusText.append("\n>>> Starting Service...")
        val intent = Intent(this, ANetVpnService::class.java)
        intent.action = ANetVpnService.ACTION_CONNECT
        intent.putExtra("CONFIG", config)
        startForegroundService(intent)
        // Кнопка переключится сама, когда придет "VPN Tunnel UP" от Rust
    }

    // ОСТАНОВКА
    private fun stopVpnService() {
        statusText.append("\n>>> Stopping Service...")
        val intent = Intent(this, ANetVpnService::class.java)
        intent.action = ANetVpnService.ACTION_STOP
        startService(intent) // Для остановки можно обычный startService
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }
}
