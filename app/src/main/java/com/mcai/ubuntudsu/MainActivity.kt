package com.mcai.ubuntudsu

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.core.StatusDetector
import com.mcai.ubuntudsu.ui.Ui
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var cardRoot: FrameLayout
    private lateinit var bgView: ImageView
    private lateinit var deviceText: TextView
    private lateinit var gsiText: TextView
    private lateinit var ubuntuText: TextView
    private lateinit var titleGlass: LinearLayout
    private lateinit var cardTitle: TextView
    private lateinit var copyrightText: TextView
    private lateinit var rootBadge: LinearLayout
    private lateinit var rootDot: View
    private lateinit var rootLabel: TextView
    private lateinit var metricsTitle: TextView
    private lateinit var cpuGauge: UsageGauge
    private lateinit var gpuGauge: UsageGauge
    private lateinit var storageValue: TextView
    private lateinit var storageDetail: TextView
    private lateinit var memoryValue: TextView
    private lateinit var memoryDetail: TextView
    private val metricsHandler = Handler(Looper.getMainLooper())
    private var cpuSample: Any? = null
    private val metricsUpdater = object : Runnable {
        override fun run() {
            updateMetrics()
            metricsHandler.postDelayed(this, 2000L)
        }
    }

    private val pickBackground =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { saveBackground(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(
            getPreferences(MODE_PRIVATE).getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
        buildUi()
        loadBackground()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        metricsHandler.removeCallbacks(metricsUpdater)
        metricsHandler.post(metricsUpdater)
    }

    override fun onPause() {
        metricsHandler.removeCallbacks(metricsUpdater)
        super.onPause()
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        val scroll = ScrollView(this).apply { Ui.animateLiquidBackground(this) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(24, d), Ui.dp(16, d), Ui.dp(24, d))
        }

        cardRoot = FrameLayout(this).apply {
             background = Ui.glassSurface(this@MainActivity, 20f)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                     outline.setRoundRect(0, 0, view.width, view.height, Ui.dp(20, d).toFloat())
                }
            }
            layoutParams = Ui.layoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(200, d),
            )
        }
        bgView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(22, d), Ui.dp(20, d), Ui.dp(22, d), Ui.dp(18, d))
        }
        deviceText = TextView(this).apply {
            textSize = 12f
             setTextColor(Ui.secondaryText(this@MainActivity))
        }
        cardTitle = TextView(this).apply {
            text = "Linux - Dsu"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
             setTextColor(Ui.primaryText(this@MainActivity))
        }
        titleGlass = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(12, d), Ui.dp(4, d), Ui.dp(12, d), Ui.dp(4, d))
            background = Ui.glassSurface(this@MainActivity, 20f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(54, d)).apply {
                topMargin = Ui.dp(8, d)
                bottomMargin = Ui.dp(6, d)
            }
        }
        titleGlass.addView(cardTitle)
        copyrightText = TextView(this).apply {
            text = "天明构建  ·  Copyright © 2026"
            textSize = 8f
            gravity = Gravity.CENTER
             setTextColor(if (Ui.isDark(this@MainActivity)) Color.WHITE else Color.rgb(74, 85, 104))
            setPadding(0, Ui.dp(1, d), 0, 0)
        }
        titleGlass.addView(copyrightText)
        val statusColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(4, d)
            }
        }
        gsiText = statusRow(context = this, label = "GSI 系统", value = "检测中...")
        ubuntuText = statusRow(context = this, label = "Linux", value = "检测中...")
        statusColumn.addView(gsiText)
        statusColumn.addView(ubuntuText)

        rootBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassStatusBackground(Ui.isDark(this@MainActivity), d)
            setPadding(Ui.dp(10, d), 0, Ui.dp(12, d), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
            }
        }
        rootDot = Ui.statusDot(this, Color.parseColor("#B0B0B0")).apply {
            val size = Ui.dp(12, d)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        rootLabel = TextView(this).apply {
            text = "ROOT 检测中"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
             setTextColor(Ui.secondaryText(this@MainActivity))
            layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(6, d)
            }
        }
        rootBadge.addView(rootDot)
        rootBadge.addView(rootLabel)

        cardContent.addView(deviceText)
        cardContent.addView(titleGlass)
        cardContent.addView(statusColumn)
        cardRoot.addView(bgView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cardRoot.addView(cardContent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply {
                 bottomMargin = Ui.dp(2, d)
                 marginEnd = Ui.dp(8, d)
            }
        }
        rootBadge.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Ui.dp(32, d),
        )
        bottomRow.addView(smallButton(R.drawable.ic_refresh, "刷新") { refreshStatus() })
        bottomRow.addView(smallButton(R.drawable.ic_palette, "背景") { pickBackground.launch("image/*") })
        bottomRow.addView(smallButton(R.drawable.ic_dark_mode, "夜间模式") { showThemeDialog() })
        cardRoot.addView(bottomRow)
        root.addView(cardRoot)
        root.addView(rootBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(36, d)).apply {
            topMargin = Ui.dp(6, d)
            gravity = Gravity.END
        })

        ubuntuText.text = if (Env.ubuntuInstalled(this)) "Linux：已安装（大小计算中...）" else "Linux：未安装"

        val spacer = View(this)
        root.addView(spacer, Ui.layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(6, d)))

        root.addView(
            Ui.entryButton(this, "Linux", "Rootfs 安装 · 终端 · Chroot 运行", "L", "#E95420", R.drawable.icon_linux_modern) {
                startActivity(Intent(this, UbuntuActivity::class.java))
            },
        )
        root.addView(spacerView(this, 6))
        root.addView(
            Ui.entryButton(this, "DSU 管理", "GSI 安装 · 镜像管理 · 状态检测", "D", "#1A73E8", R.drawable.icon_dsu_modern) {
                startActivity(Intent(this, DsuActivity::class.java))
            },
        )
        root.addView(spacerView(this, 10))
        root.addView(createMetricsSection(d))
        scroll.addView(root)
        setContentView(scroll)
        Ui.enableEdgeToEdge(this, scroll)
    }

    private fun createMetricsSection(density: Float): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, density), Ui.dp(10, density), Ui.dp(12, density), Ui.dp(10, density))
            background = Ui.glassSurface(this@MainActivity, 18f)
            elevation = Ui.dp(3, density).toFloat()
        }
        metricsTitle = TextView(this).apply {
            text = "实时状态"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@MainActivity))
            setPadding(0, 0, 0, Ui.dp(4, density))
        }
        section.addView(metricsTitle)
        val gauges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        cpuGauge = UsageGauge(this, "CPU", Ui.buttonPrimary(this))
        gpuGauge = UsageGauge(this, "GPU", Ui.buttonSecondary(this))
        gauges.addView(cpuGauge, LinearLayout.LayoutParams(0, Ui.dp(86, density), 1f).apply { marginEnd = Ui.dp(4, density) })
        gauges.addView(gpuGauge, LinearLayout.LayoutParams(0, Ui.dp(86, density), 1f).apply { marginStart = Ui.dp(4, density) })
        section.addView(gauges)

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val storageCard = detailMetric("存储", "读取中...", "", Ui.buttonPrimary(this))
        val memoryCard = detailMetric("内存", "读取中...", "", Ui.buttonSecondary(this))
        storageValue = storageCard.first
        storageDetail = storageCard.second
        memoryValue = memoryCard.first
        memoryDetail = memoryCard.second
        details.addView(storageCard.third, LinearLayout.LayoutParams(0, Ui.dp(60, density), 1f).apply { marginEnd = Ui.dp(4, density) })
        details.addView(memoryCard.third, LinearLayout.LayoutParams(0, Ui.dp(60, density), 1f).apply { marginStart = Ui.dp(4, density) })
        section.addView(details)
        return section
    }

    private fun detailMetric(title: String, value: String, detail: String, color: Int): Triple<TextView, TextView, View> {
        val d = resources.displayMetrics.density
        val valueView = TextView(this).apply {
            text = value
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@MainActivity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 8, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP
            )
        }
        val detailView = TextView(this).apply {
            text = detail
            textSize = 9f
            setTextColor(Ui.secondaryText(this@MainActivity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 7, 9, 1, android.util.TypedValue.COMPLEX_UNIT_SP
            )
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(10, d), Ui.dp(5, d), Ui.dp(10, d), Ui.dp(4, d))
            background = Ui.glassSurface(this@MainActivity, 14f)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 11f
                setTextColor(Ui.secondaryText(this@MainActivity))
            })
            addView(valueView)
            addView(detailView)
        }
        return Triple(valueView, detailView, card)
    }

    private fun updateMetrics() {
        executor.execute {
            val result = StatusDetector.deviceMetrics(this@MainActivity, cpuSample)
            runOnUiThread {
                cpuSample = result.second
                val metrics = result.first
                cpuGauge.value = metrics.cpuPercent
                gpuGauge.value = metrics.gpuPercent
                storageValue.text = "${metrics.storagePercent}%"
                storageDetail.text = "${metrics.storageUsed} / ${metrics.storageTotal}"
                memoryValue.text = "${metrics.memoryPercent}%"
                memoryDetail.text = "${metrics.memoryUsed} / ${metrics.memoryTotal}"
            }
        }
    }

    private class UsageGauge(
        context: android.content.Context,
        private val name: String,
        private val accent: Int,
    ) : View(context) {
        var value: Int? = null
            set(newValue) {
                field = newValue
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val size = minOf(width, height).toFloat()
            val radius = size * 0.40f
            val stroke = size * 0.09f
            val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.strokeCap = Paint.Cap.ROUND
            val dark = Ui.isDark(context)
            paint.color = if (dark) {
                Color.argb(120, 255, 255, 255)
            } else {
                Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent))
            }
            canvas.drawArc(ring, -90f, 360f, false, paint)
            val percent = value
            if (percent != null && percent > 0) {
                paint.color = accent
                canvas.drawArc(ring, -90f, 3.6f * percent, false, paint)
            }
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            val secondary = Ui.secondaryText(context)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = radius * 0.36f
            paint.color = secondary
            val nameBaseline = cy - radius * 0.52f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(name, cx, nameBaseline, paint)
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = radius * 0.52f
            paint.color = if (percent != null) accent else secondary
            val valueBaseline = cy + radius * 0.48f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(percent?.let { "$it%" } ?: "--", cx, valueBaseline, paint)
        }
    }

    private fun spacerView(context: android.content.Context, height: Int): View {
        val d = context.resources.displayMetrics.density
        return View(context).also { v ->
            v.layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(height, d))
        }
    }

    private fun statusRow(context: android.content.Context, label: String, value: String): TextView =
        TextView(context).apply {
            text = "$label：$value"
            textSize = 14f
            setTextColor(Ui.primaryText(context))
            val d = context.resources.displayMetrics.density
            setPadding(Ui.dp(10, d), Ui.dp(4, d), Ui.dp(10, d), Ui.dp(4, d))
            background = glassStatusBackground(Ui.isDark(context), d)
            layoutParams = Ui.layoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = Ui.dp(6, context.resources.displayMetrics.density)
            }
        }

    private fun smallButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
        val d = resources.displayMetrics.density
        val button = ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = desc
            scaleType = ImageView.ScaleType.CENTER
            setPadding(Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d))
            background = Ui.glassButton(this@MainActivity)
            elevation = Ui.dp(4, d).toFloat()
            imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(35, 40, 48))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(32, d), Ui.dp(32, d)).apply {
                marginStart = Ui.dp(5, d)
            }
            setOnClickListener { onClick() }
        }
        Ui.pressAnimation(button)
        return button
    }

    private fun isDarkMode(): Boolean =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

     private fun surfaceColor(): Int = Ui.surface(this)

    private fun controlColor(): Int = if (isDarkMode()) Color.parseColor("#3A3D40") else Color.parseColor("#EEF0F2")

    private fun glassStatusBackground(dark: Boolean, density: Float): GradientDrawable =
        Ui.rounded(
            if (dark) Color.argb(68, 0, 0, 0) else Color.argb(78, 255, 255, 255),
            8f,
            density,
        )

    private fun primaryTextColor(): Int = if (isDarkMode()) Color.WHITE else Color.parseColor("#444444")

    private fun showThemeDialog() {
        val modes = arrayOf("跟随系统", "浅色", "深色")
        val current = getPreferences(MODE_PRIVATE).getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val checked = when (current) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("夜间模式")
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                val mode = when (which) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                getPreferences(MODE_PRIVATE).edit().putInt("theme_mode", mode).apply()
                AppCompatDelegate.setDefaultNightMode(mode)
                dialog.dismiss()
            }
            .show()
    }

    private fun refreshStatus() {
        val ctx = this
        gsiText.text = "GSI 系统：检测中..."
        ubuntuText.text = "Linux：检测中..."
        rootLabel.text = "ROOT 检测中"
        rootDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#B0B0B0"))
            setStroke(Ui.dp(1, resources.displayMetrics.density), Color.WHITE)
        }
        executor.execute {
            val device = StatusDetector.deviceSummary()
            val (gsiState, _) = StatusDetector.gsiState()
            val rootOk = StatusDetector.rootAvailable()
            val gsiLabel = when (gsiState) {
                com.mcai.ubuntudsu.core.GsiState.RUNNING -> "运行中"
                com.mcai.ubuntudsu.core.GsiState.INSTALLED -> "已安装"
                com.mcai.ubuntudsu.core.GsiState.ENABLED -> "已启用"
                com.mcai.ubuntudsu.core.GsiState.DISABLED -> "已停用"
                com.mcai.ubuntudsu.core.GsiState.NORMAL -> "未安装"
                com.mcai.ubuntudsu.core.GsiState.UNKNOWN -> "未检测到"
            }
            runOnUiThread {
                deviceText.text = device
                gsiText.text = "GSI 系统：$gsiLabel"
                ubuntuText.text = if (Env.ubuntuInstalled(ctx)) "Linux：已安装（大小计算中...）" else "Linux：未安装"
                rootLabel.text = if (rootOk) "ROOT 已授权" else "ROOT 未授权"
                rootDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(if (rootOk) "#5CE1A5" else "#FF756F"))
                    setStroke(Ui.dp(1, resources.displayMetrics.density), Color.WHITE)
                }
            }
            if (Env.ubuntuInstalled(ctx)) {
                val ubuntuSize = runCatching { Env.formatSize(Env.dirSize(Env.rootfs(ctx))) }
                    .getOrElse { "读取失败" }
                runOnUiThread { ubuntuText.text = "Linux：已安装 ($ubuntuSize)" }
            }
        }
    }

    private fun loadBackground() {
        val file = Env.background(this)
        if (file.isFile) {
            runCatching {
                val bitmap = BitmapFactory.decodeFile(file.path)
                if (bitmap != null) {
                    bgView.setImageBitmap(bitmap)
                    bgView.visibility = View.VISIBLE
                    applyBackgroundContrast(bitmap)
                }
            }
        }
    }

    private fun saveBackground(uri: Uri) {
        runCatching {
            val target = Env.background(this)
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            }
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(target.path, options)?.let { bitmap ->
                bgView.setImageBitmap(bitmap)
                bgView.visibility = View.VISIBLE
                applyBackgroundContrast(bitmap)
            }
            Toast.makeText(this, "背景已更新", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "背景设置失败: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyBackgroundContrast(bitmap: Bitmap) {
        val sample = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        val pixel = sample.getPixel(0, 0)
        sample.recycle()
        val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
        val foreground = if (luminance < 150) Color.WHITE else Color.rgb(20, 20, 20)
        deviceText.setTextColor(foreground)
        gsiText.setTextColor(foreground)
        setTextColorRecursively(cardRoot, foreground)
        titleGlass.background = Ui.rounded(
            if (luminance < 150) Color.argb(125, 0, 0, 0) else Color.argb(125, 255, 255, 255),
            10f,
            resources.displayMetrics.density,
        )
        val darkBackground = luminance < 150
        gsiText.background = glassStatusBackground(darkBackground, resources.displayMetrics.density)
        ubuntuText.background = glassStatusBackground(darkBackground, resources.displayMetrics.density)
        rootBadge.background = glassStatusBackground(darkBackground, resources.displayMetrics.density)
        rootLabel.setTextColor(foreground)
        val buttonIcon = if (luminance < 150) Color.rgb(30, 35, 42) else Color.WHITE
        val buttonFill = if (luminance < 150) {
            Color.argb(190, 255, 255, 255)
        } else {
            Color.argb(150, 20, 26, 34)
        }
        val buttonEdge = if (luminance < 150) {
            Color.argb(225, 255, 255, 255)
        } else {
            Color.argb(220, 255, 255, 255)
        }
        for (index in 0 until cardRoot.childCount) {
            val child = cardRoot.getChildAt(index)
            if (child is ViewGroup) {
                for (childIndex in 0 until child.childCount) {
                    (child.getChildAt(childIndex) as? ImageButton)?.apply {
                        background = GradientDrawable().apply {
                            setColor(buttonFill)
                            cornerRadius = Ui.dp(10, resources.displayMetrics.density).toFloat()
                            setStroke(Ui.dp(1, resources.displayMetrics.density), buttonEdge)
                        }
                        elevation = Ui.dp(4, resources.displayMetrics.density).toFloat()
                        imageTintList = android.content.res.ColorStateList.valueOf(buttonIcon)
                    }
                }
            }
        }
    }

    private fun setTextColorRecursively(view: View, color: Int) {
        if (view is TextView) view.setTextColor(color)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setTextColorRecursively(view.getChildAt(index), color)
            }
        }
    }
}
