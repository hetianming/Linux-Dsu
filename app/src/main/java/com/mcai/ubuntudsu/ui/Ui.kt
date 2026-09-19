package com.mcai.ubuntudsu.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.app.Activity
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.content.res.Configuration
import android.animation.ValueAnimator
import android.os.Build
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 全局设计系统：拟态方案（Neumorphism）+ 液态玻璃（Liquid Glass）渲染架构
 *
 * 设计语言
 * ─ 日间（图1）：柔和蓝灰液体渐变背景，元素自背景「挤出」——
 *   玻璃半透明填充 + 左上白色高光内环 + 右下蓝灰阴影外环，真实彩色投影（API28+）
 * ─ 夜间（图2）：深海军蓝液体渐变背景，深色玻璃卡片 + 霓虹青蓝描边发光，
 *   投影转为深蓝环境光 + accent 点光
 *
 * 全部界面（MainActivity 各页 / 下载管理 / Onboarding / 终端 / 文件管理 / VNC 等）
 * 统一经由本对象取色与取形，保证全局一致。
 */
object Ui {
    private const val MAX_CORNER_RADIUS_DP = 32f

    // ==================== 拟态调色板 ====================

    /** 日间拟态调色板（图1） */
    private object Day {
        const val BG = 0xFFE7EDF6.toInt()
        const val CARD_TOP = 0xEBFFFFFF.toInt()      // 玻璃填充顶部（高不透明白）
        const val CARD_MID = 0xCCF7FAFE.toInt()
        const val CARD_BOTTOM = 0xA8E8EFFA.toInt()   // 底部微透，透出液体背景
        const val SHADOW_RING = 0x66A9BBD6.toInt()   // 右下阴影外环（蓝灰）
        const val HIGHLIGHT_RING = 0xC8FFFFFF.toInt() // 左上高光内环（白）
        const val TRACK = 0xFFD9E2F0.toInt()         // 凹陷轨道底色
        const val PRIMARY = 0xFF2F7CF6.toInt()
        const val PRIMARY_TOP = 0xFF5EA0FF.toInt()
        const val PRIMARY_BOTTOM = 0xFF2F6BF0.toInt()
    }

    /** 夜间拟态调色板（雾霾蓝垂直弥散 · 微光 · 磨砂玻璃） */
    private object Night {
        const val BG = 0xFF28313F.toInt()            // 雾霾蓝暗调（背景基准色）
        const val CARD_TOP = 0x5F262236.toInt()      // 磨砂玻璃填充（微透背景光斑）
        const val CARD_MID = 0x46212032.toInt()
        const val CARD_BOTTOM = 0x301C1B29.toInt()
        const val SHADOW_RING = 0x8C0B0A14.toInt()   // 深黑紫阴影外环
        const val HIGHLIGHT_RING = 0x42C9BCFF.toInt() // 淡青白高光内环
        const val NEON_EDGE = 0x389B9CF6.toInt()     // 淡紫霓虹外描边
        const val TRACK = 0xFF1E1B28.toInt()
        const val PRIMARY = 0xFF4D9FFF.toInt()
        const val PRIMARY_TOP = 0xFF62A8FF.toInt()
        const val PRIMARY_BOTTOM = 0xFF2E6CF0.toInt()
    }

    private fun cardTop(c: android.content.Context) = if (isDark(c)) Night.CARD_TOP else Day.CARD_TOP
    private fun cardMid(c: android.content.Context) = if (isDark(c)) Night.CARD_MID else Day.CARD_MID
    private fun cardBottom(c: android.content.Context) = if (isDark(c)) Night.CARD_BOTTOM else Day.CARD_BOTTOM
    private fun shadowRing(c: android.content.Context) = if (isDark(c)) Night.SHADOW_RING else Day.SHADOW_RING
    private fun highlightRing(c: android.content.Context) = if (isDark(c)) Night.HIGHLIGHT_RING else Day.HIGHLIGHT_RING

    fun isDark(context: android.content.Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun enableEdgeToEdge(activity: Activity, content: View) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    fun background(context: android.content.Context): Int = if (isDark(context)) Night.BG else Day.BG

    /** 液态玻璃背景：夜间 = 雾霾蓝垂直弥散（上暗下浅、纯色渐变）；日间 = 蓝灰斜向渐变 */
    fun liquidBackground(context: android.content.Context): GradientDrawable {
        val dark = isDark(context)
        return GradientDrawable(
            if (dark) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.TL_BR,
            if (dark) {
                intArrayOf(Color.rgb(40, 49, 63), Color.rgb(60, 74, 94), Color.rgb(86, 103, 126))
            } else {
                intArrayOf(Color.rgb(237, 242, 250), Color.rgb(226, 234, 245), Color.rgb(216, 227, 242))
            },
        )
    }

    /**
     * 全屏动态液体背景：
     * - 日间：9 秒呼吸渐变，拟态元素由此「生长」出来
     * - 夜间：雾霾蓝垂直弥散——上半深灰蓝（雾霾蓝暗调）向下渐亮至浅雾蓝，
     *   低饱和、无纹理的纯色渐变，仅保留 12 秒极轻的明度呼吸（静谧感）
     */
    fun animateLiquidBackground(view: View) {
        val context = view.context
        if (isDark(context)) {
            val start = intArrayOf(Color.rgb(40, 49, 63), Color.rgb(60, 74, 94), Color.rgb(86, 103, 126))
            val end = intArrayOf(Color.rgb(44, 54, 69), Color.rgb(64, 80, 100), Color.rgb(91, 110, 134))
            val drawable = liquidBackground(context)
            view.background = drawable
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 12000L
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
            return
        }
        val start = intArrayOf(Color.rgb(237, 242, 250), Color.rgb(226, 234, 245), Color.rgb(216, 227, 242))
        val end = intArrayOf(Color.rgb(242, 246, 252), Color.rgb(222, 232, 244), Color.rgb(207, 221, 236))
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

    // ==================== 基础色板（全局取色入口） ====================

    fun surface(context: android.content.Context): Int = if (isDark(context)) Color.rgb(30, 28, 41) else Color.WHITE
    fun surfaceMuted(context: android.content.Context): Int = if (isDark(context)) Color.rgb(26, 24, 37) else Color.parseColor("#EDF1F8")
    fun dsuCard(context: android.content.Context): Int = if (isDark(context)) Color.rgb(30, 28, 41) else Color.WHITE
    fun dsuEntry(context: android.content.Context): Int = if (isDark(context)) Color.rgb(26, 24, 37) else Color.parseColor("#EDF1F8")
    fun border(context: android.content.Context): Int = if (isDark(context)) Color.argb(90, 139, 130, 246) else Color.parseColor("#C4D2E6")
    fun primaryText(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#EDEBF6") else Color.parseColor("#1B2A41")
    fun secondaryText(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#9A94B8") else Color.parseColor("#5C6F8A")
    fun buttonPrimary(context: android.content.Context): Int = if (isDark(context)) Night.PRIMARY else Day.PRIMARY
    fun buttonSecondary(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#8172C4") else Color.parseColor("#355CC9")
    fun buttonSuccess(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#4ADE80") else Color.parseColor("#16A34A")
    fun buttonWarning(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#FBBF24") else Color.parseColor("#D97706")
    fun buttonDanger(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#F87171") else Color.parseColor("#DC2626")
    fun buttonText(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#F4F1FA") else Color.parseColor("#1E2C42")

    // ==================== 拟态核心原语 ====================

    /**
     * 拟态卡片（凸起）：液态玻璃填充 + 左上高光内环 + 右下阴影外环
     * 夜间附加 accent 霓虹外描边（发光）。这是全局一切卡片/按钮的视觉基座。
     */
    fun neuCard(context: android.content.Context, radiusDp: Float = 20f, accent: Int? = null): Drawable {
        val density = context.resources.displayMetrics.density
        val dark = isDark(context)
        val radius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
        val layers = mutableListOf<Drawable>()

        // L0 右下阴影外环（较粗、低透明度——拟态阴影边缘）
        layers += GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius + density
            setColor(Color.TRANSPARENT)
            setStroke(dp(2, density), shadowRing(context))
        }

        // L1 液态玻璃填充（垂直渐变，透出动态背景）
        layers += GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(cardTop(context), cardMid(context), cardBottom(context)),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }

        // L2 左上高光内环（受光边）
        layers += GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius - density * 0.5f
            setColor(Color.TRANSPARENT)
            setStroke(dp(1, density), highlightRing(context))
        }

        // L3（夜间）accent 霓虹外描边：图2 夜间发光质感
        if (dark) {
            val neon = accent?.let {
                Color.argb(60, Color.red(it), Color.green(it), Color.blue(it))
            } ?: Night.NEON_EDGE
            layers += GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius + density * 1.5f
                setColor(Color.TRANSPARENT)
                setStroke(dp(1, density), neon)
            }
        }

        return LayerDrawable(layers.toTypedArray())
    }

    /**
     * 拟态凹陷容器：按压进入的槽位（输入框底、进度轨道、次级信息槽）
     * 内阴影环 + 深一档的填充
     */
    fun neuInset(context: android.content.Context, radiusDp: Float = 14f): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val dark = isDark(context)
        val radius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (dark) {
                intArrayOf(Color.argb(200, 15, 14, 24), Color.argb(160, 21, 19, 32))
            } else {
                intArrayOf(Color.argb(255, 214, 224, 240), Color.argb(255, 224, 232, 246))
            },
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            // 内阴影：上深下亮的内缘错觉
            setStroke(dp(1, density), if (dark) Color.argb(110, 7, 6, 13) else Color.argb(140, 176, 192, 216))
        }
    }

    /**
     * 拟态实心渐变按钮：accent 渐变填充 + 顶部高光内环 + 右下深色外环
     * 用于主操作（开始下载 / 安装等强动作）
     */
    fun neuSolidButton(top: Int, bottom: Int, radiusDp: Float = 14f, context: android.content.Context? = null): Drawable {
        val density = (context?.resources?.displayMetrics?.density) ?: 2.5f
        val radius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
        return LayerDrawable(
            arrayOf(
                // 阴影外环
                GradientDrawable().apply {
                    cornerRadius = radius + density
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2, density), Color.argb(96, Color.red(bottom) / 3, Color.green(bottom) / 3, Color.blue(bottom) / 3))
                },
                // 渐变填充
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(top, bottom)).apply {
                    cornerRadius = radius
                },
                // 顶部高光
                GradientDrawable().apply {
                    cornerRadius = radius - density * 0.5f
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1, density), Color.argb(120, 255, 255, 255))
                },
            ),
        )
    }

    /**
     * 拟态轮廓：仅设置圆角 outline（供裁剪/涟漪用），不再产生 elevation 投影。
     * 拟态的「浮起感」由 neuCard 高光/阴影双环描边承担，全局零投影更干净。
     */
    fun applyNeuShadow(view: View, elevationDp: Float, cornerRadiusDp: Float = 16f, accent: Int? = null) {
        val density = view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, cornerRadiusDp * density)
            }
        }
    }

    // ==================== 通用形状 ====================

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

    // ==================== 兼容原语（路由到拟态系统） ====================

    /** 拟态凸起按钮：玻璃填充 + 高光/阴影双环 + 涟漪（全局按钮基座） */
    fun glassButton(context: android.content.Context, accent: Int? = null): RippleDrawable {
        val content = neuCard(context, 16f, accent)
        val rippleColor = accent?.let {
            Color.argb(70, Color.red(it), Color.green(it), Color.blue(it))
        } ?: if (isDark(context)) Color.argb(60, 111, 168, 255) else Color.argb(50, 47, 124, 246)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)
    }

    /** 拟态卡片（原玻璃表面 → 全局拟态卡片） */
    fun glassSurface(context: android.content.Context, radiusDp: Float = 22f): Drawable =
        neuCard(context, radiusDp)

    /** 高模糊磨砂面板：更不透明的拟态卡片，用于小窗口等需要强遮挡的场景 */
    fun frostedSurface(
        context: android.content.Context,
        radiusDp: Float = 24f,
        stroke: Boolean = true,
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val dark = isDark(context)
        val radius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
        val layers = mutableListOf<Drawable>()
        if (stroke) {
            layers += GradientDrawable().apply {
                cornerRadius = radius + density
                setColor(Color.TRANSPARENT)
                setStroke(dp(2, density), shadowRing(context))
            }
        }
        layers += GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (dark) {
                intArrayOf(Color.argb(244, 28, 26, 40), Color.argb(242, 25, 23, 36), Color.argb(242, 21, 20, 31))
            } else {
                intArrayOf(Color.argb(246, 251, 253, 255), Color.argb(244, 246, 249, 253), Color.argb(244, 240, 245, 251))
            },
        ).apply {
            cornerRadius = radius
        }
        if (stroke) {
            layers += GradientDrawable().apply {
                cornerRadius = radius - density * 0.5f
                setColor(Color.TRANSPARENT)
                setStroke(dp(1, density), highlightRing(context))
            }
            if (dark) {
                layers += GradientDrawable().apply {
                    cornerRadius = radius + density * 1.5f
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1, density), Night.NEON_EDGE)
                }
            }
        }
        return LayerDrawable(layers.toTypedArray())
    }

    /** 水晶玻璃分隔条：拼接卡内分区之间的横向高光玻璃线 */
    fun crystalDivider(context: android.content.Context, density: Float): View {
        val dark = isDark(context)
        val track = if (dark) Color.argb(46, 111, 168, 255) else Color.argb(140, 214, 228, 248)
        val highlight = if (dark) Color.argb(110, 168, 214, 255) else Color.argb(220, 255, 255, 255)
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

    // 页面标题行右侧的关于入口图标：与 ROOT 徽章同款底，点击弹设置/关于弹层
    fun settingsIconButton(activity: android.app.Activity, onPick: () -> Unit): View =
        ImageView(activity).apply {
            setImageResource(com.mcai.ubuntudsu.R.drawable.ic_info)
            imageTintList = android.content.res.ColorStateList.valueOf(secondaryText(activity))
            background = neuInset(activity, 9f)
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
            // 版本号动态读取（与 build.gradle.kts versionName 同步）
            val ver = runCatching {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
            }.getOrNull() ?: "--"
            text = "版本 v$ver  ·  天明构建  ·  Copyright © 2026"
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
                "· Linux ARM® 架构：Chroot 方式安装运行 Ubuntu rootfs（本地 / 云端镜像），root 权限直通\n" +
                "· 容器终端：Termux 风格 Chroot 终端，支持 apt 安装软件包\n" +
                "· 桌面：XFCE / KDE / GNOME + VNC 远程桌面与音频桥接\n" +
                "· 文件管理：内置 rootfs 文件浏览器，支持编辑 / 重命名 / 新建删除\n" +
                "· 下载管理：多任务并行下载，断点续传\n" +
                "· 进程管理：/proc 双点采样实测 CPU / 内存 / 后台耗电，任务栏后台应用一览\n" +
                "· 检查更新：GitHub Releases 在线检测新版本，下载安装一步完成"
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
                android.view.MotionEvent.ACTION_DOWN -> {
                    target.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    target.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        Haptics.performImmediate(target)
                    }
                }
            }
            false
        }
    }

    /**
     * 全局触摸分发器：在 Activity 的 dispatchTouchEvent 中调用
     * 自动对可点击 View 在 ACTION_UP 时触发震动反馈
     */
    fun dispatchHaptic(root: View?, event: MotionEvent) {
        Haptics.onTouch(root, event)
    }

    fun dp(value: Int, density: Float): Int = (value * density).toInt()

    fun layoutParams(wc: Int, hc: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(wc, hc)

    fun statusDot(context: android.content.Context, color: Int): View {
        val size = Ui.dp(10, context.resources.displayMetrics.density)
        val density = context.resources.displayMetrics.density
        val dark = isDark(context)
        return View(context).apply {
            // 发光状态点：中心实心 + 外圈光晕（夜间更亮）
            background = LayerDrawable(
                arrayOf(
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(if (dark) 70 else 46, Color.red(color), Color.green(color), Color.blue(color)))
                    },
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke(dp(1, density), Color.argb(110, 255, 255, 255))
                    },
                ),
            ).apply {
                setLayerSize(0, size, size)
                setLayerSize(1, dp(6, density), dp(6, density))
                setLayerGravity(1, Gravity.CENTER)
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

    /** 拟态入口行：凸起卡片 + 彩色投影 + 图标凹槽（全局列表入口基座） */
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
        val accent = runCatching { Color.parseColor(colorHex) }.getOrDefault(buttonPrimary(context))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14, density), dp(11, density), dp(14, density), dp(11, density))
            background = glassButton(context)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        applyNeuShadow(row, 4f, 16f, accent)
        pressAnimation(row)
        val icon: View = if (imageRes != null) LinearLayout(context).apply {
            // 图标凹槽：拟态凹陷底座 + 细描边
            if (framed) {
                background = neuInset(context, 11f)
                setPadding(dp(3, density), dp(3, density), dp(3, density), dp(3, density))
            }
            gravity = Gravity.CENTER
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
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
            background = LayerDrawable(
                arrayOf(
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    },
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.argb(255, (Color.red(accent) * 0.75f + 255 * 0.25f).toInt(), (Color.green(accent) * 0.75f + 255 * 0.25f).toInt(), (Color.blue(accent) * 0.75f + 255 * 0.25f).toInt()),
                            accent,
                        ),
                    ).apply {
                        shape = GradientDrawable.OVAL
                    },
                ),
            )
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

    /**
     * 拟态大图标入口方块：无框大图标 + 标题 + 副标题（Linux / DSU 页网格入口基座）
     * 一排两个往下排的宫格样式，零投影，浮起感由卡片双环描边承担
     */
    fun iconTile(
        context: android.content.Context,
        title: String,
        subtitle: String,
        imageRes: Int,
        accent: Int? = null,
        iconTint: Int? = null,
        onClick: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8, density), dp(10, density), dp(8, density), dp(9, density))
            background = glassButton(context, accent)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            // 无框大图标：去掉凹槽底座，图标本体直接放大
            addView(ImageView(context).apply {
                setImageResource(imageRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                iconTint?.let { imageTintList = android.content.res.ColorStateList.valueOf(it) }
                layoutParams = LinearLayout.LayoutParams(dp(48, density), dp(48, density))
            })
            addView(TextView(context).apply {
                text = title
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primaryText(context))
                gravity = Gravity.CENTER
                setPadding(0, dp(6, density), 0, 0)
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 9.5f
                setTextColor(secondaryText(context))
                gravity = Gravity.CENTER
                setPadding(0, dp(1, density), 0, 0)
            })
        }
        pressAnimation(tile)
        return tile
    }

    /**
     * 拟态胶囊进度条：凹陷轨道（内阴影环）+ 渐变填充（顶部高光）
     */
    fun pillProgressDrawable(context: android.content.Context): Drawable {
        val density = context.resources.displayMetrics.density
        val dark = isDark(context)
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99, density).toFloat()
            if (dark) {
                setColor(Night.TRACK)
                setStroke(dp(1, density), Color.argb(120, 8, 7, 14))
            } else {
                setColor(Day.TRACK)
                setStroke(dp(1, density), Color.argb(120, 176, 192, 216))
            }
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
