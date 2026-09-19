package com.mcai.ubuntudsu.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.View

/**
 * Liquid glass core rendering engine.
 *
 * Three rendering tiers:
 *  1. Hardware blur (API 31+, RenderEffect) - real-time, GPU-accelerated
 *  2. Bitmap stack blur (all APIs) - captures and blurs bitmap
 *  3. Gradient-only glass (all APIs) - visual glass layers without real blur
 *
 * Glass visual layers (drawn on top of blurred or plain background):
 *  - Tint: semi-transparent vertical gradient overlay
 *  - Specular: radial highlight simulating a top-left light source
 *  - Refraction: edge-bright gradient simulating light bending at glass edges
 *  - Edge glow: thin bright stroke along the rounded rect boundary
 *  - Noise: subtle grain texture for surface realism
 */
internal object LiquidGlassRenderer {

    // ==================== Hardware Blur (API 31+) ====================

    val isHardwareBlurSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun applyHardwareBlur(view: View, radius: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val r = radius.coerceIn(0f, 75f)
            view.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            )
        }
    }

    fun clearHardwareBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }

    // ==================== Stack Blur (bitmap-based, all APIs) ====================

    /**
     * Mario Klingemann's stack blur algorithm.
     * O(n) per pixel, produces Gaussian-quality blur in two separable passes.
     */
    fun stackBlur(source: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return source
        val w = source.width
        val h = source.height
        if (w == 0 || h == 0) return source

        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)

        val vmin = IntArray(maxOf(w, h))
        val vmax = IntArray(maxOf(w, h))

        // Pre-compute division lookup table
        var divSum = (div + 1) shr 1
        divSum *= divSum
        val dv = IntArray(256 * divSum)
        for (i in 0 until 256 * divSum) {
            dv[i] = i / divSum
        }

        var yi = 0
        var yw = 0

        // ---- Horizontal pass ----
        for (y in 0 until h) {
            var rsum = 0
            var gsum = 0
            var bsum = 0
            for (i in -radius..radius) {
                val clamped = if (i < 0) 0 else if (i > wm) wm else i
                val p = pix[yi + clamped]
                rsum += (p shr 16) and 0xff
                gsum += (p shr 8) and 0xff
                bsum += p and 0xff
            }
            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                if (y == 0) {
                    vmin[x] = minOf(x + radius + 1, wm)
                    vmax[x] = maxOf(x - radius, 0)
                }

                var p = pix[yw + vmin[x]]
                rsum += (p shr 16) and 0xff
                gsum += (p shr 8) and 0xff
                bsum += p and 0xff

                p = pix[yw + vmax[x]]
                rsum -= (p shr 16) and 0xff
                gsum -= (p shr 8) and 0xff
                bsum -= p and 0xff

                yi++
            }
            yw += w
        }

        // ---- Vertical pass ----
        for (x in 0 until w) {
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var yp = -radius * w
            for (i in -radius..radius) {
                val idx = maxOf(0, yp) + x
                rsum += r[idx]
                gsum += g[idx]
                bsum += b[idx]
                yp += w
            }
            yi = x
            for (y in 0 until h) {
                pix[yi] = (0xff000000.toInt()) or
                    (dv[rsum] shl 16) or
                    (dv[gsum] shl 8) or
                    dv[bsum]

                if (x == 0) {
                    vmin[y] = minOf(y + radius + 1, hm) * w
                    vmax[y] = maxOf(y - radius, 0) * w
                }

                rsum += r[x + vmin[y]]
                gsum += g[x + vmin[y]]
                bsum += b[x + vmin[y]]

                rsum -= r[x + vmax[y]]
                gsum -= g[x + vmax[y]]
                bsum -= b[x + vmax[y]]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    /**
     * Downscale + stack blur for better performance on large bitmaps.
     */
    fun fastBlur(source: Bitmap, radius: Int, downscaleFactor: Float = 0.25f): Bitmap {
        if (radius < 1) return source
        val w = source.width
        val h = source.height
        if (w == 0 || h == 0) return source

        // Downscale for faster blur
        val scaledW = (w * downscaleFactor).toInt().coerceAtLeast(1)
        val scaledH = (h * downscaleFactor).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)

        // Apply stack blur on the smaller bitmap
        val blurred = stackBlur(scaled, radius)

        // Scale back up to original size
        return Bitmap.createScaledBitmap(blurred, w, h, true)
    }

    // ==================== Glass Shaders ====================

    /**
     * Glass tint shader: semi-transparent vertical gradient overlay.
     * Top is slightly more opaque (light gathering), bottom fades.
     */
    fun createTintShader(bounds: RectF, tint: Int, alpha: Float): LinearGradient {
        val r = Color.red(tint)
        val g = Color.green(tint)
        val b = Color.blue(tint)
        return LinearGradient(
            bounds.left, bounds.top,
            bounds.left, bounds.bottom,
            intArrayOf(
                Color.argb((alpha * 255 * 1.3f).toInt().coerceAtMost(255), r, g, b),
                Color.argb((alpha * 255 * 0.9f).toInt().coerceAtMost(255), r, g, b),
                Color.argb((alpha * 255 * 0.5f).toInt().coerceAtMost(255), r, g, b),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * Specular highlight: radial gradient from top-left simulating a light source.
     * Creates the "shine" on the glass surface.
     */
    fun createSpecularShader(bounds: RectF, intensity: Float): RadialGradient {
        val cx = bounds.width() * 0.2f + bounds.left
        val cy = bounds.height() * 0.1f + bounds.top
        val radius = maxOf(bounds.width(), bounds.height()) * 0.9f
        return RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.argb((intensity * 130).toInt().coerceAtMost(255), 255, 255, 255),
                Color.argb((intensity * 50).toInt().coerceAtMost(255), 255, 255, 255),
                Color.argb(0, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * Edge refraction: vertical gradient brighter at top and bottom edges,
     * simulating light bending through the glass boundary.
     */
    fun createRefractionShader(bounds: RectF, intensity: Float): LinearGradient {
        return LinearGradient(
            bounds.left, bounds.top,
            bounds.left, bounds.bottom,
            intArrayOf(
                Color.argb((intensity * 90).toInt().coerceAtMost(255), 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Color.argb((intensity * 60).toInt().coerceAtMost(255), 255, 255, 255),
            ),
            floatArrayOf(0f, 0.12f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * Inner edge glow: bright stroke paint for the rounded rect boundary.
     */
    fun createEdgeGlowPaint(density: Float, color: Int, alpha: Int, widthDp: Float): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthDp * density
            this.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }
    }

    /**
     * Bottom shadow: subtle dark gradient at the bottom for depth.
     */
    fun createDepthShadowShader(bounds: RectF, intensity: Float): LinearGradient {
        return LinearGradient(
            bounds.left, bounds.top,
            bounds.left, bounds.bottom,
            intArrayOf(
                Color.argb(0, 0, 0, 0),
                Color.argb(0, 0, 0, 0),
                Color.argb((intensity * 40).toInt().coerceAtMost(255), 0, 0, 0),
            ),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }
}
