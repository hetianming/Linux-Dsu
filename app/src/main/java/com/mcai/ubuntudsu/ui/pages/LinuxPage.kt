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

        // 状态预览卡：红点 + 系统版本 · 已安装 / Linux 容器 · 未安装（单行，不再堆技术字段）
        val infoCard = card()
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(View(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (Ui.isDark(activity)) Color.parseColor("#FF6B6B") else Color.parseColor("#D93838"))
            }
            layoutParams = LinearLayout.LayoutParams(Ui.dp(9, d), Ui.dp(9, d))
        })
        infoText = TextView(activity).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(9, d), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(infoText)
        infoCard.addView(statusRow)
        page.addView(infoCard)

        page.addView(spacer(10))
        // 功能区：2×2 卡片网格（与「多功能」页同一套卡片语言），四入口对称
        val features = listOf(
            Feature(R.drawable.icon_terminal_runner, "#2D64AA", "容器终端") {
                requireRootfs { activity.startActivity(Intent(activity, TerminalActivity::class.java)) }
            },
            Feature(R.drawable.icon_linux_modern, "#E95420", "桌面环境") {
                requireRootfs {
                    activity.startActivity(
                        Intent(activity, TerminalActivity::class.java).apply {
                            putExtra(TerminalActivity.EXTRA_DESKTOP, true)
                        },
                    )
                }
            },
            Feature(R.drawable.ic_folder_manager, "#6C4AC2", "文件管理") {
                requireRootfs { activity.startActivity(Intent(activity, RootfsFilesActivity::class.java)) }
            },
            Feature(R.drawable.icon_install_rootfs, "#11998E", "安装 rootfs") {
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
                    Ui.featureCard(activity, f.iconRes, Color.parseColor(f.tint), f.title, null, false) { f.action() },
                    LinearLayout.LayoutParams(0, Ui.dp(76, d), 1f).apply {
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
        infoText.text = if (installed) {
            "${prettyVersion() ?: "Linux 容器"} · 已安装"
        } else {
            "Linux 容器 · 未安装"
        }
    }

    /** 从 os-release 里提取适合展示的系统名（优先 PRETTY_NAME，去掉末尾代号括号） */
    private fun prettyVersion(): String? {
        val raw = rootfsVersion()
        if (raw.isBlank() || raw.contains("未找到")) return null
        val pretty = Regex("""PRETTY_NAME\s*=\s*"([^"\n]+)"""").find(raw)?.groupValues?.get(1)
            ?: raw.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return null
        return pretty
            .replace(Regex("""\s*\([^)]*\)\s*$"""), "")
            .trim()
            .take(36)
            .ifBlank { null }
    }

    private fun rootfsVersion(): String {
        val rootfs = Env.rootfs(activity)
        val candidates = listOf("etc/版本信息", "etc/version", "etc/ubuntu_version", "etc/os-release")
        val file = candidates.asSequence().map { java.io.File(rootfs, it) }.firstOrNull { it.isFile }
        return runCatching { file?.readText(Charsets.UTF_8)?.take(2000)?.trim() }.getOrNull()
            ?.ifBlank { "未找到版本信息文件" } ?: "未找到版本信息文件"
    }

    private fun confirmUninstall() {
        if (!Env.ubuntuInstalled(activity)) {
            Toast.makeText(activity, "当前没有已安装的 Linux rootfs", Toast.LENGTH_SHORT).show()
            return
        }
        val running = TerminalSessionStore.takeRunning()
        if (running != null) {
            val runningDialog = AlertDialog.Builder(activity)
                .setTitle("终端正在运行")
                .setMessage("请先结束终端进程并卸载挂载点，再删除 rootfs。")
                .setPositiveButton("关闭终端") { _, _ ->
                    running.finishIfRunning()
                    Toast.makeText(activity, "终端已请求关闭，请稍后再卸载", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
            Ui.styleDialog(activity, runningDialog)
            return
        }
        val uninstallDialog = AlertDialog.Builder(activity)
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
        Ui.styleDialog(activity, uninstallDialog)
    }

    /** 功能卡数据：图标 / 品牌色 / 标题 / 副标题 / 点击动作 */
    private data class Feature(
        val iconRes: Int,
        val tint: String,
        val title: String,
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
