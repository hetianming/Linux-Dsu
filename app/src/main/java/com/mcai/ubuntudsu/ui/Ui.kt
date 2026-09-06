package com.mcai.ubuntudsu.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.app.Activity
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.content.res.Configuration
import android.animation.ValueAnimator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

object Ui {
    private const val MAX_CORNER_RADIUS_DP = 32f

    fun isDark(context: android.content.Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun enableEdgeToEdge(activity: Activity, content: View) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val left = content.paddingLeft
        val top = content.paddingTop
        val right = content.paddingRight
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !isDark(activity)
            isAppearanceLightNavigationBars = !isDark(activity)
        }
    }

    fun background(context: android.content.Context): Int = if (isDark(context)) Color.rgb(112, 112, 112) else Color.parseColor("#F5F6FB")

    fun liquidBackground(context: android.content.Context): GradientDrawable {
        val dark = isDark(context)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (dark) {
                intArrayOf(Color.rgb(24, 35, 50), Color.rgb(42, 58, 72), Color.rgb(20, 31, 45))
            } else {
                intArrayOf(Color.rgb(224, 239, 238), Color.rgb(207, 220, 226), Color.rgb(185, 211, 223))
            },
        )
    }

    fun animateLiquidBackground(view: View) {
        val context = view.context
        val dark = isDark(context)
        val start = if (dark) {
            intArrayOf(Color.rgb(24, 35, 50), Color.rgb(42, 58, 72), Color.rgb(20, 31, 45))
        } else {
            intArrayOf(Color.rgb(224, 239, 238), Color.rgb(207, 220, 226), Color.rgb(185, 211, 223))
        }
        val end = if (dark) {
            intArrayOf(Color.rgb(34, 47, 63), Color.rgb(52, 67, 80), Color.rgb(25, 39, 54))
        } else {
            intArrayOf(Color.rgb(235, 243, 239), Color.rgb(216, 226, 229), Color.rgb(169, 198, 215))
        }
        val drawable = liquidBackground(context)
        view.background = drawable
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 9000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val fraction = it.animatedValue as Float
                drawable.setColors(start.mapIndexed { index, color ->
                    Color.rgb(
                        (Color.red(color) + (Color.red(end[index]) - Color.red(color)) * fraction).toInt(),
                        (Color.green(color) + (Color.green(end[index]) - Color.green(color)) * fraction).toInt(),
                        (Color.blue(color) + (Color.blue(end[index]) - Color.blue(color)) * fraction).toInt(),
                    )
                }.toIntArray())
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = animator.start()
            override fun onViewDetachedFromWindow(v: View) = animator.cancel()
        })
        if (view.isAttachedToWindow) animator.start()
    }
    fun surface(context: android.content.Context): Int = if (isDark(context)) Color.rgb(48, 48, 48) else Color.WHITE
    fun surfaceMuted(context: android.content.Context): Int = if (isDark(context)) Color.rgb(52, 52, 52) else Color.parseColor("#EEF0F2")
    fun dsuCard(context: android.content.Context): Int = if (isDark(context)) Color.rgb(48, 48, 48) else Color.WHITE
    fun dsuEntry(context: android.content.Context): Int = if (isDark(context)) Color.rgb(48, 48, 48) else Color.parseColor("#EEF0F2")
    fun border(context: android.content.Context): Int = if (isDark(context)) Color.rgb(150, 150, 150) else Color.parseColor("#D5DCE8")
    fun primaryText(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#FFF1E2") else Color.parseColor("#1A1A1A")
    fun secondaryText(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#E0C5AF") else Color.parseColor("#687181")
    fun buttonPrimary(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#D47A48") else Color.parseColor("#2493A4")
    fun buttonSecondary(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#8172C4") else Color.parseColor("#355CC9")
    fun buttonSuccess(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#6FA874") else Color.parseColor("#20C55A")
    fun buttonWarning(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#C28A45") else Color.parseColor("#F0A010")
    fun buttonDanger(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#C56767") else Color.parseColor("#EF454A")
    fun buttonText(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#FFF8F1") else Color.parseColor("#263342")

    fun rounded(color: Int, radiusDp: Float, density: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
        }

    fun strokeRounded(color: Int, strokeColor: Int, strokeWidthDp: Float, density: Float, radiusDp: Float = 8f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
            setStroke((strokeWidthDp * density).toInt(), strokeColor)
        }

    fun glassButton(context: android.content.Context, accent: Int? = null): RippleDrawable {
        val density = context.resources.displayMetrics.density
        val dark = isDark(context)
        val base = if (dark) Color.argb(128, 255, 255, 255) else Color.argb(184, 255, 255, 255)
        val middle = if (dark) Color.argb(72, 220, 230, 245) else Color.argb(112, 245, 250, 255)
        val bottom = if (dark) Color.argb(52, 180, 200, 220) else Color.argb(82, 205, 220, 235)
        val edge = accent?.let { Color.argb(185, Color.red(it), Color.green(it), Color.blue(it)) }
            ?: if (dark) Color.argb(170, 255, 255, 255) else Color.argb(225, 255, 255, 255)
        val content = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(base, middle, bottom)).apply {
            cornerRadius = dp(18, density).toFloat()
            setStroke(dp(1, density), edge)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(70, 90, 180, 255)), content, null)
    }

    fun glassSurface(context: android.content.Context, radiusDp: Float = 22f): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val dark = isDark(context)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                if (dark) Color.argb(116, 255, 255, 255) else Color.argb(194, 255, 255, 255),
                if (dark) Color.argb(62, 220, 230, 245) else Color.argb(104, 240, 248, 255),
                if (dark) Color.argb(44, 170, 190, 215) else Color.argb(70, 195, 215, 230),
            ),
        ).apply {
            cornerRadius = dp(radiusDp.toInt(), density).toFloat()
            setStroke(dp(1, density), if (dark) Color.argb(175, 255, 255, 255) else Color.argb(235, 255, 255, 255))
        }
    }

    fun pressAnimation(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> target.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> target.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    fun dp(value: Int, density: Float): Int = (value * density).toInt()

    fun layoutParams(wc: Int, hc: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(wc, hc)

    fun statusDot(context: android.content.Context, color: Int): View {
        val size = Ui.dp(10, context.resources.displayMetrics.density)
        return View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_VERTICAL }
        }
    }

    fun logTextView(context: android.content.Context): TextView = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setTextColor(primaryText(context))
        setPadding(0, 0, 0, 0)
        setTextIsSelectable(true)
    }

    fun entryButton(
        context: android.content.Context,
        title: String,
        subtitle: String,
        badge: String,
        colorHex: String,
        imageRes: Int? = null,
        onClick: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20, density), dp(18, density), dp(20, density), dp(18, density))
            background = glassButton(context)
            elevation = dp(5, density).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        pressAnimation(row)
        val icon: View = if (imageRes != null) ImageView(context).apply {
            setImageResource(imageRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14, density).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(48, density), dp(48, density))
        } else TextView(context).apply {
            text = badge
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
            }
            layoutParams = LinearLayout.LayoutParams(dp(48, density), dp(48, density))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16, density)
            }
        }
        column.addView(TextView(context).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText(context))
        })
        column.addView(TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(secondaryText(context))
        })
        row.addView(icon)
        row.addView(column)
        row.addView(TextView(context).apply {
            text = "›"
            textSize = 22f
            setTextColor(secondaryText(context))
        })
        return row
    }
}
