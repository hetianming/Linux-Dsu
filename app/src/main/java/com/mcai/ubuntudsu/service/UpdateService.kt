package com.mcai.ubuntudsu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.content.FileProvider
import com.mcai.ubuntudsu.core.ProgressNotification
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用自更新下载：前台服务后台静默下载新版本 APK（可离开 APP 继续下载），
 * 进度走 [ProgressNotification]（灵动岛/流体云同样可见），完成后点击通知即可安装。
 */
class UpdateService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_VERSION = "version"
        const val EXTRA_NAME = "name"
        const val CHANNEL_ID = "app_update"
        const val NOTE_ID = 9001
        const val ACTION_DONE = "com.mcai.ubuntudsu.update.DONE"
        const val EXTRA_APK = "apk"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: ""
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "新版本 $version"
        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(
            NOTE_ID,
            ProgressNotification.build(
                this, CHANNEL_ID, "正在下载更新", "$name · 0%", 0, true, null, true,
            ),
        )
        Thread { download(url, version, name) }.start()
        return START_NOT_STICKY
    }

    private fun download(url: String, version: String, name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        try {
            val dir = File(cacheDir, "update").apply { mkdirs() }
            // 清理旧安装包
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "UbuntuDSU-$version.apk")
            val tmp = File(dir, "UbuntuDSU-$version.apk.part")

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "UbuntuDSU-App")
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var lastNotified = -10
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct - lastNotified >= 2) {
                                lastNotified = pct
                                nm.notify(
                                    NOTE_ID,
                                    ProgressNotification.build(
                                        this, CHANNEL_ID, "正在下载更新",
                                        "$name · $pct%", pct, true, null, true,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(out)) throw IllegalStateException("无法保存安装包")

            // 完成通知：点击安装
            val install = PendingIntent.getActivity(
                this, NOTE_ID,
                installIntent(out).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            nm.notify(
                NOTE_ID,
                ProgressNotification.build(
                    this, CHANNEL_ID, "更新包已下载", "点击安装 $version", null, false, install, true,
                ),
            )
            // 通知前台界面直接弹安装（前台时），后台时由完成通知点击触发
            sendBroadcast(
                Intent(ACTION_DONE).setPackage(packageName).putExtra(EXTRA_APK, out.absolutePath),
            )
        } catch (e: Exception) {
            runCatching {
                nm.notify(
                    NOTE_ID,
                    ProgressNotification.build(
                        this, CHANNEL_ID, "更新下载失败", e.message ?: "网络异常", null, false, null, false,
                    ),
                )
            }
        } finally {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    /** APK 安装 Intent（FileProvider 授权读取） */
    private fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", apk,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW).apply {
                description = "下载 UbuntuDSU 新版本安装包"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }
}
