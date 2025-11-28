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

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var connectButton: Button

    // Флаг текущего состояния
    private var isVpnConnected = false

    companion object {
        init { System.loadLibrary("anet_mobile") }
    }

    private external fun initLogger()

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
        connectButton = findViewById(R.id.connect)

        initLogger()

        // Ресивер
        val filter = IntentFilter("org.alco.anet.VPN_STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        // Обработчик клика
        connectButton.setOnClickListener {
            if (isVpnConnected) {
                // Если подключено -> жмем Stop
                stopVpnService()
            } else {
                // Если отключено -> жмем Connect (с проверками)
                checkPermissionsAndStart()
            }
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

    // ЗАПУСК
    private fun startVpnService() {
        statusText.append("\n>>> Starting Service...")
        val intent = Intent(this, ANetVpnService::class.java)
        intent.action = ANetVpnService.ACTION_CONNECT
        intent.putExtra("CONFIG", """...ТВОЙ_КОНФИГ...""")
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
