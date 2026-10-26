package com.mcai.ubuntudsu.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.mcai.ubuntudsu.MainActivity
import com.mcai.ubuntudsu.ui.Ui

/**
 * 流体云 / 系统级灵动岛引擎。
 *
 * ColorOS / RealmeUI / HyperOS / 小米 / 华为 / 荣耀等系统的「流体云」会**自动接管**
 * 标准的「媒体会话 + 媒体样式通知」并渲染成摄像头区域的灵动胶囊
 * （与酷狗 / 网易云上岛是同一套机制：系统媒体通知 → 系统渲染为灵动岛）。
 *
 * 因此这里**不自行绘制灵动岛**，而是用平台 [MediaSession] + [Notification.MediaStyle] 发出带真实进度的
 * 媒体通知交给系统接管；自家绘制的胶囊（DynamicIsland）仅在非流体云机型作为兜底。
 * 进度通过 [PlaybackState] 的 position 表达（duration 固定常量，position = percent/100*duration），
 * 系统据此在胶囊上画出进度环 / 进度条；通知栏折叠态同时带真实百分比，双保险。
 */
object FluidCloud {

    const val CHANNEL = "fluid_cloud_channel"
    const val NOTE_ID = 9001
    private const val DURATION = 100_000L

    enum class Kind { INSTALL, DOWNLOAD, DNA }

    @Volatile
    private var ctx: Context? = null
    @Volatile
    private var session: MediaSession? = null
    @Volatile
    private var focusKind: Kind? = null
    @Volatile
    private var active = false
    private val lock = Any()

    private var lastKind = Kind.DOWNLOAD
    private var lastTitle = ""
    private var lastName = ""
    private var lastSub = ""
    private var lastPercent = 0
    private var lastAction: PendingIntent? = null

    @Volatile
    private var lastNotification: Notification? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        ensureChannel()
    }

    private fun nm(): NotificationManager =
        ctx!!.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL, "流体云进度", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "流体云 / 灵动岛进度（系统自动接管为摄像头区域胶囊）"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm().createNotificationChannel(ch)
    }

    private fun ensureSession(): MediaSession {
        if (session == null) {
            session = MediaSession(ctx!!, "UbuntuDSU-FluidCloud").apply {
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setCallback(object : MediaSession.Callback() {})
                isActive = true
            }
        }
        return session!!
    }

    /** 申请成为流体云焦点。高优先级任务（安装 > 下载 > DNA）进行中时，低优先级返回 false 不打断 */
    fun request(
        kind: Kind,
        title: String,
        fileName: String,
        status: String,
        percent: Int,
        action: PendingIntent?,
    ): Boolean = synchronized(lock) {
        if (focusKind != null && priority(focusKind!!) > priority(kind)) return false
        focusKind = kind
        lastKind = kind
        lastTitle = title
        lastName = fileName
        lastSub = status
        lastPercent = percent
        lastAction = action
        active = true
        render(true)
        true
    }

    /** 当前焦点任务的进度刷新（非焦点任务忽略） */
    fun update(kind: Kind, percent: Int, status: String) = synchronized(lock) {
        if (focusKind != kind) return
        lastPercent = percent
        lastSub = status
        render(true)
    }

    /** 当前焦点任务收尾（成功 / 失败 / 取消）。非焦点任务忽略 */
    fun finish(kind: Kind, success: Boolean, finalText: String) = synchronized(lock) {
        if (focusKind != kind) return
        lastSub = finalText
        if (success) lastPercent = 100
        active = false
        render(false)
        focusKind = null
    }

    /** 彻底停止：释放媒体会话与通知（服务销毁 / 全部任务结束） */
    fun clear() = synchronized(lock) {
        focusKind = null
        active = false
        runCatching { nm().cancel(NOTE_ID) }
        runCatching { session?.release() }
        session = null
        lastNotification = null
    }

    /** 当前构建好的通知（供前台服务 startForeground 使用） */
    fun notification(): Notification? = lastNotification

    private fun priority(k: Kind) = when (k) {
        Kind.INSTALL -> 3
        Kind.DOWNLOAD -> 2
        Kind.DNA -> 1
    }

    private fun render(ongoing: Boolean) {
        val s = ensureSession()
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, lastTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, lastName)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, lastSub)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, DURATION)
                .build()
        )
        val state = if (ongoing) PlaybackState.STATE_PLAYING
        else PlaybackState.STATE_STOPPED
        val pos = (lastPercent.coerceIn(0, 100) * DURATION / 100)
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, pos, 1f, SystemClock.elapsedRealtime())
                .setActions(
                    PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE
                )
                .build()
        )
        lastNotification = build(ongoing)
    }

    private fun build(ongoing: Boolean): Notification {
        val accent = when (lastKind) {
            Kind.INSTALL -> Color.parseColor("#FFB74D")
            Kind.DOWNLOAD -> Color.parseColor("#4FC3F7")
            Kind.DNA -> Color.parseColor("#5AD4A0")
        }
        val content = PendingIntent.getActivity(
            ctx!!, 0,
            Intent(ctx!!, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val style = Notification.MediaStyle().apply { setMediaSession(session!!.sessionToken) }
        val b = Notification.Builder(ctx!!, CHANNEL)
            .setSmallIcon(
                if (ongoing) android.R.drawable.stat_sys_download
                else android.R.drawable.stat_sys_download_done
            )
            .setContentTitle(lastTitle)
            .setContentText("$lastSub · $lastPercent%")
            .setSubText(lastName)
            .setStyle(style)
            .setContentIntent(content)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, lastPercent, false)
            .setColor(accent)
        lastAction?.let {
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", it)
        }
        if (!ongoing) b.setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }
}
