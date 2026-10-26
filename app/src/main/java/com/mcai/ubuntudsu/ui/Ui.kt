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
import android.view.ViewGroup
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
    val typeface = Typeface.SANS_SERIF

    // ==================== 拟态调色板 ====================

    /** 日间拟态调色板（Neumorphism 基准：纯色 #E0E5EC 底 + 柔和双影） */
    private object Day {
        const val BG = 0xFFE0E5EC.toInt()
        const val CARD_TOP = 0xFFB7C6DA.toInt()      // 压暗一档蓝灰玻璃（不再纯白，配黑字 + 弥散光斑）
        const val CARD_MID = 0xFFAFBFCE.toInt()
        const val CARD_BOTTOM = 0xFFA6B5C6.toInt()
        const val SHADOW_RING = 0xFFA3B1C6.toInt()   // 右下深色投影（柔化）
        const val HIGHLIGHT_RING = 0xFFFFFFFF.toInt() // 左上高光
        const val TRACK = 0xFFD3DBE8.toInt()         // 凹陷轨道底色（深一档）
        const val PRIMARY = 0xFF2E5FB8.toInt()
        const val PRIMARY_TOP = 0xFF4A90E2.toInt()
        const val PRIMARY_BOTTOM = 0xFF2E5FB8.toInt()
    }

    /** 夜间拟态调色板（#2C3340 底 + 低光比双影，微霓虹外缘） */
    private object Night {
        const val BG = 0xFF222622.toInt()
        const val CARD_TOP = Color.TRANSPARENT      // 夜间卡片按钮保持通透，移除透明蓝底色
        const val CARD_MID = Color.TRANSPARENT
        const val CARD_BOTTOM = Color.TRANSPARENT
        const val SHADOW_RING = 0xFF0E121A.toInt()   // 右下深黑投影
        const val HIGHLIGHT_RING = 0x24FFFFFF.toInt() // 左上淡白高光
        const val NEON_EDGE = 0x33FFFFFF.toInt()     // 夜间微光外缘（提亮，保证暗卡边缘可见）
        const val TRACK = 0xFF20242E.toInt()
        const val PRIMARY = 0xFF3D4440.toInt()
        const val PRIMARY_TOP = 0xFF5E6A5E.toInt()
        const val PRIMARY_BOTTOM = 0xFF3D4440.toInt()
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
    // 键盘感知：键盘弹起时底部内边距取 ime.bottom，可视区收缩后 ScrollView 自动把聚焦输入框滚到键盘上方
    fun applyContentInsets(view: View, extraTopDp: Int = 12, extraBottomDp: Int = 0) {
        val density = view.resources.displayMetrics.density
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // API 30+ 由 ime insets 驱动；旧版本靠 manifest adjustResize 调整窗口，避免双重补偿
            val ime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsetsCompat.Type.ime())
            } else {
                androidx.core.graphics.Insets.NONE
            }
            v.setPadding(
                v.paddingLeft,
                bars.top + dp(extraTopDp, density),
                v.paddingRight,
                maxOf(baseBottom + dp(extraBottomDp, density), ime.bottom),
            )
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

    /** 纯色背景（新拟态：元素与背景同色系，日间 #E0E5EC / 夜间 #2C3340） */
    fun liquidBackground(context: android.content.Context): GradientDrawable {
        val dark = isDark(context)
        val c = if (dark) Color.rgb(44, 51, 64) else Color.rgb(224, 229, 236)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(c, c),
        )
    }

    /**
     * 全屏背景：动态弥散光斑（Liquid Backdrop）。
     * 底层蓝灰渐变 + 缓慢漂移的淡色光斑（弱化后），玻璃的折射感来自背景色彩流动。
     * Drawable 可见时自动播放动画。
     */
    fun animateLiquidBackground(view: View) {
        val d = com.mcai.ubuntudsu.ui.glass.BreathingBalloonDrawable(view.context, isDark(view.context))
        view.background = d
    }

    // ==================== 基础色板（全局取色入口） ====================

    fun surface(context: android.content.Context): Int = if (isDark(context)) Night.BG else Day.BG
    fun surfaceMuted(context: android.content.Context): Int = if (isDark(context)) Color.rgb(36, 42, 54) else Color.parseColor("#DCE3EE")
    /** 玻璃按钮日间填充：蓝灰玻璃（不随主题变化），夜间为深蓝玻璃 */
    fun surfaceGlass(context: android.content.Context): Int = if (isDark(context)) Color.TRANSPARENT else 0xFFB7C6DA.toInt()
    fun dsuCard(context: android.content.Context): Int = if (isDark(context)) Night.BG else Day.BG
    fun dsuEntry(context: android.content.Context): Int = if (isDark(context)) Color.rgb(36, 42, 54) else Color.parseColor("#DCE3EE")
    fun border(context: android.content.Context): Int = if (isDark(context)) Color.argb(50, 255, 255, 255) else Color.parseColor("#C4D2E6")
    fun primaryText(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#FFFFFF") else Color.parseColor("#141B26")
    fun secondaryText(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#EDF3FB") else Color.parseColor("#3B4A5E")
    fun buttonPrimary(context: android.content.Context): Int = if (isDark(context)) Night.PRIMARY else Day.PRIMARY
    fun buttonSecondary(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#5A6B58") else Color.parseColor("#7C8AA6")
    fun buttonSuccess(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#34B06A") else Color.parseColor("#1E8A60")
    fun buttonWarning(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#FBBF24") else Color.parseColor("#B57623")
    fun buttonDanger(context: android.content.Context): Int = if (isDark(context)) Color.parseColor("#F87171") else Color.parseColor("#C62828")
    fun buttonText(context: android.content.Context): Int = if (isDark(context)) Color.WHITE else Color.parseColor("#1A2434")

    /**
     * 弹框「确定」按钮文字色：夜间亮青蓝 / 日间深蓝。
     * 不能直接用 buttonPrimary —— 夜间它是深蓝，压在深色弹框底上几乎不可见。
     */
    fun dialogAction(context: android.content.Context): Int =
        if (isDark(context)) Color.parseColor("#7CC7FF") else Color.parseColor("#1F5FA8")

    /**
     * 统一弹框外观：背景跟随主题（深/浅），确定按钮高对比强调色，正文次要色。
     * 用于所有 AlertDialog，避免夜间出现「深底 + 深色按钮」「白底 + 白字」。
     */
    fun styleDialog(context: android.content.Context, dialog: androidx.appcompat.app.AlertDialog) {
        val d = context.resources.displayMetrics.density
        runCatching {
            dialog.window?.setBackgroundDrawable(rounded(dsuCard(context), 24f, d))
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(dialogAction(context))
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setTextColor(dialogAction(context))
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setTextColor(secondaryText(context))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(secondaryText(context))
        }
    }

    // ==================== DNA 工具界面专属色板（夜间模式适配） ====================

    /**
     * DNA 工具箱（Dna*Activity + DnaToolsPage）专用取色器。
     * 跟随 APP 全局日/夜间主题，与 APP 主体（MainActivity 各页 / 拟态卡片）完全一致，
     * 不再使用独立的暖色护眼主题。各 DNA 界面统一调用此对象取色。
     */
    class DnaPalette(val context: android.content.Context) {
        private val dark = isDark(context)

        /** 根背景渐变（顶→底）：昼 #E0E5EC 单色拟态底 / 夜 #2C3340 深蓝拟态底 */
        @JvmField val bgTop: Int = if (dark) Color.parseColor("#2C3340") else Color.parseColor("#E0E5EC")
        @JvmField val bgBottom: Int = if (dark) Color.parseColor("#232833") else Color.parseColor("#D5DEE9")

        /** 标题 / 主文字：对齐 APP primaryText 昼 #243244 夜 #EAF0F8 */
        @JvmField val title: Int = if (dark) Color.parseColor("#EAF0F8") else Color.parseColor("#243244")

        /** 副标签 / 次要文字：夜间提亮到 #D5DFEF，确保深色卡片上对比度充足 */
        @JvmField val subtitle: Int = if (dark) Color.parseColor("#D5DFEF") else Color.parseColor("#5C6A7C")

        /** 说明 / 正文：夜间提亮到 #D5DFEF */
        @JvmField val body: Int = if (dark) Color.parseColor("#D5DFEF") else Color.parseColor("#5C6A7C")

        /** 提示 / 占位文字：夜间 #C3CFE1（明显高于旧 #B8C4D6），昼间 #67768A */
        @JvmField val hint: Int = if (dark) Color.parseColor("#C3CFE1") else Color.parseColor("#67768A")

        /** 按钮文字 / 强调：对齐 APP buttonText 昼 #1A2434 夜 #FFFFFF */
        @JvmField val accent: Int = if (dark) Color.WHITE else Color.parseColor("#1A2434")

        /** 成功徽章（绿）夜间提亮 #74E3B2，保证深底可读 */
        @JvmField val success: Int = if (dark) Color.parseColor("#74E3B2") else Color.parseColor("#1f7d72")
        /** 警告徽章（琥珀）夜间提亮 #FFD28C */
        @JvmField val warning: Int = if (dark) Color.parseColor("#FFD28C") else Color.parseColor("#8a5a00")

        /** 危险 / 红字 夜间提亮 #FFA79D */
        @JvmField val danger: Int = if (dark) Color.parseColor("#FFA79D") else Color.parseColor("#a33b3b")

        /**
         * 玻璃卡片底色：夜间改为高不透明深蓝玻璃（约 94%），
         * 避免旧的 20% 白玻璃在深色背景上变成浅灰、导致白字不可读。
         */
        @JvmField val cardGlass: Int = if (dark) Color.argb(240, 26, 34, 46) else Color.argb(58, 255, 255, 255)

        /** 玻璃卡片描边：夜间 35% 白，昼间 40% 白 */
        @JvmField val cardStroke: Int = if (dark) Color.argb(89, 255, 255, 255) else Color.argb(102, 255, 255, 255)

        /** 内嵌输入框 / 胶囊底：夜间深色不透明，避免沿用浅色主题默认色导致文字看不清 */
        @JvmField val inset: Int = if (dark) Color.argb(204, 15, 21, 30) else Color.argb(140, 255, 255, 255)

        /** 日志 / 终端面板底 (白底蓝字，昼夜间统一) */
        @JvmField val logBg: Int = if (dark) Color.argb(230, 40, 48, 64) else Color.argb(230, 247, 251, 255)

        /** 玻璃胶囊 / 按钮底 (半透明玻璃，昼白玻璃 / 夜深玻璃) */
        @JvmField val glass: Int = if (dark) Color.argb(90, 58, 67, 85) else Color.argb(120, 255, 255, 255)

        /** 胶囊描边 保持白色高对比度 */
        @JvmField val glassStroke: Int = Color.argb(204, 255, 255, 255)

        fun bgGradient(): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(bgTop, bgBottom),
            )

        fun bgDrawable(): android.graphics.drawable.Drawable = bgGradient()
    }

    /** 取当前上下文的 DNA 色板（按 isDark 分流） */
    fun dnaPalette(context: android.content.Context): DnaPalette = DnaPalette(context)

    /**
     * DNA 页面统一玻璃卡片底：夜间为高不透明深蓝玻璃，昼间为浅白玻璃。
     * 所有 Dna*Activity 的 glassCard() 都应调用本方法，避免夜间出现「浅灰卡片 + 白字」的低对比。
     */
    fun dnaGlassCard(context: android.content.Context, radiusDp: Float = 18f): android.graphics.drawable.GradientDrawable {
        val p = dnaPalette(context)
        val d = context.resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(p.cardGlass)
            cornerRadius = radiusDp * d
            setStroke(maxOf(1, d.toInt()), p.cardStroke)
        }
    }


    // ==================== 拟态核心原语 ====================

    /** 真模糊投影：柔和双影（左上高光 + 右下阴影），用于卡片"浮起"质感 */
    fun neuSoftShadow(context: android.content.Context, radiusDp: Float = 16f): Drawable {
        val density = context.resources.displayMetrics.density
        val radius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
        // L0 右下深投影（粗描边低透明 = 柔和扩散，偏右下 inset）
        val shadow = GradientDrawable().apply {
            cornerRadius = radius + density * 1.5f
            setColor(Color.TRANSPARENT)
            setStroke(dp(5, density), shadowRing(context))
        }
        // L1 左上高光（偏左上 inset）
        val highlight = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(2, density), highlightRing(context))
        }
        // L2 填充（与背景同色系）
        val fill = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(cardTop(context), cardMid(context), cardBottom(context)),
        ).apply { cornerRadius = radius }
        val layers = arrayOf(fill, highlight, shadow)
        return LayerDrawable(layers).apply {
            // 绝对像素 inset：投影偏右下、高光偏左上，中间填充居中
            val insetPx = dp(5, density).toInt()
            setLayerInset(2, insetPx, insetPx, insetPx + dp(2, density).toInt(), insetPx + dp(2, density).toInt())
            setLayerInset(1, insetPx + dp(1, density).toInt(), insetPx - dp(1, density).toInt(), insetPx + dp(1, density).toInt(), insetPx - dp(1, density).toInt())
        }
    }

    /**
     * 拟态卡片（凸起）：填充 + 左上高光 + 右下柔和投影
     * 这是全局一切卡片/按钮的视觉基座。
     */
    fun neuCard(context: android.content.Context, radiusDp: Float = 20f, accent: Int? = null): Drawable {
        val density = context.resources.displayMetrics.density
        val dark = isDark(context)
        val radius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
        val layers = mutableListOf<Drawable>()

        // L1 填充：半透明玻璃，让背后的呼吸气球背景透进来（玻璃模糊透感）
        // 夜间给深色玻璃半透明底（不再全透明），保证卡内文字在背景图片上清晰可读
        val fillColors = if (dark) intArrayOf(
            0xD9161D2B.toInt(),  // 约 85% 深蓝玻璃
            0xD4111720.toInt(),
            0xD00D131B.toInt(),
        ) else intArrayOf(
            0xD9CDD9E8.toInt(),  // 约 85% 不透明白蓝玻璃
            0xD4C3D0E2.toInt(),
            0xD0BAC9DD.toInt(),
        )
        layers += GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            fillColors,
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }
        // L1.5 动态弥散光斑（卡片内部色彩流动，玻璃折射感）
        layers += com.mcai.ubuntudsu.ui.glass.CardGlowDrawable(dark)
        // L2 拟态玻璃渲染边框（玻璃描边，夜间亮、日间白）
        layers += GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius - density * 0.5f
            setColor(Color.TRANSPARENT)
            setStroke(dp(1, density), if (dark) 0x33FFFFFF.toInt() else 0x66FFFFFF.toInt())
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
                intArrayOf(Color.argb(200, 24, 28, 38), Color.argb(198, 32, 38, 50))
            } else {
                intArrayOf(Color.argb(200, 210, 219, 232), Color.argb(198, 222, 230, 242))
            },
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            // 内阴影：上深下亮的内缘错觉（新拟态柔化）
            setStroke(dp(1, density), if (dark) Color.argb(150, 6, 7, 12) else Color.argb(170, 163, 177, 198))
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
                // 柔和右下投影（粗描边低透明 = 新拟态柔影）
                GradientDrawable().apply {
                    cornerRadius = radius + density
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3, density), Color.argb(80, Color.red(bottom) / 4, Color.green(bottom) / 4, Color.blue(bottom) / 4))
                },
                // 渐变填充
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(top, bottom)).apply {
                    cornerRadius = radius
                },
                // 顶部高光
                GradientDrawable().apply {
                    cornerRadius = radius - density * 0.5f
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1, density), Color.argb(130, 255, 255, 255))
                },
            ),
        )
    }

    /**
     * 拟态轮廓：仅设置圆角 outline（供裁剪/涟漪用），不再产生 elevation 投影。
     * 拟态的「浮起感」由 neuCard 高光/阴影双环描边承担，全局零投影更干净。
     */
    fun applyNeuShadow(view: View, elevationDp: Float, cornerRadiusDp: Float = 16f, accent: Int? = null) {
        // 纯 outline 裁切（无系统 elevation 投影）：
        // 卡片立体感由 neuCard 的 L0 LayerDrawable 圆角柔和投影层承担，
        // 系统 ViewOutlineProvider + elevation 会在 View 矩形边界画直角黑影，故不启用。
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, cornerRadiusDp * view.resources.displayMetrics.density)
            }
        }
        view.clipToOutline = true
    }

    // ==================== 通用形状 ====================
    /** 拟态玻璃选择弹窗：标题 + 纵向玻璃按钮列表 + 取消，复用 frostedSurface/neuSolidButton 视觉语言 */
    fun showGlassChoiceDialog(
        activity: android.app.Activity,
        title: String,
        subtitle: String? = null,
        options: List<String>,
        onPick: (Int) -> Unit,
        onDismiss: (() -> Unit)? = null,
    ) {
        val density = activity.resources.displayMetrics.density
        val dark = isDark(activity)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = frostedSurface(activity, 24f)
            setPadding(dp(16, density), dp(16, density), dp(16, density), dp(12, density))
            val widthPx = (activity.resources.displayMetrics.widthPixels * 0.5f).toInt()
            layoutParams = android.view.ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        panel.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText(activity))
            setPadding(0, 0, 0, dp(2, density))
        })
        if (subtitle != null) panel.addView(TextView(activity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(secondaryText(activity))
            setPadding(0, 0, 0, dp(2, density))
        })
        panel.addView(View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                if (dark) Color.argb(46, 255, 255, 255) else Color.argb(140, 214, 228, 248),
                if (dark) Color.argb(110, 255, 255, 255) else Color.argb(220, 255, 255, 255),
                if (dark) Color.argb(46, 255, 255, 255) else Color.argb(140, 214, 228, 248),
            )).apply {
                cornerRadius = dp(1, density).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2, density)).apply {
                setMargins(0, dp(10, density), 0, dp(10, density))
            }
        })
        val cancelBtn = TextView(activity).apply {
            text = "取消"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(secondaryText(activity))
            background = neuInset(activity, 10f)
            setPadding(0, dp(8, density), 0, dp(8, density))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(6, density), 0, 0)
            }
            pressAnimation(this)
            visibility = View.GONE
        }
        panel.addView(cancelBtn)
        val dialog = android.app.Dialog(activity).apply {
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0.45f)
            setContentView(panel)
            setCanceledOnTouchOutside(true)
        }
        cancelBtn.setOnClickListener {
            dialog.dismiss()
            onDismiss?.invoke()
        }
        options.forEachIndexed { index, label ->
            val btn = TextView(activity).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) buttonText(activity) else primaryText(activity))
                background = neuSolidButton(
                    if (index == 0) Night.PRIMARY_TOP else (if (dark) Color.argb(120, 255, 255, 255) else Color.argb(255, 176, 190, 208)),
                    if (index == 0) Night.PRIMARY_BOTTOM else (if (dark) Color.argb(110, 255, 255, 255) else Color.argb(255, 168, 182, 200)),
                    12f, activity,
                )
                setPadding(0, dp(10, density), 0, dp(10, density))
                pressAnimation(this)
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            }
            btn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val bottom = if (index < options.lastIndex) dp(8, density) else 0
                setMargins(0, 0, 0, bottom)
            }
            panel.addView(btn, panel.childCount - 1)
        }
        cancelBtn.visibility = View.VISIBLE
        dialog.show()
    }

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
        } ?: if (isDark(context)) Color.argb(60, 255, 255, 255) else Color.argb(50, 47, 124, 246)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)
    }

    /**
     * 选项 chip 选中态：固定亮蓝渐变实心（夜间也醒目可读）。
     * 不能用主题主色 buttonPrimary —— 夜间它是暗灰绿 #3D4440，
     * 压在深色玻璃卡上几乎看不出选中、文字对比度也不够。
     */
    fun chipSelected(context: android.content.Context): Drawable =
        neuSolidButton(Day.PRIMARY_TOP, Day.PRIMARY_BOTTOM, 15f, context)

    /**
     * 浅色拟态按键：日间白玻璃填充 + 蓝灰高光/阴影双环 + 8dp 圆角（不随夜间主题变暗）。
     * 终端快捷栏专用——按键内放黑色文字，保证任何主题下都清晰可读。
     */
    fun lightGlassButton(context: android.content.Context, radiusDp: Float = 8f, accent: Int? = null): RippleDrawable {
        val density = context.resources.displayMetrics.density
        val radius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
        val layers = mutableListOf<Drawable>()
        // L0 右下阴影外环
        layers += GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius + density
            setColor(Color.TRANSPARENT)
            setStroke(dp(2, density), Color.argb(120, 169, 187, 214))
        }
        // L1 白玻璃填充（垂直渐变，日间基调，保证黑字可读）
        layers += GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFFFF, 0xFFF7FAFE.toInt(), 0xFFEAF2FA.toInt()),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }
        // L2 左上高光内环
        layers += GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius - density * 0.5f
            setColor(Color.TRANSPARENT)
            setStroke(dp(1, density), 0xC8FFFFFF.toInt())
        }
        // L3 accent 霓虹描边（激活态）
        accent?.let {
            val neon = Color.argb(90, Color.red(it), Color.green(it), Color.blue(it))
            layers += GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius + density * 1.5f
                setColor(Color.TRANSPARENT)
                setStroke(dp(1, density), neon)
            }
        }
        val content = LayerDrawable(layers.toTypedArray())
        val rippleColor = accent?.let {
            Color.argb(70, Color.red(it), Color.green(it), Color.blue(it))
        } ?: Color.argb(50, 47, 124, 246)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)
    }

    /** 拟态卡片（原玻璃表面 → 全局拟态卡片） */
    fun glassSurface(context: android.content.Context, radiusDp: Float = 22f): Drawable =
        neuCard(context, radiusDp)

    /**
     * 通用功能卡（2 列网格入口）：左侧品牌色圆角块 + 图标，右侧标题 + 可选副标题。
     * 「多功能」页与「Linux」页共用，保证全 APP 卡片语言一致；宽度由外层行内等分，高度建议 84dp。
     *
     * @param tint 品牌色（用于色块底/描边/涟漪）
     * @param whiteText true 时标题用白字 + 黑色描边阴影（壁纸背景场景）
     */
    fun featureCard(
        context: android.content.Context,
        iconRes: Int,
        tint: Int,
        title: String,
        subtitle: String? = null,
        whiteText: Boolean = false,
        dangerText: Boolean = false,
        onClick: (View) -> Unit,
    ): View {
        val d = context.resources.displayMetrics.density
        val tr = Color.red(tint)
        val tg = Color.green(tint)
        val tb = Color.blue(tint)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(12, d), dp(10, d), dp(12, d), dp(10, d))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, tr, tg, tb)),
                glassSurface(context, 18f),
                null,
            )
            setOnClickListener { onClick(this) }
        }
        pressAnimation(card)

        // 左侧：品牌色圆角块 + 图标
        val iconBox = android.widget.FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(if (isDark(context)) 64 else 34, tr, tg, tb))
                cornerRadius = dp(14, d).toFloat()
                setStroke(maxOf(1, dp(1, d)), Color.argb(if (isDark(context)) 140 else 110, tr, tg, tb))
            }
            layoutParams = LinearLayout.LayoutParams(dp(48, d), dp(48, d))
        }
        iconBox.addView(ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(28, d), dp(28, d)).apply {
                gravity = Gravity.CENTER
            }
        })
        card.addView(iconBox)

        // 右侧：标题 + 副标题
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12, d), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    when {
                        whiteText -> Color.WHITE
                        dangerText -> buttonDanger(context)
                        else -> primaryText(context)
                    }
                )
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (whiteText) setShadowLayer(5f, 0f, 0f, Color.BLACK)
            })
            if (!subtitle.isNullOrEmpty()) {
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(if (whiteText) Color.argb(220, 255, 255, 255) else secondaryText(context))
                    setPadding(0, dp(2, d), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    if (whiteText) setShadowLayer(4f, 0f, 0f, Color.BLACK)
                })
            }
        })

        return card
    }

    /**
     * 彩色渐变玻璃功能卡背景（移植参考项目 gsiGlassCard）：
     *  三层玻璃——品牌三色对角渐变底 + 白色菲涅尔描边层 + 顶部镜面高光带，
     *  外裹白色半透明涟漪，配 8dp 投影形成"彩色玻璃浮起"质感。
     * 传入 `colors` 为 [0]=左上, [1]=中, [2]=右下的三色（如参考的语义五色）。
     * 若 `accent` 为 null 用 `colors` 末色；否则用 accent 调涟漪。
     */
    fun gradientGlassCard(context: android.content.Context, colors: IntArray, accent: Int? = null, radiusDp: Float = 22f): Drawable {
        val density = context.resources.displayMetrics.density
        val radius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colors[0], colors[1], colors[2])).apply {
            cornerRadius = radius
        }
        val strokeLayer = GradientDrawable().apply {
            setColor(0x12000000)
            setStroke(Math.max(1, dp(1, density)), 0xCFFFFFFF.toInt())
            cornerRadius = radius - dp(1, density)
        }
        val highlight = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x59FFFFFF.toInt(), 0x00FFFFFF)).apply {
            cornerRadius = radius - dp(7, density)
        }
        val glass = LayerDrawable(
            arrayOf(base, strokeLayer, highlight),
        ).apply {
            setLayerInset(1, 1, 1, 1, 1)
            setLayerInset(2, dp(2, density), dp(1, density), dp(2, density), dp(18, density))
        }
        val rippleColor = 0x40FFFFFF
        return RippleDrawable(ColorStateList.valueOf(rippleColor), glass, null)
    }

    /** 导航玻璃舱背景（移植参考 navigation_glass_bg）：蓝灰玻璃 + 白描边 + 顶部高光带 */
    fun navGlassPanel(context: android.content.Context, radiusDp: Float = 32f): Drawable {
        val density = context.resources.displayMetrics.density
        val radius = radiusDp.coerceAtMost(MAX_CORNER_RADIUS_DP) * density
        val dark = isDark(context)
        val shadow = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (dark) 0x00000000 else 0x3827465C.toInt())
        }
        // 拟态玻璃渲染边框：夜间用亮霓虹外缘，保证暗玻璃上舱体边缘清晰
        val main = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (dark) intArrayOf(0x4CFFFFFF.toInt(), 0x26FFFFFF.toInt(), 0x1AFFFFFF.toInt())
            else intArrayOf(0x4CECF8FC.toInt(), 0x26CFE2EC.toInt(), 0x1AA7C0CF.toInt())).apply {
            cornerRadius = radius
            setStroke(dp(1, density), if (dark) 0x99FFFFFF.toInt() else 0x78F3FBFF.toInt())
        }
        val highlight = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x55FFFFFF.toInt(), 0x00FFFFFF)).apply {
            cornerRadius = radius - dp(4, density)
        }
        val layer = LayerDrawable(arrayOf(shadow, main, highlight))
        layer.setLayerInset(1, 0, dp(2, density), 0, 0)
        layer.setLayerInset(2, dp(3, density), dp(2, density), dp(3, density), dp(32, density))
        return layer
    }

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
                intArrayOf(Color.argb(235, 28, 26, 40), Color.argb(230, 25, 23, 36), Color.argb(230, 21, 20, 31))
            } else {
                intArrayOf(Color.argb(238, 251, 253, 255), Color.argb(232, 246, 249, 253), Color.argb(232, 240, 245, 251))
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
        val track = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(140, 214, 228, 248)
        val highlight = if (dark) Color.argb(110, 255, 255, 255) else Color.argb(220, 255, 255, 255)
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

    // 首页标题行关于弹层：仅展示关于信息（主题切换入口在「多功能」页全屏设置）
    fun showThemeDialog(activity: android.app.Activity, onThemeChanged: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        val aboutPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20, density), dp(6, density), dp(20, density), dp(8, density))
        }

        // 关于信息置顶：用主色绿显示标题与核心信息，避免默认蓝字与界面不协调
        aboutPanel.addView(TextView(activity).apply {
            text = "关于信息"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(buttonSuccess(activity))
            setPadding(0, dp(2, density), 0, dp(6, density))
        })
        aboutPanel.addView(TextView(activity).apply {
            text = "Linux - Dsu"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(buttonSuccess(activity))
            setPadding(0, 0, 0, dp(2, density))
        })
        aboutPanel.addView(TextView(activity).apply {
            val ver = runCatching {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
            }.getOrNull() ?: "--"
            text = "版本 v$ver  ·  天明构建  ·  Copyright © 2026"
            textSize = 11f
            setTextColor(secondaryText(activity))
            setPadding(0, 0, 0, dp(10, density))
        })

        aboutPanel.addView(TextView(activity).apply {
            text = "功能简介"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(buttonSuccess(activity))
            setPadding(0, 0, 0, dp(4, density))
        })
        aboutPanel.addView(TextView(activity).apply {
            text = "· DSU 管理：ROOT 调用 dynamic_system 服务安装 GSI 镜像，自定义 userdata 容量、清理旧缓存、一键重启进入\n" +
                "· Linux ARM® 架构：Chroot 安装运行 Ubuntu rootfs（本地 / 云端镜像），root 权限直通，可卸载还原\n" +
                "· 容器终端：Termux 风格 Chroot 终端，支持 apt 安装软件包\n" +
                "· 远程桌面：XFCE / KDE 桌面 + VNC 远程连接，音频桥接、分辨率自选、触控 / 轨迹板双指针模式\n" +
                "· ROM 固件：HyperOS 与 ColorOS / FlymeOS / realme UI 固件双源，设备品牌 / 机型筛选，版本号新到旧排序，内置 aria2c 下载与复制链接\n" +
                "· ROM 移植：DNA 工具箱分解 / 合成 SUPER、payload 提取、镜像格式互转，一站式移植开发\n" +
                "· 下载管理：多任务并行下载，断点续传，全部 / 下载中 / 已完成 / 已暂停分类\n" +
                "· 文件管理：内置 rootfs 文件浏览器，编辑 / 重命名 / 新建删除\n" +
                "· 进程管理：/proc 双点采样实测 CPU / 内存 / 后台耗电，后台应用一览\n" +
                "· OTG 刷机助手：检测 USB 设备 ADB / Fastboot 状态，刷机日志实时输出\n" +
                "· U 盘启动：本地制作 U 盘 IMG 镜像并虚拟 U 盘暴露给电脑\n" +
                "· 日历工时记：上下班打卡、日历月视图、工时与工资统计，支持补录与修改每天工时\n" +
                "· 检查更新：GitHub Release 在线检测，国内代理 API 兜底，下载线路测速切换并校验文件完整性"
            textSize = 11f
            setTextColor(secondaryText(activity))
            setLineSpacing(dp(3, density).toFloat(), 1f)
        })

        val scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = true
            addView(aboutPanel)
        }
        val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(scroll)
            .setPositiveButton("关闭", null)
        val dialog = builder.create()
        dialog.show()
        // 弹窗标题「关于信息」改为主色（替代默认蓝字）：取标题 TextView 重设颜色
        runCatching {
            val titleTv = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
            titleTv?.text = "关于信息"
            titleTv?.setTextColor(buttonSuccess(activity))
            titleTv?.setTextSize(16f)
            titleTv?.setTypeface(titleTv.typeface, Typeface.BOLD)
        }
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
    /**
     * 卡片级入口块：无背景大图标 + 标题 + 副标题。
     * 用于分区入口（Linux/DSU 页的大图标卡），图标直接悬浮不套背景。
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
            setPadding(dp(14, density), dp(12, density), dp(14, density), dp(12, density))
            background = neuCard(context, radiusDp = 20f, accent = accent)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            // 玻璃瓷砖：拟态玻璃渲染边框 + 内部弥散光斑，图标居中
            addView(ImageView(context).apply {                setImageResource(imageRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                iconTint?.let { imageTintList = android.content.res.ColorStateList.valueOf(it) }
                layoutParams = LinearLayout.LayoutParams(dp(44, density), dp(44, density))
            })
            addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primaryText(context))
                gravity = Gravity.CENTER
                setPadding(0, dp(6, density), 0, 0)
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 10f
                setTextColor(secondaryText(context))
                gravity = Gravity.CENTER
                setPadding(0, dp(2, density), 0, 0)
            })
        }
        applyNeuShadow(tile, 3f, 20f)
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
