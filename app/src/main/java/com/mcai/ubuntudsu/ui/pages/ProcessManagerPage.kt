package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.core.AppProcessInfo
import com.mcai.ubuntudsu.core.ProcessScanner
import com.mcai.ubuntudsu.core.RootShell
import com.mcai.ubuntudsu.core.SortMode
import com.mcai.ubuntudsu.ui.Ui
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ProcessManagerPage(
    private val activity: Activity,
    private val onDismiss: (() -> Unit)? = null,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var refreshBtn: TextView
    private lateinit var summaryCpu: View
    private lateinit var summaryMem: View
    private lateinit var summaryRunning: View

    private var currentTab = 0 // 0:运行中 1:CPU 2:内存 3:后台耗电
    private var showSystemApps = false
    private var currentApps = emptyList<AppProcessInfo>()
    private var isLoading = false

    private val tabTitles = listOf("正在运行", "CPU 占用", "内存占用", "后台耗电")

    // 应用图标缓存
    private val iconCache = object : LruCache<String, Drawable>(80) {
        override fun sizeOf(key: String, value: Drawable): Int = 1
    }

    private fun getAppIcon(packageName: String): Drawable? {
        val cached = iconCache.get(packageName)
        if (cached != null) return cached
        return runCatching {
            val pm = activity.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val icon = pm.getApplicationIcon(appInfo)
            iconCache.put(packageName, icon)
            icon
        }.getOrNull()
    }

    fun build(): View {
        val d = activity.resources.displayMetrics.density
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(8, d))
        }

        // ===== 标题栏（返回在左，标题居中，开关在右）=====
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(12, d))
        }
        // 左侧：返回按钮
        val backBtn = TextView(activity).apply {
            text = "‹ 返回"
            textSize = 13f
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonPrimary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            setOnClickListener {
                onDismiss?.invoke()
                activity.onBackPressed()
            }
        }
        titleRow.addView(backBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        // 中间：标题
        val titleText = TextView(activity).apply {
            text = "进程管理"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleText)
        // 右侧：系统应用开关
        titleRow.addView(buildToggleSystemBtn(d), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        page.addView(titleRow)

        // ===== 概览卡片 =====
        val overview = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            elevation = Ui.dp(3, d).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        overview.addView(TextView(activity).apply {
            text = "系统概览"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        val metricRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(8, d), 0, 0)
        }
        summaryCpu = buildMetric("CPU", "--", Ui.buttonPrimary(activity), d)
        summaryMem = buildMetric("内存", "--", Ui.buttonSecondary(activity), d)
        summaryRunning = buildMetric("运行中", "--", Ui.buttonSuccess(activity), d)
        metricRow.addView(summaryCpu, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        metricRow.addView(summaryMem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(6, d) })
        metricRow.addView(summaryRunning, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(6, d) })
        overview.addView(metricRow)
        overview.addView(TextView(activity).apply {
            text = "提示：结束进程需 ROOT 权限，点击应用可查看详情并强制停止"
            textSize = 10f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(6, d), 0, 0)
        })
        page.addView(overview)

        // ===== Tab 切换 =====
        page.addView(buildTabBar(d))

        // ===== 状态栏 + 刷新 =====
        val statusBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(10, d), 0, Ui.dp(6, d))
        }
        statusText = TextView(activity).apply {
            text = "加载中..."
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBar.addView(statusText)
        refreshBtn = TextView(activity).apply {
            text = "刷新"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(4, d), Ui.dp(12, d), Ui.dp(4, d))
            setOnClickListener { loadData() }
        }
        statusBar.addView(refreshBtn)
        page.addView(statusBar)

        // ===== 列表 =====
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scrollView.addView(listContainer)
        page.addView(scrollView)

        // 首次加载
        loadData()

        return page
    }

    // ===== 顶部 Tab 栏 =====
    private fun buildTabBar(d: Float): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.glassSurface(activity, 14f)
            elevation = Ui.dp(2, d).toFloat()
            setPadding(Ui.dp(4, d), Ui.dp(4, d), Ui.dp(4, d), Ui.dp(4, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(36, d),
            )
        }
        tabTitles.forEachIndexed { index, title ->
            val tab = TextView(activity).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, if (index == currentTab) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(
                    if (index == currentTab) Ui.buttonText(activity)
                    else Ui.secondaryText(activity)
                )
                background = if (index == currentTab) {
                    Ui.glassButton(activity, Ui.buttonPrimary(activity))
                } else {
                    Ui.rounded(Color.TRANSPARENT, 10f, d)
                }
                Ui.pressAnimation(this)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index > 0) marginStart = Ui.dp(2, d)
                }
                setOnClickListener {
                    if (currentTab != index) {
                        currentTab = index
                        updateTabStyles(bar, d)
                        loadData()
                    }
                }
            }
            bar.addView(tab)
        }
        return bar
    }

    private fun updateTabStyles(bar: LinearLayout, d: Float) {
        for (i in 0 until bar.childCount) {
            val tab = bar.getChildAt(i) as TextView
            val active = i == currentTab
            tab.setTypeface(tab.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            tab.setTextColor(if (active) Ui.buttonText(activity) else Ui.secondaryText(activity))
            tab.background = if (active) {
                Ui.glassButton(activity, Ui.buttonPrimary(activity))
            } else {
                Ui.rounded(Color.TRANSPARENT, 10f, d)
            }
        }
    }

    // ===== 系统应用开关 =====
    private fun buildToggleSystemBtn(d: Float): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(6, d), Ui.dp(3, d), Ui.dp(6, d), Ui.dp(3, d))
            background = Ui.rounded(
                if (Ui.isDark(activity)) Color.argb(40, 255, 255, 255) else Color.argb(50, 0, 0, 0),
                10f, d
            )
            Ui.pressAnimation(this)
            addView(TextView(activity).apply {
                text = "系统应用"
                textSize = 10f
                setTextColor(Ui.secondaryText(activity))
            })
            // 开关指示圆点
            addView(View(activity).apply {
                val size = Ui.dp(14, d)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = Ui.dp(4, d) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (showSystemApps) Ui.buttonSuccess(activity) else Ui.secondaryText(activity))
                    alpha = if (showSystemApps) 255 else 120
                }
            })
            setOnClickListener {
                showSystemApps = !showSystemApps
                // 更新开关状态显示
                (getChildAt(1) as? View)?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (showSystemApps) Ui.buttonSuccess(activity) else Ui.secondaryText(activity))
                    alpha = if (showSystemApps) 255 else 120
                }
                loadData()
            }
        }
    }

    // ===== 概览指标 =====
    private fun buildMetric(label: String, value: String, color: Int, d: Float): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(8, d), Ui.dp(6, d), Ui.dp(8, d), Ui.dp(6, d))
            background = Ui.rounded(
                if (Ui.isDark(activity)) Color.argb(30, 255, 255, 255) else Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)),
                10f, d
            )
        }
        card.addView(TextView(activity).apply {
            text = value
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
        })
        card.addView(TextView(activity).apply {
            text = label
            textSize = 10f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        })
        return card
    }

    // ===== 数据加载 =====
    private fun loadData() {
        if (isLoading) return
        isLoading = true
        statusText.text = "正在扫描..."
        refreshBtn.isEnabled = false
        refreshBtn.alpha = 0.5f

        executor.execute {
            try {
                val sortMode = when (currentTab) {
                    0 -> SortMode.CPU
                    1 -> SortMode.CPU
                    2 -> SortMode.MEMORY
                    3 -> SortMode.FOREGROUND_TIME
                    else -> SortMode.CPU
                }

                val apps = ProcessScanner.scanApps(activity, sortMode)

                // 过滤
                val filtered = apps.filter { app ->
                    if (!showSystemApps && app.isSystemApp && !app.isOwnApp) return@filter false
                    if (currentTab == 0) app.isRunning || app.hasRunningService
                    else true
                }

                currentApps = filtered

                // 统计数据
                val runningCount = filtered.count { it.isRunning }
                val totalCpu = filtered.sumOf { it.cpuPercent.toDouble() }.toFloat()
                val totalMem = filtered.sumOf { it.memoryKb }

                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    (summaryCpu as? LinearLayout)?.let {
                        (it.getChildAt(0) as? TextView)?.text = "%.1f%%".format(totalCpu)
                    }
                    (summaryMem as? LinearLayout)?.let {
                        (it.getChildAt(0) as? TextView)?.text = ProcessScanner.formatMemory(totalMem)
                    }
                    (summaryRunning as? LinearLayout)?.let {
                        (it.getChildAt(0) as? TextView)?.text = "$runningCount"
                    }
                    renderList(filtered)
                    statusText.text = "共 ${filtered.size} 个应用"
                    refreshBtn.isEnabled = true
                    refreshBtn.alpha = 1f
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    statusText.text = "加载失败: ${e.message?.take(30)}"
                    refreshBtn.isEnabled = true
                    refreshBtn.alpha = 1f
                }
            } finally {
                isLoading = false
            }
        }
    }

    // ===== 列表渲染 =====
    private fun renderList(apps: List<AppProcessInfo>) {
        val d = activity.resources.displayMetrics.density
        listContainer.removeAllViews()

        if (apps.isEmpty()) {
            listContainer.addView(TextView(activity).apply {
                text = "暂无数据"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Ui.secondaryText(activity))
                setPadding(0, Ui.dp(40, d), 0, Ui.dp(40, d))
            })
            return
        }

        apps.forEachIndexed { index, app ->
            val item = buildAppItem(app)
            listContainer.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) topMargin = Ui.dp(6, d)
            })
        }
    }

    private fun buildAppItem(app: AppProcessInfo): View {
        val d = activity.resources.displayMetrics.density

        val item = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
            background = Ui.glassSurface(activity, 14f)
            elevation = Ui.dp(2, d).toFloat()
            isClickable = true
            isFocusable = true
        }
        Ui.pressAnimation(item)

        // 第一行：图标 + 名称 + 主指标
        val row1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 应用图标（真实应用图标）
        val iconBox = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(40, d), Ui.dp(40, d))
            gravity = Gravity.CENTER
        }
        val appIcon = getAppIcon(app.packageName)
        if (appIcon != null) {
            iconBox.addView(ImageView(activity).apply {
                setImageDrawable(appIcon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
            })
        } else {
            // 兜底：首字母图标
            iconBox.background = Ui.rounded(
                if (Ui.isDark(activity)) Color.argb(50, 255, 255, 255) else Color.argb(60, 200, 200, 220),
                10f, d
            )
            iconBox.addView(TextView(activity).apply {
                text = app.appLabel.take(1)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.buttonPrimary(activity))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
            })
        }
        row1.addView(iconBox)

        // 名称 + 包名
        val nameCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(10, d)
                marginEnd = Ui.dp(8, d)
            }
        }
        nameCol.addView(TextView(activity).apply {
            text = app.appLabel
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        nameCol.addView(TextView(activity).apply {
            text = buildString {
                append(app.packageName)
                if (app.processCount > 0) append("  ·  ${app.processCount}进程")
            }
            textSize = 10f
            setTextColor(Ui.secondaryText(activity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(2, d), 0, 0)
        })
        row1.addView(nameCol)

        // 主指标值
        val mainValue = when (currentTab) {
            0, 1 -> ProcessScanner.formatCpu(app.cpuPercent)
            2 -> ProcessScanner.formatMemory(app.memoryKb)
            3 -> ProcessScanner.formatForegroundTime(app.foregroundTimeMs)
            else -> ProcessScanner.formatCpu(app.cpuPercent)
        }
        val mainColor = when {
            currentTab == 0 && !app.isRunning -> Ui.secondaryText(activity)
            currentTab == 1 && app.cpuPercent >= 20f -> Ui.buttonDanger(activity)
            currentTab == 1 && app.cpuPercent >= 5f -> Ui.buttonWarning(activity)
            currentTab == 2 && app.memoryKb >= 200 * 1024 -> Ui.buttonDanger(activity)
            currentTab == 2 && app.memoryKb >= 100 * 1024 -> Ui.buttonWarning(activity)
            else -> Ui.buttonPrimary(activity)
        }
        row1.addView(TextView(activity).apply {
            text = mainValue
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(mainColor)
            gravity = Gravity.CENTER
        })

        item.addView(row1)

        // 第二行：状态标签 + 操作按钮
        val row2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(6, d), 0, 0)
        }

        // 状态标签
        val tagContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (app.isOwnApp) {
            tagContainer.addView(buildTag("本应用", Ui.buttonPrimary(activity), d))
        } else if (app.isSystemApp) {
            tagContainer.addView(buildTag("系统", Ui.secondaryText(activity), d, true))
        }
        if (app.isRunning) {
            tagContainer.addView(buildTag("运行中", Ui.buttonSuccess(activity), d).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = Ui.dp(4, d)
            })
        } else if (app.hasRunningService) {
            tagContainer.addView(buildTag("服务中", Ui.buttonWarning(activity), d).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = Ui.dp(4, d)
            })
        }
        row2.addView(tagContainer)

        // 详细信息按钮
        row2.addView(TextView(activity).apply {
            text = "详情"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Ui.secondaryText(activity))
            setPadding(Ui.dp(8, d), Ui.dp(3, d), Ui.dp(8, d), Ui.dp(3, d))
            background = Ui.rounded(
                if (Ui.isDark(activity)) Color.argb(40, 255, 255, 255) else Color.argb(30, 100, 100, 120),
                8f, d
            )
            Ui.pressAnimation(this)
            setOnClickListener { showAppDetail(app) }
        })

        // 强制停止按钮（非本应用才显示）
        if (!app.isOwnApp) {
            row2.addView(TextView(activity).apply {
                text = "结束"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Ui.buttonText(activity))
                background = Ui.glassButton(activity, Ui.buttonDanger(activity))
                Ui.pressAnimation(this)
                setPadding(Ui.dp(10, d), Ui.dp(3, d), Ui.dp(10, d), Ui.dp(3, d))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(6, d) }
                setOnClickListener { showKillConfirm(app) }
            })
        }

        item.addView(row2)

        // 点击整个条目打开详情
        item.setOnClickListener { showAppDetail(app) }

        return item
    }

    private fun buildTag(text: String, color: Int, d: Float, muted: Boolean = false): View {
        return TextView(activity).apply {
            this.text = text
            textSize = 9f
            setTextColor(if (muted) Ui.secondaryText(activity) else color)
            gravity = Gravity.CENTER
            background = Ui.rounded(
                if (muted) {
                    if (Ui.isDark(activity)) Color.argb(50, 150, 150, 150) else Color.argb(40, 180, 180, 180)
                } else {
                    if (Ui.isDark(activity)) Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
                    else Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))
                },
                6f, d
            )
            setPadding(Ui.dp(5, d), Ui.dp(1, d), Ui.dp(5, d), Ui.dp(1, d))
        }
    }

    // ===== 应用详情 =====
    private fun showAppDetail(app: AppProcessInfo) {
        val d = activity.resources.displayMetrics.density
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(16, d), Ui.dp(20, d), Ui.dp(16, d))
        }

        // 应用名称
        view.addView(TextView(activity).apply {
            text = app.appLabel
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        view.addView(TextView(activity).apply {
            text = app.packageName
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(12, d))
        })

        // 详细数据
        val details = listOf(
            "运行状态" to if (app.isRunning) "正在运行" else if (app.hasRunningService) "服务运行中" else "未运行",
            "进程数" to "${app.processCount} 个",
            "CPU 占用" to ProcessScanner.formatCpu(app.cpuPercent),
            "内存占用" to ProcessScanner.formatMemory(app.memoryKb),
            "前台运行" to ProcessScanner.formatForegroundTime(app.foregroundTimeMs),
            "最后使用" to ProcessScanner.formatLastUsed(app.lastUsedTime),
            "应用类型" to if (app.isSystemApp) "系统应用" else "用户应用",
        )

        for ((label, value) in details) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(6, d), 0, Ui.dp(6, d))
            }
            row.addView(TextView(activity).apply {
                text = label
                textSize = 12f
                setTextColor(Ui.secondaryText(activity))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(activity).apply {
                text = value
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
            })
            view.addView(row)
        }

        val builder = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("关闭", null)

        if (!app.isOwnApp) {
            builder.setPositiveButton("强制停止") { _, _ ->
                killApp(app)
            }
        }

        builder.setNeutralButton("应用信息") { _, _ ->
            // 打开系统应用详情页
            runCatching {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", app.packageName, null)
                activity.startActivity(intent)
            }
        }

        builder.show()
    }

    // ===== 结束进程确认 =====
    private fun showKillConfirm(app: AppProcessInfo) {
        AlertDialog.Builder(activity)
            .setTitle("强制停止应用")
            .setMessage(
                "确定要强制停止「${app.appLabel}」吗？\n\n" +
                    "包名: ${app.packageName}\n" +
                    "进程数: ${app.processCount}\n" +
                    "CPU: ${ProcessScanner.formatCpu(app.cpuPercent)}\n" +
                    "内存: ${ProcessScanner.formatMemory(app.memoryKb)}\n\n" +
                    "强制停止后，该应用的所有服务和后台进程将被终止。"
            )
            .setPositiveButton("强制停止") { _, _ -> killApp(app) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun killApp(app: AppProcessInfo) {
        val loading = AlertDialog.Builder(activity)
            .setTitle("正在停止")
            .setMessage("正在停止 ${app.appLabel}...")
            .setCancelable(false)
            .show()

        executor.execute {
            val success = ProcessScanner.killAppProcesses(app.packageName, activity)

            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                runCatching { loading.dismiss() }

                if (success) {
                    AlertDialog.Builder(activity)
                        .setTitle("操作成功")
                        .setMessage("「${app.appLabel}」已强制停止。")
                        .setPositiveButton("确定") { _, _ -> loadData() }
                        .show()
                } else {
                    val hasRoot = runCatching { RootShell.available() }.getOrDefault(false)
                    val msg = if (!hasRoot) {
                        "操作失败，需要 ROOT 权限才能强制停止应用。"
                    } else {
                        "操作失败，该应用可能无法被终止或已自动重启。"
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("操作失败")
                        .setMessage(msg)
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }
    }
}
