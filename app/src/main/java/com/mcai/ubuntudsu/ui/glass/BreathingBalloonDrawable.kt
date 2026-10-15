package com.mcai.ubuntudsu.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.Interpolator

class BreathingBalloonDrawable(
    private val context: Context,
    private val dark: Boolean,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var phase = 0f

    // 自定义缓动：两端减速，波动更自然
    private val breathingInterpolator: Interpolator = object : Interpolator {
        override fun getInterpolation(input: Float): Float {
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

    // 底图：动态呼吸壁纸（全局缓存 + 下采样，避免每个 Activity 重复解码与内存膨胀）
    private val baseBitmap: Bitmap? get() = cachedBaseBitmap(context)

    // 风吹布波浪：网格顶点缓存（每帧重算，避免分配）
    private val meshW = 28
    private val meshH = 28
    private val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)

    // 两个球：右上偏青绿大球 + 左下蓝青大球，留白更多，互不重叠
    private val balloons = arrayOf(
        floatArrayOf(0.74f, 0.16f, 0.50f, 0.095f, 0.042f, 4.1f, 0.9f),
        floatArrayOf(0.26f, 0.80f, 0.52f, 0.092f, 0.040f, 5.4f, 2.3f),
    )

    // 球体配色：冷色蓝紫，在青绿底图上对比更明显
    private val warmCoolStops = if (dark) {
        intArrayOf(
            0xFF5B8DEF.toInt(),
            0xFF7C9FE8.toInt(),
            0xFF6288C8.toInt(),
            0xFF4A9FD8.toInt(),
            0xFF8B7FD4.toInt(),
        )
    } else {
        intArrayOf(
            0xFF6AA3E8.toInt(),
            0xFF8CB8F0.toInt(),
            0xFF7C93DC.toInt(),
            0xFF5FA9D6.toInt(),
            0xFF9A88D8.toInt(),
        )
    }

    private val balloonStarts = intArrayOf(0, 2)

    // 球体透明度提高，在底图上更明显
    private val balloonAlphas: IntArray = if (dark) {
        intArrayOf(205, 188)
    } else {
        intArrayOf(225, 208)
    }

    // 夜间在亮色底图上叠加深色蒙版，保持夜间观感
    private val nightScrim: Int = 0xB31A2230.toInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** 构建风吹布波浪网格：整图铺满裁剪区，顶点按正弦波偏移，形成布料飘动 */
    private fun buildWaveMesh(dx: Float, dy: Float, dw: Float, dh: Float, t: Float) {
        val ampX = dw * 0.020f
        val ampY = dh * 0.016f
        val twoPi = (Math.PI * 2).toFloat()
        var idx = 0
        for (j in 0..meshH) {
            val fy = j.toFloat() / meshH
            for (i in 0..meshW) {
                val fx = i.toFloat() / meshW
                val bx = dx + fx * dw
                val by = dy + fy * dh
                // 横向主波（向下流动）+ 纵向次波，叠加成布料起伏
                val ox = ampX * Math.sin((fy * twoPi * 1.1f + t * twoPi + fx * 1.2f).toDouble()).toFloat()
                val oy = ampY * Math.sin((fx * twoPi * 0.85f + t * twoPi * 0.8f + fy * 1.4f).toDouble()).toFloat()
                verts[idx++] = bx + ox
                verts[idx++] = by + oy
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()

        // 1. 底图：居中裁切铺满 + 风吹布波浪形变
        val bmp = baseBitmap
        if (bmp != null && !bmp.isRecycled) {
            // 多铺出一圈余量（> 波浪最大位移），避免边缘波动时漏边
            val marginX = w * 0.06f
            val marginY = h * 0.06f
            val cover = Math.max((w + marginX * 2f) / bmp.width, (h + marginY * 2f) / bmp.height)
            val dw = bmp.width * cover
            val dh = bmp.height * cover
            val dx = left + (w - dw) / 2f
            val dy = top + (h - dh) / 2f
            buildWaveMesh(dx, dy, dw, dh, phase)
            paint.shader = null
            paint.alpha = 255
            canvas.drawBitmapMesh(bmp, meshW, meshH, verts, 0, null, 0, paint)
        } else {
            // 解码失败时回退到纯色底
            paint.shader = LinearGradient(
                0f, top, 0f, top + h,
                if (dark) intArrayOf(0xFF0F1520.toInt(), 0xFF182030.toInt())
                else intArrayOf(0xFFEAF0F8.toInt(), 0xFFCDD6E8.toInt()),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(left, top, left + w, top + h, paint)
            paint.shader = null
        }

        // 夜间叠加深色蒙版
        if (dark) {
            paint.color = nightScrim
            canvas.drawRect(left, top, left + w, top + h, paint)
        }

        // 2. 两个球：漂移 + 呼吸缩放 + 色彩脉动
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

    companion object {
        private const val MAX_DIM = 1600

        @Volatile
        private var sharedBitmap: Bitmap? = null

        /** 全局共享底图，解码一次 + 下采样到 MAX_DIM 以内 */
        private fun cachedBaseBitmap(context: Context): Bitmap? {
            sharedBitmap?.let { if (!it.isRecycled) return it }
            synchronized(this) {
                sharedBitmap?.let { if (!it.isRecycled) return it }
                val decoded = runCatching {
                    val res = context.resources
                    val id = com.mcai.ubuntudsu.R.drawable.bg_liquid
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeResource(res, id, bounds)
                    val longest = Math.max(bounds.outWidth, bounds.outHeight)
                    var sample = 1
                    while (longest / sample > MAX_DIM) sample *= 2
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeResource(res, id, opts)
                }.getOrNull()
                sharedBitmap = decoded
                return decoded
            }
        }
    }
}
