package com.mcai.ubuntudsu.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import com.mcai.ubuntudsu.R

/**
 * 统一的进度通知构建器。
 *
 * ColorOS / HyperOS 的「流体云 / 灵动胶囊」会自动把**带确定进度的前台通知**提升为胶囊，
 * 前提是：
 *  1. 使用固定的 NotificationChannel（IMPORTANCE_LOW 静默渠道）；
 *  2. setProgress(100, percent, false) 传真实百分比，而不是不确定态；
 *  3. 任务进行中 setOngoing(true) + setOnlyAlertOnce(true)；
 *  4. 完成时切成非 ongoing、可自动消失，并给出成功/失败文案。
 *
 * 所有长任务通知（DNA 工具箱、DSU 安装、ROM 下载）都走这里，保证各机型行为一致。
 */
object ProgressNotification {

    const val CHANNEL_TOOLS = "dna_tools_progress"
    const val CHANNEL_INCREMENTAL = "dna_inc_channel"
    const val CHANNEL_INSTALL = "dsu_install"

    /**
     * Android 16 Live Updates（内部代号 Promoted Ongoing Notification）。
     * 官方常量 `Notification.EXTRA_REQUEST_PROMOTED_ONGOING` 在 API 36.1 才进 SDK，
     * 36.0 上只能按文档值写 extras，两种写法等价。
     */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    /**
     * 把「带进度的进行中通知」提升为实时动态。
     *
     * ColorOS 16 / HyperOS 3 的流体云就是渲染这类通知：状态栏/摄像头区域出现胶囊，
     * 点击展开为进度卡片；与媒体样式无关，所以不会显示成音乐播放器。
     *
     * 资格条件（官方）：ProgressStyle + ongoing + contentTitle + 非 groupSummary +
     * 非 colorized + 渠道非 IMPORTANCE_MIN + 声明 POST_PROMOTED_NOTIFICATIONS。
     *
     * @return false = 该设备不支持实时动态（Android 15 及以下 / 用户关闭），
     *         调用方应转走 [applyMediaFallback]。
     */
    fun applyLiveUpdate(builder: Notification.Builder, percent: Int?): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val p = percent?.coerceIn(0, 100)
        builder.setStyle(
            Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(100)
                .setProgressIndeterminate(p == null),
        )
        // 胶囊内文字：系统最多展示 7 个字符，超出只留图标
        builder.setShortCriticalText(if (p == null) "" else "$p%")
        builder.extras?.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        return true
    }

    // ==================== 媒体会话兜底（Android 15 及以下） ====================

    private val mediaLock = Any()
    private var mediaSession: MediaSession? = null

    private fun mediaSession(context: Context): MediaSession = synchronized(mediaLock) {
        mediaSession?.let { return it }
        MediaSession(context.applicationContext, "UbuntuDSU").also {
            it.setCallback(object : MediaSession.Callback() {})
            it.isActive = true
            mediaSession = it
        }
    }

    /**
     * 媒体样式兜底：ColorOS 15 / HyperOS 等把「媒体会话通知」渲染为摄像头区域胶囊，
     * 这是第三方应用在老系统上唯一的上岛通道（本应用已在真机验证）。
     *
     * 刻意不写 DURATION / ART 元数据、position 用 UNKNOWN：
     * 时间轴（00:01/01:40）与音符封面都不会出现，卡片呈现为
     * 「APP 图标 + 标题 + 进度文本 + 暂停/取消按钮」的进度卡。
     *
     * @param compactActions 通知上的 action 个数（紧凑行显示前几个）
     */
    fun applyMediaFallback(
        context: Context,
        builder: Notification.Builder,
        title: String,
        text: String,
        compactActions: Int,
    ) {
        val s = mediaSession(context)
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
                .build(),
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP,
                )
                .setState(
                    PlaybackState.STATE_PLAYING,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    0f,
                )
                .build(),
        )
        val style = Notification.MediaStyle().setMediaSession(s.sessionToken)
        if (compactActions > 0) {
            style.setShowActionsInCompactView(*IntArray(compactActions) { it })
        }
        builder.setStyle(style)
        // 封面用 APP 图标（不设则系统默认画音符，被误认成音乐播放器）
        runCatching {
            builder.setLargeIcon(Icon.createWithResource(context, context.applicationInfo.icon))
        }
    }

    /**
     * 系统是否真的会接管（把我们的进度通知渲染成摄像头区域胶囊）。
     * 返回 false 时（老系统 / 用户关掉了实时动态），APP 需要自己画胶囊兜底。
     */
    fun systemTakeOver(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 36) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager != null &&
                runCatching { manager.canPostPromotedNotifications() }.getOrDefault(false)
            ) {
                return true
            }
        }
        // 老系统：进行中任务会发媒体会话兜底通知，由系统媒体胶囊接管
        return true
    }

    /** 创建/更新渠道（名称即系统设置里显示的通知类别，如「DNA 工具箱进度」） */
    fun ensureChannel(context: Context, channelId: String, name: String, description: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW).apply {
            this.description = description
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建进度通知。
     * @param percent 0..100；传 null 表示不确定进度（解析阶段）
     * @param ongoing true=进行中（不可滑掉，流体云持续展示）；false=已结束（可自动消失）
     */
    fun build(
        context: Context,
        channelId: String,
        title: String,
        text: String,
        percent: Int?,
        ongoing: Boolean,
        contentIntent: PendingIntent? = null,
        success: Boolean = true,
    ): Notification {
        val builder = Notification.Builder(context, channelId)
            // 统一用 APP 桌面图标（白色剪影），流体云/灵动岛胶囊左侧显示的就是它
            .setSmallIcon(R.drawable.ic_notify_app)
            .setLargeIcon(Icon.createWithResource(context, context.applicationInfo.icon))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, (percent ?: 0).coerceIn(0, 100), percent == null)

        if (contentIntent != null) builder.setContentIntent(contentIntent)
        if (!ongoing) builder.setAutoCancel(true)

        // Android 12+：前台服务通知立即展示，避免延迟到 10 秒后才出现胶囊
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        // 进行中的任务才申请提升为实时动态；不支持时走媒体会话兜底（同样由系统上岛）
        if (ongoing) {
            if (!applyLiveUpdate(builder, percent)) {
                applyMediaFallback(context, builder, title, text, 0)
            }
        }
        return builder.build()
    }

    /** 从日志行里尽力解析百分比，供只有文本回调的任务估算进度 */
    fun percentFrom(text: String): Int? {
        val match = Regex("""(\d{1,3})\s*%""").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
    }
}
