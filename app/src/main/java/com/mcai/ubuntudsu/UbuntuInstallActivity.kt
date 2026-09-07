package com.mcai.ubuntudsu

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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

class UbuntuInstallActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var urlInput: EditText
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var backupButton: Button

    private val createBackup = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/x-xz"),
    ) { uri -> uri?.let { backupRootfs(it) } }

    private val pickArchive =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { installLocal(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "安装 Ubuntu"
        buildUi()
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(18, d), Ui.dp(20, d), Ui.dp(18, d))
            background = Ui.glassSurface(this@UbuntuInstallActivity, 24f)
        }

        fun title(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
             setTextColor(Ui.primaryText(this@UbuntuInstallActivity))
        }

        val scroll = ScrollView(this).apply { Ui.animateLiquidBackground(this) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(16, d), Ui.dp(16, d), Ui.dp(24, d))
        }

        val localCard = card()
        localCard.addView(title("本地安装"))
        localCard.addView(TextView(this).apply {
            text = "从存储中选择 rootfs 压缩包（支持 .tar.gz / .tar.xz）"
            textSize = 12f
             setTextColor(Ui.secondaryText(this@UbuntuInstallActivity))
            setPadding(0, Ui.dp(4, d), 0, 0)
        })
        localCard.addView(button("选择压缩包") {
            pickArchive.launch(arrayOf("application/x-gzip", "application/x-xz", "application/octet-stream", "*/*"))
        })
        root.addView(localCard)

        root.addView(spacer(14))
        val backupCard = card()
        backupCard.addView(title("备份 rootfs"))
        backupCard.addView(TextView(this).apply {
            text = "将已安装的 Ubuntu rootfs 打包为 .tar.xz 文件"
            textSize = 12f
            setTextColor(Ui.secondaryText(this@UbuntuInstallActivity))
        })
        backupButton = button("选择位置并备份") { createBackup.launch("ubuntu-rootfs.tar.xz") }
        backupCard.addView(backupButton)
        root.addView(backupCard)

        root.addView(spacer(14))
        val cloudCard = card()
        cloudCard.addView(title("云端下载安装"))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@UbuntuInstallActivity,
                android.R.layout.simple_spinner_dropdown_item,
                RootfsInstaller.mirrorPresets.map { it.first },
            )
        }
        cloudCard.addView(spinner, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        urlInput = EditText(this).apply {
            hint = "或输入自定义镜像 URL"
            textSize = 13f
            setSingleLine(true)
        }
        cloudCard.addView(urlInput)
        cloudCard.addView(button("开始下载并安装") {
            val url = urlInput.text.toString().trim().ifEmpty {
                RootfsInstaller.mirrorPresets[spinner.selectedItemPosition].second
            }
            installCloud(url)
        })
        root.addView(cloudCard)

        root.addView(spacer(14))
        val progressCard = card()
        progressText = TextView(this).apply {
            text = "空闲"
            textSize = 12f
             setTextColor(Ui.secondaryText(this@UbuntuInstallActivity))
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10000
        }
        progressCard.addView(progressText)
        progressCard.addView(progressBar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(progressCard)

        root.addView(spacer(14))
        val logCard = card()
        logCard.addView(title("日志"))
        logScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(220, d))
        }
        logText = Ui.logTextView(this)
        logScroll.addView(logText)
        logCard.addView(logScroll)
        logCard.visibility = View.GONE
        root.addView(logCard)

        scroll.addView(root)
        setContentView(scroll)
        Ui.enableEdgeToEdge(this, scroll)
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(12, resources.displayMetrics.density) }
        background = Ui.glassButton(this@UbuntuInstallActivity)
        setTextColor(Ui.primaryText(this@UbuntuInstallActivity))
        Ui.pressAnimation(this)
        setOnClickListener { onClick() }
    }

    private fun spacer(height: Int): View {
        val d = resources.displayMetrics.density
        return View(this).also { it.layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(height, d)) }
    }

    private fun installLocal(uri: Uri) {
        setBusy(true)
        log("本地安装: $uri")
        executor.execute {
            val result = RootfsInstaller.installFromLocal(this, uri) { progress -> updateProgress(progress) }
            runOnUiThread {
                if (result.isSuccess) {
                    log("本地安装完成")
                    progressBar.isIndeterminate = false
                    progressBar.progress = 10000
                    progressText.text = "安装完成 100%"
                    Toast.makeText(this, "安装完成", Toast.LENGTH_SHORT).show()
                } else {
                    log("本地安装失败: ${result.exceptionOrNull()?.message}")
                    Toast.makeText(this, "安装失败", Toast.LENGTH_SHORT).show()
                }
                setBusy(false)
            }
        }
    }

    private fun installCloud(url: String) {
        setBusy(true)
        log("云端下载: $url")
        executor.execute {
            val result = RootfsInstaller.downloadAndInstall(this, url) { progress -> updateProgress(progress) }
            runOnUiThread {
                if (result.isSuccess) {
                    log("云端安装完成")
                    progressBar.isIndeterminate = false
                    progressBar.progress = 10000
                    progressText.text = "安装完成 100%"
                    Toast.makeText(this, "安装完成", Toast.LENGTH_SHORT).show()
                } else {
                    log("云端安装失败: ${result.exceptionOrNull()?.message}")
                    Toast.makeText(this, "下载/安装失败", Toast.LENGTH_SHORT).show()
                }
                setBusy(false)
            }
        }
    }

    private fun backupRootfs(uri: Uri) {
        setBusy(true)
        backupButton.isEnabled = false
        log("开始备份 rootfs: $uri")
        executor.execute {
            val result = RootfsInstaller.backup(this, uri) { progress -> updateProgress(progress) }
            runOnUiThread {
                if (result.isSuccess) {
                    log("rootfs 备份完成")
                    progressBar.progress = 10000
                    progressText.text = "备份完成 100%"
                    Toast.makeText(this, "备份完成", Toast.LENGTH_SHORT).show()
                } else {
                    val error = result.exceptionOrNull()
                    log("rootfs 备份失败: ${error?.javaClass?.simpleName}: ${error?.message}")
                    Toast.makeText(this, "备份失败: ${error?.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
                backupButton.isEnabled = true
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            progressText.text = "处理中..."
        }
    }

    private fun updateProgress(progress: InstallProgress) {
        runOnUiThread {
            progressText.text = progress.text()
            if (progress.total > 0) {
                progressBar.isIndeterminate = false
                progressBar.progress = ((progress.current * 10000) / progress.total).toInt().coerceIn(0, 10000)
            } else if (progress.phase == "extract") {
                progressBar.isIndeterminate = true
            }
        }
    }

    private fun log(line: String) {
        runOnUiThread {
            logText.append(line + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
