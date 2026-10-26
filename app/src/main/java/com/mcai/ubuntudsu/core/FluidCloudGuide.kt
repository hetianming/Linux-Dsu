package com.mcai.ubuntudsu.core

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * 各系统「灵动岛 / 流体云 / 实况窗」开关引导。
 *
 * 上岛机制（APP 侧已全系统通用）：
 * - Android 16+：Live Updates 进度通知（ProgressStyle + POST_PROMOTED_NOTIFICATIONS）；
 * - 老系统：媒体会话通知兜底（各厂商灵动岛渲染媒体胶囊的通用机制）。
 *
 * 但各家系统开关的名称与入口不同，且默认可能关闭：
 * - ColorOS（OPPO/一加/真我）：流体云显示实时活动
 * - 澎湃OS（小米/红米）：灵动岛 / 超级岛（实时活动 + 应用授权列表）
 * - OriginOS（vivo/iQOO）：原子通知 → 实况窗
 * - Flyme（魅族）：灵动环
 * - HarmonyOS（华为）：实况窗
 * - MagicOS（荣耀）：灵动胶囊 / 实况窗
 * - One UI（三星）：Now Bar
 *
 * 首次启动检测：能 API 检测的（Android 16+）按状态弹；其余国产厂商首次启动提示一次。
 */
object FluidCloudGuide {

    /** 厂商灵动岛信息：系统名 / 开关名 / 设置路径提示 */
    data class Vendor(val system: String, val feature: String, val path: String)

    private const val PREFS = "fluid_cloud_guide"
    private const val KEY_ASKED = "asked_v1"

    /** 识别当前设备的灵动岛实现（null = 类原生等无此能力，不引导） */
    fun vendor(context: Context): Vendor? {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        val b = (Build.BRAND ?: "").lowercase()
        fun has(vararg keys: String) = keys.any { m.contains(it) || b.contains(it) }
        return when {
            has("oppo", "oneplus", "realme") -> Vendor(
                "ColorOS", "流体云显示实时活动",
                "设置 → 通知与状态栏 → 流体云（或本应用通知管理 → 流体云显示实时活动）",
            )
            has("xiaomi", "redmi", "poco") -> Vendor(
                "澎湃OS", "灵动岛 / 超级岛",
                "设置 → 通知与控制中心 → 灵动岛，并在「实时活动」应用列表中允许本应用",
            )
            has("vivo", "iqoo") -> Vendor(
                "OriginOS", "实况窗",
                "设置 → 通知与状态栏 → 原子通知 → 实况窗",
            )
            has("meizu") -> Vendor(
                "Flyme", "灵动环",
                "设置 → 通知与状态栏 → 灵动环 / Aicy 灵动环",
            )
            has("huawei") -> Vendor(
                "HarmonyOS", "实况窗",
                "设置 → 通知和状态栏 → 实况窗",
            )
            has("honor") -> Vendor(
                "MagicOS", "灵动胶囊 / 实况窗",
                "设置 → 通知和状态栏 → 实况窗",
            )
            has("samsung") -> Vendor(
                "One UI", "Now Bar",
                "设置 → 通知 → 高级设置 → Now Bar",
            )
            else -> null
        }
    }

    private fun asked(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    /** 是否需要引导 */
    fun shouldGuide(context: Context): Boolean {
        if (asked(context)) return false
        if (Build.VERSION.SDK_INT >= 36) {
            // 已接入 Live Updates 的系统：用 API 精确检测开关状态
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return false
            val enabled =
                runCatching { manager.canPostPromotedNotifications() }.getOrDefault(true)
            return !enabled
        }
        // 老系统无法 API 检测：国产厂商首次启动提示一次（媒体胶囊兜底也可能被开关拦截）
        return vendor(context) != null
    }

    /** 标记已引导（无论用户是否去开，均不再重复弹窗） */
    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** 跳转到本应用的通知设置页（多数系统该页含灵动岛/流体云开关；优先尝试实时动态直达页） */
    fun openSettings(activity: Activity) {
        // Android 16 QPR1+ 的直达 action（部分 ROM 支持），不支持则回退到应用通知设置
        val direct = Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS")
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        if (direct.resolveActivity(activity.packageManager) != null) {
            runCatching {
                activity.startActivity(direct)
                return
            }
        }
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
            )
        }
    }
}
