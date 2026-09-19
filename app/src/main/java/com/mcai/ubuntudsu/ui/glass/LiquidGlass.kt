package com.mcai.ubuntudsu.ui.glass

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mcai.ubuntudsu.ui.Ui

/**
 * Factory and presets for liquid glass UI elements.
 * Provides convenient methods to create glass panels with pre-configured visual styles.
 *
 * Usage:
 *  - [card] / [overlay] / [button]: create a [LiquidGlassView] with a matching preset
 *  - [createTextCard]: glass panel with title and optional subtitle
 *  - [applyPreset]: apply a preset to an existing [LiquidGlassView]
 */
object LiquidGlass {

    /**
     * Visual configuration for a glass panel.
     */
    data class GlassPreset(
        val blurRadius: Float,
        val tint: Int,
        val tintAlpha: Float,
        val cornerRadiusDp: Float,
        val specularIntensity: Float,
        val refractionIntensity: Float,
        val shadowIntensity: Float,
        val edgeGlowColor: Int,
        val edgeGlowAlpha: Int,
    )

    // ==================== Presets ====================

    /** Card-style glass panel for content sections. */
    val cardLight = GlassPreset(
        blurRadius = 20f,
        tint = Color.argb(50, 255, 255, 255),
        tintAlpha = 0.16f,
        cornerRadiusDp = 22f,
        specularIntensity = 0.6f,
        refractionIntensity = 0.4f,
        shadowIntensity = 0.3f,
        edgeGlowColor = Color.WHITE,
        edgeGlowAlpha = 210,
    )

    val cardDark = GlassPreset(
        blurRadius = 20f,
        tint = Color.argb(35, 255, 255, 255),
        tintAlpha = 0.08f,
        cornerRadiusDp = 22f,
        specularIntensity = 0.4f,
        refractionIntensity = 0.3f,
        shadowIntensity = 0.2f,
        edgeGlowColor = Color.WHITE,
        edgeGlowAlpha = 160,
    )

    /** Full-screen overlay glass panel (for dialogs, onboarding). */
    val overlayLight = GlassPreset(
        blurRadius = 30f,
        tint = Color.argb(60, 255, 255, 255),
        tintAlpha = 0.22f,
        cornerRadiusDp = 28f,
        specularIntensity = 0.7f,
        refractionIntensity = 0.5f,
        shadowIntensity = 0.35f,
        edgeGlowColor = Color.WHITE,
        edgeGlowAlpha = 230,
    )

    val overlayDark = GlassPreset(
        blurRadius = 30f,
        tint = Color.argb(45, 255, 255, 255),
        tintAlpha = 0.12f,
        cornerRadiusDp = 28f,
        specularIntensity = 0.5f,
        refractionIntensity = 0.4f,
        shadowIntensity = 0.25f,
        edgeGlowColor = Color.WHITE,
        edgeGlowAlpha = 180,
    )

    /** Compact glass button style. */
    val buttonLight = GlassPreset(
        blurRadius = 12f,
        tint = Color.argb(40, 255, 255, 255),
        tintAlpha = 0.12f,
        cornerRadiusDp = 18f,
        specularIntensity = 0.5f,
        refractionIntensity = 0.3f,
        shadowIntensity = 0.2f,
        edgeGlowColor = Color.WHITE,
        edgeGlowAlpha = 190,
    )

    val buttonDark = GlassPreset(
        blurRadius = 12f,
        tint = Color.argb(25, 255, 255, 255),
        tintAlpha = 0.06f,
        cornerRadiusDp = 18f,
        specularIntensity = 0.3f,
        refractionIntensity = 0.2f,
        shadowIntensity = 0.15f,
        edgeGlowColor = Color.WHITE,
        edgeGlowAlpha = 140,
    )

    /** Navigation bar glass strip. */
    val navBarLight = GlassPreset(
        blurRadius = 25f,
        tint = Color.argb(45, 255, 255, 255),
        tintAlpha = 0.14f,
        cornerRadiusDp = 26f,
        specularIntensity = 0.55f,
        refractionIntensity = 0.35f,
        shadowIntensity = 0.25f,
        edgeGlowColor = Color.WHITE,
        edgeGlowAlpha = 200,
    )

    val navBarDark = GlassPreset(
        blurRadius = 25f,
        tint = Color.argb(30, 255, 255, 255),
        tintAlpha = 0.07f,
        cornerRadiusDp = 26f,
        specularIntensity = 0.35f,
        refractionIntensity = 0.25f,
        shadowIntensity = 0.18f,
        edgeGlowColor = Color.WHITE,
        edgeGlowAlpha = 150,
    )

    // ==================== Auto dark/light ====================

    private fun isDark(context: Context): Boolean = Ui.isDark(context)

    fun card(context: Context): GlassPreset = if (isDark(context)) cardDark else cardLight
    fun overlay(context: Context): GlassPreset = if (isDark(context)) overlayDark else overlayLight
    fun button(context: Context): GlassPreset = if (isDark(context)) buttonDark else buttonLight
    fun navBar(context: Context): GlassPreset = if (isDark(context)) navBarDark else navBarLight

    // ==================== Factory methods ====================

    /**
     * Create a [LiquidGlassView] with the given preset.
     */
    fun createView(context: Context, preset: GlassPreset): LiquidGlassView {
        return LiquidGlassView(context).also { view -> applyPreset(view, preset) }
    }

    /**
     * Apply a preset to an existing [LiquidGlassView].
     */
    fun applyPreset(view: LiquidGlassView, preset: GlassPreset) {
        view.blurRadius = preset.blurRadius
        view.glassTint = preset.tint
        view.tintAlpha = preset.tintAlpha
        view.cornerRadiusDp = preset.cornerRadiusDp
        view.specularIntensity = preset.specularIntensity
        view.refractionIntensity = preset.refractionIntensity
        view.shadowIntensity = preset.shadowIntensity
        view.edgeGlowColor = preset.edgeGlowColor
        view.edgeGlowAlpha = preset.edgeGlowAlpha
    }

    /**
     * Create a glass card with a title and optional subtitle.
     * Returns the [LiquidGlassView] containing the text content.
     */
    fun createTextCard(
        context: Context,
        title: String,
        subtitle: String? = null,
        body: String? = null,
        preset: GlassPreset? = null,
    ): LiquidGlassView {
        val dark = isDark(context)
        val p = preset ?: if (dark) cardDark else cardLight
        val view = createView(context, p)
        val d = context.resources.displayMetrics.density

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(16, d), Ui.dp(20, d), Ui.dp(16, d))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(TextView(context).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(context))
        })

        subtitle?.let {
            layout.addView(TextView(context).apply {
                text = it
                textSize = 13f
                setTextColor(Ui.secondaryText(context))
                setPadding(0, Ui.dp(4, d), 0, 0)
            })
        }

        body?.let {
            layout.addView(TextView(context).apply {
                text = it
                textSize = 14f
                setTextColor(Ui.primaryText(context))
                setPadding(0, Ui.dp(12, d), 0, 0)
                setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
            })
        }

        view.addView(layout)
        return view
    }

    /**
     * Create a glass button (clickable [LiquidGlassView] with a text label).
     * [onClick] is the last parameter to support trailing-lambda syntax.
     */
    fun createGlassButton(
        context: Context,
        label: String,
        preset: GlassPreset? = null,
        onClick: () -> Unit,
    ): LiquidGlassView {
        val dark = isDark(context)
        val p = preset ?: if (dark) buttonDark else buttonLight
        val view = createView(context, p)
        val d = context.resources.displayMetrics.density

        val labelView = TextView(context).apply {
            text = label
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.buttonText(context))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(44, d),
                Gravity.CENTER
            )
        }

        view.addView(labelView)
        view.isClickable = true
        view.isFocusable = true
        Ui.pressAnimation(view)
        view.setOnClickListener { onClick() }
        return view
    }
}
