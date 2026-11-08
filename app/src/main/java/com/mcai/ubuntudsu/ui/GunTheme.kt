package com.mcai.ubuntudsu.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

/**
 * v2.0.30：Gunyah 页面族主题 = APP 全局 [Ui] 的转发层，不再自带独立色板。
 *
 * 历史问题：这里曾维护「夜间暗绿玻璃 / 日间蓝灰」两套自有色板，还给根布局铺不透明底，
 * 导致虚拟机 tab 在 APP 里像另一套皮肤（白底灰蓝卡片 vs 全局液态渐变 + 玻璃拟态）。
 *
 * 现在：所有颜色、卡片、按钮一律从 [Ui] 取，日夜判定也用 [Ui.isDark]，
 * 与首页 / 多功能 / DSU 完全同步；内嵌 tab 页保持透明，直接透出 MainActivity 的液态背景。
 *
 * 兼容：公开 API 的字段名、方法名、参数、返回类型全部保持不变，因此 9 个 VM 页面里
 * 277 处 GunTheme.dp、60 处 GunTheme.TEXT 等调用点一字未改。每个页面 onCreate 首行
 * （或 VmPage.build 首行）仍需调用 [applyTheme] 装载当前主题色板。
 */
object GunTheme {

    // 默认取 APP 日间色板作兜底；applyTheme 后按当前日夜模式覆盖
    var isNight = false
    var BG = 0xFFE0E5EC.toInt(); var BG2 = 0xFFDCE3EE.toInt()
    var CARD = 0xFFDCE3EE.toInt(); var CARD_ALT = 0xFFDCE3EE.toInt()
    var CARD_TOP = 0xD9CDD9E8.toInt(); var CARD_BOTTOM = 0xD0BAC9DD.toInt()
    var ACCENT = 0xFF1F5FA8.toInt(); var ACCENT_SOFT = 0xFF2E5FB8.toInt()
    var DANGER = 0xFFC62828.toInt(); var WARN = 0xFFB57623.toInt()
    var TEXT = 0xFF141B26.toInt(); var TEXT2 = 0xFF3B4A5E.toInt(); var MUTED = 0xB23B4A5E.toInt()
    var BORDER = 0xFFC4D2E6.toInt(); var CHIP = 0xFFDCE3EE.toInt()
    var ON_ACCENT = Color.WHITE; var ON_WARN = 0xFF201508.toInt()

    /** 按 APP 全局主题装载色板。每个 Gunyah Activity onCreate 首行 / VmPage.build 首行调用。 */
    fun applyTheme(ctx: Context) {
        isNight = Ui.isDark(ctx)
        TEXT = Ui.primaryText(ctx)
        TEXT2 = Ui.secondaryText(ctx)
        MUTED = withAlpha(Ui.secondaryText(ctx), 0.70f)
        // 卡片上可读的强调色（夜间亮青蓝 / 日间深蓝），与全局弹框强调色一致
        ACCENT = Ui.dialogAction(ctx)
        ACCENT_SOFT = Ui.buttonPrimary(ctx)
        DANGER = Ui.buttonDanger(ctx)
        WARN = Ui.buttonWarning(ctx)
        BG = Ui.background(ctx)
        BG2 = Ui.surfaceMuted(ctx)
        CARD = Ui.surfaceMuted(ctx)
        CARD_ALT = Ui.dsuEntry(ctx)
        BORDER = Ui.border(ctx)
        CHIP = Ui.surfaceMuted(ctx)
        ON_ACCENT = Color.WHITE
        ON_WARN = 0xFF201508.toInt()
        // 半透明玻璃填充：与 Ui.neuCard 完全同款，让背后 APP 液态背景透出来
        if (isNight) {
            CARD_TOP = 0xD9161D2B.toInt()
            CARD_BOTTOM = 0xD00D131B.toInt()
        } else {
            CARD_TOP = 0xD9CDD9E8.toInt()
            CARD_BOTTOM = 0xD0BAC9DD.toInt()
        }
    }

    private fun withAlpha(color: Int, factor: Float): Int = Color.argb(
        (Color.alpha(color) * factor).toInt().coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color),
    )

    /** 页面底：APP 全局拟态渐变（夜深 / 日浅）。仅独立 Activity 铺；内嵌 tab 页透明。 */
    fun background(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(BG2, BG),
    )

    /** 玻璃卡片：与 Ui.neuCard 同款半透明填充 + 亮描边，圆角对齐全局。 */
    fun card(radiusDp: Float = 18f, density: Float = 1f): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(CARD_TOP, CARD_BOTTOM),
        ).apply {
            cornerRadius = radiusDp * density
            setStroke((1 * density).toInt(), if (isNight) 0x33FFFFFF else 0x66FFFFFF)
        }

    /** 内嵌小块（环境网格 / 盘位行底）：全局凹陷槽色。 */
    fun cardAlt(radiusDp: Float = 12f, density: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(CARD_ALT)
            setStroke((1 * density).toInt(), if (isNight) 0x26000000 else 0x2AA3B1C6)
        }

    /** 通用药丸按钮 */
    fun chip(density: Float = 1f, accent: Int = CHIP): RippleDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(accent)
        }
        return RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF), bg, ColorDrawable(Color.WHITE),
        )
    }

    /** 全圆角徽章 */
    fun pill(density: Float = 1f, accent: Int = CHIP): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 100f * density
            setColor(accent)
        }

    /** 主按钮：与全局 Ui.chipSelected 同款亮蓝渐变（夜间也醒目），白字。 */
    fun primaryButton(density: Float = 1f): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF4A90E2.toInt(), 0xFF2E5FB8.toInt()),
        ).apply { cornerRadius = 15f * density }

    /** 底部/侧边按钮的纯色块 */
    fun iconTile(density: Float = 1f, fill: Int = CHIP, radiusDp: Float = 12f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fill)
        }

    /** 按压反馈 */
    fun pressAnimation(view: View) {
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.82f
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> v.alpha = 1f
            }
            false
        }
    }

    fun dp(value: Int, density: Float): Int = (value * density).toInt()
}
