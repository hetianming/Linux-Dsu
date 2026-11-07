package com.mcai.ubuntudsu.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View

/**
 * Gunyah 页面族双主题（每个 Activity onCreate 首行调 [applyTheme]）：
 * ─ 夜间：GunyahiOS 暗绿玻璃（深绿黑底 + 绿主色 #3DDC84）
 * ─ 日间：同步 APP 本身拟态主题（#E0E5EC 浅灰蓝底 + 蓝主色 #2E5FB8）
 * 日夜判定与 APP 一致（Ui.isDark → 系统 uiMode）。
 */
object GunTheme {
    // ==================== 夜间：暗绿玻璃 ====================
    private object Night {
        const val BG = 0xFF0A120E.toInt()
        const val BG2 = 0xFF0D1A13.toInt()
        const val CARD = 0xFF13241D.toInt()
        const val CARD_ALT = 0xFF0F1C16.toInt()
        const val ACCENT = 0xFF3DDC84.toInt()
        const val ACCENT_SOFT = 0xFF2FBF71.toInt()
        const val DANGER = 0xFFFF6B6B.toInt()
        const val WARN = 0xFFE0A458.toInt()
        const val TEXT = 0xFFF0F7F2.toInt()
        const val TEXT2 = 0xFFA9C4B4.toInt()
        const val MUTED = 0xFF95AFA0.toInt()
        const val BORDER = 0xFF1E3A2E.toInt()
        const val CHIP = 0xFF33473E.toInt()
        const val ON_ACCENT = 0xFF062012.toInt() // 绿底上的深字
        const val ON_WARN = 0xFF201508.toInt()
    }

    // ==================== 日间：同步 APP 拟态主题 ====================
    // 与 Ui.Day 完全对齐：浅灰蓝底 #E0E5EC + 蓝灰玻璃卡片（#B7C6DA→#A6B5C6），
    // 不再用近白卡片（纯白在日间和 APP 其它页面强烈割裂）。
    private object Day {
        const val BG = 0xFFE0E5EC.toInt()          // 与 Ui.Day.BG 一致
        const val BG2 = 0xFFE7EDF5.toInt()
        const val CARD = 0xFFBCC9DA.toInt()        // 卡片基色（渐变中间调）
        const val CARD_TOP = 0xFFC8D4E3.toInt()    // 与 Ui.Day.CARD_TOP 同系
        const val CARD_BOTTOM = 0xFFB0BFD2.toInt() // 与 Ui.Day.CARD_BOTTOM 同系
        const val CARD_ALT = 0xFFD3DBE8.toInt()    // 与 Ui.Day.TRACK 一致
        const val ACCENT = 0xFF23509F.toInt()      // Ui.Day.PRIMARY 加深一档，浅蓝卡片上文字可读
        const val ACCENT_SOFT = 0xFF3F86D8.toInt() // 与 Ui.Day.PRIMARY_TOP 同系
        const val DANGER = 0xFFC23B3B.toInt()
        const val WARN = 0xFF9A6410.toInt()
        const val TEXT = 0xFF16202C.toInt()
        const val TEXT2 = 0xFF34455B.toInt()
        const val MUTED = 0xFF46586E.toInt()
        const val BORDER = 0xFFA9B7C9.toInt()
        const val CHIP = 0xFFC7D2E1.toInt()
        const val ON_ACCENT = 0xFFFFFFFF.toInt()   // 蓝底白字
        const val ON_WARN = 0xFFFFFFFF.toInt()
    }

    // ==================== 当前色板（applyTheme 时写入） ====================
    var isNight = true
    var BG = Night.BG; var BG2 = Night.BG2
    var CARD = Night.CARD; var CARD_ALT = Night.CARD_ALT
    var CARD_TOP = Night.CARD; var CARD_BOTTOM = Night.CARD
    var ACCENT = Night.ACCENT; var ACCENT_SOFT = Night.ACCENT_SOFT
    var DANGER = Night.DANGER; var WARN = Night.WARN
    var TEXT = Night.TEXT; var TEXT2 = Night.TEXT2; var MUTED = Night.MUTED
    var BORDER = Night.BORDER; var CHIP = Night.CHIP
    var ON_ACCENT = Night.ON_ACCENT; var ON_WARN = Night.ON_WARN

    /** 按 APP 日夜模式装载色板。每个 Gunyah Activity 的 onCreate 首行调用。 */
    fun applyTheme(ctx: Context) {
        isNight = Ui.isDark(ctx)
        if (isNight) {
            BG = Night.BG; BG2 = Night.BG2
            CARD = Night.CARD; CARD_ALT = Night.CARD_ALT
            CARD_TOP = Night.CARD; CARD_BOTTOM = Night.CARD
            ACCENT = Night.ACCENT; ACCENT_SOFT = Night.ACCENT_SOFT
            DANGER = Night.DANGER; WARN = Night.WARN
            TEXT = Night.TEXT; TEXT2 = Night.TEXT2; MUTED = Night.MUTED
            BORDER = Night.BORDER; CHIP = Night.CHIP
            ON_ACCENT = Night.ON_ACCENT; ON_WARN = Night.ON_WARN
        } else {
            BG = Day.BG; BG2 = Day.BG2
            CARD = Day.CARD; CARD_ALT = Day.CARD_ALT
            CARD_TOP = Day.CARD_TOP; CARD_BOTTOM = Day.CARD_BOTTOM
            ACCENT = Day.ACCENT; ACCENT_SOFT = Day.ACCENT_SOFT
            DANGER = Day.DANGER; WARN = Day.WARN
            TEXT = Day.TEXT; TEXT2 = Day.TEXT2; MUTED = Day.MUTED
            BORDER = Day.BORDER; CHIP = Day.CHIP
            ON_ACCENT = Day.ON_ACCENT; ON_WARN = Day.ON_WARN
        }
    }

    /** 页面底：双色渐变（夜间深绿黑 / 日间浅灰蓝） */
    fun background(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(BG2, BG),
    )

    /** 玻璃卡片：圆角 + 描边（日间用 APP 同款蓝灰玻璃渐变，夜间纯色） */
    fun card(radiusDp: Float = 18f, density: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            if (isNight) {
                setColor(CARD)
            } else {
                colors = intArrayOf(CARD_TOP, CARD_BOTTOM)
                orientation = GradientDrawable.Orientation.TL_BR
            }
            setStroke((1 * density).toInt(), BORDER)
        }

    /** 内嵌小块（环境网格 / 盘位行底） */
    fun cardAlt(radiusDp: Float = 12f, density: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(CARD_ALT)
        }

    /** 通用药丸按钮 */
    fun chip(density: Float = 1f, accent: Int = CHIP): RippleDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(accent)
        }
        return RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), bg, ColorDrawable(Color.WHITE))
    }

    /** 全圆角徽章 */
    fun pill(density: Float = 1f, accent: Int = CHIP): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 100f * density
            setColor(accent)
        }

    /** 主按钮（主色渐变：夜间绿 / 日间蓝） */
    fun primaryButton(density: Float = 1f): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(ACCENT, ACCENT_SOFT),
        ).apply {
            cornerRadius = 15f * density
        }

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
