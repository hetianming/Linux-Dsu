package com.mcai.ubuntudsu

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mcai.ubuntudsu.service.DownloadService
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DownloadsActivity : AppCompatActivity() {

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
        )

        private val tasks = ConcurrentHashMap<String, DownloadTask>()
        fun getTasks(): Map<String, DownloadTask> = tasks.toMap()
        fun addTask(task: DownloadTask) { tasks[task.id] = task }
        fun removeTask(id: String) { tasks.remove(id) }
    }

    private lateinit var taskContainer: LinearLayout
    private lateinit var emptyView: TextView
    private var downloadReceiver: BroadcastReceiver? = null
    private val taskViews = mutableMapOf<String, TaskViewHolder>()

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
            text = "清空"
            textSize = 12f
            setTextColor(Color.parseColor("#F44336"))
            gravity = Gravity.CENTER
            setPadding(Ui.dp(8, d), Ui.dp(4, d), Ui.dp(8, d), Ui.dp(4, d))
            background = Ui.glassSurface(this@DownloadsActivity, 8f)
            setOnClickListener {
                Haptics.perform(this)
                clearCompletedTasks()
            }
        })
        page.addView(titleRow)

        emptyView = TextView(this).apply {
            text = "暂无下载任务\n在 ROM 固件页选择设备开始下载"
            textSize = 13f
            setTextColor(Ui.secondaryText(this@DownloadsActivity))
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(40, d), 0, Ui.dp(40, d))
            visibility = View.GONE
        }
        page.addView(emptyView)

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
        refreshTaskList()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadReceiver?.let { unregisterReceiver(it) }
    }

    private fun registerDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME) ?: ""
                val deviceName = intent.getStringExtra(DownloadService.EXTRA_DEVICE) ?: ""
                val taskId = fileName.ifBlank { "unknown" }

                when {
                    intent.hasExtra(DownloadService.EXTRA_DONE) -> {
                        val success = intent.getBooleanExtra(DownloadService.EXTRA_SUCCESS, false)
                        val msg = intent.getStringExtra(DownloadService.EXTRA_MESSAGE) ?: ""
                        val savedPath = intent.getStringExtra(DownloadService.EXTRA_SAVED_PATH) ?: ""
                        // Only update existing tasks, don't create new ghost entries on cancel/complete
                        if (tasks.containsKey(taskId)) {
                            val task = tasks[taskId]!!
                            task.state = if (success) 2 else 4
                            task.status = if (success) "完成" else "失败: $msg"
                            task.savedPath = savedPath
                            task.progress = if (success) 100 else task.progress
                            refreshTaskList()
                        }
                    }
                    intent.hasExtra(DownloadService.EXTRA_STATE) -> {
                        val state = intent.getIntExtra(DownloadService.EXTRA_STATE, 0)
                        val progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0)
                        val speed = intent.getStringExtra(DownloadService.EXTRA_SPEED) ?: ""
                        val status = intent.getStringExtra(DownloadService.EXTRA_STATUS_TEXT) ?: ""
                        val task = tasks[taskId] ?: DownloadTask(taskId).also { tasks[taskId] = it }
                        task.fileName = fileName
                        task.deviceName = deviceName
                        task.progress = progress
                        task.speed = speed
                        task.status = status
                        task.state = state
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

    private fun refreshTaskList() {
        val activeTasks = tasks.values.sortedByDescending { it.startTime }
        emptyView.visibility = if (activeTasks.isEmpty()) View.VISIBLE else View.GONE
        val toRemove = taskViews.keys.filter { id -> activeTasks.none { it.id == id } }
        toRemove.forEach { taskViews.remove(it) }
        taskContainer.removeAllViews()
        if (activeTasks.isEmpty()) return
        val d = resources.displayMetrics.density
        activeTasks.forEach { task ->
            val holder = taskViews.getOrPut(task.id) { TaskViewHolder(this, task.id) }
            holder.update(task)
            taskContainer.addView(holder.rootView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(8, d) })
        }
    }

    private fun clearCompletedTasks() {
        val completed = tasks.filter { it.value.state == 2 || it.value.state == 3 || it.value.state == 4 }.keys
        completed.forEach { tasks.remove(it) }
        refreshTaskList()
    }

    private inner class TaskViewHolder(
        private val ctx: Context,
        private val taskId: String,
    ) {
        lateinit var rootView: LinearLayout
        private lateinit var fileNameText: TextView
        private lateinit var deviceText: TextView
        private lateinit var progressBar: ProgressBar
        private lateinit var progressPercentText: TextView
        private lateinit var speedText: TextView
        private lateinit var etaText: TextView
        private lateinit var pauseBtn: TextView
        private lateinit var cancelBtn: TextView

        init { buildView() }

        private fun buildView() {
            val d = ctx.resources.displayMetrics.density
            rootView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
                background = Ui.glassSurface(ctx as Activity, 14f)
            }

            // 文件名行
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fileNameText = TextView(ctx).apply {
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(ctx as Activity))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row1.addView(fileNameText)
            rootView.addView(row1)

            // 设备名
            deviceText = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Ui.secondaryText(ctx as Activity))
                setPadding(0, Ui.dp(2, d), 0, Ui.dp(4, d))
            }
            rootView.addView(deviceText)

            // 进度行：百分比左侧 + 进度条右侧
            val progressRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(4, d), 0, Ui.dp(4, d))
            }
            progressPercentText = TextView(ctx).apply {
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(8, d) }
            }
            // 绿色底 + 蓝色进度条，8dp粗
            progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(8, d), 1f)
                progressDrawable = ContextCompat.getDrawable(ctx, android.R.drawable.progress_horizontal)
                // 自定义进度条颜色：绿色底，蓝色进度
                // 使用 LayerDrawable 的默认样式，通过 tint 控制颜色
                // 这里使用系统默认样式，但通过progressTint和progressBackgroundTint设置
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4285F4"))
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                }
            }
            progressRow.addView(progressPercentText)
            progressRow.addView(progressBar)
            rootView.addView(progressRow)

            // 信息行：速度 | 已用时间/预计完成时间
            val infoRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            speedText = TextView(ctx).apply {
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(ctx as Activity))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            etaText = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Ui.secondaryText(ctx as Activity))
                setPadding(Ui.dp(8, d), 0, 0, 0)
            }
            infoRow.addView(speedText)
            infoRow.addView(etaText)
            rootView.addView(infoRow)

            // 操作按钮行
            val actionRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(8, d), 0, 0)
            }
            pauseBtn = TextView(ctx).apply {
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(16, d), Ui.dp(8, d), Ui.dp(16, d), Ui.dp(8, d))
                background = Ui.glassButton(ctx as Activity, Ui.buttonPrimary(ctx as Activity))
                setTextColor(Ui.buttonText(ctx as Activity))
                setOnClickListener {
                    Haptics.perform(this)
                    togglePauseResume()
                }
            }
            cancelBtn = TextView(ctx).apply {
                text = "取消"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(16, d), Ui.dp(8, d), Ui.dp(16, d), Ui.dp(8, d))
                background = Ui.glassButton(ctx as Activity, Color.parseColor("#F44336"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    Haptics.perform(this)
                    doCancel()
                }
            }
            actionRow.addView(pauseBtn)
            actionRow.addView(cancelBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = Ui.dp(8, d) })
            rootView.addView(actionRow)
        }

        fun update(task: DownloadTask) {
            fileNameText.text = task.fileName.ifBlank { "未知文件" }
            deviceText.text = task.deviceName.ifBlank { "" }
            progressBar.progress = task.progress
            progressBar.max = 100
            progressPercentText.text = "${task.progress}%"

            // 速度
            speedText.text = task.speed.ifBlank { "" }

            // 时间计算
            val elapsedTime = if (task.startTime > 0) {
                formatDuration(System.currentTimeMillis() - task.startTime)
            } else {
                "00:00"
            }
            val totalETA = calculateETA(task)
            etaText.text = if (totalETA.isNotBlank()) "$elapsedTime / $totalETA" else elapsedTime

            // 按钮状态
            when (task.state) {
                1 -> { // downloading
                    pauseBtn.text = "暂停"
                    pauseBtn.visibility = View.VISIBLE
                    cancelBtn.visibility = View.VISIBLE
                }
                5 -> { // paused
                    pauseBtn.text = "继续"
                    pauseBtn.visibility = View.VISIBLE
                    cancelBtn.visibility = View.VISIBLE
                }
                2, 3, 4 -> { // done / cancelled / failed
                    pauseBtn.visibility = View.GONE
                    cancelBtn.visibility = View.GONE
                }
                else -> {
                    pauseBtn.text = "暂停"
                    pauseBtn.visibility = View.VISIBLE
                    cancelBtn.visibility = View.VISIBLE
                }
            }
        }

        private fun formatDuration(ms: Long): String {
            val seconds = ms / 1000
            val hours = seconds / 3600
            val mins = (seconds % 3600) / 60
            val secs = seconds % 60
            return if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, mins, secs)
            } else {
                String.format("%02d:%02d", mins, secs)
            }
        }

        private fun calculateETA(task: DownloadTask): String {
            if (task.state != 1 || task.progress <= 0 || task.speed.isBlank()) {
                return if (task.state == 2) "已完成" else ""
            }
            val bps = parseSpeed(task.speed)
            if (bps > 0) {
                val elapsedMs = System.currentTimeMillis() - task.startTime
                val elapsedSec = elapsedMs / 1000.0
                val downloaded = bps * elapsedSec
                val total = downloaded * 100.0 / task.progress
                val remaining = total - downloaded
                if (remaining > 0) {
                    val remainingSec = (remaining / bps).toLong()
                    return formatDuration(remainingSec * 1000)
                }
            }
            return ""
        }

        private fun parseSpeed(speedStr: String): Double {
            if (speedStr.isBlank()) return 0.0
            val speedMatch = Regex("""([\d.]+)\s*(\w+)/s""").find(speedStr)
            if (speedMatch != null) {
                val value = speedMatch.groupValues[1].toDoubleOrNull() ?: return 0.0
                val unit = speedMatch.groupValues[2]
                return when (unit.uppercase()) {
                    "B" -> value
                    "KB" -> value * 1024
                    "MB" -> value * 1024 * 1024
                    "GB" -> value * 1024 * 1024 * 1024
                    else -> value * 1024
                }
            }
            return 0.0
        }

        private fun togglePauseResume() {
            val task = tasks[taskId] ?: return
            when (task.state) {
                1 -> { // downloading -> pause
                    task.state = 5
                    task.status = "已暂停"
                    sendBroadcast(Intent(ACTION_PAUSE).apply { setPackage(packageName) })
                    refreshTaskList()
                }
                5 -> { // paused -> resume
                    task.state = 1
                    task.status = "下载中"
                    sendBroadcast(Intent(ACTION_RESUME).apply { setPackage(packageName) })
                    refreshTaskList()
                }
            }
        }

        private fun doCancel() {
            startService(Intent(this@DownloadsActivity, DownloadService::class.java).apply {
                action = DownloadService.ACTION_CANCEL
            })
            tasks.remove(taskId)
            taskViews.remove(taskId)
            refreshTaskList()
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        ev?.let { Haptics.onTouch(window.decorView, it) }
        return super.dispatchTouchEvent(ev)
    }
}
