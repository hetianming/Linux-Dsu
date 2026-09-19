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

        page.addView(spacer(6))
        // 安装/卸载二合一卡片：一张拟态玻璃卡内两行入口，水晶玻璃渲染条分格
        val manageCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 3f, 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(8, d) }
        }
        manageCard.addView(
            actionRow(R.drawable.icon_install_rootfs, "安装 rootfs 系统", "本地安装 · 云端下载 · 备份") {
                activity.startActivity(Intent(activity, RootfsInstallActivity::class.java))
            },
        )
        manageCard.addView(
            Ui.crystalDivider(activity, d),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(2, d)),
        )
        manageCard.addView(
            actionRow(R.drawable.icon_trash_rootfs, "卸载 rootfs 系统", "删除已安装的 Ubuntu 系统") { confirmUninstall() },
        )
        page.addView(manageCard)

        page.addView(
            sectionLabel("运行环境", d),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(10, d); bottomMargin = Ui.dp(8, d) },
        )
        // 大图标入口：一排两个往下排（容器终端 / 桌面环境），第三项文件管理独占一排
        val tileRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        tileRow.addView(
            Ui.iconTile(activity, "容器终端", "Chroot 容器 · Termux 风格", R.drawable.icon_terminal_runner, Color.parseColor("#E95420")) {
                requireRootfs {
                    activity.startActivity(Intent(activity, TerminalActivity::class.java))
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        tileRow.addView(
            Ui.iconTile(activity, "桌面环境", "XFCE · KDE · GNOME + VNC", R.drawable.icon_linux_modern, Color.parseColor("#2D64AA")) {
                requireRootfs {
                    activity.startActivity(
                        Intent(activity, TerminalActivity::class.java).apply {
                            putExtra(TerminalActivity.EXTRA_DESKTOP, true)
                        },
                    )
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(8, d) },
        )
        page.addView(tileRow)
        page.addView(
            Ui.iconTile(activity, "文件管理", "浏览 · 编辑 rootfs 内文件", R.drawable.ic_folder_manager, Color.parseColor("#6C4AC2")) {
                requireRootfs {
                    activity.startActivity(Intent(activity, RootfsFilesActivity::class.java))
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(8, d) },
        )

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
        infoText.text = "系统版本信息：$version\n状态：已安装\n路径：$path\n大小：计算中..."
        executor.execute {
            val size = runCatching { Env.formatSize(Env.dirSize(Env.rootfs(activity))) }
                .getOrElse { "读取失败" }
            activity.runOnUiThread {
                infoText.text = "系统版本信息：$version\n状态：已安装\n路径：$path\n大小：$size"
            }
        }
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
                    RootShell.exec(
                        "rm -rf '${rootfs.path.replace("'", "'\\''")}'",
                        timeoutMs = 300000,
                    )
                    runCatching { rootfs.deleteRecursively() }
                    activity.runOnUiThread {
                        Toast.makeText(activity, "已卸载", Toast.LENGTH_SHORT).show()
                        refreshInfo()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

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

    // 分区小标题：运行环境 / 系统管理等网格区头部
    private fun sectionLabel(text: String, d: Float): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.secondaryText(activity))
    }

    // 二合一卡片内的入口行：无独立背景，靠外层玻璃卡 + 水晶分隔条分格
    private fun actionRow(iconRes: Int, heading: String, detail: String, onClick: () -> Unit): View {
        val d = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(9, d), Ui.dp(10, d), Ui.dp(9, d))
            setOnClickListener { onClick() }
            Ui.pressAnimation(this)
            addView(ImageView(activity).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(Ui.dp(36, d), Ui.dp(36, d))
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Ui.dp(10, d)
                }
                addView(TextView(activity).apply {
                    text = heading
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Ui.primaryText(activity))
                })
                addView(TextView(activity).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(Ui.secondaryText(activity))
                    setPadding(0, Ui.dp(1, d), 0, 0)
                })
            })
            addView(TextView(activity).apply {
                text = "›"
                textSize = 22f
                setTextColor(Ui.secondaryText(activity))
            })
        }
    }
}
