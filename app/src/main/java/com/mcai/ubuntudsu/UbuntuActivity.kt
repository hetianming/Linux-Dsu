package com.mcai.ubuntudsu

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.core.RootShell
import com.mcai.ubuntudsu.core.StatusDetector
import com.mcai.ubuntudsu.core.TerminalSessionStore
import com.mcai.ubuntudsu.ui.Ui
import java.util.concurrent.Executors

class UbuntuActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var infoText: TextView
    private lateinit var installHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Linux"
        buildUi()
        refreshInfo()
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        val scroll = ScrollView(this).apply { Ui.animateLiquidBackground(this) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(16, d), Ui.dp(16, d), Ui.dp(24, d))
        }

        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(18, d), Ui.dp(20, d), Ui.dp(18, d))
            background = Ui.glassSurface(this@UbuntuActivity, 24f)
        }
        infoText = TextView(this).apply {
            textSize = 13f
              setTextColor(Ui.primaryText(this@UbuntuActivity))
        }
        infoCard.addView(infoText)
        root.addView(infoCard)

        installHint = TextView(this).apply {
            text = "Ubuntu rootfs 尚未安装，点击下方“安装 rootfs 系统”开始。"
            textSize = 13f
             setTextColor(if (Ui.isDark(this@UbuntuActivity)) Color.parseColor("#FFB4A8") else Color.parseColor("#B5473B"))
            setPadding(Ui.dp(4, d), Ui.dp(10, d), Ui.dp(4, d), 0)
        }
        root.addView(installHint)

        root.addView(spacer(16))
        root.addView(
            Ui.entryButton(this, "安装 rootfs 系统", "本地安装 · 云端下载安装", "+", "#2BB673", R.drawable.icon_install_rootfs) {
                startActivity(Intent(this, UbuntuInstallActivity::class.java))
            },
        )
        root.addView(spacer(14))
        root.addView(
            Ui.entryButton(this, "终端运行器", "Chroot 运行 · Termux 风格终端", ">", "#E95420", R.drawable.icon_terminal_runner) {
                if (Env.ubuntuInstalled(this)) {
                    startActivity(Intent(this, TerminalActivity::class.java))
                } else {
                    Toast.makeText(this, "Ubuntu rootfs 尚未安装，正在打开安装界面", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, UbuntuInstallActivity::class.java))
                }
            },
        )
        root.addView(spacer(14))
        root.addView(
            Ui.entryButton(this, "卸载 rootfs 系统", "删除已安装的 Ubuntu 系统", "-", "#E5484D", R.drawable.icon_trash_rootfs) {
                confirmUninstall()
            },
        )

        scroll.addView(root)
        setContentView(scroll)
        Ui.enableEdgeToEdge(this, scroll)
    }

    private fun spacer(height: Int): View {
        val d = resources.displayMetrics.density
        return View(this).also { it.layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(height, d)) }
    }

    private fun refreshInfo() {
        val installed = Env.ubuntuInstalled(this)
        val path = Env.rootfs(this).path
        installHint.visibility = if (installed) View.GONE else View.VISIBLE
        if (!installed) {
            infoText.text = "系统版本信息：未安装\n状态：未安装\n安装后可通过终端进入 Linux（Chroot + Root 权限）"
            return
        }
        val version = rootfsVersion()
        infoText.text = "系统版本信息：$version\n状态：已安装\n路径：$path\n大小：计算中..."
        executor.execute {
            val size = runCatching { Env.formatSize(Env.dirSize(Env.rootfs(this))) }
                .getOrElse { "读取失败" }
            runOnUiThread {
                infoText.text = "系统版本信息：$version\n状态：已安装\n路径：$path\n大小：$size"
            }
        }
    }

    private fun rootfsVersion(): String {
        val rootfs = Env.rootfs(this)
        val candidates = listOf("etc/版本信息", "etc/version", "etc/ubuntu_version", "etc/os-release")
        val file = candidates.asSequence().map { java.io.File(rootfs, it) }.firstOrNull { it.isFile }
        return runCatching { file?.readText(Charsets.UTF_8)?.trim() }.getOrNull()
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(120)
            ?.ifBlank { "未找到版本信息文件" } ?: "未找到版本信息文件"
    }

    private fun confirmUninstall() {
        if (!Env.ubuntuInstalled(this)) {
            Toast.makeText(this, "当前没有已安装的 Linux rootfs", Toast.LENGTH_SHORT).show()
            return
        }
        val running = TerminalSessionStore.takeRunning()
        if (running != null) {
            AlertDialog.Builder(this)
                .setTitle("终端正在运行")
                .setMessage("请先结束终端进程并卸载挂载点，再删除 rootfs。")
                .setPositiveButton("关闭终端") { _, _ ->
                    running.finishIfRunning()
                    Toast.makeText(this, "终端已请求关闭，请稍后再卸载", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
                .setTitle("卸载 Linux")
            .setMessage("将删除 rootfs 目录及全部数据，此操作不可恢复。确定继续？")
            .setPositiveButton("卸载") { _, _ ->
                executor.execute {
                    val rootfs = Env.rootfs(this)
                    RootShell.exec(
                        "rm -rf '${rootfs.path.replace("'", "'\\''")}'",
                        timeoutMs = 300000,
                    )
                    runCatching { rootfs.deleteRecursively() }
                    runOnUiThread {
                        Toast.makeText(this, "已卸载", Toast.LENGTH_SHORT).show()
                        refreshInfo()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
