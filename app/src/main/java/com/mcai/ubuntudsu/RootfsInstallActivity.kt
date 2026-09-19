package com.mcai.ubuntudsu

import android.animation.ValueAnimator
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.core.InstallProgress
import com.mcai.ubuntudsu.core.RootfsInstaller
import com.mcai.ubuntudsu.ui.Ui
import java.util.concurrent.Executors

// 小窗口样式的 rootfs 安装 / 备份界面：从 Linux 页点击"安装 rootfs 系统"弹出
class RootfsInstallActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var progressCard: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var percentText: TextView
    private lateinit var urlInput: EditText
    private lateinit var backupButton: TextView
    private var scanAnim: ValueAnimator? = null

    private val pickArchiveLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            val path = result.data?.getStringExtra(RootfsFilesActivity.RESULT_FILE_PATH)
            if (path != null) installLocal(android.net.Uri.fromFile(java.io.File(path)))
        }

    private val createBackupLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/gzip"),
        ) { uri -> uri?.let { backupRootfs(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onDestroy() {
        scanAnim?.cancel()
        scanAnim = null
        super.onDestroy()
    }

    // 小窗口内容超出可用高度时内部滚动
    private inner class SmallWindowScrollView(context: Context) : ScrollView(context) {
        var maxHeightPx = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (maxHeightPx > 0 && measuredHeight > maxHeightPx) {
                setMeasuredDimension(measuredWidth, maxHeightPx)
            }
        }
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(14, d))
        }

        // 标题行 + 关闭
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(10, d))
        }
        titleRow.addView(TextView(this).apply {
            text = "安装 / 备份 rootfs"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(this@RootfsInstallActivity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "×"
            textSize = 22f
            setTextColor(Ui.secondaryText(this@RootfsInstallActivity))
            setPadding(Ui.dp(12, d), 0, Ui.dp(4, d), 0)
            setOnClickListener { finish() }
            Ui.pressAnimation(this)
        })
        page.addView(titleRow)

        // 本地安装
        val localCard = card()
        localCard.addView(sectionTitle("本地安装"))
        localCard.addView(TextView(this).apply {
            text = "从存储中选择 rootfs 压缩包（支持 .tar.gz / .tar.xz）"
            textSize = 11f
            setTextColor(Ui.secondaryText(this@RootfsInstallActivity))
            setPadding(0, Ui.dp(3, d), 0, 0)
        })
        localCard.addView(button("选择压缩包") {
            // 内置文件选择器：根目录 /sdcard，支持 tar.gz / tar.xz
            pickArchiveLauncher.launch(
                android.content.Intent(this, RootfsFilesActivity::class.java).apply {
                    putExtra(RootfsFilesActivity.EXTRA_PICK, true)
                    putExtra(RootfsFilesActivity.EXTRA_TITLE, "选择 rootfs 压缩包")
                    putExtra(RootfsFilesActivity.EXTRA_EXT, ".tar.gz,.tar.xz,.tgz,.txz")
                },
            )
        })
        page.addView(localCard)

        // 备份
        val backupCard = card()
        backupCard.addView(sectionTitle("备份 rootfs"))
        backupCard.addView(TextView(this).apply {
            text = "将已安装的 Ubuntu rootfs 打包为 .tar.gz 文件"
            textSize = 11f
            setTextColor(Ui.secondaryText(this@RootfsInstallActivity))
            setPadding(0, Ui.dp(3, d), 0, 0)
        })
        backupButton = button("选择位置并备份") { createBackupLauncher.launch("ubuntu-rootfs.tar.gz") }
        backupCard.addView(backupButton)
        page.addView(backupCard)

        // 云端下载安装
        val cloudCard = card()
        cloudCard.addView(sectionTitle("云端下载安装"))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@RootfsInstallActivity,
                android.R.layout.simple_spinner_dropdown_item,
                RootfsInstaller.mirrorPresets.map { it.first },
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(4, d) }
        }
        cloudCard.addView(spinner)
        urlInput = EditText(this).apply {
            hint = "或输入自定义镜像 URL"
            textSize = 12f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(6, d) }
        }
        cloudCard.addView(urlInput)
        cloudCard.addView(button("开始下载并安装") {
            val url = urlInput.text.toString().trim().ifEmpty {
                RootfsInstaller.mirrorPresets[spinner.selectedItemPosition].second
            }
            installCloud(url)
        })
        page.addView(cloudCard)

        // 进度
        progressCard = card().also { it.visibility = View.GONE }
        progressText = TextView(this).apply {
            text = "空闲"
            textSize = 11f
            setTextColor(Ui.secondaryText(this@RootfsInstallActivity))
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10000
            progressDrawable = Ui.pillProgressDrawable(this@RootfsInstallActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(22, d),
            ).apply { topMargin = Ui.dp(8, d) }
        }
        percentText = Ui.percentTextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(6, d) }
        }
        progressCard.addView(progressText)
        progressCard.addView(progressBar)
        progressCard.addView(percentText)
        page.addView(progressCard)

        val scroll = SmallWindowScrollView(this).apply {
            maxHeightPx = (resources.displayMetrics.heightPixels * 0.78f).toInt()
            background = Ui.frostedSurface(this@RootfsInstallActivity, 24f)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, Ui.dp(24, d).toFloat())
                }
            }
        }
        scroll.addView(page, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val windowWidth = minOf(resources.displayMetrics.widthPixels - Ui.dp(28, d), Ui.dp(420, d))
        root.addView(scroll, FrameLayout.LayoutParams(windowWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        // 小窗口点外部关闭
        setFinishOnTouchOutside(true)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        val d = resources.displayMetrics.density
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
        background = Ui.glassSurface(this@RootfsInstallActivity, 18f)
        // 圆角 outline 投影：裸 elevation 对 LayerDrawable 背景会渲染成方形影子
        Ui.applyNeuShadow(this, 3f, 18f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(8, d) }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.primaryText(this@RootfsInstallActivity))
    }

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Ui.primaryText(this@RootfsInstallActivity))
        background = Ui.glassButton(this@RootfsInstallActivity)
        val d = resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(36, d),
        ).apply { topMargin = Ui.dp(8, d) }
        Ui.pressAnimation(this)
        setOnClickListener { onClick() }
    }

    private fun stopScan() {
        scanAnim?.cancel()
        scanAnim = null
    }

    private fun installLocal(uri: Uri) {
        setBusy(true)
        executor.execute {
            val result = RootfsInstaller.installFromLocal(this, uri) { progress -> updateProgress(progress) }
            runOnUiThread {
                if (result.isSuccess) {
                    showPercent(10000, "安装完成")
                    Toast.makeText(this, "安装完成", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "安装失败", Toast.LENGTH_SHORT).show()
                }
                setBusy(false)
            }
        }
    }

    private fun installCloud(url: String) {
        setBusy(true)
        executor.execute {
            val result = RootfsInstaller.downloadAndInstall(this, url) { progress -> updateProgress(progress) }
            runOnUiThread {
                if (result.isSuccess) {
                    showPercent(10000, "安装完成")
                    Toast.makeText(this, "安装完成", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "下载/安装失败", Toast.LENGTH_SHORT).show()
                }
                setBusy(false)
            }
        }
    }

    private fun backupRootfs(uri: Uri) {
        setBusy(true)
        backupButton.isEnabled = false
        executor.execute {
            val result = RootfsInstaller.backup(this, uri) { progress -> updateProgress(progress) }
            runOnUiThread {
                if (result.isSuccess) {
                    showPercent(10000, "备份完成")
                    Toast.makeText(this, "备份完成", Toast.LENGTH_SHORT).show()
                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(this, "备份失败: ${error?.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
                backupButton.isEnabled = true
                setBusy(false)
            }
        }
    }

    // 完成态：满进度 + 100% 百分比
    private fun showPercent(level: Int, text: String) {
        stopScan()
        progressBar.isIndeterminate = false
        progressBar.progress = level
        percentText.text = String.format("%.1f %%", level / 100.0)
        progressText.text = text
    }

    private fun setBusy(busy: Boolean) {
        progressCard.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        if (busy) {
            stopScan()
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            percentText.text = "0 %"
            progressText.text = "处理中..."
        }
    }

    private fun updateProgress(progress: InstallProgress) {
        runOnUiThread {
            progressText.text = progress.text()
            if (progress.total > 0) {
                stopScan()
                progressBar.isIndeterminate = false
                val pct = ((progress.current * 10000) / progress.total).toInt().coerceIn(0, 10000)
                progressBar.progress = pct
                percentText.text = String.format("%.1f %%", pct / 100.0)
            } else if (progress.phase == "extract") {
                if (scanAnim == null) scanAnim = Ui.scanAnimator(progressBar).also { it.start() }
            }
        }
    }
}
