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
import android.widget.RadioButton
import android.widget.RadioGroup
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

        // 2. Page container
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
            ).apply {
                bottomMargin = Ui.dp(40, d)
            }
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

        // Edge-to-edge insets
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(rootLayout)

        showPage(0, animate = false)
    }

    private fun setupGradientBackground(root: FrameLayout) {
        // DNA NEXT style: 6-color dynamic cycling gradient
        val colorSets = listOf(
            intArrayOf(
                Color.rgb(255, 182, 193), // pink
                Color.rgb(230, 190, 220), // light purple
                Color.rgb(176, 196, 222), // steel blue
                Color.rgb(135, 206, 235), // sky blue
            ),
            intArrayOf(
                Color.rgb(255, 200, 150), // peach
                Color.rgb(255, 183, 178), // coral pink
                Color.rgb(210, 180, 222), // lavender
                Color.rgb(174, 214, 241), // light blue
            ),
            intArrayOf(
                Color.rgb(200, 230, 201), // mint
                Color.rgb(255, 218, 185), // peach puff
                Color.rgb(255, 160, 122), // salmon
                Color.rgb(221, 160, 221), // plum
            ),
            intArrayOf(
                Color.rgb(176, 224, 230), // powder blue
                Color.rgb(255, 192, 203), // pink
                Color.rgb(230, 230, 250), // lavender
                Color.rgb(135, 206, 250), // light sky blue
            ),
        )
        bgDrawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, colorSets[0])
        root.background = bgDrawable

        // Cycle through color sets smoothly
        bgAnimator = ValueAnimator.ofFloat(0f, colorSets.size.toFloat()).apply {
            duration = 8000L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val pos = anim.animatedValue as Float
                val idx = pos.toInt() % colorSets.size
                val nextIdx = (idx + 1) % colorSets.size
                val fraction = pos - idx.toFloat()
                val current = colorSets[idx]
                val next = colorSets[nextIdx]
                val blended = current.mapIndexed { i, c ->
                    Color.rgb(
                        (Color.red(c) + (Color.red(next[i]) - Color.red(c)) * fraction).toInt(),
                        (Color.green(c) + (Color.green(next[i]) - Color.green(c)) * fraction).toInt(),
                        (Color.blue(c) + (Color.blue(next[i]) - Color.blue(c)) * fraction).toInt(),
                    )
                }.toIntArray()
                bgDrawable?.setColors(blended)
            }
        }
        bgAnimator?.start()
    }

    // ==================== Page Builders ====================

    private fun createPage(index: Int): View = when (index) {
        0 -> createWelcomePage()
        1 -> createAgreementPage()
        2 -> createPermissionsPage()
        3 -> createSettingsPage()
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
            textSize = 44f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // Thin shadow effect (radius=2f, dx=0, dy=1f, color=semi-transparent black)
            setShadowLayer(2f, 0f, 1f, Color.argb(120, 0, 0, 0))
        }
        content.addView(welcomeText)
        
        // Start flowing rainbow gradient animation after layout
        welcomeText.post {
            val paint = welcomeText.paint
            val textWidth = paint.measureText("欢迎使用")
            val colors = intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
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
            // Ripple effect on click
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(40, 255, 255, 255))
                    setStroke(Ui.dp(2, d), Color.argb(200, 255, 255, 255))
                },
                null
            )
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
     * Page 1: Agreement
     * White card with agreement text, checkbox, continue button
     */
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(20, d).toFloat()
                setColor(Color.WHITE)
            }
        }

        // Title
        card.addView(TextView(this).apply {
            text = "用户协议"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })

        // Agreement text
        card.addView(TextView(this).apply {
            text = "欢迎使用 TMUI OS。本应用为 Android 设备提供 Linux 桌面环境运行能力，包括 DSU GSI 安装、Chroot Linux 容器、终端模拟及 VNC 远程桌面等功能。\n\n使用本应用需要设备已获取 ROOT 权限，并可能涉及系统级操作。请您仔细阅读以下条款后再决定是否继续使用。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
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
            setTextColor(Color.BLACK)
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(12, d).toFloat()
                setColor(Color.parseColor("#4285F4"))
            }
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(20, d).toFloat()
                setColor(Color.WHITE)
            }
        }

        // Title
        card.addView(TextView(this).apply {
            text = "环境与权限"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })

        // Description
        card.addView(TextView(this).apply {
            text = "选择现在要检查的运行条件。其余设置可稍后在应用内修改。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
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

        // Root notice (not a real permission request, just status display)
        card.addView(TextView(this).apply {
            text = "Root 不会自动请求。点按下方项目可验证已授予的 UID。"
            textSize = 12f
            setTextColor(Color.GRAY)
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(12, d).toFloat()
                setColor(Color.parseColor("#4285F4"))
            }
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
            setPadding(0, Ui.dp(12, d), 0, Ui.dp(12, d))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(12, d).toFloat()
                setColor(Color.rgb(245, 245, 250))
            }
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
            setTextColor(Color.BLACK)
        })
        textCol.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(Color.GRAY)
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
     * Page 3: System Setup — actual useful settings for Linux-Dsu
     * Root manager / chroot path / VNC resolution
     */
    private fun createSettingsPage(): View {
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
            setPadding(Ui.dp(20, d), Ui.dp(24, d), Ui.dp(20, d), Ui.dp(24, d))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(20, d).toFloat()
                setColor(Color.WHITE)
            }
        }

        // Title
        card.addView(TextView(this).apply {
            text = "主题配置"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })

        // Description
        card.addView(TextView(this).apply {
            text = "个性化你的应用外观。选择暗色模式、主题色和强调色，随时可在设置中修改。"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(12, d))
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        // 1. Dark Mode Switch
        card.addView(makeSectionTitle("暗色模式"))
        val darkSwitch = android.widget.Switch(this).apply {
            text = "启用暗色模式"
            textSize = 14f
            setTextColor(Color.BLACK)
            isChecked = getSharedPreferences(PREF_ONBOARDING, MODE_PRIVATE).getBoolean("dark_mode", false)
        }
        card.addView(darkSwitch)

        // 2. Theme Color
        card.addView(makeSectionTitle("主题色", topPad = 16))
        val themeColorGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            "极光蓝" to "blue",
            "薄荷绿" to "green",
            "日落橙" to "orange",
            "暗夜紫" to "purple",
        ).forEach { (label, value) ->
            themeColorGroup.addView(RadioButton(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.BLACK)
                tag = value
                isChecked = value == "blue"
            })
        }
        card.addView(themeColorGroup)

        // 3. Accent Color
        card.addView(makeSectionTitle("强调色", topPad = 16))
        val accentColorGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            "碧波蓝" to "accent_blue",
            "青柠绿" to "accent_green",
            "葡萄紫" to "accent_purple",
            "蜜桃粉" to "accent_pink",
        ).forEach { (label, value) ->
            accentColorGroup.addView(RadioButton(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.BLACK)
                tag = value
                isChecked = value == "accent_blue"
            })
        }
        card.addView(accentColorGroup)

        scroll.addView(card)
        container.addView(scroll)

        // Bottom "下一步" button
        val nextBtn = TextView(this).apply {
            text = "下一步"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(12, d).toFloat()
                setColor(Color.parseColor("#4285F4"))
            }
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
                // Save theme selections
                val themeTag = themeColorGroup.findViewById<RadioButton>(themeColorGroup.checkedRadioButtonId)?.tag as? String ?: "blue"
                val accentTag = accentColorGroup.findViewById<RadioButton>(accentColorGroup.checkedRadioButtonId)?.tag as? String ?: "accent_blue"
                getSharedPreferences(PREF_ONBOARDING, MODE_PRIVATE).edit().apply {
                    putBoolean("dark_mode", darkSwitch.isChecked)
                    putString("theme_color", themeTag)
                    putString("accent_color", accentTag)
                    apply()
                }
                goToNextPage()
            }
        }
        container.addView(nextBtn)
        return container
    }

    private fun makeSectionTitle(title: String, topPad: Int = 12): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#4285F4"))
            setPadding(0, Ui.dp(topPad, resources.displayMetrics.density), 0, Ui.dp(4, resources.displayMetrics.density))
        }
    }

    /**
     * Page 4: Done
     * App icon, "TMUI OSv1.0", "设置完毕", "开始使用" button
     */
    private fun createDonePage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            ).apply {
                topMargin = Ui.dp(180, d)
            }
        }

        // App icon
        val iconSize = Ui.dp(80, d)
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo_combined)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                bottomMargin = Ui.dp(8, d)
            }
        })

        // "天明研发版" — larger, positioned below the logo
        content.addView(TextView(this).apply {
            text = "天明研发版"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(8, d), 0, 0)
        })

        // "导向完成" — smaller subtitle
        content.addView(TextView(this).apply {
            text = "导向完成"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.argb(160, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(2, d), 0, 0)
        })

        container.addView(content)

        // Bottom "开始使用" button — placed directly below the indicators
        val startBtn = TextView(this).apply {
            text = "开始使用"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(12, d).toFloat()
                setColor(Color.parseColor("#4285F4"))
            }
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
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.WHITE else Color.argb(120, 255, 255, 255))
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
