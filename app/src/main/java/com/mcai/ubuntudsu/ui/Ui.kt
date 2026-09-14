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
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !isDark(activity)
            isAppearanceLightNavigationBars = !isDark(activity)
        }
    }

    // 背景铺满全屏，内容避让 systemBars 并留出呼吸间距：给页面容器（通常是 ScrollView）挂 insets 监听
    fun applyContentInsets(view: View, extraTopDp: Int = 12, extraBottomDp: Int = 0) {
        val density = view.resources.displayMetrics.density
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + dp(extraTopDp, density), v.paddingRight, baseBottom + dp(extraBottomDp, density))
            insets
        }
        // 动态添加的页面不会经历首次 insets 遍历，attach 后主动请求一次分发
        if (view.isAttachedToWindow) {
            ViewCompat.requestApplyInsets(view)
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    ViewCompat.requestApplyInsets(v)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
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

    // 高模糊磨砂面板：比 glassSurface 更不透明，用于小窗口等需要强遮挡的场景
    // stroke=false 时无外层描边（如文件管理器底部工具条）
    fun frostedSurface(
        context: android.content.Context,
        radiusDp: Float = 24f,
        stroke: Boolean = true,
    ): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val dark = isDark(context)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                if (dark) Color.argb(240, 33, 40, 52) else Color.argb(242, 250, 252, 255),
                if (dark) Color.argb(238, 28, 35, 46) else Color.argb(240, 244, 248, 252),
                if (dark) Color.argb(238, 24, 30, 40) else Color.argb(240, 240, 245, 250),
            ),
        ).apply {
            cornerRadius = dp(radiusDp.toInt(), density).toFloat()
            if (stroke) {
                setStroke(dp(1, density), if (dark) Color.argb(120, 255, 255, 255) else Color.argb(200, 210, 224, 240))
            }
        }
    }

    // 水晶玻璃分隔条：拼接卡内分区之间的横向高光玻璃线
    fun crystalDivider(context: android.content.Context, density: Float): View {
        val dark = isDark(context)
        val track = if (dark) Color.argb(56, 255, 255, 255) else Color.argb(150, 255, 255, 255)
        val highlight = if (dark) Color.argb(140, 235, 245, 255) else Color.argb(230, 255, 255, 255)
        return View(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2, density),
            )
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(track, highlight, track)).apply {
                cornerRadius = dp(1, density).toFloat()
            }
        }
    }

    // 页面标题行右侧的设置入口图标：与 ROOT 徽章同款底，点击弹主题选择
    fun settingsIconButton(activity: android.app.Activity, onPick: () -> Unit): View =
        ImageView(activity).apply {
            setImageResource(com.mcai.ubuntudsu.R.drawable.ic_settings)
            imageTintList = android.content.res.ColorStateList.valueOf(secondaryText(activity))
            background = rounded(
                if (isDark(activity)) Color.argb(68, 0, 0, 0) else Color.argb(78, 255, 255, 255),
                8f,
                activity.resources.displayMetrics.density,
            )
            val d = activity.resources.displayMetrics.density
            setPadding(dp(5, d), dp(5, d), dp(5, d), dp(5, d))
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(28, d), dp(28, d)).apply {
                marginStart = dp(6, d)
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            }
            pressAnimation(this)
            setOnClickListener { onPick() }
        }

    // 设置弹层（各页设置入口共用）：关于信息（主题切换入口在「更多」页）
    fun showThemeDialog(activity: android.app.Activity, onThemeChanged: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        val aboutPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20, density), dp(4, density), dp(20, density), dp(6, density))
        }
        aboutPanel.addView(TextView(activity).apply {
            text = "关于"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText(activity))
        })
        aboutPanel.addView(TextView(activity).apply {
            text = "Linux - Dsu"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText(activity))
            setPadding(0, dp(6, density), 0, dp(2, density))
        })
        aboutPanel.addView(TextView(activity).apply {
            text = "版本 1.0  ·  天明构建  ·  Copyright © 2026"
            textSize = 11f
            setTextColor(secondaryText(activity))
            setPadding(0, 0, 0, dp(6, density))
        })
        aboutPanel.addView(TextView(activity).apply {
            text = "功能简介"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText(activity))
            setPadding(0, 0, 0, dp(3, density))
        })
        aboutPanel.addView(TextView(activity).apply {
            text = "· DSU：通过 ROOT 调用系统 dynamic_system 服务安装 GSI 镜像，支持自定义 userdata 容量、一键重启进入\n" +
                "· Linux：Chroot 方式安装运行 Ubuntu rootfs（本地 / TUNA 云端镜像），root 权限直通\n" +
                "· 终端：Termux 风格 Chroot 终端，支持 apt 安装软件包\n" +
                "· 桌面：XFCE / KDE / GNOME + VNC 远程桌面与音频桥接\n" +
                "· 文件管理：内置 rootfs 文件浏览器，支持编辑 / 重命名 / 新建删除"
            textSize = 11f
            setTextColor(secondaryText(activity))
            setLineSpacing(dp(3, density).toFloat(), 1f)
        })
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("设置")
            .setView(aboutPanel)
            .setPositiveButton("关闭", null)
            .show()
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
        framed: Boolean = true,
        onClick: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14, density), dp(11, density), dp(14, density), dp(11, density))
            background = glassButton(context)
            elevation = dp(3, density).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        pressAnimation(row)
        val icon: View = if (imageRes != null) LinearLayout(context).apply {
            // 图标容器：默认圆角玻璃底 + 细描边，内衬图标 FIT_CENTER；framed=false 时裸图无边框
            if (framed) {
                background = strokeRounded(
                    if (isDark(context)) Color.argb(46, 255, 255, 255) else Color.argb(235, 255, 255, 255),
                    if (isDark(context)) Color.argb(150, 255, 255, 255) else Color.argb(200, 255, 255, 255),
                    1.5f,
                    density,
                    radiusDp = 11f,
                )
                setPadding(dp(2, density), dp(2, density), dp(2, density), dp(2, density))
            }
            gravity = Gravity.CENTER
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(11, density).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(36, density), dp(36, density))
            addView(ImageView(context).apply {
                setImageResource(imageRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
            })
        } else TextView(context).apply {
            text = badge
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
            }
            layoutParams = LinearLayout.LayoutParams(dp(36, density), dp(36, density))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10, density)
            }
        }
        column.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText(context))
        })
        column.addView(TextView(context).apply {
            text = subtitle
            textSize = 11f
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

    // 胶囊进度条样式：圆角轨道（低饱和实底、无描边）+ 渐变绿胶囊填充（配合下方居中百分比文字）
    fun pillProgressDrawable(context: android.content.Context): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        val dark = isDark(context)
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99, density).toFloat()
            setColor(if (dark) Color.parseColor("#39404C") else Color.parseColor("#DFE6EF"))
        }
        // 渐变绿：亮薄荷绿 -> 翠绿，横向过渡
        val fill = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#7CE8B5"), Color.parseColor("#2BB673")),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99, density).toFloat()
        }
        val clip = android.graphics.drawable.ClipDrawable(
            fill,
            Gravity.START,
            android.graphics.drawable.ClipDrawable.HORIZONTAL,
        )
        return android.graphics.drawable.LayerDrawable(arrayOf(track, clip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    // 进度条下方居中的百分比文字
    fun percentTextView(context: android.content.Context): TextView = TextView(context).apply {
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(primaryText(context))
    }

    // 不确定进度时的来回扫动动画（自定义 drawable 无系统 indeterminate 动画，用扫动模拟）
    fun scanAnimator(bar: android.widget.ProgressBar): ValueAnimator = ValueAnimator.ofInt(0, bar.max).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { bar.progress = it.animatedValue as Int }
    }
}
