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
        private const val PAGE_COUNT = 7

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
    private val permissionSwitches = linkedMapOf<String, Switch>()
    private val pendingPermissionKeys = linkedSetOf<String>()
    private var syncingPermissionRows = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        if (isCompleted(this)) {
            goToMain()
            return
        }

        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != 1001 && requestCode != 1002 && requestCode != 1003) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val requestedKeys = linkedSetOf<String>()
        if (permissions.contains(android.Manifest.permission.POST_NOTIFICATIONS)) requestedKeys += "notification"
        if (
            permissions.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE) ||
            permissions.contains(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            requestedKeys += "storage"
        }
        refreshPermissionState()
        requestedKeys.forEach { key ->
            pendingPermissionKeys.remove(key)
        }
    }

    private fun markPendingPermission(key: String) {
        pendingPermissionKeys += key
    }

    private fun refreshPermissionState() {
        notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        storageGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        usageAccessGranted = hasUsageAccess()
        rootVerified = runCatching { com.mcai.ubuntudsu.core.RootShell.available() }.getOrDefault(false)
        syncPermissionRows()
    }

    private fun hasUsageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return runCatching {
                val appOps = getSystemService(android.app.AppOpsManager::class.java)
                val flag = appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                )
                flag == android.app.AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
        }
        return false
    }

    private fun syncPermissionRows() {
        syncingPermissionRows = true
        try {
            permissionSwitches.entries.forEach { (key, sw) ->
                if (pendingPermissionKeys.contains(key)) {
                    return@forEach
                }
                sw.isChecked = isRowGranted(key)
            }
        } finally {
            syncingPermissionRows = false
        }
    }

    private fun applyPermissionSwitchState(key: String, checked: Boolean) {
        val sw = permissionSwitches[key] ?: return
        syncingPermissionRows = true
        try {
            sw.isChecked = checked
        } finally {
            syncingPermissionRows = false
        }
    }

    private fun isRowGranted(key: String): Boolean = when (key) {
        "notification" -> notificationGranted
        "storage" -> storageGranted
        "usage" -> usageAccessGranted
        "root" -> rootVerified
        else -> false
    }

    private fun trackPermissionRow(switch: Switch, key: String) {
        switch.tag = key
        permissionSwitches[key] = switch
    }

    private fun requestOneTapPermissions() {
        refreshPermissionState()
        val pending = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
            pending += android.Manifest.permission.POST_NOTIFICATIONS
            markPendingPermission("notification")
        }
        val storagePermission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (!storageGranted) {
            pending += storagePermission
            markPendingPermission("storage")
        }
        if (pending.isNotEmpty()) {
            requestPermissions(pending.toTypedArray(), 1003)
        }
        if (!usageAccessGranted) {
            runCatching {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }.onFailure {
                Toast.makeText(this, Lang.t("系统设置页不可用"), Toast.LENGTH_SHORT).show()
            }
        }
        if (!rootVerified) {
            rootVerified = runCatching { com.mcai.ubuntudsu.core.RootShell.available() }.getOrDefault(false)
            applyPermissionSwitchState("root", rootVerified)
        }
        refreshPermissionState()
        Toast.makeText(this, Lang.t("已发起可自动授权项，返回应用后自动检测真实状态"), Toast.LENGTH_SHORT).show()
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
        2 -> createFeaturePage(
            title = Lang.t("DSU 与 Linux 桌面"),
            subtitle = Lang.t("无需解锁、不动原系统，临时启动新系统镜像"),
            items = listOf(
                Triple(R.drawable.icon_dsu_modern, Lang.t("DSU 动态系统更新"), Lang.t("ROOT 直装 GSI 镜像，自定义 userdata 容量、清理旧缓存、一键重启切换")),
                Triple(R.drawable.icon_linux_modern, Lang.t("Linux ARM® 架构"), Lang.t("Chroot 安装运行 Ubuntu rootfs（本地 / 云端镜像），可卸载还原")),
                Triple(R.drawable.icon_terminal_runner, Lang.t("容器终端"), Lang.t("Termux 风格 Chroot 终端，支持 apt 安装软件包")),
                Triple(R.drawable.ic_desktop_start, Lang.t("远程桌面"), Lang.t("XFCE / KDE 桌面 + VNC 远程连接，音频桥接、分辨率自选")),
            ),
        )
        3 -> createFeaturePage(
            title = Lang.t("ROM 固件与移植"),
            subtitle = Lang.t("固件双源下载，DNA 工具箱一站式移植开发"),
            items = listOf(
                Triple(R.drawable.icon_rom_firmware, Lang.t("ROM 固件下载"), Lang.t("HyperOS 与 ColorOS / FlymeOS / realme UI 双源，品牌机型筛选，aria2c 加速")),
                Triple(R.drawable.icon_rom_port, Lang.t("ROM 移植开发"), Lang.t("DNA 工具箱分解 / 合成 SUPER、payload 提取、镜像格式互转")),
                Triple(R.drawable.icon_otg, Lang.t("OTG 刷机助手"), Lang.t("检测 USB 设备 ADB / Fastboot 状态，刷机日志实时输出")),
                Triple(R.drawable.icon_usb_boot, Lang.t("U 盘启动"), Lang.t("本地制作 U 盘 IMG 镜像并虚拟 U 盘暴露给电脑")),
            ),
        )
        4 -> createPermissionsPage()
        5 -> createEnvCheckPage()
        6 -> createDonePage()
        else -> createWelcomePage()
    }

    /** 功能介绍页：标题 + 副标题 + 图标条目卡片 + 底部「下一步」 */
    private fun createFeaturePage(
        title: String,
        subtitle: String,
        items: List<Triple<Int, String, String>>,
    ): View {
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
            setPadding(Ui.dp(20, d), Ui.dp(24, d), Ui.dp(20, d), Ui.dp(20, d))
            background = Ui.neuCard(this@OnboardingActivity, 20f)
            Ui.applyNeuShadow(this, 5f, 20f)
        }

        card.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })
        card.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(14, d))
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        items.forEachIndexed { index, (iconRes, name, desc) ->
            if (index > 0) {
                card.addView(
                    Ui.crystalDivider(this, d),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Ui.dp(2, d),
                    ).apply { topMargin = Ui.dp(2, d); bottomMargin = Ui.dp(2, d) },
                )
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(12, d), 0, Ui.dp(12, d))
                addView(ImageView(this@OnboardingActivity).apply {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(Ui.dp(40, d), Ui.dp(40, d))
                })
                addView(LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = Ui.dp(13, d)
                    }
                    addView(TextView(this@OnboardingActivity).apply {
                        text = name
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ui.primaryText(this@OnboardingActivity))
                    })
                    addView(TextView(this@OnboardingActivity).apply {
                        text = desc
                        textSize = 12f
                        setTextColor(Ui.secondaryText(this@OnboardingActivity))
                        setPadding(0, Ui.dp(3, d), 0, 0)
                        setLineSpacing(Ui.dp(2, d).toFloat(), 1f)
                    })
                })
            })
        }

        scroll.addView(card)
        container.addView(scroll)
        container.addView(bottomNextButton())
        return container
    }

    /** 底部「下一步」按钮（功能介绍页共用）：拟态实心渐变 + 阴影外环 */
    private fun bottomNextButton(): View {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = Lang.t("下一步")
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#62A8FF") else android.graphics.Color.parseColor("#5EA0FF"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#2E6CF0") else android.graphics.Color.parseColor("#2F6BF0"),
                12f, this@OnboardingActivity,
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

        // Logo (same launcher icon, unchanged display size)
        val logoSize = Ui.dp(140, d)
        content.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
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
            text = Lang.t("欢迎使用")
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
            val textWidth = paint.measureText(Lang.t("欢迎使用"))
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
     * 引导首页专属背景：静态混搭渐变（浅黑 → 绿 → 蓝 → 白）。
     * 斜向对角多色渐变，整体偏深，保证白色导向按钮与文字清晰可读。
     * 无动画、不耗电。
     */
    private class RainbowFlowView(context: android.content.Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var w = 0
        private var h = 0

        // 静态混搭渐变：浅黑 → 绿 → 蓝 → 白（对角方向，白色仅占右下角小段做提亮）
        private val colors = intArrayOf(
            Color.parseColor("#34383F"), // 浅黑
            Color.parseColor("#2F7A5B"), // 绿
            Color.parseColor("#2A5A8E"), // 蓝
            Color.parseColor("#D9DFE7"), // 白
        )
        private val stops = floatArrayOf(0f, 0.42f, 0.72f, 1f)

        override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
            w = width
            h = height
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                colors, stops,
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
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
            text = Lang.t("用户协议")
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })

        // Agreement text
        card.addView(TextView(this).apply {
            text = Lang.t("欢迎使用 TMUI OS。本应用为 Android 设备提供 Linux 桌面环境运行能力，包括 DSU GSI 安装、Chroot Linux 容器、终端模拟及 VNC 远程桌面等功能。\n\n使用本应用需要设备已获取 ROOT 权限，并可能涉及系统级操作。请您仔细阅读以下条款后再决定是否继续使用。")
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
            text = Lang.t("我已阅读并同意用户协议与隐私说明")
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
            text = Lang.t("下一步")
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
                    Toast.makeText(this@OnboardingActivity, Lang.t("请先同意用户协议"), Toast.LENGTH_SHORT).show()
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
            text = Lang.t("环境与权限")
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })

        // Description
        card.addView(TextView(this).apply {
            text = Lang.t("选择现在要检查的运行条件。其余设置可稍后在应用内修改。")
            textSize = 14f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(16, d))
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        // Notification permission
        card.addView(makePermissionRow("通知权限", "允许应用发送通知提醒", "notification", notificationGranted) { checked ->
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                markPendingPermission("notification")
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        })

        card.addView(makePermissionRow("存储访问", "允许访问设备存储空间", "storage", storageGranted) { checked ->
            if (checked) {
                markPendingPermission("storage")
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                    1002
                )
            }
        })

        card.addView(makePermissionRow("使用情况访问", "进程管理读取任务栏后台应用与前台识别", "usage", usageAccessGranted) { checked ->
            if (checked) {
                runCatching {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }.onFailure {
                    Toast.makeText(this@OnboardingActivity, Lang.t("系统设置页不可用"), Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Root notice (not a real permission request, just status display)
        card.addView(TextView(this).apply {
            text = Lang.t("Root 不会自动请求。点按下方项目可验证已授予的 UID。")
            textSize = 12f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(4, d))
        })

        card.addView(makePermissionRow("验证 Root", "检查 ROOT 权限可用性", "root", rootVerified) { checked ->
            if (checked) {
                rootVerified = runCatching { com.mcai.ubuntudsu.core.RootShell.available() }.getOrDefault(false)
                applyPermissionSwitchState("root", rootVerified)
                if (!rootVerified) {
                    Toast.makeText(this@OnboardingActivity, Lang.t("Root 验证未通过"), Toast.LENGTH_SHORT).show()
                }
            }
        })

        val oneTapBtn = TextView(this).apply {
            text = Lang.t("一键授权")
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#22C55E") else android.graphics.Color.parseColor("#4ADE80"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#15803D") else android.graphics.Color.parseColor("#16A34A"),
                12f, this@OnboardingActivity
            )
            Ui.applyNeuShadow(this, 5f, 12f, Ui.buttonSuccess(this@OnboardingActivity))
            setPadding(0, Ui.dp(12, d), 0, Ui.dp(12, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = Ui.dp(12, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener {
                requestOneTapPermissions()
            }
        }
        card.addView(oneTapBtn)

        scroll.addView(card)
        container.addView(scroll)
        val nextBtn = TextView(this).apply {
            text = Lang.t("下一步")
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

    private fun makePermissionRow(title: String, desc: String, key: String, initial: Boolean, onToggle: (Boolean) -> Unit): View {
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
            setOnCheckedChangeListener { _, isChecked ->
                if (!syncingPermissionRows) onToggle(isChecked)
            }
        }
        trackPermissionRow(switch, key)
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
            text = Lang.t("环境体检")
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })

        // Description
        card.addView(TextView(this).apply {
            text = Lang.t("在开始之前，为你实测 ROOT 授权、CPU 架构与存储空间，全部通过即可获得最佳安装体验。")
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
                text = Lang.t("检测中…")
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

        val rootRow = checkRow(Lang.t("ROOT 权限"))
        val archRow = checkRow(Lang.t("CPU 架构"))
        val storageRow = checkRow(Lang.t("存储空间"))

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
                if (rootOk) Lang.t("已获取 ROOT 授权，全部功能可用") else Lang.t("未获取 ROOT 授权，核心功能受限"),
            )
            Thread.sleep(400)
            val archOk = Build.SUPPORTED_ABIS.contains("arm64-v8a")
            mark(
                archRow,
                if (archOk) 0 else 2,
                if (archOk) Lang.t("arm64-v8a · 兼容主流 rootfs 镜像") else Lang.t("未检测到 arm64，兼容性受限"),
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
                Lang.t("剩余 %.1f GB · 建议 ≥ 5GB").format(freeGb),
            )
        }.start()

        scroll.addView(card)
        container.addView(scroll)

        // Bottom "下一步" button
        val nextBtn = TextView(this).apply {
            text = Lang.t("下一步")
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
            text = Lang.t("一切就绪")
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = Lang.t("你的口袋 Linux 工具箱已备好，四大核心能力随时待命")
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
            Triple(R.drawable.icon_terminal_runner, Lang.t("容器终端"), Lang.t("Chroot 容器 · Termux 风格 · apt 装包")),
            Triple(R.drawable.icon_linux_modern, Lang.t("桌面环境"), Lang.t("XFCE / KDE / GNOME + VNC 远程桌面")),
            Triple(R.drawable.icon_dsu_modern, Lang.t("DSU 管理"), Lang.t("ROOT 直装 GSI 镜像 · 一键重启切换")),
            Triple(R.drawable.ic_download, Lang.t("下载管理"), Lang.t("并行下载 · 断点续传 · 镜像直取")),
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

        // 开源仓库地址（点击复制到剪贴板）
        content.addView(TextView(this).apply {
            text = Lang.t("开源仓库  github.com/hetianming/Linux-Dsu")
            textSize = 12f
            setTextColor(Ui.buttonPrimary(this@OnboardingActivity))
            gravity = Gravity.CENTER
            setPadding(Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d), 0)
            isClickable = true
            Ui.pressAnimation(this)
            setOnClickListener {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("repo", "https://github.com/hetianming/Linux-Dsu"))
                Toast.makeText(this@OnboardingActivity, Lang.t("仓库地址已复制"), Toast.LENGTH_SHORT).show()
            }
        })
        container.addView(content)

        // Bottom "开始使用" button — placed directly below the indicators
        val startBtn = TextView(this).apply {
            text = Lang.t("开始使用")
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
        // 协议页只能通过「下一步」按钮且勾选同意后前进，禁止滑动跳过
        if (currentPage == 1 && !agreementChecked) {
            Toast.makeText(this, Lang.t("请先同意用户协议"), Toast.LENGTH_SHORT).show()
            return
        }
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
