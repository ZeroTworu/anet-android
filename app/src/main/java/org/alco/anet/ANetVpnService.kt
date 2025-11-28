package org.alco.anet

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.NotificationManager

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
    // Обрати внимание: имя функции в Rust должно быть Java_org_alco_anet_ANetVpnService_connectVpn
    private external fun connectVpn(config: String)
    // Имя в Rust: Java_org_alco_anet_ANetVpnService_stopVpn
    private external fun stopVpn()

    override fun onCreate() {
        super.onCreate()
        initLogger()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            Log.i("ANet", "Received STOP Intent")
            // Запускаем остановку в отдельном потоке, так как stopVpn в Rust ждет async задач
            Thread {
                stopVpn() // Rust вызовет onStatusChanged("VPN Stopped") перед выходом
                // Закрытие интерфейса и остановка сервиса
                stopVpnInternal()
            }.start()

            return START_NOT_STICKY
        }

        createNotificationChannel()

        // Логика запуска (CONNECT)
        // Создаем уведомление (обязательно для Foreground Service)
        val notification = NotificationCompat.Builder(this, "ANetChannel")
            .setContentTitle("ANet VPN")
            .setContentText("Connecting...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // ID уведомления 1337
        startForeground(1337, notification)

        val config = intent?.getStringExtra("CONFIG") ?: return START_NOT_STICKY
        Thread { connectVpn(config) }.start()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channelId = "ANetChannel"
            val channelName = "ANet VPN Status"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = android.app.NotificationChannel(channelId, channelName, importance)

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    // Вызывается из Rust!
    fun configureTun(ip: String, prefix: Int, mtu: Int): Int {
        Log.i("ANet", "Configuring TUN: $ip/$prefix MTU=$mtu")

        if (vpnInterface != null) {
            vpnInterface!!.close()
        }

        val builder = Builder()
        builder.addAddress(ip, prefix)
        builder.addDisallowedApplication(packageName)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("8.8.8.8")
        builder.setMtu(mtu)
        builder.setSession("ANet VPN")

        // Этот вызов создает виртуальный интерфейс
        vpnInterface = builder.establish()
        return vpnInterface?.fd ?: -1
    }

    // Колбэк из Rust
    fun onStatusChanged(status: String) {
        Log.d("ANet", "Status: $status")
        updateNotification(status)

        // Шлем статус в UI
        val intent = Intent("org.alco.anet.VPN_STATUS")
        intent.putExtra("status", status)
        intent.setPackage(packageName) // Для безопасности на Android 13+
        sendBroadcast(intent)
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, "ANetChannel")
            .setContentTitle("ANet VPN")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        try {
            notificationManager.notify(1337, notification)
        } catch (e: SecurityException) {}
    }

    private fun stopVpnInternal() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e("ANet", "Error closing interface", e)
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // На случай если система убила сервис, пробуем почистить
        stopVpnInternal()
        super.onDestroy()
    }
}
