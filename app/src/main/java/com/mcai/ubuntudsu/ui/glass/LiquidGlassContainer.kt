package com.mcai.ubuntudsu.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * A container that provides real background blur to child [LiquidGlassView] instances.
 *
 * Rendering pipeline:
 *  1. Captures its own background (the animated liquid gradient or any drawable behind it)
 *  2. Applies stack blur to the captured bitmap
 *  3. Distributes the blurred bitmap to child [LiquidGlassView] instances
 *  4. Children draw the blurred bitmap as their bottom layer + glass overlays on top
 *
 * This provides real blur on all API levels (including pre-API 31).
 * On API 31+, hardware RenderEffect is also available via [LiquidGlassView.blurRadius].
 *
 * Performance:
 *  - The container only re-captures and re-blurs when [invalidateBlur] is called
 *  - For static backgrounds (like onboarding gradient), call [invalidateBlur] once
 *  - For animated backgrounds, call [invalidateBlur] periodically (e.g. every 200ms)
 */
class LiquidGlassContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Stack blur radius for the bitmap-based blur (pre-API 31 primary path). */
    var blurRadius: Int = 18
        set(value) { field = value; needsReblur = true; invalidate() }

    /** Downscale factor for the captured bitmap (improves blur performance). */
    var downscaleFactor: Float = 0.35f
        set(value) { field = value; needsReblur = true; invalidate() }

    private var blurredBitmap: Bitmap? = null
    private var needsReblur = true
    private val density = resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Mark the background as dirty, requiring re-capture and re-blur.
     * Call this when the background content changes.
     */
    fun invalidateBlur() {
        needsReblur = true
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        // 1. Capture and blur background if needed
        if (needsReblur && width > 0 && height > 0) {
            captureAndBlur()
            needsReblur = false
        }

        // 2. Draw our own background (the liquid gradient / wallpaper)
        val bg = background
        bg?.draw(canvas)

        // 3. Draw children (LiquidGlassView instances will use the blurred bitmap)
        super.dispatchDraw(canvas)
    }

    private fun captureAndBlur() {
        if (width <= 0 || height <= 0) return

        // Recycle old bitmap
        blurredBitmap?.recycle()
        blurredBitmap = null

        // Capture the background drawable
        val bg = background ?: return

        // Create bitmap at downscaled resolution for performance
        val scaledW = (width * downscaleFactor).toInt().coerceAtLeast(1)
        val scaledH = (height * downscaleFactor).toInt().coerceAtLeast(1)

        val sourceBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(sourceBitmap)

        // Scale canvas to match original dimensions
        val scale = scaledW.toFloat() / width.toFloat()
        sourceCanvas.scale(scale, scale)

        // Draw the background into the bitmap
        bg.bounds = android.graphics.Rect(0, 0, width, height)
        bg.draw(sourceCanvas)

        // Apply stack blur
        blurredBitmap = if (blurRadius > 0) {
            LiquidGlassRenderer.stackBlur(sourceBitmap, blurRadius)
        } else {
            sourceBitmap
        }

        // Scale back to full size for crisp rendering
        if (blurredBitmap != null && blurredBitmap != sourceBitmap) {
            sourceBitmap.recycle()
        }
        blurredBitmap = blurredBitmap?.let {
            if (it.width != width || it.height != height) {
                val scaled = Bitmap.createScaledBitmap(it, width, height, true)
                if (scaled != it) it.recycle()
                scaled
            } else {
                it
            }
        }

        // Distribute to children
        distributeBlurredBackground()
    }

    private fun distributeBlurredBackground() {
        val bmp = blurredBitmap ?: return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is LiquidGlassView) {
                child.backgroundBitmap = bmp
            }
        }
    }

    /**
     * Manually set the blurred background bitmap (for use with animated backgrounds
     * where external code manages the capture/blur cycle).
     */
    fun setBlurredBackground(bitmap: Bitmap?) {
        blurredBitmap?.recycle()
        blurredBitmap = bitmap
        distributeBlurredBackground()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        blurredBitmap?.recycle()
        blurredBitmap = null
        super.onDetachedFromWindow()
    }
}
