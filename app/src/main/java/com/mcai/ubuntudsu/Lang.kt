package com.mcai.ubuntudsu

import android.content.Context
import java.util.Locale

/**
 * v2.0.38 应用内语言（跟随系统 + 设置里手动固定）。
 *
 * 设计约束（务必保留，改动前先想清楚）：
 *  1. 中文模式下 [t] 逐字返回原文，历史界面行为 100% 不变 —— 这是「不改坏」的底线。
 *  2. 英文模式下只替换 [LangMap.EN] 里**显式登记过**的界面文案；
 *     日志、shell 片段、配置 key、动态拼接串一律不登记，自动回落中文，
 *     绝不因为翻译把功能字符串改坏。
 *  3. 系统语言检测只区分中文 / 非中文：zh* → 中文，其它 → 英文。
 */
object Lang {
    const val PREF = "app_language"
    const val KEY_MODE = "mode"
    const val AUTO = "auto"
    const val ZH = "zh"
    const val EN = "en"

    @Volatile
    private var mode: String = AUTO

    /** Application 冷启动时调用；进程不重启时由 [setMode] 直接更新内存态。 */
    fun init(ctx: Context) {
        mode = runCatching {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_MODE, AUTO) ?: AUTO
        }.getOrDefault(AUTO)
    }

    fun current(): String = mode

    fun setMode(ctx: Context, m: String) {
        mode = m
        runCatching {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, m).apply()
        }
    }

    fun isEnglish(): Boolean = when (mode) {
        ZH -> false
        EN -> true
        else -> !Locale.getDefault().language.startsWith("zh", ignoreCase = true)
    }

    /** 中文原文作 key：英文且登记过才替换，否则原样返回。 */
    fun t(zh: String): String = if (isEnglish()) LangMap.EN[zh] ?: zh else zh

    // 下面几个是设置页自身的固定文案，不经过 t()，避免自引用。
    fun title(): String = if (isEnglish()) "Language" else "语言"
    fun optFollow(): String = if (isEnglish()) "Follow system" else "跟随系统"
    fun optChinese(): String = "简体中文"
    fun optEnglish(): String = if (isEnglish()) "English" else "英文"
    fun hint(): String = if (isEnglish())
        "Follows the system language by default (Chinese system → Chinese UI, otherwise English), or pin it here."
    else
        "默认跟随系统：中文系统显示中文界面，其它系统显示英文界面；也可在此固定，切换后自动重启界面生效。"
}
