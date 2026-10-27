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
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(100)
            .setProgressIndeterminate(p == null)
        // 显式写入当前百分比：ColorOS 16 上依赖「默认继承通知 progress」不可靠，
        // 进度条会停在原地不动（真机 1.8.42 实测）
        if (p != null) runCatching { style.setProgress(p) }
        builder.setStyle(style)
        // 胶囊内文字：系统最多展示 7 个字符，超出只留图标
        builder.setShortCriticalText(if (p == null) "" else "$p%")
        builder.extras?.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        return true
    }

    // ==================== 通道选择 ====================

    // 1.8.44：设置页的「猫脸封面」开关已移除。彩色封面必须走媒体会话通道，
    // 而 ColorOS 一旦接管媒体会话通知就会渲染成完整音乐播放器控制卡
    // （上一首/下一首/投播 + 控制中心占位），本项目是工具类应用，不应出现播放器形态。
    // 因此流体云恒定走实时动态进度卡（小图标剪影 + 百分比），不再提供任何切换入口。

    /**
     * 进行中任务的通道选择（统一入口，DownloadService / DsuInstallService / build 共用）：
     * - Android 16+ 走实时动态进度卡（流体云显示图标剪影 + 百分比）；
     * - 老系统 / 用户关闭实时动态 → 走无封面媒体兜底（不写封面，避免变成播放器卡）。
     */
    fun applyOngoingStyle(
        context: Context,
        builder: Notification.Builder,
        title: String,
        text: String,
        compactActions: Int,
        percent: Int?,
    ) {
        if (!applyLiveUpdate(builder, percent)) {
            applyMediaFallback(context, builder, title, text, compactActions)
        }
    }

    /**
     * 通知进度条：所有模式都写真实百分比（系统据此渲染进度胶囊/进度条）。
     * 注意不要为了「胶囊彩色」去掉进度条 —— 1.8.39 实测那样会整条变成媒体控制卡。
     */
    fun applyProgress(context: Context, builder: Notification.Builder, percent: Int?) {
        builder.setProgress(100, (percent ?: 0).coerceIn(0, 100), percent == null)
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
     * 媒体样式通知：ColorOS / HyperOS 把「媒体会话通知」渲染为摄像头区域胶囊，
     * 这是第三方应用在老系统上唯一的上岛通道（本应用已在真机验证）。
     *
     * 「去音乐化」措施：不写封面、不写 DURATION、position 用 UNKNOWN ——
     * 时间轴与播放器控件不会出现，胶囊只呈现「APP 图标 + 标题 + 进度文本 + 操作按钮」。
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
        // 刻意不写 DURATION、position 用 UNKNOWN：1.8.39 实测写了时长后，
        // ColorOS 会把通知渲染成完整媒体控制卡（上一首/下一首/投播 + 控制中心占位），
        // 还有胶囊展开白板 bug —— 进度只通过通知文本表达。
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
        // 不写封面元数据、保持「进度」类别：一旦写成媒体/传输类别，
        // ColorOS 会把整条通知渲染成完整音乐播放器控制卡（1.8.39/1.8.40 真机实测）
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
        applyProgress(context, builder, percent)

        if (contentIntent != null) builder.setContentIntent(contentIntent)
        if (!ongoing) builder.setAutoCancel(true)

        // Android 12+：前台服务通知立即展示，避免延迟到 10 秒后才出现胶囊
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        // 进行中的任务：通道选择统一走 [applyOngoingStyle]
        if (ongoing) {
            applyOngoingStyle(context, builder, title, text, 0, percent)
        }
        return builder.build()
    }

    /** 从日志行里尽力解析百分比，供只有文本回调的任务估算进度 */
    fun percentFrom(text: String): Int? {
        val match = Regex("""(\d{1,3})\s*%""").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
    }
}
