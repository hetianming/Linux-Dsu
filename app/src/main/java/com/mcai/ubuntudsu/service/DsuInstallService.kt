package com.mcai.ubuntudsu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import com.mcai.ubuntudsu.IPrivilegedService
import com.mcai.ubuntudsu.MainActivity
import com.mcai.ubuntudsu.PrivilegedRootService
import com.mcai.ubuntudsu.core.DsuInstallState
import com.mcai.ubuntudsu.core.DsuManager
import com.mcai.ubuntudsu.core.RootShell
import com.topjohnwu.superuser.ipc.RootService
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * DSU / GSI 前台安装服务。
 *
 * 安装不再绑定在 DsuPage 的 Activity 上：离开 APP、切到桌面、页面销毁都不会中断，
 * 进度同时写入系统通知与 [DsuInstallState]，返回 APP 后可继续看到实时进度。
 */
class DsuInstallService : Service() {

    companion object {
        const val EXTRA_ZIP = "zip_path"
        const val EXTRA_USERDATA_GB = "userdata_gb"
        const val EXTRA_CLEAR_CACHE = "clear_cache"
        const val ACTION_CANCEL = "com.mcai.ubuntudsu.action.CANCEL_DSU_INSTALL"

        private const val CHANNEL_ID = "dsu_install"
        private const val NOTE_ID = 2001
    }

    private val busy = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var binder: IPrivilegedService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = IPrivilegedService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    private var fileName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()

        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            runCatching { binder?.abort() }
            return START_NOT_STICKY
        }

        val zipPath = intent?.getStringExtra(EXTRA_ZIP)
        if (zipPath.isNullOrBlank() || !busy.compareAndSet(false, true)) {
            // 没有有效参数（或已有任务在跑）：直接收尾，避免空服务常驻
            if (!busy.get()) stopSelf()
            return START_NOT_STICKY
        }

        val userdataGB = intent.getIntExtra(EXTRA_USERDATA_GB, 16)
        val clearCache = intent.getBooleanExtra(EXTRA_CLEAR_CACHE, false)
        fileName = File(zipPath).name
        cancelled.set(false)

        startForeground(NOTE_ID, buildNotification(0, "准备安装…", done = false))
        DsuInstallState.publish(DsuInstallState.Phase.RUNNING, 0, "准备安装…", fileName)

        RootService.bind(Intent(this, PrivilegedRootService::class.java), connection)
        Thread { runInstall(zipPath, userdataGB, clearCache) }.start()
        return START_NOT_STICKY
    }

    // ==================== 安装执行 ====================

    private fun runInstall(zipPath: String, userdataGB: Int, clearCache: Boolean) {
        var started = false
        try {
            if (clearCache) {
                progress(0, "清理旧缓存…")
                val cleared = DsuManager.clearInstallCache { android.util.Log.i("DsuInstall", it) }
                check(cleared.success) { "清理旧缓存失败：${cleared.stderr}" }
            }

            val svc = awaitBinder(20_000) ?: error("ROOT DSU 安装器连接超时，请确认已授予 ROOT")
            val source = File(zipPath)
            val input: InputStream = runCatching { source.takeIf { it.canRead() }?.inputStream() }.getOrNull()
                ?: RootShell.openStream(zipPath)

            input.use { stream ->
                ZipInputStream(BufferedInputStream(stream, 1024 * 1024)).use { zip ->
                    if (!svc.isAvailable()) error("系统 dynamic_system 服务不可用")
                    if (!svc.startInstallation("dsu")) error("dynamic_system 拒绝开始安装")
                    started = true

                    val partitions = HashSet<String>()
                    var imageCount = 0
                    var entry = zip.nextEntry
                    while (entry != null) {
                        checkCancelled()
                        if (entry.isDirectory || !entry.name.substringAfterLast('/').endsWith(".img", true)) {
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        val name = entry.name.substringAfterLast('/').removeSuffix(".img").lowercase()
                        if (!name.matches(Regex("[a-z0-9_-]+")) || !partitions.add(name)) error("无效或重复分区：$name")
                        val size = entry.size
                        if (size <= 0) error("镜像为空：$name")
                        val partitionSize =
                            if (name == "userdata") maxOf(size, userdataGB.toLong() * 1024 * 1024 * 1024) else size
                        val result = svc.createPartition(name, partitionSize, name != "userdata")
                        if (result != 0) error("创建分区失败：$name ($result)")
                        streamImage(zip, size, name, svc)
                        if (!svc.closePartition()) error("关闭分区失败：$name")
                        imageCount++
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    if (imageCount == 0) error("ZIP 中没有可用的 img 镜像")
                    if (!partitions.contains("userdata")) {
                        if (svc.createPartition("userdata", userdataGB.toLong() * 1024 * 1024 * 1024, false) != 0) {
                            error("创建 userdata 失败")
                        }
                        if (!svc.closePartition()) error("关闭 userdata 失败")
                    }
                    if (!svc.finishInstallation()) error("完成 DSU 安装失败")
                    if (!svc.setEnable(false, false)) error("停用 DSU 自动启动失败")
                }
            }

            DsuInstallState.publish(DsuInstallState.Phase.SUCCESS, 100, "安装完成", fileName)
            finish(100, "安装完成 · 可重启到 DSU", success = true)
        } catch (t: Throwable) {
            if (started) runCatching { binder?.abort() }
            val msg = t.message ?: "未知错误"
            if (cancelled.get()) {
                DsuInstallState.publish(DsuInstallState.Phase.CANCELLED, 0, "已取消", fileName)
                finish(0, "安装已取消", success = false)
            } else {
                DsuInstallState.publish(DsuInstallState.Phase.FAILED, DsuInstallState.snapshot.percent, msg, fileName)
                finish(DsuInstallState.snapshot.percent, "安装失败：$msg", success = false)
            }
        }
    }

    private fun awaitBinder(timeoutMs: Long): IPrivilegedService? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            binder?.let { return it }
            if (cancelled.get()) return null
            Thread.sleep(120)
        }
        return binder
    }

    private fun checkCancelled() {
        if (cancelled.get()) error("已取消")
    }

    /** 4MB 共享内存整块提交，减少 Binder 往返；进度按 256MB 粒度上报 */
    private fun streamImage(input: InputStream, size: Long, name: String, svc: IPrivilegedService) {
        val bufferSize = 4 * 1024 * 1024
        SharedMemory.create("ubuntudsu-$name", bufferSize).use { memory ->
            sharedMemoryFd(memory).use { fd ->
                if (!svc.setAshmem(fd, bufferSize.toLong())) error("共享内存初始化失败：$name")
                val mapped = memory.mapReadWrite()
                try {
                    val buffer = ByteArray(bufferSize)
                    var written = 0L
                    var lastReport = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        mapped.position(0)
                        mapped.put(buffer, 0, count)
                        if (!svc.submitFromAshmem(count.toLong())) error("写入失败：$name")
                        written += count
                        if (written - lastReport >= 256L * 1024 * 1024 || written >= size) {
                            lastReport = written
                            val imageProgress = if (size > 0) (written * 80L / size).toInt() else 0
                            val percent = (10 + imageProgress).coerceIn(10, 90)
                            val gbDone = written / 1073741824.0
                            val totalGb = if (size > 0) " / " + String.format("%.1f", size / 1073741824.0) + "GB" else ""
                            progress(percent, "写入 $name  " + String.format("%.1f", gbDone) + "GB" + totalGb)
                        }
                    }
                } finally {
                    SharedMemory.unmap(mapped)
                }
            }
        }
    }

    private fun sharedMemoryFd(memory: SharedMemory): ParcelFileDescriptor =
        runCatching {
            SharedMemory::class.java.getMethod("getFdDup").invoke(memory) as ParcelFileDescriptor
        }.getOrElse {
            ParcelFileDescriptor.fromFd(
                SharedMemory::class.java.getDeclaredMethod("getFd").apply { isAccessible = true }
                    .invoke(memory) as Int,
            )
        }

    private fun progress(percent: Int, message: String) {
        DsuInstallState.publish(DsuInstallState.Phase.RUNNING, percent, message, fileName)
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTE_ID, buildNotification(percent, message, done = false))
        }
    }

    /** 收尾：保留完成通知（点击可回到 APP），释放前台状态 */
    private fun finish(percent: Int, text: String, success: Boolean) {
        busy.set(false)
        runCatching { RootService.unbind(connection) }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTE_ID, buildNotification(percent, text, done = true, success = success))
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        stopSelf()
    }

    // ==================== 通知 ====================

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DSU 安装", NotificationManager.IMPORTANCE_LOW).apply {
                description = "GSI 安装进度（离开应用后仍在后台继续）"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(
        percent: Int,
        text: String,
        done: Boolean,
        success: Boolean = true,
    ): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (done) (if (success) "DSU 安装完成" else "DSU 安装结束") else "DSU 安装进行中")
            .setContentText(if (done) text else "$text · $fileName  $percent%")
            .setSmallIcon(
                if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download,
            )
            .setContentIntent(content)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOngoing(!done)
            .setOnlyAlertOnce(true)
        if (done) {
            builder.setAutoCancel(true)
        } else {
            builder.setProgress(100, percent, false)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}
