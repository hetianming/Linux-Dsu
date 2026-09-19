package com.mcai.ubuntudsu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class DsuInstallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("dsu_install", "DSU 安装", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, "dsu_install")
            .setContentTitle("DSU 安装进行中")
            .setContentText("系统正在通过本地服务读取 GSI 安装包，请保持应用在后台运行")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopForeground(true)
        super.onDestroy()
    }

}
