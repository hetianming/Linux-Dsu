package com.mcai.ubuntudsu.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

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
            .setSmallIcon(
                if (ongoing) android.R.drawable.stat_sys_download
                else if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error,
            )
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
        return builder.build()
    }

    /** 从日志行里尽力解析百分比，供只有文本回调的任务估算进度 */
    fun percentFrom(text: String): Int? {
        val match = Regex("""(\d{1,3})\s*%""").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
    }
}
