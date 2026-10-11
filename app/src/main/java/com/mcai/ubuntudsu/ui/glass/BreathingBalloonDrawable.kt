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
        duration = 11000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = breathingInterpolator
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidateSelf()
        }
    }

    // 漂移幅度比原来更小，呼吸感更柔
    private val balloons = arrayOf(
        floatArrayOf(0.16f, 0.14f, 0.34f, 0.030f, 0.050f, 4.1f, 0.9f),
        floatArrayOf(0.76f, 0.10f, 0.28f, 0.040f, 0.065f, 5.0f, 1.7f),
        floatArrayOf(0.62f, 0.30f, 0.42f, 0.028f, 0.045f, 4.6f, 2.3f),
        floatArrayOf(0.10f, 0.52f, 0.34f, 0.035f, 0.055f, 5.4f, 3.1f),
        floatArrayOf(0.88f, 0.56f, 0.30f, 0.028f, 0.048f, 4.8f, 0.5f),
        floatArrayOf(0.48f, 0.82f, 0.38f, 0.034f, 0.060f, 5.8f, 2.0f),
        floatArrayOf(0.26f, 0.88f, 0.26f, 0.024f, 0.040f, 4.4f, 3.6f),
    )

    private val balloonColors: IntArray = if (dark) {
        intArrayOf(
            0x42FFB6C1.toInt(),
            0x42ADD8E6.toInt(),
            0x3DCC99FF.toInt(),
            0x42FFCC99.toInt(),
            0x3DB5E6B5.toInt(),
            0x34FFFFFF,
            0x2E51789E,
        )
    } else {
        intArrayOf(
            0x8CFFB6C1.toInt(),
            0x88ADD8E6.toInt(),
            0x7CC999FF.toInt(),
            0x8AFFCC99.toInt(),
            0x78B5E6B5.toInt(),
            0x5AFFFFFF,
            0x5251789E,
        )
    }

    private val baseColors: IntArray = if (dark) {
        intArrayOf(0xFF141B29.toInt(), 0xFF1A2232.toInt(), 0xFF233145.toInt(), 0xFF19202C.toInt())
    } else {
        intArrayOf(0xFFFDF6F9.toInt(), 0xFFF3EDFF.toInt(), 0xFFEAF4FF.toInt(), 0xFFE9F2FB.toInt())
    }

    private val basePositions = floatArrayOf(0f, 0.38f, 0.72f, 1f)

    private val topLight: Int = if (dark) 0x1EFFFFFF else 0x66FFFFFF

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
            val cx = left + (b[0] + b[3] * Math.sin((t + b[5]).toDouble()).toFloat()) * w
            val cy = top + (b[1] - b[4] * Math.cos((t + b[6]).toDouble()).toFloat()) * h
            val breathe = 0.97f + 0.06f * Math.cos((t * 1.1f + i * 0.9f).toDouble()).toFloat()
            val rx = b[2] * maxDim * 0.45f * breathe
            val ry = rx * 1.18f
            val color = balloonColors[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val bl = Color.blue(color)

            val body = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
            paint.shader = RadialGradient(
                cx - rx * 0.35f,
                cy - ry * 0.42f,
                Math.max(rx, ry) * 1.7f,
                intArrayOf(
                    Color.argb(minOf(Color.alpha(color) + 18, 255), r, g, bl),
                    color,
                    0,
                ),
                floatArrayOf(0f, 0.55f, 1f),
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
