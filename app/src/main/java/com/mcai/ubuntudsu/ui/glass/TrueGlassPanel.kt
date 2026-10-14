package com.mcai.ubuntudsu.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import com.mcai.ubuntudsu.ui.Ui

/**
 * 多层真液态玻璃面板（True Glass Panel）。
 *
 * 移植自参考项目 TrueGlass.TrueGlassPanel，并按双主题各调一组配色：
 *  ① 斜向半透明渐变主体（玻璃厚度不均，左上最透亮）
 *  ② 玻璃内折射色斑（背景的青/紫透过玻璃可见——液态玻璃的灵魂）
 *  ③ 底部色彩渗透（背景色"折射"进玻璃）
 *  ④ 斜向镜面反光带（表面对环境光的镜面反射）
 *  ⑤ 菲涅尔渐变描边（顶亮→侧收→底部回亮）
 *  ⑥ 顶部内侧反光弧 + 内侧光泽带 + 左上角光源亮斑
 *
 * 继承 [LinearLayout]，可直接 addView 内容。通过 [applyNeuShadow] 之外的自绘
 * 完成玻璃质感，无需 background。
 */
class TrueGlassPanel @JvmOverloads constructor(
    context: Context,
    private val radiusDp: Float = 26f,
    private val darkOverride: Boolean = Ui.isDark(context),
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    // 夜间：深玻璃（青蓝渗透 + 弱高光）；日间：浅玻璃（强白高光 + 冷蓝底）
    private val dark = darkOverride

    /** 玻璃主体三色（左上透亮 → 收敛 → 底部回白） */
    private val bodyColors: IntArray = if (dark) {
        intArrayOf(0x00000000, 0x00000000, 0x00000000)
    } else {
        intArrayOf(0xC4FFFFFF.toInt(), 0x84FFFFFF.toInt(), 0xA8FFFFFF.toInt())
    }

    private val refractionColors: IntArray = if (dark) {
        intArrayOf(0x00000000, 0x00000000)
    } else {
        intArrayOf(0x2C8FB8E8.toInt(), 0x28B9A8EC.toInt())
    }

    private val bottomTint: Int = if (dark) 0x00000000 else 0x2E9FBFD8.toInt()

    private val rimStroke: IntArray = if (dark) {
        intArrayOf(0xE6FFFFFF.toInt(), 0x59FFFFFF.toInt(), 0x2EFFFFFF.toInt(), 0x8CFFFFFF.toInt())
    } else {
        intArrayOf(0xE6FFFFFF.toInt(), 0x59FFFFFF.toInt(), 0x2EFFFFFF.toInt(), 0x8CFFFFFF.toInt())
    }

    private val sheenColor: Int = if (dark) 0x90FFFFFF.toInt() else 0xB8FFFFFF.toInt()

    private val spotIntensity: Int = if (dark) 0x44FFFFFF.toInt() else 0x54FFFFFF.toInt()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        rim.style = Paint.Style.STROKE
        inner.style = Paint.Style.STROKE
        sheen.style = Paint.Style.STROKE
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View?, outline: Outline) {
                view?.let {
                    outline.setRoundRect(0, 0, it.width, it.height, radiusDp * d)
                }
            }
        }
        setElevation(if (dark) 0f else 6 * d)
        @Suppress("DEPRECATION")
        setOutlineSpotShadowColor(if (dark) 0x00000000 else 0x33395273)
        @Suppress("DEPRECATION")
        setOutlineAmbientShadowColor(if (dark) 0x00000000 else 0x24395273)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = minOf(radiusDp * d, minOf(w, h) / 2f)
        val inset = 0.75f * d
        box.set(inset, inset, w - inset, h - inset)

        // ① 玻璃主体：135° 斜向渐变
        body.shader = LinearGradient(0f, 0f, w, h, bodyColors, floatArrayOf(0f, 0.38f, 0.72f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, r, r, body)

        // ①.b 玻璃内折射色斑
        spot.shader = RadialGradient(w * 0.90f, h * 0.14f, maxOf(w, h) * 0.48f, refractionColors[0], 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, r, r, spot)
        spot.shader = RadialGradient(w * 0.06f, h * 0.90f, maxOf(w, h) * 0.42f, refractionColors[1], 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, r, r, spot)
        spot.shader = null

        // ② 色彩渗透：底部折射出的背景色
        body.shader = LinearGradient(0f, h * 0.55f, 0f, h, 0x00FFFFFF, bottomTint, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, r, r, body)
        body.shader = null

        // ②.b 斜向镜面反光带
        streak.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0x00FFFFFF, if (dark) 0x22FFFFFF else 0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0.14f, 0.32f, 0.50f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(box, r, r, streak)
        streak.shader = null

        // ③ 菲涅尔渐变描边
        rim.strokeWidth = 1.2f * d
        rim.shader = LinearGradient(0f, 0f, 0f, h, rimStroke, floatArrayOf(0f, 0.30f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, r, r, rim)
        rim.shader = null

        // ⑤ 内侧光泽带：顶部 30% 高度
        inner.strokeWidth = 1f * d
        inner.shader = LinearGradient(0f, box.top, 0f, box.top + h * 0.3f, if (dark) 0x44FFFFFF else 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(
            RectF(box.left + 2f * d, box.top + 2f * d, box.right - 2f * d, box.bottom - 2f * d),
            r - 2f * d, r - 2f * d, inner,
        )
        inner.shader = null

        // ⑥ 左上角光源亮斑
        spot.shader = RadialGradient(
            box.left + w * 0.16f, box.top + h * 0.10f, minOf(w, h) * 0.55f,
            spotIntensity, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(box, r, r, spot)
        spot.shader = null

        super.onDraw(canvas)
    }
}
