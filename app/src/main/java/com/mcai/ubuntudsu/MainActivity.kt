package com.mcai.ubuntudsu

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mcai.ubuntudsu.ui.Haptics
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
    private val tabs = listOf("首页", "多功能", "Linux", "DSU")
    private var currentTab = 0
    private lateinit var pageHost: FrameLayout
    private lateinit var pageStrip: android.widget.HorizontalScrollView
    private lateinit var pageRow: LinearLayout
    private lateinit var rootLayout: FrameLayout
    private val navItems = mutableListOf<TextView>()
    private var homePage: HomePage? = null
    private var linuxPage: LinuxPage? = null
    private var dsuPage: DsuPage? = null
    private var settingsPage: SettingsPage? = null
    private val pageCache = mutableMapOf<Int, View>()
    private var navBar: LinearLayout? = null
    private var navContainer: FrameLayout? = null
    private var glassNav: com.mcai.ubuntudsu.ui.glass.LiquidGlassView? = null
    private var liquidIndicator: com.mcai.ubuntudsu.ui.glass.LiquidGlassIndicator? = null
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var dragActive = false
    private var dragTargetTab = -1
    private var dragDownInNav = false
    private var dragHalfHaptic = false

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
        // 同时接受图片和视频：选图走静态背景，选 mp4 走视频动态背景
        runCatching { pickHeroImageLauncher.launch(arrayOf("image/*", "video/*")) }
            .onFailure {
                android.widget.Toast.makeText(this, "无法打开背景选择器", android.widget.Toast.LENGTH_SHORT).show()
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
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            Ui.dispatchHaptic(window.decorView, event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = event.x
                swipeDownY = event.y
                dragActive = false
                dragTargetTab = -1
                dragHalfHaptic = false
                dragDownInNav = tabAtPoint(event.rawX, event.rawY) >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - swipeDownX
                val dy = event.y - swipeDownY
                if (!dragActive) {
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop * 0.38f
                    if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 0.52f) {
                        val target = if (dx < 0) currentTab + 1 else currentTab - 1
                        if (target in tabs.indices) {
                            dragActive = true
                            dragTargetTab = target
                            // ColorOS 式防误触：滑动一旦成立，立即向整棵视图树发 CANCEL，
                            // 正被按住的按钮终止本次触摸序列，后续 UP 不会再触发它的点击
                            val cancel = MotionEvent.obtain(event)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            liquidIndicator?.animate()?.cancel()
                            liquidIndicator?.setLiquidPressed(true)
                        }
                    }
                }
                if (dragActive && dragTargetTab != -1) {
                    // 酷安式跟手：pageStrip scrollX 横向平移，相邻页从边缘自然露出；透镜同步跟手
                    val sw = pageStrip.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val progress = (kotlin.math.abs(dx) / sw.toFloat()).coerceIn(0f, 1f)
                    val fromScroll = currentTab * sw
                    val toScroll = dragTargetTab * sw
                    pageStrip.scrollX = (fromScroll + (toScroll - fromScroll) * progress).toInt()
                    moveLiquidIndicatorLive(currentTab, dragTargetTab, progress)
                    if (!dragHalfHaptic && progress >= 0.5f) {
                        dragHalfHaptic = true
                        Haptics.perform(liquidIndicator)
                    }
                    // 切页手势接管：事件不再下发给任何页面控件
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragActive && dragTargetTab != -1) {
                    val sw = pageStrip.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val commit = event.actionMasked == MotionEvent.ACTION_UP &&
                        kotlin.math.abs(event.x - swipeDownX) >= sw * 0.18f
                    if (commit) {
                        selectTab(dragTargetTab)
                    } else {
                        springLiquidIndicatorBack()
                        animatePageSlide(currentTab, 220)
                    }
                    liquidIndicator?.setLiquidPressed(false)
                    dragActive = false
                    dragTargetTab = -1
                    // 抬手同样由切页手势消费，绝不下发，杜绝滑动结束误点按钮
                    return true
                } else if (event.actionMasked == MotionEvent.ACTION_UP && dragDownInNav) {
                    tabAtPoint(event.rawX, event.rawY).takeIf { it >= 0 }?.let { selectTab(it) }
                }
                dragActive = false
                dragTargetTab = -1
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        rootLayout = root
        root.clipChildren = false
        // 根布局承接全屏液体渐变背景（含状态栏区域），页面自身保持透明
        Ui.animateLiquidBackground(root)
        pageHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            clipChildren = false
            clipToPadding = false
        }
        // 横向页条：HorizontalScrollView 承载 4 个并排功能页（各 1 屏宽），
        // 自身 1 屏宽；scrollX 驱动切页，触摸坐标系正确跟随（不会锁触摸）
        pageStrip = object : android.widget.HorizontalScrollView(this) {
            // 拦截关闭：水平滑动手势由 Activity.dispatchTouchEvent 统一驱动，
            // 这里不消费水平触摸，避免抢占页面内垂直滚动与点击
            override fun onInterceptTouchEvent(ev: MotionEvent) = false
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = false
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        pageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        pageStrip.addView(pageRow)
        root.addView(pageHost)
        pageHost.addView(pageStrip)

        // 底部导航舱：玻璃舱体 + 可滑动的液态玻璃透镜指示器（选中的 tab 后方）
        val navContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            background = Ui.navGlassPanel(this@MainActivity)
            clipChildren = false
            clipToPadding = false
            setPadding(Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d))
        }
        // 透镜尺寸贴合导航项（宽 = 单 tab 等分宽，高 = 栏高 - 内边距），随屏等比缩放不再硬编码
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d))
        }
        // 单 tab 宽 = (栏宽 - 左右内边距) / 4；透镜略收一点留缝隙
        val containerInnerW = (resources.displayMetrics.widthPixels - 2 * Ui.dp(24, d))  // 左右 12dp 边距 + 6dp 栏内边
        val indicatorW = ((containerInnerW - 4 * Ui.dp(6, d)) / 4 * 0.9f).toInt().coerceAtLeast(Ui.dp(56, d))
        // 导航栏固定高度：每个 tab 整格可点（文字区外触摸也有响应），透镜高保持溢出少量形成玻璃球凸起感
        val navBarH = Ui.dp(46, d)
        val indicatorH = (Ui.dp(12, d) * 4 + Ui.dp(16, d)) * 0.85f
        val indicator = com.mcai.ubuntudsu.ui.glass.LiquidGlassIndicator(this)
        indicator.elevation = Ui.dp(4, d).toFloat()
        indicator.visibility = View.INVISIBLE
        navContainer.addView(
            indicator,
            FrameLayout.LayoutParams(indicatorW, indicatorH.toInt(), Gravity.CENTER_VERTICAL),
        )
        this.liquidIndicator = indicator

        val activeColor = 0x66FFFFFF.toInt()
        val inactiveColor = 0x99233C50.toInt()
        val navActiveText = if (Ui.isDark(this)) 0xFFFFFFFF.toInt() else 0xFF101826.toInt()
        val navInactiveText = if (Ui.isDark(this)) 0xFF2A3040.toInt() else 0xFF2C3A52.toInt()
        tabs.forEachIndexed { tab, label ->
            val item = TextView(this).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setSingleLine(true)
                setTextColor(if (tab == 0) navActiveText else navInactiveText)
                setTypeface(typeface, if (tab == 0) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setShadowLayer(
                    Ui.dp(1, d).toFloat(), 0f, Ui.dp(2, d).toFloat(),
                    if (tab == 0) activeColor else (if (Ui.isDark(this@MainActivity)) 0x66000000.toInt() else inactiveColor),
                )
                // 透镜后方导航项：等宽可点击，自身无背景（激活态由指示器 + 文字色体现）
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
                )
                setOnClickListener { selectTab(tab) }
            }
            Ui.pressAnimation(item)
            navItems.add(item)
            navBar.addView(item)
        }
        navContainer.addView(navBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            navBarH,
            Gravity.CENTER_VERTICAL,
        ))
        // 导航层 Z 轴高于透镜：文字始终在玻璃球上方，触摸优先命中导航项（整格区域）
        navBar.elevation = Ui.dp(8, d).toFloat()
        this.navBar = navBar
        this.navContainer = navContainer

        val navLayoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            marginStart = Ui.dp(6, d)
            marginEnd = Ui.dp(6, d)
            bottomMargin = Ui.dp(12, d)
        }
        navContainer.layoutParams = navLayoutParams
        root.addView(navContainer, navLayoutParams)

        setContentView(root)
        Ui.enableEdgeToEdge(this, root)
        // 预构建四个页面并水平排布（酷安式跟手横滑），pageHost 通过 translationX 整体横向平移
        root.post {
            ensureAllPagesBuilt()
            positionPageHost(currentTab)
            moveLiquidIndicator(currentTab)
        }
        // 沉浸式适配统一在根布局处理：
        // 1. 顶部留出状态栏高度 + 呼吸间距，页面内容整体下移
        // 2. 底部导航栏避开手势条，页面内容底部避让导航栏 + 手势条
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ws = getPreferences(MODE_PRIVATE).getBoolean("wallpaper_sync", false)
            v.setPadding(bars.left, if (currentTab == 3 && ws) 0 else bars.top + Ui.dp(2, d), bars.right, 0)
            pageHost.setPadding(0, 0, 0, if (currentTab == 3 && ws) 0 else Ui.dp(56 + 16 + 12, d) + bars.bottom)
            // 玻璃导航舱避让手势条
            (navContainer?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = bars.bottom + Ui.dp(8, d)
                navContainer?.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun switchToTab(tab: Int) {
        selectTab(tab)
    }

    /** 预构建四个页面并水平并排到 pageRow，保证横滑时相邻页即时可见（酷安式跟手） */
    private fun ensureAllPagesBuilt() {
        val sw = pageStrip.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        tabs.forEachIndexed { index, _ ->
            if (pageCache.containsKey(index)) return@forEachIndexed
            val page: View = when (index) {
                1 -> { if (settingsPage == null) settingsPage = SettingsPage(this, { recreate() }); settingsPage!!.build() }
                2 -> { if (linuxPage == null) linuxPage = LinuxPage(this, executor); linuxPage!!.build() }
                3 -> { if (dsuPage == null) { dsuPage = DsuPage(this, executor, pickZipLauncher); dsuPage?.bindRootService() }; dsuPage!!.build() }
                else -> { if (homePage == null) homePage = HomePage(this); homePage!!.build() }
            }
            val wrapped = ScrollView(this).apply {
                if (index == 1) isFillViewport = true
                addView(page)
            }
            pageCache[index] = wrapped
            // 每页 1 屏宽并排，pageRow 总宽 4 屏，由 HorizontalScrollView 横向滚动展示
            pageRow.addView(
                wrapped,
                LinearLayout.LayoutParams(sw, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
    }

    /** 立即（无动画）将 pageStrip 定位到第 [tab] 页（scrollX 正确偏移触摸坐标系） */
    private fun positionPageHost(tab: Int) {
        val sw = pageStrip.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        pageStrip.scrollX = tab * sw
    }

    /** 带动画地将 pageStrip 滑动到第 [tab] 页（酷安式横滑，无缩放/透明变形） */
    private fun animatePageSlide(tab: Int, duration: Long = 280) {
        val sw = pageStrip.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val from = pageStrip.scrollX
        val to = tab * sw
        if (from == to) return
        val start = System.currentTimeMillis()
        val anim = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - start
                val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                pageStrip.scrollX = (from + (to - from) * eased).toInt()
                if (t < 1f) pageStrip.post(this)
            }
        }
        pageStrip.post(anim)
    }

    private fun selectTab(tab: Int) {
        if (tab == currentTab) {
            moveLiquidIndicator(tab)
            return
        }
        val d = resources.displayMetrics.density
        val previousTab = currentTab
        currentTab = tab
        for (index in navItems.indices) {
            val item = navItems[index]
            val active = index == tab
            val isWallpaper = tab == 1 && getPreferences(android.app.Activity.MODE_PRIVATE).getBoolean("wallpaper_sync", false)
            if (isWallpaper) {
                item.setTextColor(if (active) android.graphics.Color.WHITE else android.graphics.Color.argb(200, 255, 255, 255))
                item.setShadowLayer(4f, 1f, 1f, android.graphics.Color.argb(180, 0, 0, 0))
            } else {
                val activeTextColor = if (Ui.isDark(this)) 0xFFFFFFFF.toInt() else 0xFF101826.toInt()
                val inactiveTextColor = if (Ui.isDark(this)) 0xFF2A3040.toInt() else 0xFF2C3A52.toInt()
                item.setTextColor(if (active) activeTextColor else inactiveTextColor)
                item.setShadowLayer(
                    Ui.dp(1, d).toFloat(), 0f, Ui.dp(2, d).toFloat(),
                    if (active) 0x55FFFFFF.toInt() else (if (Ui.isDark(this)) 0x66000000.toInt() else 0x00FFFFFF),
                )
            }
            item.setTypeface(item.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            item.background = null
        }
        moveLiquidIndicator(tab)
        if (previousTab != tab && previousTab in navItems.indices) {
            spawnNavDrop(navItems[previousTab], navItems[tab])
        }
        // 酷安式：pageHost 整体横向平移，相邻页从边缘自然露出，无缩放/透明变形
        ensureAllPagesBuilt()
        animatePageSlide(tab)
        when (tab) {
            0 -> homePage?.refreshStatus()
        }
    }

    /**
     * 液态玻璃透镜滑动到选中导航项后方。
     * 计算第 [tab] 个导航项在 [navContainer] 内的中心,把 [liquidIndicator]
     * 滑过去并垂直贴中,末尾轻微放大回弹表现"液态玻璃聚焦"。
     */
    private fun moveLiquidIndicator(tab: Int) {
        val indicator = liquidIndicator ?: return
        val container = navContainer ?: return
        if (tab !in navItems.indices) return
        val target = navItems[tab]
        indicator.visibility = View.VISIBLE
        if (target.width == 0) {
            target.post { moveLiquidIndicator(tab) }
            return
        }
        val cx = target.left + target.width / 2f
        val dx = cx - indicator.width / 2f
        indicator.animate()
            .translationX(dx)
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.6f))
            .start()
        indicator.animate()
            .scaleX(1.14f)
            .scaleY(1.14f)
            .setDuration(110)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                indicator.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(190)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
        // 焦点态：透镜按压感（放大即焦点）
        indicator.setLiquidPressed(false)
        // 导航项在最前、透镜贴在其后方
        (navContainer as? FrameLayout)?.bringChildToFront(navBar ?: indicator)
    }

    /** 拖拽跟手：透镜在 [from] 与 [to] 两个导航项之间按 [progress]（0..1）实时位移，无动画 */
    private fun moveLiquidIndicatorLive(from: Int, to: Int, progress: Float) {
        val indicator = liquidIndicator ?: return
        if (from !in navItems.indices || to !in navItems.indices) return
        val a = navItems[from]
        val b = navItems[to]
        if (a.width == 0 || b.width == 0) return
        indicator.visibility = View.VISIBLE
        val ax = a.left + a.width / 2f - indicator.width / 2f
        val bx = b.left + b.width / 2f - indicator.width / 2f
        indicator.translationX = ax + (bx - ax) * progress
        indicator.translationY = 0f
        indicator.scaleX = 1.1f
        indicator.scaleY = 1.1f
    }

    /** 拖拽未达阈值松手：透镜弹回当前选中项，轻微回弹表现液态回缩 */
    private fun springLiquidIndicatorBack() {
        val indicator = liquidIndicator ?: return
        if (currentTab !in navItems.indices) return
        val target = navItems[currentTab]
        if (target.width == 0) return
        val cx = target.left + target.width / 2f - indicator.width / 2f
        indicator.animate()
            .translationX(cx)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.6f))
            .start()
    }

    /** 单个 tab 的宽度（导航项布局后取实测值，未布局时用屏宽估算） */
    private fun tabWidth(): Float {
        val item = navItems.firstOrNull() ?: return resources.displayMetrics.widthPixels / 4f
        return if (item.width > 0) item.width.toFloat() else resources.displayMetrics.widthPixels / 4f
    }

    /** 屏幕坐标 → 导航 tab 序号；不在导航栏区域返回 -1（整栏含内边距都可命中） */
    private fun tabAtPoint(rawX: Float, rawY: Float): Int {
        val container = navContainer ?: return -1
        val loc = IntArray(2)
        container.getLocationOnScreen(loc)
        if (rawX < loc[0] || rawX > loc[0] + container.width) return -1
        if (rawY < loc[1] || rawY > loc[1] + container.height) return -1
        val bar = navBar ?: return -1
        if (bar.width == 0) return -1
        val barLoc = IntArray(2)
        bar.getLocationOnScreen(barLoc)
        val rel = (rawX - barLoc[0]) / bar.width.toFloat()
        val idx = (rel * tabs.size).toInt()
        return idx.coerceIn(0, tabs.size - 1)
    }

    // 导航水滴动画：从旧按钮中心溅起水滴，弧线飞向新按钮落点
    @Suppress("unused")
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
