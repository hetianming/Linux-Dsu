package com.mcai.ubuntudsu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.mcai.ubuntudsu.core.Aria2c
import com.mcai.ubuntudsu.core.DownloadNode
import com.mcai.ubuntudsu.core.JavaDownloader
import com.mcai.ubuntudsu.core.RomApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 多任务并发下载前台服务：
 * - 每个任务独立线程 / 暂停 / 取消 / 通知（互不干扰）
 * - ROM 走 JavaDownloader 多线程分块；自定义链接走内置 aria2c 引擎（libaria2c.so）
 * - aria2c 暂停 = 终止进程并保留 .aria2 断点文件，继续 = 断点续传
 * - 通知栏小窗口：每任务 [暂停/继续] [取消]，取消立即撤下该任务通知
 * - 通过广播实时更新 UI，每条广播携带 task_id
 */
class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "rom_download"
        const val NOTIF_ID_BASE = 2001

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
        const val EXTRA_TASK_ID = "task_id"
        /** 自定义直链：true 走内置 aria2c 引擎 */
        const val EXTRA_USE_ARIA2 = "use_aria2"

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
        const val EXTRA_TOTAL_SIZE = "total_size"
        const val EXTRA_DOWNLOADED_SIZE = "downloaded_size"
    }

    /** 单个下载任务的全部独立状态 */
    private inner class TaskCtx(
        val id: String,
        val url: String,
        val fileName: String,
        val label: String,
        val deviceName: String,
        val version: String,
        val nodeIndex: Int,
        val useAria2: Boolean,
    ) {
        val cancelled = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        @Volatile var progress = 0
        @Volatile var totalSize = 0L
        @Volatile var downloadedSize = 0L
        /** 该任务通知是否存活（取消/完成后撤下，拦截在途回调 re-post） */
        @Volatile var notifActive = true
        /** 终态（成功/失败/取消）；暂停不算终态 */
        @Volatile var done = false
        @Volatile var thread: Thread? = null
        val notifId: Int = NOTIF_ID_BASE + (id.hashCode() and 0x7FFF)
    }

    private val tasks = ConcurrentHashMap<String, TaskCtx>()
    private var actionReceiver: android.content.BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        actionReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_PAUSE -> targets(intent).forEach { pauseTask(it) }
                    ACTION_RESUME -> targets(intent).forEach { resumeTask(it) }
                    ACTION_CANCEL -> targets(intent).forEach { cancelTask(it) }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_CANCEL)
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(actionReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(actionReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "download.zip"
                val useAria2 = intent.getBooleanExtra(EXTRA_USE_ARIA2, false)
                val id = filename

                // 去重：同 id 任务仍在运行（含暂停）时忽略重复启动
                val existing = tasks[id]
                if (existing != null && !existing.done) return START_NOT_STICKY

                val task = TaskCtx(
                    id = id,
                    url = url,
                    fileName = filename,
                    label = intent.getStringExtra(EXTRA_LABEL) ?: "下载",
                    deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "",
                    version = intent.getStringExtra(EXTRA_VERSION) ?: "",
                    nodeIndex = intent.getIntExtra(EXTRA_NODE_INDEX, 3),
                    useAria2 = useAria2,
                )
                tasks[id] = task

                // startForegroundService 要求尽快 startForeground：本任务通知即前台通知
                startForeground(task.notifId, buildNotif(task))
                // 其余在途任务的通知补充 post（多任务小窗口并存）
                postOtherNotifs(task)
                launch(task)
            }
            ACTION_PAUSE -> targets(intent).forEach { pauseTask(it) }
            ACTION_RESUME -> targets(intent).forEach { resumeTask(it) }
            ACTION_CANCEL -> targets(intent).forEach { cancelTask(it) }
        }
        return START_NOT_STICKY
    }

    /** 控制指令的目标任务：带 task_id 精确命中；无 id 时作用于全部未终态任务（批量语义） */
    private fun targets(intent: Intent): List<TaskCtx> {
        val id = intent.getStringExtra(EXTRA_TASK_ID)
        if (id != null) return listOfNotNull(tasks[id])
        return tasks.values.filter { !it.done }
    }

    // ==================== 任务执行 ====================

    private fun launch(task: TaskCtx, fromSelf: Boolean = false) {
        // fromSelf：由即将退出的旧线程发起的重启（继续指令与杀进程竞态），忽略自身存活检查
        if (!fromSelf) {
            task.thread?.let { if (it.isAlive) return }
        }
        task.thread = Thread {
            if (task.useAria2) runAria2(task) else runRom(task)
        }.apply { start() }
    }

    /** ROM 下载：RomApi 线路 + JavaDownloader 多线程分块（暂停为原地等待） */
    private fun runRom(task: TaskCtx) {
        broadcastTask(task, 1, 0, "开始下载")
        val nodes = DownloadNode.values()
        val node = nodes.getOrElse(task.nodeIndex) { nodes[3] }
        val downloadUrl = RomApi.getDownloadUrl(task.fileName, task.version, node)
        val targetFile = File(JavaDownloader.defaultSaveDir(), task.fileName)

        val result = JavaDownloader.download(
            this,
            downloadUrl,
            targetFile,
            onProgress = { pct ->
                task.progress = pct
                if (task.notifActive) updateNotif(task)
                broadcastTask(task, 1, pct, "$pct%")
            },
            isCancelled = { task.cancelled.get() },
            isPaused = { task.paused.get() },
            onLog = { log -> broadcastLog(task, log) },
            onSizeInfo = { total, downloaded ->
                task.totalSize = total
                task.downloadedSize = downloaded
                broadcastTask(task, 1, task.progress, "${task.progress}%")
            },
        )

        when {
            result.success -> finishTask(task, true, "下载完成", result.file?.absolutePath ?: "")
            task.cancelled.get() -> finishTask(task, false, "已取消", "")
            else -> finishTask(task, false, "下载失败: ${result.message}", "")
        }
    }

    /**
     * 解析保存文件：优先 /sdcard/Downloads（实测可写），不可写时回退应用私有目录。
     * 与 JavaDownloader 的回退策略一致，保证无「所有文件访问权限」时 aria2c 也能直接落盘
     */
    private fun resolveSaveFile(fileName: String): File {
        val dir = JavaDownloader.defaultSaveDir()
        val writable = runCatching {
            if (!dir.exists()) dir.mkdirs()
            dir.isDirectory && dir.canWrite() && run {
                val t = File(dir, ".dl_write_test")
                t.createNewFile(); t.delete(); true
            }
        }.getOrDefault(false)
        if (writable) return File(dir, fileName)
        val fb = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        return File(fb, fileName)
    }

    /** 自定义直链：内置 aria2c 引擎。暂停=杀进程保留断点，继续=断点续传重启进程 */
    private fun runAria2(task: TaskCtx) {
        broadcastTask(task, 1, task.progress, if (task.progress > 0) "断点续传中" else "开始下载")
        val targetFile = resolveSaveFile(task.fileName)
        if (targetFile.parentFile?.absolutePath != JavaDownloader.defaultSaveDir().absolutePath) {
            broadcastLog(task, "⚠️ /sdcard/Downloads 不可写，本次保存到：${targetFile.parent}")
        }

        val result = Aria2c.download(
            this,
            task.url,
            targetFile,
            onProgress = { pct ->
                task.progress = pct
                if (task.notifActive) updateNotif(task)
                broadcastTask(task, 1, pct, "$pct%")
            },
            isCancelled = { task.cancelled.get() || task.paused.get() },
            onLog = { log -> broadcastLog(task, log) },
        )

        when {
            task.cancelled.get() -> finishTask(task, false, "已取消", "")
            result.success -> finishTask(task, true, "下载完成", result.file?.absolutePath ?: "")
            result.message == "已取消" -> {
                // 进程被主动终止（暂停或继续竞态）：
                // paused 仍为 true → 真暂停：广播暂停态，断点文件保留；
                // paused 已被置 false → 继续指令与杀进程竞态，立即断点续传重启
                if (task.paused.get()) {
                    broadcastTask(task, 5, task.progress, "已暂停")
                    if (task.notifActive) updateNotif(task)
                    promoteForeground()
                } else {
                    launch(task, fromSelf = true)
                }
            }
            else -> {
                // aria2c 全灭（引擎缺失/服务器怪异响应）：降级 JavaDownloader HTTP 引擎兜底
                broadcastLog(task, "aria2c 失败：${result.message.take(80)}，切换 HTTP 引擎重试")
                val httpResult = JavaDownloader.download(
                    this,
                    task.url,
                    targetFile,
                    onProgress = { pct ->
                        task.progress = pct
                        if (task.notifActive) updateNotif(task)
                        broadcastTask(task, 1, pct, "$pct%")
                    },
                    isCancelled = { task.cancelled.get() },
                    isPaused = { task.paused.get() },
                    onLog = { log -> broadcastLog(task, log) },
                )
                when {
                    task.cancelled.get() -> finishTask(task, false, "已取消", "")
                    httpResult.success -> finishTask(task, true, "下载完成", httpResult.file?.absolutePath ?: "")
                    // HTTP 兜底期间被暂停：线程即将退出，交由 resumeTask 重启（走 aria2c 断点续传）
                    task.paused.get() -> {
                        broadcastTask(task, 5, task.progress, "已暂停")
                        if (task.notifActive) updateNotif(task)
                        promoteForeground()
                    }
                    else -> finishTask(task, false, "下载失败: ${httpResult.message}", "")
                }
            }
        }
    }

    // ==================== 控制指令 ====================

    private fun pauseTask(task: TaskCtx) {
        if (task.done) return
        task.paused.set(true)
        broadcastTask(task, 5, task.progress, "已暂停")
        if (task.notifActive) updateNotif(task)
    }

    private fun resumeTask(task: TaskCtx) {
        if (task.done) return
        task.paused.set(false)
        broadcastTask(task, 1, task.progress, "继续下载")
        if (task.notifActive) updateNotif(task)
        // aria2 路径：暂停时线程已随进程退出，这里断点续传重启；
        // JavaDownloader 路径：原地解冻；若线程意外死亡同样重启
        task.thread?.let { if (it.isAlive) return }
        launch(task)
    }

    private fun cancelTask(task: TaskCtx) {
        task.cancelled.set(true)
        task.paused.set(false)
        task.done = true
        // 立即撤下通知并拦住在途回调 re-post
        task.notifActive = false
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(task.notifId)
        broadcastTask(task, 3, 0, "已取消")
        broadcastDone(task, false, "已取消", "")
        promoteForeground()
    }

    /** 终态收尾：更新通知、广播完成、前台交接、空闲自停（已被取消的任务直接跳过，避免双重广播） */
    private fun finishTask(task: TaskCtx, success: Boolean, message: String, savedPath: String) {
        if (task.done) return
        task.done = true
        task.notifActive = false
        val nm = getSystemService(NotificationManager::class.java)
        if (success) {
            task.progress = 100
            if (task.totalSize > 0) task.downloadedSize = task.totalSize
            // 完成通知短暂展示 3 秒后撤下
            nm.notify(task.notifId, buildNotif(task).apply {
                flags = flags and Notification.FLAG_ONGOING_EVENT.inv()
            })
            broadcastTask(task, 2, 100, "下载完成")
            broadcastDone(task, true, message, savedPath)
            Thread {
                Thread.sleep(3000)
                nm.cancel(task.notifId)
                promoteForeground()
            }.start()
        } else {
            nm.cancel(task.notifId)
            broadcastTask(task, 4, task.progress, message)
            broadcastDone(task, false, message, "")
            promoteForeground()
        }
    }

    // ==================== 前台通知管理 ====================

    /** 前台通知交接：取第一个在途任务的通知为前台，其余任务通知补充 post；无在途则停服务 */
    private fun promoteForeground() {
        val active = tasks.values.filter { it.notifActive && !it.done }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        startForeground(active.first().notifId, buildNotif(active.first()))
        val nm = getSystemService(NotificationManager::class.java)
        active.drop(1).forEach { nm.notify(it.notifId, buildNotif(it)) }
    }

    /** 新任务启动时，为其余在途任务补发通知（各自独立小窗口） */
    private fun postOtherNotifs(exclude: TaskCtx) {
        val nm = getSystemService(NotificationManager::class.java)
        tasks.values
            .filter { it !== exclude && it.notifActive && !it.done }
            .forEach { nm.notify(it.notifId, buildNotif(it)) }
    }

    private fun updateNotif(task: TaskCtx) {
        if (!task.notifActive) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(task.notifId, buildNotif(task))
    }

    // ==================== 广播 ====================

    private fun broadcastTask(task: TaskCtx, state: Int, progress: Int, status: String) {
        // 暂停已受理（未终态）时丢弃在途的「下载中」进度广播：
        // 进程真正被杀前回调仍在触发，这些广播会把界面/通知翻回下载中，造成"暂停没生效要点两次"的错觉
        if (state == 1 && !task.done && task.paused.get()) return
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_SPEED, "")
            putExtra(EXTRA_STATUS_TEXT, status)
            putExtra(EXTRA_FILE_NAME, task.fileName)
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_DEVICE, task.deviceName)
            putExtra(EXTRA_TOTAL_SIZE, task.totalSize)
            putExtra(EXTRA_DOWNLOADED_SIZE, task.downloadedSize)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastLog(task: TaskCtx, log: String) {
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_LOG, log)
            putExtra(EXTRA_FILE_NAME, task.fileName)
            putExtra(EXTRA_TASK_ID, task.id)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDone(task: TaskCtx, success: Boolean, message: String, savedPath: String) {
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_DONE, true)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_SAVED_PATH, savedPath)
            putExtra(EXTRA_FILE_NAME, task.fileName)
            putExtra(EXTRA_TASK_ID, task.id)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ==================== 通知 ====================

    private fun actionIntent(task: TaskCtx, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            task.notifId * 10 + code,
            Intent(action).apply {
                setPackage(packageName)
                putExtra(EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "固件与文件下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示下载任务进度（支持多任务并行）"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotif(task: TaskCtx): Notification {
        val paused = task.paused.get() && !task.done
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("${task.label} · ${task.fileName.takeLast(38)}")
            .setContentText(if (paused) "已暂停 · ${task.progress}%" else "${task.progress}%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            // 每任务独立小窗口：下载中 → [暂停][取消]；已暂停 → [继续][取消]
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "继续" else "暂停",
                actionIntent(task, if (paused) ACTION_RESUME else ACTION_PAUSE, if (paused) 2 else 1),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                actionIntent(task, ACTION_CANCEL, 0),
            )

        if (task.progress in 1..99) {
            builder.setProgress(100, task.progress, false)
        } else if (task.progress >= 100 && task.done) {
            builder.setProgress(0, 0, false)
            builder.setContentText("下载完成")
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
        } else {
            builder.setProgress(100, 0, false)
        }

        return builder.build()
    }

    override fun onDestroy() {
        actionReceiver?.let { unregisterReceiver(it) }
        actionReceiver = null
        super.onDestroy()
    }
}
