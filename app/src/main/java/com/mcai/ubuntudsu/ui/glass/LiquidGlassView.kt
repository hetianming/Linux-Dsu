package com.mcai.ubuntudsu.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * Liquid glass panel: a semi-transparent glass surface with layered visual effects.
 *
 * Rendering layers (bottom to top):
 *  1. Blurred background bitmap (optional, provided by LiquidGlassContainer)
 *  2. Glass tint gradient
 *  3. Specular highlight (top-left light source)
 *  4. Edge refraction (bright top/bottom edges)
 *  5. Depth shadow (bottom darkening)
 *  6. Edge glow stroke (bright boundary line)
 *
 * When placed inside a [LiquidGlassContainer], real background blur is achieved
 * via bitmap capture + stack blur on all API levels.
 * Standalone usage (without container) draws gradient-only glass layers.
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    // ---- Glass visual properties ----

    /** Blur radius for hardware RenderEffect (API 31+). DISABLED: RenderEffect
     *  blurs the entire view including children, making text unreadable.
     *  Background blur is provided exclusively via [backgroundBitmap]. */
    var blurRadius: Float = 0f
        set(value) { field = 0f /* hardware blur disabled — use backgroundBitmap */ }

    /** Tint color (ARGB). Applied as semi-transparent vertical gradient. */
    var glassTint: Int = Color.argb(40, 255, 255, 255)
        set(value) { field = value; invalidate() }

    /** Tint opacity multiplier (0.0 - 1.0). */
    var tintAlpha: Float = 0.15f
        set(value) { field = value; invalidate() }

    /** Specular highlight intensity (0.0 - 1.0). 0 disables. */
    var specularIntensity: Float = 0.6f
        set(value) { field = value; invalidate() }

    /** Edge refraction intensity (0.0 - 1.0). 0 disables. */
    var refractionIntensity: Float = 0.4f
        set(value) { field = value; invalidate() }

    /** Depth shadow intensity at the bottom (0.0 - 1.0). 0 disables. */
    var shadowIntensity: Float = 0.3f
        set(value) { field = value; invalidate() }

    /** Corner radius in dp. */
    var cornerRadiusDp: Float = 22f
        set(value) { field = value; updateOutline(); invalidate() }

    /** Edge glow stroke color. */
    var edgeGlowColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    /** Edge glow stroke alpha (0 - 255). */
    var edgeGlowAlpha: Int = 200
        set(value) { field = value; invalidate() }

    // ---- Background bitmap (from LiquidGlassContainer) ----

    /**
     * Blurred background bitmap, typically set by [LiquidGlassContainer].
     * When non-null, drawn as the bottom layer before glass overlays.
     */
    var backgroundBitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    // ---- Internal ----

    private val density = resources.displayMetrics.density
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        updateOutline()
    }

    private fun updateOutline() {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height,
                    cornerRadiusDp * density
                )
            }
        }
        clipToOutline = true
    }

    private fun applyHardwareBlur() {
        if (LiquidGlassRenderer.isHardwareBlurSupported) {
            if (blurRadius > 0) {
                LiquidGlassRenderer.applyHardwareBlur(this, blurRadius)
            } else {
                LiquidGlassRenderer.clearHardwareBlur(this)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = cornerRadiusDp * density

        // 1. Draw blurred background bitmap (if provided by container)
        backgroundBitmap?.let { bmp ->
            if (!bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                glassPaint.reset()
                glassPaint.isAntiAlias = true
                glassPaint.alpha = 255
                canvas.drawBitmap(bmp, null, bounds, glassPaint)
            }
        }

        // 2. Glass tint
        glassPaint.reset()
        glassPaint.isAntiAlias = true
        glassPaint.shader = LiquidGlassRenderer.createTintShader(bounds, glassTint, tintAlpha)
        canvas.drawRoundRect(bounds, radius, radius, glassPaint)

        // 3. Specular highlight
        if (specularIntensity > 0f) {
            glassPaint.reset()
            glassPaint.isAntiAlias = true
            glassPaint.shader = LiquidGlassRenderer.createSpecularShader(bounds, specularIntensity)
            canvas.drawRoundRect(bounds, radius, radius, glassPaint)
        }

        // 4. Edge refraction
        if (refractionIntensity > 0f) {
            glassPaint.reset()
            glassPaint.isAntiAlias = true
            glassPaint.shader = LiquidGlassRenderer.createRefractionShader(bounds, refractionIntensity)
            canvas.drawRoundRect(bounds, radius, radius, glassPaint)
        }

        // 5. Depth shadow
        if (shadowIntensity > 0f) {
            glassPaint.reset()
            glassPaint.isAntiAlias = true
            glassPaint.shader = LiquidGlassRenderer.createDepthShadowShader(bounds, shadowIntensity)
            canvas.drawRoundRect(bounds, radius, radius, glassPaint)
        }

        // 6. Draw children on top of glass background
        super.dispatchDraw(canvas)

        // 7. Edge glow stroke (above children for crisp boundary)
        if (edgeGlowAlpha > 0) {
            glassPaint.reset()
            glassPaint.isAntiAlias = true
            glassPaint.style = Paint.Style.STROKE
            glassPaint.strokeWidth = density
            glassPaint.color = Color.argb(
                edgeGlowAlpha,
                Color.red(edgeGlowColor),
                Color.green(edgeGlowColor),
                Color.blue(edgeGlowColor)
            )
            val inset = density * 0.5f
            canvas.drawRoundRect(
                RectF(inset, inset, bounds.width() - inset, bounds.height() - inset),
                radius - inset, radius - inset,
                glassPaint
            )
        }
    }
}
