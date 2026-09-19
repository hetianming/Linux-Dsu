package com.mcai.ubuntudsu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.mcai.ubuntudsu.core.DownloadNode
import com.mcai.ubuntudsu.core.JavaDownloader
import com.mcai.ubuntudsu.core.RomApi
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ROM 固件下载前台服务
 * - 支持后台下载（即使关闭 Activity 也继续）
 * - 通知栏显示进度 + 取消按钮
 * - 通过广播实时更新 UI（灵动岛）
 */
class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "rom_download"
        const val NOTIF_ID = 2001

        const val ACTION_START = "com.mcai.ubuntudsu.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.mcai.ubuntudsu.CANCEL_DOWNLOAD"
        const val ACTION_PAUSE = "com.mcai.ubuntudsu.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.mcai.ubuntudsu.RESUME_DOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_VERSION = "version"
        const val EXTRA_NODE_INDEX = "node_index"
        const val EXTRA_LABEL = "label"
        const val EXTRA_DEVICE_NAME = "device_name"

        // 广播
        const val BROADCAST_UPDATE = "com.mcai.ubuntudsu.DOWNLOAD_UPDATE"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_DEVICE = "device_name"
        const val EXTRA_LOG = "log"
        const val EXTRA_DONE = "done"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SAVED_PATH = "saved_path"
        const val EXTRA_STATE = "state" // 0=idle 1=downloading 2=done 3=cancelled 4=failed 5=paused

        @Volatile private var running = false
        @Volatile private var progress = 0
        @Volatile private var fileName = ""
        @Volatile private var deviceName = ""
        @Volatile private var labelInfo = ""

        fun isDownloading(): Boolean = running
        fun getProgress(): Int = progress
        fun getFileName(): String = fileName
        fun getDeviceName(): String = deviceName
        fun getLabelInfo(): String = labelInfo
    }

    private val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var downloadThread: Thread? = null
    private var cancelReceiver: android.content.BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 注册取消广播接收器（通知栏取消按钮通过 BroadcastReceiver 触发）
        cancelReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_CANCEL -> {
                        cancelled.set(true)
                        paused.set(false)
                        broadcastState(3, 0, "", "正在取消...")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    ACTION_PAUSE -> {
                        paused.set(true)
                        broadcastState(5, progress, "", "已暂停")
                    }
                    ACTION_RESUME -> {
                        paused.set(false)
                        broadcastState(1, progress, "", "继续下载")
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_CANCEL)
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(cancelReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(cancelReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "download.zip"
                val version = intent.getStringExtra(EXTRA_VERSION) ?: ""
                val nodeIndex = intent.getIntExtra(EXTRA_NODE_INDEX, 3)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "ROM"
                val devName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: ""

                startForeground(NOTIF_ID, buildNotification(label, 0, "准备下载..."))
                startDownload(url, filename, version, nodeIndex, label, devName)
            }
            ACTION_CANCEL -> {
                cancelled.set(true)
                paused.set(false)
                broadcastState(3, 0, "", "正在取消...")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PAUSE -> {
                paused.set(true)
                broadcastState(5, progress, "", "已暂停")
            }
            ACTION_RESUME -> {
                paused.set(false)
                broadcastState(1, progress, "", "继续下载")
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(url: String, filename: String, version: String, nodeIndex: Int, label: String, devName: String) {
        running = true
        progress = 0
        fileName = filename
        deviceName = devName
        labelInfo = label
        cancelled.set(false)

        broadcastState(1, 0, "", "开始下载")

        Thread {
            val nodes = DownloadNode.values()
            val node = nodes.getOrElse(nodeIndex) { nodes[3] }
            val downloadUrl = RomApi.getDownloadUrl(filename, version, node)
            val targetFile = File(JavaDownloader.defaultSaveDir(), filename)

            val result = JavaDownloader.download(
                this,
                downloadUrl,
                targetFile,
                onProgress = { pct ->
                    progress = pct
                    updateNotification(label, pct, "$pct%")
                    broadcastState(1, pct, "", "$pct%")
                },
                isCancelled = { cancelled.get() },
                isPaused = { paused.get() },
                onLog = { log ->
                    broadcastLog(log)
                },
            )

            running = false
            when {
                result.success -> {
                    progress = 100
                    updateNotification(label, 100, "下载完成")
                    broadcastState(2, 100, "", "下载完成")
                    broadcastDone(true, "下载完成", result.file?.absolutePath ?: "")
                    Thread.sleep(3000)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                cancelled.get() -> {
                    updateNotification(label, 0, "已取消")
                    broadcastState(3, 0, "", "已取消")
                    broadcastDone(false, "已取消", "")
                    Thread.sleep(1000)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                else -> {
                    updateNotification(label, 0, "下载失败: ${result.message}")
                    broadcastState(4, 0, "", "下载失败: ${result.message}")
                    broadcastDone(false, result.message, "")
                    Thread.sleep(3000)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.start()
    }

    // ========== 广播 ==========

    private fun broadcastState(state: Int, progress: Int, speed: String, status: String) {
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_STATUS_TEXT, status)
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_DEVICE, deviceName)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastLog(log: String) {
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_LOG, log)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDone(success: Boolean, message: String, savedPath: String) {
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_DONE, true)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_SAVED_PATH, savedPath)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ========== 通知 ==========

    private val cancelPendingIntent: PendingIntent by lazy {
        // 使用 BroadcastReceiver 方式，避免 Android 12+ 后台 Service 启动限制
        PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_CANCEL).apply { setPackage(packageName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ROM 固件下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示 ROM 固件下载进度"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, progress: Int, text: String): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消下载",
                cancelPendingIntent,
            )

        if (progress in 1..99) {
            builder.setProgress(100, progress, false)
        } else if (progress == 100) {
            builder.setProgress(0, 0, false)
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
        }

        return builder.build()
    }

    private fun updateNotification(title: String, progress: Int, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, progress, text))
    }

    override fun onDestroy() {
        running = false
        cancelReceiver?.let { unregisterReceiver(it) }
        cancelReceiver = null
        super.onDestroy()
    }
}
