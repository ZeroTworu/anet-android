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

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }
    }

    private external fun initLogger()

    // Лаунчер для запроса прав на VPN
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        }
    }

    // Лаунчер для запроса прав на Уведомления (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Права дали, можно пробовать VPN
            attemptVpnConnection()
        } else {
            // Права не дали, пишем в лог, но VPN все равно пробуем (будет без уведомлений в шторке)
            statusText.append("\nNotification permission denied. VPN might run silently.")
            attemptVpnConnection()
        }
    }

    // Приемник статусов от Сервиса
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status")
            status?.let {
                statusText.append("\n$it")
                val scroll = findViewById<ScrollView>(R.id.scrollView)
                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText) // <-- Инициализируем поле

        initLogger()

        // Регистрируем ресивер для получения логов
        val filter = IntentFilter("org.alco.anet.VPN_STATUS")
        // Для Android 13 (Tiramisu) и выше нужен флаг экспорта
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        val btn = findViewById<Button>(R.id.connect)
        btn.setOnClickListener {
            checkPermissionsAndStart()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }

    // Шаг 1: Проверка прав на уведомления (только для Android 13+)
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

    // Шаг 2: Проверка прав на VPN (системный диалог "Ключик")
    private fun attemptVpnConnection() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // Прав нет, показываем диалог
            vpnPermissionLauncher.launch(intent)
        } else {
            // Права есть, запускаем
            startVpnService()
        }
    }

    // Шаг 3: Запуск сервиса
    private fun startVpnService() {
        statusText.append("\nStarting Service...")
        val intent = Intent(this, ANetVpnService::class.java)
        intent.putExtra("CONFIG", """...""") // Твой конфиг
        startForegroundService(intent)
    }
}
