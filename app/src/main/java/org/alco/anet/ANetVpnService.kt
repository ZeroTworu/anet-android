package org.alco.anet

import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

private val CHANNEL_ID = "ANetChannel"

class ANetVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }
    }

    // Native methods
    private external fun initLogger()
    private external fun connectVpn(config: String)
    private external fun stopVpn()

    override fun onCreate() {
        super.onCreate()
        initLogger()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "ANet VPN Status",
                android.app.NotificationManager.IMPORTANCE_LOW // Чтобы не пикало
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpnNative()
            return START_NOT_STICKY
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ANet VPN")
            .setContentText("Connected to secure network")
            .setSmallIcon(R.mipmap.ic_launcher) // Или android.R.drawable.ic_dialog_info
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()


        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                1337,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1337, notification)
        }


        val config = intent?.getStringExtra("CONFIG") ?: return START_NOT_STICKY
        Thread { connectVpn(config) }.start()

        return START_STICKY
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

    // Вызывается из Rust!
    fun onStatusChanged(status: String) {
        Log.d("ANet", "Status: $status")

        // 1. Обновляем уведомление (чтобы юзер видел в шторке)
        updateNotification(status)

        // 2. Шлем в UI (MainActivity)
        val intent = Intent("org.alco.anet.VPN_STATUS")
        intent.putExtra("status", status)
        // Используем LocalBroadcastManager (если есть) или обычный sendBroadcast
        // Для Android Tiramisu+ (13) нужно указывать пакет для безопасности
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun updateNotification(status: String) {
        val notification = NotificationCompat.Builder(this, "ANetChannel")
            .setContentTitle("ANet VPN")
            .setContentText(status) // <-- Текст из Rust
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        // Обновляем уведомление (ID должен совпадать с startForeground)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1337, notification)
    }

    private fun stopVpnNative() {
        stopVpn()
        vpnInterface?.close()
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        stopVpnNative()
        super.onDestroy()
    }
}