package com.mcai.ubuntudsu.ui.glass

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator

class BreathingBalloonDrawable(private val dark: Boolean) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f

    // 自定义缓动：呼吸感（吸气缓慢、呼气缓慢，两端减速）
    private val breathingInterpolator: Interpolator = object : Interpolator {
        override fun getInterpolation(input: Float): Float {
            // 用余弦做柔和缓入缓出，两端速度为 0
            return (1f - Math.cos(input * Math.PI).toFloat() * 0.5f)
        }
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 12500L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = breathingInterpolator
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidateSelf()
        }
    }

    // 两个球：右上偏青绿大球 + 左下蓝青大球，留白更多，互不重叠
    // dx 放大到 0.095f / 0.092f，让左右漂移到快要碰到屏幕边缘才回头
    private val balloons = arrayOf(
        floatArrayOf(0.74f, 0.16f, 0.50f, 0.095f, 0.042f, 4.1f, 0.9f),
        floatArrayOf(0.26f, 0.80f, 0.52f, 0.092f, 0.040f, 5.4f, 2.3f),
    )

    private val warmCoolStops = if (dark) {
        intArrayOf(
            0xFF2F9E85.toInt(),
            0xFF3F86B8.toInt(),
            0xFF3A6DA6.toInt(),
            0xFF2E8A76.toInt(),
            0xFF46A37E.toInt(),
        )
    } else {
        intArrayOf(
            0xFF74D6B4.toInt(),
            0xFF8FD1E6.toInt(),
            0xFF7FA8D5.toInt(),
            0xFF62B7A8.toInt(),
            0xFF71C98F.toInt(),
        )
    }

    private val balloonStarts = intArrayOf(0, 2)

    private val balloonAlphas: IntArray = if (dark) {
        intArrayOf(185, 168)
    } else {
        intArrayOf(210, 195)
    }

    private val baseColors: IntArray = if (dark) {
        intArrayOf(0xFF172122.toInt(), 0xFF192F31.toInt(), 0xFF1B3332.toInt(), 0xFF101C1E.toInt())
    } else {
        intArrayOf(0xFFEFFBFF.toInt(), 0xFFE1F4FF.toInt(), 0xFFD9EAF6.toInt(), 0xFFEEF7F3.toInt())
    }

    private val basePositions = floatArrayOf(0f, 0.38f, 0.72f, 1f)

    private val topLight: Int = if (dark) 0x18FFFFFF else 0x1AFFFFFF

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()

        paint.shader = LinearGradient(
            0f, top, 0f, top + h,
            baseColors, basePositions, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, top, left + w, top + h, paint)
        paint.shader = null

        val t = phase * Math.PI.toFloat() * 2f
        val maxDim = Math.max(w, h)

        for (i in balloons.indices) {
            val b = balloons[i]
            val cx = left + (b[0] + b[3] * Math.sin((t * (0.85f + i * 0.21f) + b[5]).toDouble()).toFloat()) * w
            val cy = top + (b[1] - b[4] * Math.cos((t * (1.05f + i * 0.17f) + b[6]).toDouble()).toFloat() - 0.03f * Math.sin((t * (0.38f + i * 0.14f) + i * 1.1f).toDouble()).toFloat()) * h
            val breathe = 0.995f + 0.015f * Math.cos((t * 1.1f + i * 0.9f).toDouble()).toFloat()
            val rx = b[2] * maxDim * 0.58f * breathe
            val ry = rx

            // 每球按自己的相位周期做色彩脉动，互不同步
            val cycle = 1.45f + i * 0.35f
            val pulse = 0.96f + 0.04f * Math.cos((t * cycle + i * 2.3f).toDouble()).toFloat()
            val alphaBase = (balloonAlphas[i] * pulse).toInt().coerceIn(0, 255)

            val body = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
            paint.shader = RadialGradient(
                (cx - rx * 0.35f).toFloat(),
                (cy - ry * 0.42f).toFloat(),
                (Math.max(rx, ry) * 1.7f).toFloat(),
                intArrayOf(
                    withAlpha(warmCoolStops[balloonStarts[i] % warmCoolStops.size], minOf(alphaBase + 18, 255)),
                    withAlpha(warmCoolStops[(balloonStarts[i] + 1) % warmCoolStops.size], alphaBase),
                    withAlpha(warmCoolStops[(balloonStarts[i] + 2) % warmCoolStops.size], alphaBase * 7 / 10),
                    withAlpha(warmCoolStops[(balloonStarts[i] + 3) % warmCoolStops.size], 0),
                    0x00000000,
                ),
                floatArrayOf(0f, 0.46f, 0.72f, 0.92f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawOval(body, paint)
            paint.shader = null
        }

        paint.shader = LinearGradient(
            0f, top, 0f, top + h * 0.42f,
            topLight, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, top, left + w, top + h * 0.42f, paint)
        paint.shader = null
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

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
