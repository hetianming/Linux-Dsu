package com.mcai.ubuntudsu.ui.glass

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/**
 * 卡片级动态弥散光斑背景（Card Glow Backdrop）。
 *
 * 与全屏版 [LiquidBackdropDrawable] 同源但更克制：
 *  ① 光斑数量从 6 减到 3，透明度更低，避免小卡片上色彩过饱和
 *  ② 光斑偏左上（模拟光源），右下收暗
 *  ③ 缓慢漂移 + 呼吸缩放，Drawable 可见时自动播放
 *
 * 用作卡片/按钮的底层 background（叠在拟态填充之下），让卡片本身
 * 自带动态光斑，玻璃折射感来自卡片内部色彩流动。
 */
class CardGlowDrawable(private val dark: Boolean) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 12000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidateSelf()
        }
    }

    /** 卡片级光斑：cx, cy（比例），半径比例（比全屏更小更克制） */
    private val blobs = arrayOf(
        floatArrayOf(0.18f, 0.14f, 0.52f),
        floatArrayOf(0.88f, 0.20f, 0.46f),
        floatArrayOf(0.60f, 0.92f, 0.50f),
    )

    /** 日间：柔和冷光（淡蓝 / 青绿 / 淡紫），低透明 */
    private val dayGlow = intArrayOf(
        0x3D9FC8F0.toInt(), 0x3079D9CE.toInt(), 0x28B9A8EC.toInt(),
    )

    /** 夜间：低透明中性光斑（白灰 / 浅绿 / 深灰），避免夜间卡片泛蓝 */
    private val nightGlow = intArrayOf(
        0x30FFFFFF.toInt(), 0x289ACD6F.toInt(), 0x246B6B7A.toInt(),
    )

    private val glow = if (dark) nightGlow else dayGlow

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()

        val t = phase * Math.PI.toFloat() * 2f
        val maxDim = Math.max(w, h)
        // 裁切到卡片圆角范围，避免圆形光斑在四角溢出
        val saveCount = canvas.save()
        val cardRadius = Math.min(20f * 2.75f, Math.min(w, h) / 2f)
        val path = android.graphics.Path().apply {
            addRoundRect(RectF(left, top, left + w, top + h), cardRadius, cardRadius, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(path)
        for (i in blobs.indices) {
            val b = blobs[i]
            val sway = Math.sin((t + i * 1.6f).toDouble()).toFloat()
            val breath = 0.90f + 0.10f * Math.cos((t + i * 0.8f).toDouble()).toFloat()
            val cx = left + (b[0] + 0.04f * sway) * w
            val cy = top + (b[1] + 0.03f * Math.cos((t + i).toDouble()).toFloat()) * h
            val radius = b[2] * maxDim * breath
            paint.shader = RadialGradient(cx, cy, radius, glow[i], 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, paint)
        }
        paint.shader = null
        canvas.restoreToCount(saveCount)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
        return changed
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
