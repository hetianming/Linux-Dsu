package com.mcai.ubuntudsu

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.ui.pages.DsuPage
import com.mcai.ubuntudsu.ui.pages.HomePage
import com.mcai.ubuntudsu.ui.pages.LinuxPage
import com.mcai.ubuntudsu.ui.pages.SettingsPage
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val lifecycleStopHooks = mutableListOf<Runnable>()
    fun addLifecycleStopHook(hook: Runnable) { lifecycleStopHooks.add(hook) }
    private val executor = Executors.newSingleThreadExecutor()
    private val tabs = listOf("首页", "Linux", "DSU", "更多")
    private var currentTab = 0
    private lateinit var pageHost: FrameLayout
    private lateinit var rootLayout: FrameLayout
    private val navItems = mutableListOf<TextView>()
    private var homePage: HomePage? = null
    private var linuxPage: LinuxPage? = null
    private var dsuPage: DsuPage? = null
    private var settingsPage: SettingsPage? = null
    private val pageCache = mutableMapOf<Int, View>()
    private var navBar: LinearLayout? = null
    private var glassNav: com.mcai.ubuntudsu.ui.glass.LiquidGlassView? = null
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeTracked = false
    private val swipeThresholdDp = 48

    private val pickZipLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            val path = result.data?.getStringExtra(RootfsFilesActivity.RESULT_FILE_PATH)
            if (path != null) dsuPage?.onZipPicked(android.net.Uri.fromFile(java.io.File(path)))
        }

    // 首页头图背景选择：OpenDocument 可持久授权，结果拷贝进应用私有目录
    private val pickHeroImageLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { homePage?.onHeroImagePicked(it) }
        }

    fun pickHeroImage() {
        runCatching { pickHeroImageLauncher.launch(arrayOf("image/*")) }
            .onFailure {
                android.widget.Toast.makeText(this, "无法打开图片选择器", android.widget.Toast.LENGTH_SHORT).show()
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(
            getPreferences(MODE_PRIVATE).getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
        requestStoragePermission()
        buildUi()
        // 进程被杀重建时恢复上次所在 tab（如 DSU 页选择 GSI 后返回）
        selectTab(savedInstanceState?.getInt("last_tab")?.takeIf { it in tabs.indices } ?: 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("last_tab", currentTab)
    }

    // 内置文件选择器需要读 /sdcard：向用户申请存储权限
    private fun requestStoragePermission() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        }
        val needed = permissions.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }
    }

    override fun onResume() {
        super.onResume()
        homePage?.refreshStatus()
        linuxPage?.refreshInfo()
    }

    override fun onDestroy() {
        dsuPage?.unbindRootService()
        lifecycleStopHooks.forEach { it.run() }
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 全局震动反馈
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            Ui.dispatchHaptic(window.decorView, event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = event.x
                swipeDownY = event.y
                swipeTracked = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeTracked) {
                    val dx = event.x - swipeDownX
                    val dy = event.y - swipeDownY
                    val thresholdPx = swipeThresholdDp * resources.displayMetrics.density
                    if (kotlin.math.abs(dx) > thresholdPx && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2) {
                        swipeTracked = true
                        val next = if (dx < 0) currentTab + 1 else currentTab - 1
                        if (next in tabs.indices) selectTab(next)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeTracked = false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        rootLayout = root
        // 根布局承接全屏液体渐变背景（含状态栏区域），页面自身保持透明
        Ui.animateLiquidBackground(root)
        pageHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(pageHost)

        val navLayoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // item 之间留出间距：外边距 + item 内边距形成呼吸感
            setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
            layoutParams = navLayoutParams
        }
        tabs.forEachIndexed { tab, label ->
            val item = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, if (tab == 0) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (tab == 0) Ui.buttonText(this@MainActivity) else Ui.secondaryText(this@MainActivity))
                background = Ui.glassButton(this@MainActivity, if (tab == 0) Ui.buttonPrimary(this@MainActivity) else null)
                // 舱内胶囊不再单独投影：玻璃舱整体投影，避免双层影子叠加发脏
                // 胶囊形 item，前后留间距
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(40, d), 1f).apply {
                    marginStart = if (tab == 0) 0 else Ui.dp(6, d)
                    marginEnd = if (tab == tabs.lastIndex) 0 else Ui.dp(6, d)
                }
                setOnClickListener { selectTab(tab) }
            }
            Ui.pressAnimation(item)
            navItems.add(item)
            navBar.addView(item)
        }
        // 液态玻璃渲染导航舱：真实 LiquidGlassView 渲染层（着色/高光/折射/景深/辉光）+ 拟态彩色投影
        val glassNav = com.mcai.ubuntudsu.ui.glass.LiquidGlass.createView(this, com.mcai.ubuntudsu.ui.glass.LiquidGlass.navBar(this)).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = Ui.dp(12, d)
                marginEnd = Ui.dp(12, d)
                bottomMargin = Ui.dp(8, d)
            }
            Ui.applyNeuShadow(this, 5f, 26f, Ui.buttonPrimary(this@MainActivity))
        }
        glassNav.addView(navBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(glassNav, glassNav.layoutParams)
        this.glassNav = glassNav
        this.navBar = navBar

        setContentView(root)
        Ui.enableEdgeToEdge(this, root)
        // 沉浸式适配统一在根布局处理：
        // 1. 顶部留出状态栏高度 + 呼吸间距，页面内容整体下移
        // 2. 底部导航栏避开手势条，页面内容底部避让导航栏 + 手势条
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ws = getPreferences(MODE_PRIVATE).getBoolean("wallpaper_sync", false)
            v.setPadding(bars.left, if (currentTab == 3 && ws) 0 else bars.top + Ui.dp(2, d), bars.right, 0)
            pageHost.setPadding(0, 0, 0, if (currentTab == 3 && ws) 0 else Ui.dp(56 + 16 + 12, d) + bars.bottom)
            // 玻璃导航舱避让手势条
            (glassNav?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = bars.bottom + Ui.dp(8, d)
                glassNav?.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun switchToTab(tab: Int) {
        selectTab(tab)
    }

    private fun selectTab(tab: Int) {
        if (tab == currentTab && pageHost.childCount > 0) return
        val d = resources.displayMetrics.density
        val previousTab = currentTab
        currentTab = tab
        for (index in navItems.indices) {
            val item = navItems[index]
            val active = index == tab
            // 更多页+壁纸同步时，导航栏文字加阴影增强可读性
            val wallpaperSync = getPreferences(android.app.Activity.MODE_PRIVATE).getBoolean("wallpaper_sync", false)
            if (tab == 3 && wallpaperSync) {
                item.setTextColor(if (active) android.graphics.Color.WHITE else android.graphics.Color.argb(200, 255, 255, 255))
                item.setShadowLayer(4f, 1f, 1f, android.graphics.Color.argb(180, 0, 0, 0))
            } else {
                item.setTextColor(if (active) Ui.buttonText(this) else Ui.secondaryText(this))
                item.setShadowLayer(0f, 0f, 0f, 0)
            }
            item.setTypeface(item.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            item.background = Ui.glassButton(this, if (active) Ui.buttonPrimary(this) else null)
            // 舱内胶囊不投影，仅靠胶囊底色区分激活态
        }
        // 水滴切换动画：旧 tab 按钮位置泛起涟漪水滴，向新 tab 方向飞溅
        if (previousTab != tab && previousTab in navItems.indices) {
            spawnNavDrop(navItems[previousTab], navItems[tab])
        }
        val cached = pageCache[tab]
        val wrapped: View
        if (cached != null) {
            wrapped = cached
        } else {
            if (tab == 1 && linuxPage == null) {
                linuxPage = LinuxPage(this, executor)
            }
            if (tab == 2 && dsuPage == null) {
                dsuPage = DsuPage(this, executor, pickZipLauncher)
                dsuPage?.bindRootService()
            }
            if (tab == 3 && settingsPage == null) {
                settingsPage = SettingsPage(this, { recreate() })
            }
            if (tab == 0 && homePage == null) {
                homePage = HomePage(this, executor)
            }
            val page: View = when (tab) {
                1 -> linuxPage!!.build()
                2 -> dsuPage!!.build()
                3 -> settingsPage!!.build()
                else -> homePage!!.build()
            }
            wrapped = ScrollView(this).apply {
                // 更多页：填充视口，让壁纸背景覆盖全屏，图标可垂直居中
                if (tab == 3) isFillViewport = true
                addView(page)
            }
            pageCache[tab] = wrapped
        }
        pageHost.removeAllViews()

        // 更多页开启壁纸同步时：取消 root 和 pageHost 的 padding，让壁纸延伸到屏幕边缘（含状态栏）
        val wallpaperSync = getPreferences(android.app.Activity.MODE_PRIVATE).getBoolean("wallpaper_sync", false)
        val rootInsets = window.decorView.rootWindowInsets
        if (tab == 3 && wallpaperSync) {
            rootLayout.setPadding(0, 0, 0, 0)
            pageHost.setPadding(0, 0, 0, 0)
        } else {
            val topInset = if (rootInsets != null) {
                WindowInsetsCompat.toWindowInsetsCompat(rootInsets)
                    .getInsets(WindowInsetsCompat.Type.systemBars()).top
            } else 0
            val bottomInset = if (rootInsets != null) {
                WindowInsetsCompat.toWindowInsetsCompat(rootInsets)
                    .getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            } else 0
            rootLayout.setPadding(0, topInset + Ui.dp(2, d), 0, 0)
            pageHost.setPadding(0, 0, 0, Ui.dp(56 + 16 + 12, d) + bottomInset)
        }

        pageHost.addView(
            wrapped,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // 页面水感入场：轻弹落下（水滴落入页面的弹性）
        wrapped.alpha = 0f
        wrapped.scaleX = 0.92f
        wrapped.scaleY = 0.92f
        wrapped.translationY = Ui.dp(14, resources.displayMetrics.density).toFloat()
        wrapped.pivotY = (resources.displayMetrics.heightPixels * 0.85).toFloat()
        wrapped.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(420)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f))
            .start()
        when (tab) {
            0 -> homePage?.refreshStatus()
        }
    }

    // 导航水滴动画：从旧按钮中心溅起水滴，弧线飞向新按钮落点
    private fun spawnNavDrop(from: View, to: View) {
        val root = (navBar?.parent as? ViewGroup) ?: return
        val d = resources.displayMetrics.density
        val accent = android.graphics.Color.parseColor(if (Ui.isDark(this)) "#66EAF4FF" else "#995B6CFF")
        val glow = android.graphics.Color.parseColor(if (Ui.isDark(this)) "#99FFFFFF" else "#CCFFFFFF")
        fun centerInView(v: View): Pair<Float, Float> {
            val fromLoc = IntArray(2)
            val rootLoc = IntArray(2)
            v.getLocationOnScreen(fromLoc)
            root.getLocationOnScreen(rootLoc)
            return (fromLoc[0] - rootLoc[0] + v.width / 2).toFloat() to
                (fromLoc[1] - rootLoc[1] + v.height / 2).toFloat()
        }
        val (sx, sy) = centerInView(from)
        val (ex, ey) = centerInView(to)
        repeat(5) { index ->
            // 水滴更小更轻：6/9/12dp；自定义绘制带高光的球体水滴
            val size = Ui.dp(6 + (index % 3) * 3, d)
            val drop = object : android.view.View(this) {
                override fun onDraw(canvas: android.graphics.Canvas) {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    paint.shader = android.graphics.RadialGradient(
                        width * 0.35f, height * 0.3f, size.toFloat(),
                        glow, accent, android.graphics.Shader.TileMode.CLAMP,
                    )
                    canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
                }
            }.apply {
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    leftMargin = sx.toInt() - size / 2
                    topMargin = sy.toInt() - size / 2
                }
            }
            root.addView(drop, drop.layoutParams)
            // 弧线飞溅：水平线性位移 + 垂直先上抛后落下（两次动画拼接）
            val dx = (ex - sx) * (0.75f + 0.12f * index)
            val rise = -(28 + index * 12) * d
            val upDur = 170L + index * 22L
            val downDur = 210L + index * 22L
            drop.animate()
                .translationX(dx)
                .translationY(rise)
                .setDuration(upDur)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    drop.animate()
                        .translationY(ey - sy)
                        .alpha(0f)
                        .setDuration(downDur)
                        .setInterpolator(android.view.animation.AccelerateInterpolator())
                        .withEndAction { root.removeView(drop) }
                        .start()
                }
                .start()
        }
        // 新按钮涟漪扩散：两圈水波环依次荡开（外圈更大更慢，水纹荡漾感）
        val rippleStroke = android.graphics.Color.parseColor(if (Ui.isDark(this)) "#80EAF4FF" else "#995B6CFF")
        repeat(2) { round ->
            val ripple = android.view.View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(Ui.dp(2 - round, d), rippleStroke)
                }
                layoutParams = FrameLayout.LayoutParams(Ui.dp(20, d), Ui.dp(20, d)).apply {
                    leftMargin = ex.toInt() - Ui.dp(10, d)
                    topMargin = ey.toInt() - Ui.dp(10, d)
                }
            }
            root.addView(ripple, ripple.layoutParams)
            ripple.alpha = 0f
            // 第二圈延迟触发，形成荡漾节奏
            ripple.animate()
                .alpha(if (round == 0) 0.9f else 0.6f)
                .setDuration(80)
                .withEndAction {
                    ripple.animate()
                        .scaleX(6.5f - round)
                        .scaleY(6.5f - round)
                        .alpha(0f)
                        .setDuration(540L - round * 120L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { root.removeView(ripple) }
                        .start()
                }
                .start()
        }
    }
}
