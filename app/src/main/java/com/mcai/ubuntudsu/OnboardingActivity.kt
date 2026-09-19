package com.mcai.ubuntudsu

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mcai.ubuntudsu.ui.Ui

/**
 * DNA NEXT style onboarding flow — full-screen immersive setup wizard.
 *
 * Pages:
 *  0. Welcome     – pink-blue gradient background, centered combined logo, "TMUI OSv1.0", bottom circular arrow
 *  1. Agreement   – white card with user agreement, checkbox, continue button
 *  2. Permissions – notification / storage / root verification with switches
 *  3. Settings    – color source, dark/light mode, UI scale slider
 *  4. Done        – app icon, "TMUI OSv1.0", "设置完毕", "开始使用" button
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREF_ONBOARDING = "onboarding"
        private const val KEY_COMPLETED = "completed"
        private const val KEY_AGREED = "agreed"
        private const val KEY_UI_SCALE = "ui_scale"
        private const val PAGE_COUNT = 5

        fun isCompleted(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences(PREF_ONBOARDING, MODE_PRIVATE).getBoolean(KEY_COMPLETED, false)
    }

    private val pages = mutableListOf<View>()
    private var currentPage = 0
    private lateinit var pageContainer: FrameLayout
    private lateinit var indicatorContainer: LinearLayout
    private lateinit var rootLayout: FrameLayout
    private val indicatorDots = mutableListOf<View>()
    /** 欢迎页全屏动态彩虹背景层（挂 rootLayout，仅第 0 页显示） */
    private lateinit var rainbowFlow: RainbowFlowView

    // Swipe tracking
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var isSwiping = false

    // Animated background
    private var bgAnimator: ValueAnimator? = null
    private var bgDrawable: GradientDrawable? = null

    // State
    private var agreementChecked = false
    private var notificationGranted = false
    private var storageGranted = false
    private var usageAccessGranted = false
    private var rootVerified = false
    private var uiScale = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        if (isCompleted(this)) {
            goToMain()
            return
        }

        buildUi()
    }

    // ==================== UI Construction ====================

    private fun buildUi() {
        val d = resources.displayMetrics.density
        rootLayout = FrameLayout(this)

        // 1. Animated gradient background (full screen)
        setupGradientBackground(rootLayout)

        // 1.5 欢迎页动态阳光彩虹背景：独立全屏层（延伸到状态栏/导航栏下方），仅首页显示
        rainbowFlow = RainbowFlowView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        rootLayout.addView(rainbowFlow)

        // 2. Page container（接收 systemBars padding：内容避让，背景层保持全屏）
        pageContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        rootLayout.addView(pageContainer)

        // 3. Bottom indicators
        indicatorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        }
        for (i in 0 until PAGE_COUNT) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(6, d), Ui.dp(6, d)).apply {
                    marginStart = if (i == 0) 0 else Ui.dp(4, d)
                    marginEnd = if (i == PAGE_COUNT - 1) 0 else Ui.dp(4, d)
                }
                background = makeDotDrawable(false)
            }
            indicatorDots.add(dot)
            indicatorContainer.addView(dot)
        }
        rootLayout.addView(indicatorContainer)

        setContentView(rootLayout)
        Ui.enableEdgeToEdge(this, rootLayout)

        // Edge-to-edge insets：padding 落在 pageContainer / 指示器上，
        // rootLayout 自身不再留白，彩虹背景真正全屏（修复状态栏白条）
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            pageContainer.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            (indicatorContainer.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                bars.bottom + Ui.dp(40, d)
            indicatorContainer.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(rootLayout)

        showPage(0, animate = false)
    }

    private fun setupGradientBackground(root: FrameLayout) {
        // 全局拟态液态玻璃背景：与主界面同源的呼吸渐变（日间蓝灰 / 夜间深海军蓝）
        Ui.animateLiquidBackground(root)
    }

    // ==================== Page Builders ====================

    private fun createPage(index: Int): View = when (index) {
        0 -> createWelcomePage()
        1 -> createAgreementPage()
        2 -> createPermissionsPage()
        3 -> createEnvCheckPage()
        4 -> createDonePage()
        else -> createWelcomePage()
    }

    /**
     * Page 0: Welcome
     * Full-screen DNA NEXT style: animated gradient, colored logo, 
     * "BOX 🧰 TM®" rainbow text, circular arrow button with ripple + scale effect.
     */
    private fun createWelcomePage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        // 彩虹背景由 rootLayout 上的全屏 rainbowFlow 层提供（延伸到状态栏/导航栏下方）

        // Central content: logo + rainbow text — centered vertically, slightly above center
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL,
            ).apply {
                // Slightly above true center (~6% of screen height upward)
                topMargin = -Ui.dp(48, d)
            }
        }

        // Logo (8O.png colorful combined image)
        val logoSize = Ui.dp(140, d)
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo_combined)
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).apply {
                bottomMargin = Ui.dp(4, d)
            }
        })

        // "欢迎使用" — custom TextView with left-to-right rainbow gradient flowing animation
        val welcomeText = object : TextView(this) {
            private var gradientShader: android.graphics.LinearGradient? = null
            fun setGradientShader(shader: android.graphics.LinearGradient) {
                gradientShader = shader
                invalidate()
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                gradientShader?.let { paint.shader = it }
                super.onDraw(canvas)
            }
        }.apply {
            text = "欢迎使用"
            textSize = 40f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // 极淡阴影（radius=1.5f, dx=0, dy=1f），只做轻微浮起，不加深字色
            setShadowLayer(1.5f, 0f, 1f, Color.argb(50, 0, 0, 0))
        }
        content.addView(welcomeText)

        // Start flowing rainbow gradient animation after layout
        welcomeText.post {
            val paint = welcomeText.paint
            val textWidth = paint.measureText("欢迎使用")
            // 柔和彩虹（Material 400 级）：明快不深重，与浅色玻璃底协调
            val colors = intArrayOf(
                Color.parseColor("#FF8A80"),
                Color.parseColor("#FFB74D"),
                Color.parseColor("#FFD54F"),
                Color.parseColor("#81C784"),
                Color.parseColor("#4FC3F7"),
                Color.parseColor("#9575CD"),
                Color.parseColor("#F06292"),
                Color.parseColor("#FF8A80"),
            )
            val animator = ValueAnimator.ofFloat(0f, textWidth * 2).apply {
                duration = 4000L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    val offset = anim.animatedValue as Float
                    welcomeText.setGradientShader(android.graphics.LinearGradient(
                        -textWidth + offset, 0f,
                        textWidth + offset, 0f,
                        colors,
                        null,
                        android.graphics.Shader.TileMode.CLAMP
                    ))
                }
            }
            animator.start()
        }

        container.addView(content)

        // Bottom circular arrow button — position matching video (~100dp from bottom)
        val arrowBtnSize = Ui.dp(56, d)
        val arrowBtn = FrameLayout(this).apply {
            // 拟态玻璃圆钮：半透明玻璃 + 高光/阴影双环 + 涟漪
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 90, 160, 255)),
                Ui.neuCard(this@OnboardingActivity, 28f, Ui.buttonPrimary(this@OnboardingActivity)),
                null
            )
            // 拟态彩色投影
            Ui.applyNeuShadow(this, 7f, 28f, Ui.buttonPrimary(this@OnboardingActivity))
            layoutParams = FrameLayout.LayoutParams(arrowBtnSize, arrowBtnSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = Ui.dp(100, d)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // Scale down then up animation
                animate()
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(100)
                    .withEndAction {
                        animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                        goToNextPage()
                    }
                    .start()
            }
        }

        // Arrow icon
        arrowBtn.addView(TextView(this).apply {
            text = "→"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })

        container.addView(arrowBtn)
        return container
    }

    // Rainbow text: each character gets a different pastel color
    private fun applyRainbowText(tv: TextView) {
        val text = tv.text.toString()
        val spannable = SpannableString(text)
        val rainbowColors = intArrayOf(
            Color.rgb(255, 255, 255), // white
            Color.rgb(255, 182, 193), // pink
            Color.rgb(176, 196, 222), // steel blue
            Color.rgb(230, 190, 220), // light purple
            Color.rgb(255, 218, 185), // peach
            Color.rgb(174, 214, 241), // light blue
            Color.rgb(221, 160, 221), // plum
            Color.rgb(200, 230, 201), // mint
        )
        var colorIdx = 0
        for (i in text.indices) {
            val c = text[i]
            if (!c.isWhitespace() && c != '\uFE0F' && c != '\u200D') {
                spannable.setSpan(
                    ForegroundColorSpan(rainbowColors[colorIdx % rainbowColors.size]),
                    i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                colorIdx++
            }
        }
        tv.text = spannable
    }

    // Wave animation: smooth color gradient cycle using ArgbEvaluator
    // Red -> Blue -> Green -> Red, 4s cycle
    private var waveAnimator: ValueAnimator? = null
    private fun startWaveAnimation(tv: TextView, colors: IntArray) {
        val evaluator = android.animation.ArgbEvaluator()
        waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                // Cycle: Red(0.0) -> Blue(0.33) -> Green(0.66) -> Red(1.0)
                val color = when {
                    fraction < 0.33f -> evaluator.evaluate(fraction / 0.33f, Color.RED, Color.BLUE) as Int
                    fraction < 0.66f -> evaluator.evaluate((fraction - 0.33f) / 0.33f, Color.BLUE, Color.GREEN) as Int
                    else -> evaluator.evaluate((fraction - 0.66f) / 0.34f, Color.GREEN, Color.RED) as Int
                }
                tv.setTextColor(color)
            }
        }
        waveAnimator?.start()
    }

    /**
     * 引导首页专属背景：动态线性"阳光彩虹"渐变。
     * 斜向 LinearGradient 沿轴向无限流动（首尾同色 + REPEAT 保证无缝循环），
     * 叠加两团缓慢漂移的日光光晕，营造阳光穿过棱镜的柔和氛围。
     * 视图 detach 时自动取消动画，不耗电。
     */
    private class RainbowFlowView(context: android.content.Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val matrix = android.graphics.Matrix()
        private var phase = 0f
        private var animator: android.animation.ValueAnimator? = null
        private var w = 0
        private var h = 0
        private var band = 1f
        private var glowR = 1f
        private var flowShader: android.graphics.LinearGradient? = null
        private var warmShader: android.graphics.RadialGradient? = null
        private var coolShader: android.graphics.RadialGradient? = null

        // 阳光彩虹色环：暖橙→金→嫩绿→青→蓝→紫→粉，首尾同色形成无缝循环
        private val rainbow: IntArray
        private val bgBase: Int
        private val warmColor: Int
        private val coolColor: Int

        init {
            val dark = Ui.isDark(context)
            // 夜间稍浓郁、日间更柔和，与全局液态玻璃配色协调
            val alpha = if (dark) 175 else 128
            fun c(rgb: Int) = (alpha shl 24) or (rgb and 0xFFFFFF)
            rainbow = intArrayOf(
                c(0xFF8A3D), c(0xFFC24D), c(0xFDE96B), c(0x8FE3A0),
                c(0x6FC8FF), c(0x9D8CFF), c(0xFF8CC0), c(0xFF8A3D),
            )
            bgBase = if (dark) Color.parseColor("#151A2E") else Color.parseColor("#F3F5FA")
            warmColor = if (dark) 0x38FFC978 else 0x50FFD27A
            coolColor = if (dark) 0x305A9CFF else 0x4278B4FF
        }

        override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
            w = width
            h = height
            band = (maxOf(width, height) * 2.4f).coerceAtLeast(1f)
            flowShader = android.graphics.LinearGradient(
                0f, 0f, band, 0f, rainbow, null, android.graphics.Shader.TileMode.REPEAT,
            )
            glowR = maxOf(width, height) * 0.75f
            // 光晕 shader 以 (glowR, glowR) 为圆心，绘制时用 localMatrix 平移定位
            warmShader = android.graphics.RadialGradient(
                glowR, glowR, glowR, warmColor, warmColor and 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP,
            )
            coolShader = android.graphics.RadialGradient(
                glowR, glowR, glowR, coolColor, coolColor and 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.drawColor(bgBase)
            val flow = flowShader
            if (flow != null) {
                canvas.save()
                canvas.rotate(-18f, w / 2f, h / 2f)
                matrix.reset()
                matrix.setTranslate(phase * band, 0f)
                flow.setLocalMatrix(matrix)
                paint.shader = flow
                canvas.drawRect(-band, -h.toFloat(), band * 2f, h * 2f, paint)
                canvas.restore()
                paint.shader = null
            }

            // 两团日光光晕随相位缓慢漂移（暖光左上 / 冷光右下）
            val twoPi = (Math.PI * 2).toFloat()
            val pi = Math.PI.toFloat()
            val cx1 = w * (0.30f + 0.10f * kotlin.math.sin(phase * twoPi))
            val cy1 = h * (0.22f + 0.08f * kotlin.math.cos(phase * twoPi))
            val cx2 = w * (0.74f + 0.09f * kotlin.math.sin(phase * twoPi + pi))
            val cy2 = h * (0.78f + 0.07f * kotlin.math.cos(phase * twoPi + pi))
            warmShader?.let {
                matrix.reset()
                matrix.setTranslate(cx1 - glowR, cy1 - glowR)
                it.setLocalMatrix(matrix)
                glowPaint.shader = it
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glowPaint)
            }
            coolShader?.let {
                matrix.reset()
                matrix.setTranslate(cx2 - glowR, cy2 - glowR)
                it.setLocalMatrix(matrix)
                glowPaint.shader = it
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glowPaint)
            }
            glowPaint.shader = null
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            ensureAnimator()
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        /** 离开欢迎页（visibility != VISIBLE）自动停动画，回页重启 */
        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (visibility == View.VISIBLE) {
                ensureAnimator()
            } else {
                animator?.cancel()
                animator = null
            }
        }

        private fun ensureAnimator() {
            if (animator == null && isShown) {
                animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 26000
                    interpolator = android.view.animation.LinearInterpolator()
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    addUpdateListener { anim ->
                        phase = anim.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        }
    }

    private fun createAgreementPage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        // Scrollable white card
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = Ui.dp(16, d)
                marginEnd = Ui.dp(16, d)
                topMargin = Ui.dp(60, d)
                bottomMargin = Ui.dp(16, d)
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(24, d), Ui.dp(20, d), Ui.dp(24, d))
            // 拟态卡片：玻璃填充 + 高光/阴影双环，自液态背景「挤出」
            background = Ui.neuCard(this@OnboardingActivity, 20f)
            Ui.applyNeuShadow(this, 5f, 20f)
        }

        // Title
        card.addView(TextView(this).apply {
            text = "用户协议"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })

        // Agreement text
        card.addView(TextView(this).apply {
            text = "欢迎使用 TMUI OS。本应用为 Android 设备提供 Linux 桌面环境运行能力，包括 DSU GSI 安装、Chroot Linux 容器、终端模拟及 VNC 远程桌面等功能。\n\n使用本应用需要设备已获取 ROOT 权限，并可能涉及系统级操作。请您仔细阅读以下条款后再决定是否继续使用。"
            textSize = 14f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(16, d), 0, 0)
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        // Agreement item row
        val agreementRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(20, d), 0, Ui.dp(20, d))
        }

        val checkBox = CheckBox(this).apply {
            isChecked = agreementChecked
            setOnCheckedChangeListener { _, isChecked ->
                agreementChecked = isChecked
            }
        }
        agreementRow.addView(checkBox)

        agreementRow.addView(TextView(this).apply {
            text = "我已阅读并同意用户协议与隐私说明"
            textSize = 14f
            setTextColor(Ui.primaryText(this@OnboardingActivity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(8, d)
            }
        })

        card.addView(agreementRow)

        scroll.addView(card)
        container.addView(scroll)

        // Bottom "下一步" button — placed outside the card, below the indicators
        val nextBtn = TextView(this).apply {
            text = "下一步"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // 拟态实心渐变按钮：accent 渐变 + 高光内环 + 阴影外环
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#62A8FF") else android.graphics.Color.parseColor("#5EA0FF"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#2E6CF0") else android.graphics.Color.parseColor("#2F6BF0"),
                12f, this@OnboardingActivity
            )
            Ui.applyNeuShadow(this, 5f, 12f, Ui.buttonPrimary(this@OnboardingActivity))
            setPadding(0, Ui.dp(14, d), 0, Ui.dp(14, d))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = Ui.dp(32, d)
                marginEnd = Ui.dp(32, d)
                bottomMargin = Ui.dp(56, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener {
                if (agreementChecked) {
                    goToNextPage()
                } else {
                    Toast.makeText(this@OnboardingActivity, "请先同意用户协议", Toast.LENGTH_SHORT).show()
                }
            }
        }
        container.addView(nextBtn)
        return container
    }

    /**
     * Page 2: Permissions
     * Notification / Storage / Root verification
     */
    private fun createPermissionsPage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = Ui.dp(16, d)
                marginEnd = Ui.dp(16, d)
                topMargin = Ui.dp(60, d)
                bottomMargin = Ui.dp(16, d)
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(24, d), Ui.dp(20, d), Ui.dp(24, d))
            // 拟态卡片：玻璃填充 + 高光/阴影双环，自液态背景「挤出」
            background = Ui.neuCard(this@OnboardingActivity, 20f)
            Ui.applyNeuShadow(this, 5f, 20f)
        }

        // Title
        card.addView(TextView(this).apply {
            text = "环境与权限"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })

        // Description
        card.addView(TextView(this).apply {
            text = "选择现在要检查的运行条件。其余设置可稍后在应用内修改。"
            textSize = 14f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(16, d))
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        // Notification permission
        card.addView(makePermissionRow("通知权限", "允许应用发送通知提醒", notificationGranted) { checked ->
            notificationGranted = checked
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        })

        // Storage permission
        card.addView(makePermissionRow("存储访问", "允许访问设备存储空间", storageGranted) { checked ->
            storageGranted = checked
            if (checked) {
                requestPermissions(arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ), 1002)
            }
        })

        // Usage access (special permission — jump to system settings page)
        card.addView(makePermissionRow("使用情况访问", "进程管理读取任务栏后台应用与前台识别", usageAccessGranted) { checked ->
            usageAccessGranted = checked
            if (checked) {
                // 特殊权限：跳系统"使用情况访问"页手动授予
                runCatching {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }.onFailure {
                    Toast.makeText(this@OnboardingActivity, "系统设置页不可用", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Root notice (not a real permission request, just status display)
        card.addView(TextView(this).apply {
            text = "Root 不会自动请求。点按下方项目可验证已授予的 UID。"
            textSize = 12f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(4, d))
        })

        card.addView(makePermissionRow("验证 Root", "检查 ROOT 权限可用性", rootVerified) { checked ->
            rootVerified = checked
            // In real app, would run su check here
        })

        scroll.addView(card)
        container.addView(scroll)

        // Bottom "下一步" button — placed outside the card, below the indicators
        val nextBtn = TextView(this).apply {
            text = "下一步"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // 拟态实心渐变按钮：accent 渐变 + 高光内环 + 阴影外环
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#62A8FF") else android.graphics.Color.parseColor("#5EA0FF"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#2E6CF0") else android.graphics.Color.parseColor("#2F6BF0"),
                12f, this@OnboardingActivity
            )
            Ui.applyNeuShadow(this, 5f, 12f, Ui.buttonPrimary(this@OnboardingActivity))
            setPadding(0, Ui.dp(14, d), 0, Ui.dp(14, d))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = Ui.dp(32, d)
                marginEnd = Ui.dp(32, d)
                bottomMargin = Ui.dp(56, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener { goToNextPage() }
        }
        container.addView(nextBtn)
        return container
    }

    private fun makePermissionRow(title: String, desc: String, initial: Boolean, onToggle: (Boolean) -> Unit): View {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(12, d), Ui.dp(12, d), Ui.dp(12, d))
            // 拟态凹槽：内阴影环槽位，权限行「嵌」入卡片
            background = Ui.neuInset(this@OnboardingActivity, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = Ui.dp(8, d)
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })
        textCol.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        })
        row.addView(textCol)

        val switch = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        row.addView(switch)

        return row
    }

    /**
     * Page 4: 环境体检 — 安装前实测 ROOT 授权 / CPU 架构 / 存储空间
     * 进入页面自动逐项检测（真实执行，非静态文案），状态实时上屏
     */
    private fun createEnvCheckPage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = Ui.dp(16, d)
                marginEnd = Ui.dp(16, d)
                topMargin = Ui.dp(60, d)
                bottomMargin = Ui.dp(80, d)
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(24, d), Ui.dp(20, d), Ui.dp(16, d))
            // 拟态卡片：玻璃填充 + 高光/阴影双环，自液态背景「挤出」
            background = Ui.neuCard(this@OnboardingActivity, 20f)
        }

        // Title
        card.addView(TextView(this).apply {
            text = "环境体检"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })

        // Description
        card.addView(TextView(this).apply {
            text = "在开始之前，为你实测 ROOT 授权、CPU 架构与存储空间，全部通过即可获得最佳安装体验。"
            textSize = 13f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(10, d))
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        // 检查行：状态点 + 标题 + 实时状态文案
        fun checkRow(title: String): Pair<View, TextView> {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(70, 130, 140, 165))
                }
                layoutParams = LinearLayout.LayoutParams(Ui.dp(12, d), Ui.dp(12, d)).apply {
                    topMargin = Ui.dp(5, d)
                }
            }
            val status = TextView(this).apply {
                text = "检测中…"
                textSize = 12f
                setTextColor(Ui.secondaryText(this@OnboardingActivity))
                setPadding(0, Ui.dp(2, d), 0, 0)
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(12, d), 0, Ui.dp(12, d))
                addView(dot)
                addView(LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = Ui.dp(12, d)
                    }
                    addView(TextView(this@OnboardingActivity).apply {
                        text = title
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ui.primaryText(this@OnboardingActivity))
                    })
                    addView(status)
                })
            })
            return dot to status
        }

        val rootRow = checkRow("ROOT 权限")
        val archRow = checkRow("CPU 架构")
        val storageRow = checkRow("存储空间")

        // 状态上屏：绿=通过，琥珀=受限可用，红=不满足
        fun mark(row: Pair<View, TextView>, level: Int, msg: String) {
            runOnUiThread {
                row.first.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        when (level) {
                            0 -> Ui.buttonSuccess(this@OnboardingActivity)
                            1 -> Ui.buttonWarning(this@OnboardingActivity)
                            else -> Ui.buttonDanger(this@OnboardingActivity)
                        },
                    )
                }
                row.second.text = msg
            }
        }

        // 后台顺序实测三项，每项间留出节奏感
        Thread {
            Thread.sleep(400)
            val rootOk = com.mcai.ubuntudsu.core.StatusDetector.rootAvailable()
            mark(
                rootRow,
                if (rootOk) 0 else 1,
                if (rootOk) "已获取 ROOT 授权，全部功能可用" else "未获取 ROOT 授权，核心功能受限",
            )
            Thread.sleep(400)
            val archOk = Build.SUPPORTED_ABIS.contains("arm64-v8a")
            mark(
                archRow,
                if (archOk) 0 else 2,
                if (archOk) "arm64-v8a · 兼容主流 rootfs 镜像" else "未检测到 arm64，兼容性受限",
            )
            Thread.sleep(400)
            val freeBytes = runCatching {
                android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes
            }.getOrDefault(0L)
            val freeGb = freeBytes / 1024f / 1024f / 1024f
            mark(
                storageRow,
                when {
                    freeGb >= 5f -> 0
                    freeGb >= 2f -> 1
                    else -> 2
                },
                "剩余 %.1f GB · 建议 ≥ 5GB".format(freeGb),
            )
        }.start()

        scroll.addView(card)
        container.addView(scroll)

        // Bottom "下一步" button
        val nextBtn = TextView(this).apply {
            text = "下一步"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // 拟态实心渐变按钮：accent 渐变 + 高光内环 + 阴影外环
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#62A8FF") else android.graphics.Color.parseColor("#5EA0FF"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#2E6CF0") else android.graphics.Color.parseColor("#2F6BF0"),
                12f, this@OnboardingActivity
            )
            setPadding(0, Ui.dp(14, d), 0, Ui.dp(14, d))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = Ui.dp(32, d)
                marginEnd = Ui.dp(32, d)
                bottomMargin = Ui.dp(56, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener { goToNextPage() }
        }
        container.addView(nextBtn)
        return container
    }

    /**
     * Page 4: Done — 一切就绪 + 功能亮点速览
     * 大标题「一切就绪」+ 拟态卡片内 4 项核心功能（图标 + 名称 + 一句话说明）+ 开始使用按钮
     */
    private fun createDonePage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            ).apply {
                topMargin = Ui.dp(84, d)
                marginStart = Ui.dp(28, d)
                marginEnd = Ui.dp(28, d)
            }
        }

        // 大标题 + 副标题
        content.addView(TextView(this).apply {
            text = "一切就绪"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = "你的口袋 Linux 工具箱已备好，四大核心能力随时待命"
            textSize = 13f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            gravity = Gravity.CENTER
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(18, d))
        })

        // 功能亮点卡：4 行入口预览，与主界面同款拟态质感
        val highlightCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            background = Ui.neuCard(this@OnboardingActivity, 20f)
            Ui.applyNeuShadow(this, 5f, 20f)
        }
        listOf(
            Triple(R.drawable.icon_terminal_runner, "容器终端", "Chroot 容器 · Termux 风格 · apt 装包"),
            Triple(R.drawable.icon_linux_modern, "桌面环境", "XFCE / KDE / GNOME + VNC 远程桌面"),
            Triple(R.drawable.icon_dsu_modern, "DSU 管理", "ROOT 直装 GSI 镜像 · 一键重启切换"),
            Triple(R.drawable.ic_download, "下载管理", "并行下载 · 断点续传 · 镜像直取"),
        ).forEachIndexed { index, (iconRes, title, desc) ->
            if (index > 0) {
                // 水晶玻璃分隔条：分区之间的高光细线
                highlightCard.addView(
                    Ui.crystalDivider(this, d),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Ui.dp(2, d),
                    ).apply { topMargin = Ui.dp(2, d); bottomMargin = Ui.dp(2, d) },
                )
            }
            highlightCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(10, d), 0, Ui.dp(10, d))
                addView(ImageView(this@OnboardingActivity).apply {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(Ui.dp(34, d), Ui.dp(34, d))
                })
                addView(LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = Ui.dp(12, d)
                    }
                    addView(TextView(this@OnboardingActivity).apply {
                        text = title
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ui.primaryText(this@OnboardingActivity))
                    })
                    addView(TextView(this@OnboardingActivity).apply {
                        text = desc
                        textSize = 11f
                        setTextColor(Ui.secondaryText(this@OnboardingActivity))
                        setPadding(0, Ui.dp(1, d), 0, 0)
                    })
                })
            })
        }
        content.addView(highlightCard)
        container.addView(content)

        // Bottom "开始使用" button — placed directly below the indicators
        val startBtn = TextView(this).apply {
            text = "开始使用"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // 拟态实心渐变按钮：accent 渐变 + 高光内环 + 阴影外环
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#62A8FF") else android.graphics.Color.parseColor("#5EA0FF"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#2E6CF0") else android.graphics.Color.parseColor("#2F6BF0"),
                12f, this@OnboardingActivity
            )
            Ui.applyNeuShadow(this, 5f, 12f, Ui.buttonPrimary(this@OnboardingActivity))
            setPadding(0, Ui.dp(14, d), 0, Ui.dp(14, d))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = Ui.dp(32, d)
                marginEnd = Ui.dp(32, d)
                bottomMargin = Ui.dp(56, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener {
                saveSettings()
                markCompleted()
                goToMain()
            }
        }
        container.addView(startBtn)
        return container
    }

    // ==================== Page Navigation ====================

    private fun showPage(index: Int, animate: Boolean) {
        if (index !in 0 until PAGE_COUNT) return
        currentPage = index

        // 彩虹背景仅首页显示；隐藏时 RainbowFlowView 内部自动停动画不耗电
        if (::rainbowFlow.isInitialized) {
            rainbowFlow.visibility = if (index == 0) View.VISIBLE else View.GONE
        }

        if (index >= pages.size) {
            while (pages.size <= index) {
                pages.add(createPage(pages.size))
            }
        }
        val page = pages[index]

        pageContainer.removeAllViews()
        page.alpha = if (animate) 0f else 1f
        page.translationX = if (animate) resources.displayMetrics.density * 40f else 0f
        pageContainer.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        if (animate) {
            page.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        updateIndicators(index)
    }

    private fun updateIndicators(activeIndex: Int) {
        val d = resources.displayMetrics.density
        for (i in indicatorDots.indices) {
            val dot = indicatorDots[i]
            val isActive = i == activeIndex
            val size = if (isActive) Ui.dp(8, d) else Ui.dp(6, d)
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            lp.width = size
            lp.height = size
            dot.layoutParams = lp
            dot.background = makeDotDrawable(isActive)
            dot.animate()
                .scaleX(if (isActive) 1.2f else 1f)
                .scaleY(if (isActive) 1.2f else 1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun makeDotDrawable(active: Boolean): GradientDrawable {
        // 拟态指示点：激活 = accent 实心 + 高光描边，未激活 = 半透明玻璃
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (active) {
                setColor(Ui.buttonPrimary(this@OnboardingActivity))
                setStroke(Ui.dp(1, resources.displayMetrics.density), Color.argb(150, 255, 255, 255))
            } else {
                setColor(Color.argb(110, 255, 255, 255))
            }
        }
    }

    private fun goToNextPage() {
        if (currentPage < PAGE_COUNT - 1) {
            val current = pageContainer.getChildAt(0)
            current?.animate()
                ?.alpha(0f)
                ?.translationX(-resources.displayMetrics.density * 30f)
                ?.setDuration(250)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
            showPage(currentPage + 1, animate = true)
        }
    }

    private fun goToPreviousPage() {
        if (currentPage > 0) {
            val current = pageContainer.getChildAt(0)
            current?.animate()
                ?.alpha(0f)
                ?.translationX(resources.displayMetrics.density * 30f)
                ?.setDuration(250)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
            showPage(currentPage - 1, animate = true)
        }
    }

    // ==================== Swipe Gesture ====================

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.x
                swipeStartY = event.y
                isSwiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isSwiping) {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    val threshold = 48f * resources.displayMetrics.density
                    if (kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2) {
                        isSwiping = true
                        if (dx < 0) goToNextPage() else goToPreviousPage()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isSwiping = false
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            Ui.dispatchHaptic(window.decorView, event)
        }
        return super.dispatchTouchEvent(event)
    }

    // ==================== Persistence ====================

    private fun saveSettings() {
        getSharedPreferences(PREF_ONBOARDING, MODE_PRIVATE).edit().apply {
            putBoolean(KEY_AGREED, agreementChecked)
            putInt(KEY_UI_SCALE, uiScale)
            apply()
        }
    }

    private fun markCompleted() {
        getSharedPreferences(PREF_ONBOARDING, MODE_PRIVATE).edit().apply {
            putBoolean(KEY_COMPLETED, true)
            apply()
        }
    }

    // ==================== Navigation to Main ====================

    private fun goToMain() {
        bgAnimator?.cancel()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // ==================== Lifecycle ====================

    override fun onDestroy() {
        bgAnimator?.cancel()
        bgAnimator = null
        super.onDestroy()
    }
}
