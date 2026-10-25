package com.mcai.ubuntudsu.ui.pages

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.RootfsFilesActivity
import com.mcai.ubuntudsu.RootfsInstallActivity
import com.mcai.ubuntudsu.TerminalActivity
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.core.RootShell
import com.mcai.ubuntudsu.core.TerminalSessionStore
import com.mcai.ubuntudsu.ui.Ui
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executor

class LinuxPage(
    private val activity: android.app.Activity,
    private val executor: Executor,
) {
    private lateinit var infoText: TextView
    private lateinit var installHint: TextView

    fun build(): View {
        val d = activity.resources.displayMetrics.density
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
        }
        // 标题行：左侧"Linux ARM® 架构"（设置入口仅在首页）
        page.addView(TextView(activity).apply {
            text = "Linux ARM® 架构"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, 0, 0, Ui.dp(14, d))
        })

        val infoCard = card()
        infoText = TextView(activity).apply {
            textSize = 12f
            setTextColor(Ui.primaryText(activity))
        }
        infoCard.addView(infoText)
        page.addView(infoCard)

        installHint = TextView(activity).apply {
            text = "Ubuntu rootfs 尚未安装，点击下方“安装 rootfs 系统”开始。"
            textSize = 11f
            setTextColor(if (Ui.isDark(activity)) Color.parseColor("#FFB4A8") else Color.parseColor("#B5473B"))
            setPadding(Ui.dp(4, d), Ui.dp(6, d), Ui.dp(4, d), 0)
        }
        page.addView(installHint)

        page.addView(spacer(10))
        // 功能区：2×2 卡片网格（与「多功能」页同一套卡片语言），四入口对称
        val features = listOf(
            Feature(R.drawable.icon_terminal_runner, "#2D64AA", "容器终端", "Chroot 容器 · Termux 风格") {
                requireRootfs { activity.startActivity(Intent(activity, TerminalActivity::class.java)) }
            },
            Feature(R.drawable.icon_linux_modern, "#E95420", "桌面环境", "XFCE · KDE · GNOME + VNC") {
                requireRootfs {
                    activity.startActivity(
                        Intent(activity, TerminalActivity::class.java).apply {
                            putExtra(TerminalActivity.EXTRA_DESKTOP, true)
                        },
                    )
                }
            },
            Feature(R.drawable.ic_folder_manager, "#6C4AC2", "文件管理", "浏览 · 编辑 rootfs 内文件") {
                requireRootfs { activity.startActivity(Intent(activity, RootfsFilesActivity::class.java)) }
            },
            Feature(R.drawable.icon_install_rootfs, "#11998E", "安装 rootfs", "本地安装 · 云端下载 · 备份") {
                activity.startActivity(Intent(activity, RootfsInstallActivity::class.java))
            },
        )
        features.chunked(2).forEach { rowItems ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = Ui.dp(10, d) }
            }
            rowItems.forEachIndexed { colIndex, f ->
                row.addView(
                    Ui.featureCard(activity, f.iconRes, Color.parseColor(f.tint), f.title, f.subtitle, false) { f.action() },
                    LinearLayout.LayoutParams(0, Ui.dp(84, d), 1f).apply {
                        if (colIndex > 0) marginStart = Ui.dp(10, d)
                    },
                )
            }
            page.addView(row)
        }

        // 危险操作下沉：低调红色描边按钮（点击后仍有二次确认弹窗）
        page.addView(TextView(activity).apply {
            text = "卸载 rootfs 系统"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (Ui.isDark(activity)) Color.parseColor("#FFA79D") else Color.parseColor("#B5473B"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = Ui.dp(14, d).toFloat()
                setStroke(
                    maxOf(1, Ui.dp(1, d)),
                    if (Ui.isDark(activity)) Color.parseColor("#66FFA79D") else Color.parseColor("#66B5473B"),
                )
            }
            setPadding(0, Ui.dp(11, d), 0, Ui.dp(11, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(2, d) }
            setOnClickListener { confirmUninstall() }
            Ui.pressAnimation(this)
        })

        refreshInfo()
        return page
    }

    // rootfs 未安装时自动弹出安装界面，已安装则执行后续动作
    private fun requireRootfs(action: () -> Unit) {
        if (Env.ubuntuInstalled(activity)) {
            action()
        } else {
            Toast.makeText(activity, "Ubuntu rootfs 尚未安装，请先完成安装", Toast.LENGTH_SHORT).show()
            activity.startActivity(Intent(activity, RootfsInstallActivity::class.java))
        }
    }

    fun refreshInfo() {
        val installed = Env.ubuntuInstalled(activity)
        val path = Env.rootfs(activity).path
        installHint.visibility = if (installed) View.GONE else View.VISIBLE
        if (!installed) {
            infoText.text = "系统版本信息：未安装\n状态：未安装\n安装后可通过终端进入 Linux（Chroot + Root 权限）"
            return
        }
        val version = rootfsVersion()
        infoText.text = "系统版本信息：$version\n状态：已安装\n路径：$path"
    }

    private fun rootfsVersion(): String {
        val rootfs = Env.rootfs(activity)
        val candidates = listOf("etc/版本信息", "etc/version", "etc/ubuntu_version", "etc/os-release")
        val file = candidates.asSequence().map { java.io.File(rootfs, it) }.firstOrNull { it.isFile }
        return runCatching { file?.readText(Charsets.UTF_8)?.trim() }.getOrNull()
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(120)
            ?.ifBlank { "未找到版本信息文件" } ?: "未找到版本信息文件"
    }

    private fun confirmUninstall() {
        if (!Env.ubuntuInstalled(activity)) {
            Toast.makeText(activity, "当前没有已安装的 Linux rootfs", Toast.LENGTH_SHORT).show()
            return
        }
        val running = TerminalSessionStore.takeRunning()
        if (running != null) {
            AlertDialog.Builder(activity)
                .setTitle("终端正在运行")
                .setMessage("请先结束终端进程并卸载挂载点，再删除 rootfs。")
                .setPositiveButton("关闭终端") { _, _ ->
                    running.finishIfRunning()
                    Toast.makeText(activity, "终端已请求关闭，请稍后再卸载", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("卸载 Linux")
            .setMessage("将删除 rootfs 目录及全部数据，此操作不可恢复。确定继续？")
            .setPositiveButton("卸载") { _, _ ->
                executor.execute {
                    val rootfs = Env.rootfs(activity)
                    val ref = rootfs.absolutePath
                    // rootfs 内的 /dev /dev/pts /proc /sys 是宿主内核的全局 bind/内核挂载，
                    // umount 任何一个都会破坏宿主内核挂载表导致定屏，绝不 umount。
                    // 先杀掉占用挂载的 VNC/X 进程，然后只删非挂载子目录内容，
                    // 挂载点目录本身保留（EBUSY 删不掉，|| true 吞掉）。
                    val killLines = """toybox pkill -9 Xvnc 2>/dev/null || true
toybox pkill -9 -f startxfce4 2>/dev/null || true
toybox pkill -9 -f startplasma-x11 2>/dev/null || true
toybox pkill -9 -f startplasma 2>/dev/null || true
sleep 1""".trimIndent()
                    // 只删 rootfs 内非挂载点的子目录，挂载点目录本身不动
                    val rmLine = "toybox rm -rf '$ref'/* 2>/dev/null; toybox rm -rf '$ref' 2>/dev/null || true"
                    val verify = "toybox test -e '$ref' && echo __REMAIN__ || echo __GONE__"
                    val script = "$killLines\n$rmLine\n$verify"
                    val result = RootShell.exec(script, timeoutMs = 120000)
                    val gone = result.stdout.contains("__GONE__") && !result.stdout.contains("__REMAIN__")
                    activity.runOnUiThread {
                        if (gone) {
                            Toast.makeText(activity, "已卸载 rootfs 系统", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "卸载失败：目录仍存在，请关闭终端后重试", Toast.LENGTH_LONG).show()
                        }
                        refreshInfo()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 功能卡数据：图标 / 品牌色 / 标题 / 副标题 / 点击动作 */
    private data class Feature(
        val iconRes: Int,
        val tint: String,
        val title: String,
        val subtitle: String,
        val action: () -> Unit,
    )

    private fun card(): LinearLayout = LinearLayout(activity).apply {
        val d = activity.resources.displayMetrics.density
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
        background = Ui.glassSurface(activity, 18f)
        // 圆角 outline 投影：裸 elevation 对 LayerDrawable 背景会渲染成方形影子
        Ui.applyNeuShadow(this, 3f, 18f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(8, d) }
    }

    private fun spacer(height: Int): View {
        val d = activity.resources.displayMetrics.density
        return View(activity).also { it.layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(height, d)) }
    }

}
