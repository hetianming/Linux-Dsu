package com.mcai.ubuntudsu

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mcai.ubuntudsu.core.JavaDownloader
import com.mcai.ubuntudsu.service.DownloadService
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载管理页（拟态方案 / Neumorphism + Glassmorphism）
 *
 * 功能：
 *  - 标签筛选：全部 / 下载中 / 已完成 / 已暂停
 *  - 任务卡片：文件图标、文件名、已下载/总大小、进度条、速度、状态
 *  - 单任务操作：暂停 / 继续 / 删除
 *  - 多选模式：长按进入选择，底部批量操作（全部开始 / 全部暂停 / 删除所选）
 *  - 新建下载：输入 URL 直接添加下载任务
 *  - 去浏览文件：打开下载目录
 *  - 日间 / 夜间模式自适应配色（夜间模式霓虹描边拟态风格）
 *
 * 状态码：0=idle 1=downloading 2=done 3=cancelled 4=failed 5=paused
 */
class DownloadsActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        const val ACTION_PAUSE = "com.mcai.ubuntudsu.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.mcai.ubuntudsu.RESUME_DOWNLOAD"

        data class DownloadTask(
            val id: String,
            var fileName: String = "",
            var deviceName: String = "",
            var progress: Int = 0,
            var speed: String = "",
            var status: String = "等待中",
            var state: Int = 0,
            var savedPath: String = "",
            var startTime: Long = 0,
            var totalSize: Long = 0,
            var downloadedBytes: Long = 0,
            var url: String = "",
            var selected: Boolean = false,
        )

        private val tasks = ConcurrentHashMap<String, DownloadTask>()
        fun getTasks(): Map<String, DownloadTask> = tasks.toMap()
        fun addTask(task: DownloadTask) { tasks[task.id] = task }
        fun removeTask(id: String) { tasks.remove(id) }
        fun getTask(id: String): DownloadTask? = tasks[id]
    }

    // 标签
    private enum class Tab(val label: String) { ALL("全部"), DOWNLOADING("下载中"), DONE("已完成"), PAUSED("已暂停") }

    private lateinit var taskContainer: LinearLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var selectedCountText: TextView
    private lateinit var tabButtons: Array<TextView>
    private var currentTab = Tab.ALL

    private var downloadReceiver: BroadcastReceiver? = null
    private val taskViews = mutableMapOf<String, TaskViewHolder>()
    private var selectionMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        Ui.animateLiquidBackground(root)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = resources.displayMetrics.density
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(8, d))
        }

        val d = resources.displayMetrics.density

        // ===== 标题栏：返回 / 下载管理 / 新建下载 =====
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(12, d))
        }
        titleRow.addView(TextView(this).apply {
            text = "‹ 返回"
            textSize = 13f
            setTextColor(Ui.buttonText(this@DownloadsActivity))
            background = Ui.glassButton(this@DownloadsActivity, Ui.buttonPrimary(this@DownloadsActivity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            setOnClickListener {
                Haptics.perform(this)
                finish()
            }
        })
        titleRow.addView(TextView(this).apply {
            text = "下载管理"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@DownloadsActivity))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "新建下载"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = primaryButtonBg()
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(7, d), Ui.dp(12, d), Ui.dp(7, d))
            setOnClickListener {
                Haptics.perform(this)
                showNewDownloadDialog()
            }
        })
        page.addView(titleRow)

        // ===== 标签栏 =====
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(10, d))
        }
        tabButtons = Array(Tab.values().size) { i ->
            val tab = Tab.values()[i]
            TextView(this).apply {
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(14, d), Ui.dp(7, d), Ui.dp(14, d), Ui.dp(7, d))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = Ui.dp(6, d)
                }
                setOnClickListener {
                    Haptics.perform(this)
                    selectTab(tab)
                }
            }
        }
        tabButtons.forEach { tabRow.addView(it) }
        page.addView(tabRow)

        // ===== 空状态 =====
        emptyView = buildEmptyView()
        page.addView(emptyView)

        // ===== 任务列表滚动区 =====
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        taskContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scrollView.addView(taskContainer)
        page.addView(scrollView)

        // ===== 底部批量操作栏 =====
        bottomBar = buildBottomBar()
        page.addView(bottomBar)

        root.addView(page)
        setContentView(root)
        Ui.enableEdgeToEdge(this, root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.ViewCompat.requestApplyInsets(page)
            insets
        }

        registerDownloadReceiver()
        selectTab(Tab.ALL)
        refreshTaskList()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadReceiver?.let { unregisterReceiver(it) }
    }

    // ==================== 配色辅助（委托全局拟态设计系统） ====================

    private fun isDark(): Boolean = Ui.isDark(this)

    /** 主操作按钮背景：全局拟态实心渐变（日间蓝 / 夜间青蓝） */
    private fun primaryButtonBg(): android.graphics.drawable.Drawable =
        if (isDark()) {
            Ui.neuSolidButton(Color.parseColor("#62A8FF"), Color.parseColor("#2E6CF0"), 12f, this)
        } else {
            Ui.neuSolidButton(Color.parseColor("#5EA0FF"), Color.parseColor("#2F6BF0"), 12f, this)
        }

    /** 成功色按钮（全部开始） */
    private fun successButtonBg(): android.graphics.drawable.Drawable =
        if (isDark()) {
            Ui.neuSolidButton(Color.parseColor("#34D399"), Color.parseColor("#15803D"), 12f, this)
        } else {
            Ui.neuSolidButton(Color.parseColor("#4ADE80"), Color.parseColor("#16A34A"), 12f, this)
        }

    /** 警告色按钮（全部暂停） */
    private fun warningButtonBg(): android.graphics.drawable.Drawable =
        if (isDark()) {
            Ui.neuSolidButton(Color.parseColor("#FBBF24"), Color.parseColor("#B45309"), 12f, this)
        } else {
            Ui.neuSolidButton(Color.parseColor("#FBBF24"), Color.parseColor("#D97706"), 12f, this)
        }

    /** 危险色按钮（删除） */
    private fun dangerButtonBg(): android.graphics.drawable.Drawable =
        if (isDark()) {
            Ui.neuSolidButton(Color.parseColor("#F87171"), Color.parseColor("#B91C1C"), 12f, this)
        } else {
            Ui.neuSolidButton(Color.parseColor("#F87171"), Color.parseColor("#DC2626"), 12f, this)
        }

    /** 次要描边按钮（暂停 / 继续 / 删除 单任务）：accent 玻璃底 + 描边 */
    private fun outlineButtonBg(color: Int): GradientDrawable {
        val d = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(Color.argb(if (isDark()) 30 else 40, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = Ui.dp(10, d).toFloat()
            setStroke(Ui.dp(1, d), Color.argb(if (isDark()) 200 else 220, Color.red(color), Color.green(color), Color.blue(color)))
        }
    }

    /** 任务卡片背景：全局拟态卡片 + accent 霓虹描边（夜间） */
    private fun taskCardBg(accent: Int): android.graphics.drawable.Drawable =
        Ui.neuCard(this, 16f, accent)

    // ==================== 标签 ====================

    private fun selectTab(tab: Tab) {
        currentTab = tab
        val d = resources.displayMetrics.density
        Tab.values().forEachIndexed { i, t ->
            val btn = tabButtons[i]
            val active = t == tab
            if (active) {
                btn.setTextColor(Color.WHITE)
                btn.background = if (isDark()) {
                    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#2563EB"), Color.parseColor("#1D4ED8"))).apply {
                        cornerRadius = Ui.dp(10, d).toFloat()
                        setStroke(Ui.dp(1, d), Color.argb(220, 100, 160, 255))
                    }
                } else {
                    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#2563EB"))).apply {
                        cornerRadius = Ui.dp(10, d).toFloat()
                    }
                }
            } else {
                btn.setTextColor(Ui.secondaryText(this))
                btn.background = Ui.glassSurface(this, 10f)
            }
        }
        refreshTaskList()
    }

    private fun updateTabLabels() {
        val all = tasks.values
        val counts = mapOf(
            Tab.ALL to all.size,
            Tab.DOWNLOADING to all.count { it.state == 1 },
            Tab.DONE to all.count { it.state == 2 },
            Tab.PAUSED to all.count { it.state == 5 },
        )
        Tab.values().forEachIndexed { i, t ->
            tabButtons[i].text = "${t.label} ${counts[t]}"
        }
    }

    // ==================== 空状态 ====================

    private fun buildEmptyView(): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, Ui.dp(40, d), 0, Ui.dp(40, d))
            visibility = View.GONE

            // 图标
            addView(TextView(this@DownloadsActivity).apply {
                text = "📄"
                textSize = 48f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@DownloadsActivity).apply {
                text = "暂无下载任务"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(this@DownloadsActivity))
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(12, d), 0, 0)
            })
            addView(TextView(this@DownloadsActivity).apply {
                text = "去浏览文件或新建下载任务"
                textSize = 12f
                setTextColor(Ui.secondaryText(this@DownloadsActivity))
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(4, d), 0, Ui.dp(16, d))
            })

            // 按钮行
            val btnRow = LinearLayout(this@DownloadsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            btnRow.addView(TextView(this@DownloadsActivity).apply {
                text = "去浏览文件"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.buttonPrimary(this@DownloadsActivity))
                background = outlineButtonBg(Ui.buttonPrimary(this@DownloadsActivity))
                Ui.pressAnimation(this)
                setPadding(Ui.dp(16, d), Ui.dp(8, d), Ui.dp(16, d), Ui.dp(8, d))
                setOnClickListener {
                    Haptics.perform(this)
                    openDownloadFolder()
                }
            })
            btnRow.addView(TextView(this@DownloadsActivity).apply {
                text = "新建下载"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = primaryButtonBg()
                Ui.pressAnimation(this)
                setPadding(Ui.dp(16, d), Ui.dp(8, d), Ui.dp(16, d), Ui.dp(8, d))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(10, d) }
                setOnClickListener {
                    Haptics.perform(this)
                    showNewDownloadDialog()
                }
            })
            addView(btnRow)
        }
    }

    // ==================== 底部批量操作栏 ====================

    private fun buildBottomBar(): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
            background = if (isDark()) {
                GradientDrawable().apply {
                    setColor(Color.argb(220, 18, 22, 36))
                    cornerRadius = Ui.dp(14, d).toFloat()
                    setStroke(Ui.dp(1, d), Color.argb(120, 120, 130, 160))
                }
            } else {
                Ui.glassSurface(this@DownloadsActivity, 14f)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(8, d) }
            visibility = View.GONE

            selectedCountText = TextView(this@DownloadsActivity).apply {
                text = "已选 0 项"
                textSize = 12f
                setTextColor(Ui.primaryText(this@DownloadsActivity))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(selectedCountText)

            val btnStart = TextView(this@DownloadsActivity).apply {
                text = "全部开始"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = successButtonBg()
                Ui.pressAnimation(this)
                setPadding(Ui.dp(12, d), Ui.dp(7, d), Ui.dp(12, d), Ui.dp(7, d))
                setOnClickListener {
                    Haptics.perform(this)
                    batchAction(BatchAction.START)
                }
            }
            val btnPause = TextView(this@DownloadsActivity).apply {
                text = "全部暂停"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = warningButtonBg()
                Ui.pressAnimation(this)
                setPadding(Ui.dp(12, d), Ui.dp(7, d), Ui.dp(12, d), Ui.dp(7, d))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(6, d) }
                setOnClickListener {
                    Haptics.perform(this)
                    batchAction(BatchAction.PAUSE)
                }
            }
            val btnDelete = TextView(this@DownloadsActivity).apply {
                text = "删除所选"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = dangerButtonBg()
                Ui.pressAnimation(this)
                setPadding(Ui.dp(12, d), Ui.dp(7, d), Ui.dp(12, d), Ui.dp(7, d))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(6, d) }
                setOnClickListener {
                    Haptics.perform(this)
                    batchAction(BatchAction.DELETE)
                }
            }
            addView(btnStart)
            addView(btnPause)
            addView(btnDelete)
        }
    }

    private enum class BatchAction { START, PAUSE, DELETE }

    private fun batchAction(action: BatchAction) {
        val selected = tasks.values.filter { it.selected }
        if (selected.isEmpty()) {
            Toast.makeText(this, "未选择任务", Toast.LENGTH_SHORT).show()
            return
        }
        when (action) {
            BatchAction.START -> {
                selected.filter { it.state == 5 }.forEach { task ->
                    task.state = 1
                    task.status = "下载中"
                    sendBroadcast(Intent(ACTION_RESUME).apply {
                        setPackage(packageName)
                        putExtra(DownloadService.EXTRA_TASK_ID, task.id)
                    })
                }
                Toast.makeText(this, "已继续 ${selected.count { it.state == 1 }} 个任务", Toast.LENGTH_SHORT).show()
            }
            BatchAction.PAUSE -> {
                selected.filter { it.state == 1 }.forEach { task ->
                    task.state = 5
                    task.status = "已暂停"
                    sendBroadcast(Intent(ACTION_PAUSE).apply {
                        setPackage(packageName)
                        putExtra(DownloadService.EXTRA_TASK_ID, task.id)
                    })
                }
                Toast.makeText(this, "已暂停 ${selected.count { it.state == 5 }} 个任务", Toast.LENGTH_SHORT).show()
            }
            BatchAction.DELETE -> {
                AlertDialog.Builder(this)
                    .setTitle("删除所选")
                    .setMessage("确定删除选中的 ${selected.size} 个下载任务？已下载的文件不会被删除。")
                    .setPositiveButton("删除") { _, _ ->
                        selected.forEach { task ->
                            // 在途任务同步取消服务端，避免幽灵任务重新出现
                            if (task.state == 0 || task.state == 1 || task.state == 5) {
                                sendBroadcast(Intent(DownloadService.ACTION_CANCEL).apply {
                                    setPackage(packageName)
                                    putExtra(DownloadService.EXTRA_TASK_ID, task.id)
                                })
                            }
                            tasks.remove(task.id)
                        }
                        exitSelectionMode()
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
        }
        refreshTaskList()
    }

    private fun updateBottomBar() {
        val count = tasks.values.count { it.selected }
        selectedCountText.text = "已选 $count 项"
        bottomBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
    }

    private fun exitSelectionMode() {
        selectionMode = false
        tasks.values.forEach { it.selected = false }
        updateBottomBar()
        refreshTaskList()
    }

    // ==================== 广播接收 ====================

    private fun registerDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME) ?: ""
                val deviceName = intent.getStringExtra(DownloadService.EXTRA_DEVICE) ?: ""
                // 多任务路由：优先 task_id（服务端每任务唯一），回退 fileName
                val taskId = intent.getStringExtra(DownloadService.EXTRA_TASK_ID)
                    ?: fileName.ifBlank { "unknown" }

                when {
                    intent.hasExtra(DownloadService.EXTRA_LOG) -> {
                        val log = intent.getStringExtra(DownloadService.EXTRA_LOG) ?: ""
                        val task = tasks[taskId] ?: return
                        // aria2c 每秒的 CN: 摘要行不覆盖状态；诊断行（引擎/失败原因/目录回退）实时可见
                        if (log.contains("CN:") || log.isBlank()) return
                        if (task.state == 0 || task.state == 1) {
                            task.status = log.take(60)
                            refreshTaskList()
                        }
                    }
                    intent.hasExtra(DownloadService.EXTRA_DONE) -> {
                        val success = intent.getBooleanExtra(DownloadService.EXTRA_SUCCESS, false)
                        val msg = intent.getStringExtra(DownloadService.EXTRA_MESSAGE) ?: ""
                        val savedPath = intent.getStringExtra(DownloadService.EXTRA_SAVED_PATH) ?: ""
                        if (tasks.containsKey(taskId)) {
                            val task = tasks[taskId]!!
                            task.state = if (success) 2 else 4
                            task.status = if (success) "已完成" else "失败: $msg"
                            task.savedPath = savedPath
                            task.progress = if (success) 100 else task.progress
                            if (success && task.totalSize > 0) task.downloadedBytes = task.totalSize
                            refreshTaskList()
                        }
                    }
                    intent.hasExtra(DownloadService.EXTRA_STATE) -> {
                        val state = intent.getIntExtra(DownloadService.EXTRA_STATE, 0)
                        val progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0)
                        val speed = intent.getStringExtra(DownloadService.EXTRA_SPEED) ?: ""
                        val status = intent.getStringExtra(DownloadService.EXTRA_STATUS_TEXT) ?: ""
                        val total = intent.getLongExtra(DownloadService.EXTRA_TOTAL_SIZE, 0L)
                        val downloaded = intent.getLongExtra(DownloadService.EXTRA_DOWNLOADED_SIZE, 0L)
                        val task = tasks[taskId] ?: DownloadTask(taskId).also { tasks[taskId] = it }
                        task.fileName = fileName
                        task.deviceName = deviceName
                        task.progress = progress
                        task.speed = speed
                        // 本地已暂停（state=5）时忽略在途的下载进度广播（state=1），
                        // 否则按钮会被翻回「暂停」，造成"要双击才暂停"的错觉
                        if (state == 1 && task.state == 5) {
                            task.status = "已暂停"
                        } else {
                            task.status = status
                            task.state = state
                        }
                        if (total > 0) task.totalSize = total
                        if (downloaded > 0) task.downloadedBytes = downloaded
                        if (state == 1 && task.startTime == 0L) {
                            task.startTime = System.currentTimeMillis()
                        }
                        refreshTaskList()
                    }
                }
            }
        }
        val filter = IntentFilter(DownloadService.BROADCAST_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }
    }

    // ==================== 列表刷新 ====================

    /** 列表结构签名：tab + 可见任务的 id/state/选择态。签名不变 = 无结构变化，仅内容更新。
     *  进度广播每秒到达，若每次都 removeAllViews 重建卡片，点击瞬间视图被换掉会吞掉 touch 事件
     *  —— 这正是「暂停要点两次」的主因 */
    private var lastStructureKey: String? = null

    private fun structureKey(filtered: List<DownloadTask>): String = buildString {
        append(currentTab.name).append('|')
        append(if (selectionMode) "S" else "s").append('|')
        filtered.forEach { append(it.id).append(':').append(it.state).append(':').append(if (it.selected) 1 else 0).append('|') }
    }

    private fun refreshTaskList() {
        updateTabLabels()

        // 排序必须确定性：startTime 相同（杀APP重开后广播重建任务/同毫秒创建）时
        // 若依赖 HashMap values() 顺序，任务增删会引发 rehash 导致卡片位置互换
        // —— 用户点"第二张卡"暂停的却是原第一张卡的任务。加 id 次级键锁死顺序
        val all = tasks.values.sortedWith(
            compareByDescending<DownloadTask> { it.startTime }.thenBy { it.id },
        )
        val filtered = when (currentTab) {
            Tab.ALL -> all
            Tab.DOWNLOADING -> all.filter { it.state == 1 }
            Tab.DONE -> all.filter { it.state == 2 }
            Tab.PAUSED -> all.filter { it.state == 5 }
        }

        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        val key = structureKey(filtered)
        if (key == lastStructureKey) {
            // 纯进度/速度更新：只刷新已挂载卡片的内容，绝不重建视图（保住点击事件）
            filtered.forEach { task -> taskViews[task.id]?.update(task) }
            updateBottomBar()
            return
        }
        lastStructureKey = key

        taskContainer.removeAllViews()
        taskViews.clear()

        if (filtered.isEmpty()) {
            updateBottomBar()
            return
        }

        val d = resources.displayMetrics.density

        // 稳定顺序单列表：绝不按状态分组重排。
        // 分组渲染（下载中组在上/已暂停组在下）会让卡片在每次暂停/继续时跨分组跳位，
        // 用户瞄准的按钮瞬间被另一张卡占据 → "点卡片2暂停了卡片1"的错位感。
        // 顺序恒定 startTime 倒序 + id，卡片只随自身状态原地变色换按钮，位置永不动。
        filtered.forEach { task ->
            addTaskCard(task, d)
        }

        updateBottomBar()
    }

    private fun addTaskCard(task: DownloadTask, d: Float) {
        val holder = taskViews.getOrPut(task.id) { TaskViewHolder(this, task.id) }
        holder.update(task)
        // 拟态彩色投影：卡片自液态背景浮起
        val accent = holder.currentAccent
        Ui.applyNeuShadow(holder.rootView, 4f, 16f, accent)
        taskContainer.addView(holder.rootView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(8, d) })
    }

    // ==================== 新建下载 ====================

    private fun showNewDownloadDialog() {
        val d = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = "请输入下载链接 (http/https)"
            textSize = 13f
            setTextColor(Ui.primaryText(this@DownloadsActivity))
            setHintTextColor(Ui.secondaryText(this@DownloadsActivity))
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
            background = Ui.glassSurface(this@DownloadsActivity, 10f)
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(8, d), Ui.dp(20, d), Ui.dp(4, d))
            addView(input)
            addView(TextView(this@DownloadsActivity).apply {
                text = "调用内置 aria2c 多线程引擎 · 保存到 /sdcard/Downloads\n支持断点续传，可与 ROM 下载并行"
                textSize = 10f
                setTextColor(Ui.secondaryText(this@DownloadsActivity))
                setPadding(0, Ui.dp(6, d), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("新建下载")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始下载") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(this, "链接不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startUrlDownload(url)
            }
            .show()
    }

    private fun startUrlDownload(url: String) {
        val filename = JavaDownloader.fileNameFromUrl(url)
        // 同名任务仍在下载/暂停中时不重复入队
        tasks[filename]?.let {
            if (it.state == 0 || it.state == 1 || it.state == 5) {
                Toast.makeText(this, "任务「$filename」已在下载列表中", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_FILENAME, filename)
            putExtra(DownloadService.EXTRA_VERSION, "")
            putExtra(DownloadService.EXTRA_NODE_INDEX, 3)
            putExtra(DownloadService.EXTRA_LABEL, "自定义")
            putExtra(DownloadService.EXTRA_DEVICE_NAME, "自定义下载")
            // 自定义直链：走内置 aria2c 引擎（16 连接分块 + 断点续传）
            putExtra(DownloadService.EXTRA_USE_ARIA2, true)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        addTask(DownloadTask(
            id = filename,
            fileName = filename,
            deviceName = "自定义下载",
            status = "准备下载",
            state = 0,
            url = url,
            startTime = System.currentTimeMillis(),
        ))
        Toast.makeText(this, "已添加到下载队列", Toast.LENGTH_SHORT).show()
        refreshTaskList()
    }

    // ==================== 浏览文件 ====================

    private fun openDownloadFolder() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dir)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            // 回退：用文件管理器打开
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            runCatching { startActivity(Intent.createChooser(fallback, "浏览文件")) }
        }
    }

    // ==================== 工具 ====================

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format("%.1f %s", value, units[unit])
    }

    override fun onBackPressed() {
        if (selectionMode) {
            exitSelectionMode()
            return
        }
        super.onBackPressed()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        ev?.let { Haptics.onTouch(window.decorView, it) }
        return super.dispatchTouchEvent(ev)
    }

    // ==================== 任务卡片 ViewHolder ====================

    private inner class TaskViewHolder(
        private val ctx: Context,
        private val taskId: String,
    ) {
        lateinit var rootView: LinearLayout
        private lateinit var iconView: TextView
        private lateinit var fileNameText: TextView
        private lateinit var sizeText: TextView
        private lateinit var progressBar: ProgressBar
        private lateinit var progressPercentText: TextView
        private lateinit var speedText: TextView
        private lateinit var statusText: TextView
        private lateinit var checkBox: View
        private lateinit var pauseBtn: TextView
        /** 当前任务 accent 色（供外层拟态投影取用） */
        var currentAccent: Int = Color.parseColor("#64748B")
        private lateinit var deleteBtn: TextView
        private lateinit var openBtn: TextView

        init { buildView() }

        private fun buildView() {
            val d = ctx.resources.displayMetrics.density
            rootView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
                isClickable = true
                isFocusable = true
            }

            // 第一行：复选框 + 图标 + 文件名 + 大小
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            checkBox = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(18, d), Ui.dp(18, d)).apply {
                    marginEnd = Ui.dp(8, d)
                }
                visibility = View.GONE
            }
            row1.addView(checkBox)

            iconView = TextView(ctx).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(Ui.dp(36, d), Ui.dp(36, d)).apply {
                    marginEnd = Ui.dp(10, d)
                }
            }
            row1.addView(iconView)

            fileNameText = TextView(ctx).apply {
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(ctx as Activity))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row1.addView(fileNameText)

            sizeText = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Ui.secondaryText(ctx as Activity))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(8, d) }
            }
            row1.addView(sizeText)

            rootView.addView(row1)

            // 第二行：进度条 + 百分比
            val progressRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(6, d), 0, Ui.dp(4, d))
            }
            progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(8, d), 1f)
                max = 100
            }
            progressPercentText = TextView(ctx).apply {
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(ctx as Activity))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(8, d) }
            }
            progressRow.addView(progressBar)
            progressRow.addView(progressPercentText)
            rootView.addView(progressRow)

            // 第三行：速度 + 状态
            val infoRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            speedText = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Ui.secondaryText(ctx as Activity))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            statusText = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Ui.secondaryText(ctx as Activity))
            }
            infoRow.addView(speedText)
            infoRow.addView(statusText)
            rootView.addView(infoRow)

            // 第四行：操作按钮
            val actionRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(6, d), 0, 0)
            }
            pauseBtn = TextView(ctx).apply {
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(14, d), Ui.dp(6, d), Ui.dp(14, d), Ui.dp(6, d))
                setOnClickListener {
                    Haptics.perform(this)
                    togglePauseResume()
                }
            }
            deleteBtn = TextView(ctx).apply {
                text = "删除"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(14, d), Ui.dp(6, d), Ui.dp(14, d), Ui.dp(6, d))
                setOnClickListener {
                    Haptics.perform(this)
                    doDelete()
                }
            }
            openBtn = TextView(ctx).apply {
                text = "打开"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(14, d), Ui.dp(6, d), Ui.dp(14, d), Ui.dp(6, d))
                setOnClickListener {
                    Haptics.perform(this)
                    openFile()
                }
                visibility = View.GONE
            }
            actionRow.addView(pauseBtn)
            actionRow.addView(openBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = Ui.dp(6, d) })
            actionRow.addView(deleteBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = Ui.dp(6, d) })
            rootView.addView(actionRow)

            // 点击切换选择（选择模式下）
            rootView.setOnClickListener {
                if (selectionMode) {
                    toggleSelection()
                }
            }
            // 长按进入选择模式
            rootView.setOnLongClickListener {
                if (!selectionMode) {
                    selectionMode = true
                }
                toggleSelection()
                true
            }
        }

        private fun toggleSelection() {
            val task = tasks[taskId] ?: return
            task.selected = !task.selected
            update(task)
            updateBottomBar()
        }

        private fun fileAccent(name: String): Pair<String, Int> {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext in listOf("mp4", "mkv", "avi", "mov", "flv", "mpf", "webm") -> "🎬" to Color.parseColor("#8B5CF6")
                ext in listOf("zip", "rar", "7z", "tar", "gz", "tgz") -> "📦" to Color.parseColor("#F59E0B")
                ext in listOf("pdf") -> "📕" to Color.parseColor("#EF4444")
                ext in listOf("doc", "docx") -> "📘" to Color.parseColor("#3B82F6")
                ext in listOf("xls", "xlsx") -> "📗" to Color.parseColor("#22C55E")
                ext in listOf("ppt", "pptx") -> "📙" to Color.parseColor("#F97316")
                ext in listOf("mp3", "wav", "flac", "aac") -> "🎵" to Color.parseColor("#EC4899")
                ext in listOf("img", "iso") -> "💿" to Color.parseColor("#06B6D4")
                else -> "📄" to Color.parseColor("#64748B")
            }
        }

        private fun progressColors(state: Int): Pair<Int, Int> {
            // 返回 (进度色, 轨道色)
            return when (state) {
                1 -> Color.parseColor("#3B82F6") to Color.parseColor("#DBEAFE") // 下载中：蓝
                2 -> Color.parseColor("#22C55E") to Color.parseColor("#DCFCE7") // 已完成：绿
                5 -> Color.parseColor("#EF4444") to Color.parseColor("#FEE2E2") // 已暂停：红
                else -> Color.parseColor("#94A3B8") to Color.parseColor("#E2E8F0")
            }
        }

        private fun darkProgressColors(state: Int): Pair<Int, Int> {
            return when (state) {
                1 -> Color.parseColor("#60A5FA") to Color.argb(80, 60, 80, 120)
                2 -> Color.parseColor("#4ADE80") to Color.argb(80, 40, 80, 60)
                5 -> Color.parseColor("#F87171") to Color.argb(80, 120, 50, 50)
                else -> Color.parseColor("#94A3B8") to Color.argb(80, 80, 80, 90)
            }
        }

        fun update(task: DownloadTask) {
            val d = ctx.resources.displayMetrics.density
            val (icon, accent) = fileAccent(task.fileName)
            currentAccent = accent

            rootView.background = taskCardBg(accent)

            // 复选框
            if (selectionMode) {
                checkBox.visibility = View.VISIBLE
                checkBox.background = if (task.selected) {
                    GradientDrawable().apply {
                        setColor(Ui.buttonPrimary(ctx as Activity))
                        cornerRadius = Ui.dp(4, d).toFloat()
                        setStroke(Ui.dp(1, d), Ui.buttonPrimary(ctx as Activity))
                    }
                } else {
                    GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = Ui.dp(4, d).toFloat()
                        setStroke(Ui.dp(1, d), Color.argb(150, 150, 150, 150))
                    }
                }
            } else {
                checkBox.visibility = View.GONE
            }

            // 图标
            iconView.text = icon
            iconView.background = GradientDrawable().apply {
                setColor(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)))
                cornerRadius = Ui.dp(10, d).toFloat()
                setStroke(Ui.dp(1, d), Color.argb(180, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }

            fileNameText.text = task.fileName.ifBlank { "未知文件" }

            // 大小
            val sizeStr = if (task.totalSize > 0) {
                "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalSize)}"
            } else if (task.state == 2 && task.savedPath.isNotBlank()) {
                val f = File(task.savedPath)
                if (f.exists()) formatBytes(f.length()) else "已完成"
            } else {
                "—"
            }
            sizeText.text = sizeStr

            // 进度
            val (progressColor, trackColor) = if (isDark()) darkProgressColors(task.state) else progressColors(task.state)
            progressBar.progress = task.progress
            val track = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(99, d).toFloat()
                setColor(trackColor)
            }
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(99, d).toFloat()
                setColor(progressColor)
            }
            val clip = android.graphics.drawable.ClipDrawable(
                fill,
                Gravity.START,
                android.graphics.drawable.ClipDrawable.HORIZONTAL,
            )
            val layer = android.graphics.drawable.LayerDrawable(arrayOf(track, clip))
            layer.setId(0, android.R.id.background)
            layer.setId(1, android.R.id.progress)
            progressBar.progressDrawable = layer
            progressPercentText.text = "${task.progress}%"
            progressPercentText.setTextColor(progressColor)

            // 速度 / 状态
            speedText.text = if (task.state == 1 && task.speed.isNotBlank()) task.speed else ""
            statusText.text = when (task.state) {
                1 -> "下载中"
                2 -> "✓ 已完成"
                5 -> "已暂停"
                3 -> "已取消"
                4 -> "失败"
                else -> task.status
            }
            statusText.setTextColor(when (task.state) {
                2 -> Color.parseColor(if (isDark()) "#4ADE80" else "#16A34A")
                4 -> Color.parseColor(if (isDark()) "#F87171" else "#DC2626")
                5 -> Color.parseColor(if (isDark()) "#F87171" else "#DC2626")
                else -> Ui.secondaryText(ctx as Activity)
            })

            // 操作按钮
            when (task.state) {
                1 -> { // 下载中
                    pauseBtn.text = "暂停"
                    pauseBtn.setTextColor(Color.parseColor("#D97706"))
                    pauseBtn.background = outlineButtonBg(Color.parseColor("#F59E0B"))
                    pauseBtn.visibility = View.VISIBLE
                    openBtn.visibility = View.GONE
                    deleteBtn.visibility = View.VISIBLE
                    deleteBtn.setTextColor(Color.parseColor("#DC2626"))
                    deleteBtn.background = outlineButtonBg(Color.parseColor("#EF4444"))
                }
                5 -> { // 已暂停
                    pauseBtn.text = "继续"
                    pauseBtn.setTextColor(Color.parseColor("#16A34A"))
                    pauseBtn.background = outlineButtonBg(Color.parseColor("#22C55E"))
                    pauseBtn.visibility = View.VISIBLE
                    openBtn.visibility = View.GONE
                    deleteBtn.visibility = View.VISIBLE
                    deleteBtn.setTextColor(Color.parseColor("#DC2626"))
                    deleteBtn.background = outlineButtonBg(Color.parseColor("#EF4444"))
                }
                2 -> { // 已完成
                    pauseBtn.visibility = View.GONE
                    openBtn.visibility = View.VISIBLE
                    openBtn.setTextColor(Color.parseColor("#2563EB"))
                    openBtn.background = outlineButtonBg(Color.parseColor("#3B82F6"))
                    deleteBtn.visibility = View.VISIBLE
                    deleteBtn.setTextColor(Color.parseColor("#DC2626"))
                    deleteBtn.background = outlineButtonBg(Color.parseColor("#EF4444"))
                }
                else -> {
                    pauseBtn.visibility = View.GONE
                    openBtn.visibility = View.GONE
                    deleteBtn.visibility = View.VISIBLE
                    deleteBtn.setTextColor(Color.parseColor("#DC2626"))
                    deleteBtn.background = outlineButtonBg(Color.parseColor("#EF4444"))
                }
            }
        }

        private fun togglePauseResume() {
            val task = tasks[taskId] ?: return
            when (task.state) {
                1 -> {
                    task.state = 5
                    task.status = "已暂停"
                    sendBroadcast(Intent(ACTION_PAUSE).apply {
                        setPackage(packageName)
                        putExtra(DownloadService.EXTRA_TASK_ID, taskId)
                    })
                }
                5 -> {
                    task.state = 1
                    task.status = "下载中"
                    sendBroadcast(Intent(ACTION_RESUME).apply {
                        setPackage(packageName)
                        putExtra(DownloadService.EXTRA_TASK_ID, taskId)
                    })
                }
            }
            refreshTaskList()
        }

        private fun doDelete() {
            val task = tasks[taskId] ?: return
            AlertDialog.Builder(ctx)
                .setTitle("删除下载任务")
                .setMessage("确定删除「${task.fileName.ifBlank { "未知文件" }}」？\n已下载的文件不会被删除。")
                .setPositiveButton("删除") { _, _ ->
                    // 下载中/暂停中的任务删除时同步取消服务端任务，避免幽灵任务重新出现
                    if (task.state == 0 || task.state == 1 || task.state == 5) {
                        sendBroadcast(Intent(DownloadService.ACTION_CANCEL).apply {
                            setPackage(packageName)
                            putExtra(DownloadService.EXTRA_TASK_ID, taskId)
                        })
                    }
                    tasks.remove(taskId)
                    taskViews.remove(taskId)
                    refreshTaskList()
                    Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun openFile() {
            val task = tasks[taskId] ?: return
            val path = task.savedPath.ifBlank {
                File(JavaDownloader.defaultSaveDir(), task.fileName).absolutePath
            }
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(ctx, "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension) ?: "*/*"
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { ctx.startActivity(intent) }.onFailure {
                Toast.makeText(ctx, "无法打开此文件类型", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
