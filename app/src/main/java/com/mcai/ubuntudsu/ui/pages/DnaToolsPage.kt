package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.core.DnaTools
import com.mcai.ubuntudsu.core.RootShell
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DNA 工具箱首页（对齐参考 Dsu-Manager 首页 DNA 工具布局，UbuntuDSU 拟态玻璃本地化）：
 *  - 工具链状态卡（检测 / 下载并解压 tools.zip）
 *  - 工程管理卡（当前工程 / 切换工程 / 新建 / 删除 / 解压ROM / 插件）
 *  - 分解与提取 / 合成与打包 / 格式转换 / 其他功能 四组功能入口
 */
class DnaToolsPage(private val activity: Activity) {

    private val d = activity.resources.displayMetrics.density
    private val ACCENT = Color.parseColor("#7C4DFF")
    private val handler = Handler(Looper.getMainLooper())

    private var statusText: TextView? = null
    private var projectText: TextView? = null
    private var progressBar: android.widget.ProgressBar? = null
    private var logText: TextView? = null
    private var downloadBtn: TextView? = null

    private var cancelled = AtomicBoolean(false)

    private fun dp(v: Int) = Ui.dp(v, d)

    /** 就绪绿（夜间用亮绿保证可读） */
    private fun readyGreen() = if (Ui.isDark(activity)) Color.parseColor("#5AD4A0") else Color.parseColor("#1D7A4F")
    /** 未就绪红（夜间用亮红保证可读） */
    private fun notReadyRed() = if (Ui.isDark(activity)) Color.parseColor("#FF8A80") else Color.parseColor("#A33B3B")

    fun build(): View {
        val scroll = ScrollView(activity).apply { isFillViewport = false }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(headerBar())
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        body.addView(buildStatusCard())
        body.addView(buildProjectCard())
        buildFunctionSections(body)
        root.addView(body)
        scroll.addView(root)
        refreshStatus()
        refreshProject()
        return scroll
    }

    fun destroy() {
        cancelled.set(true)
        handler.removeCallbacksAndMessages(null)
    }

    // ==================== 顶部紫色渐变圆角薄条 ====================

    private fun headerBar(): View {
        val bar = FrameLayout(activity)
        val dark = Ui.isDark(activity)
        bar.background = Ui.gradientGlassCard(
            activity,
            intArrayOf(
                if (dark) Color.parseColor("#4A3790") else Color.parseColor("#B39DFF"),
                if (dark) Color.parseColor("#37296E") else Color.parseColor("#8B5CFF"),
                if (dark) Color.parseColor("#271C55") else Color.parseColor("#6A3BD9"),
            ),
            accent = ACCENT,
            radiusDp = 18f,
        )
        bar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(4)
        }
        val row = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(TextView(activity).apply {
            text = "Android®天明 移植开发"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })
        bar.addView(row)
        return bar
    }

    // ==================== 工具链状态卡 ====================

    private fun buildStatusCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 18f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = "工具链状态"
            textSize = 13f
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        statusText = TextView(activity).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.secondaryText(activity))
        }
        titleRow.addView(statusText!!)
        card.addView(titleRow)

        progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = Ui.pillProgressDrawable(activity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            visibility = View.GONE
        }
        card.addView(progressBar)

        logText = TextView(activity).apply {
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        card.addView(logText)

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val checkBtn = TextView(activity).apply {
            text = "检测"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.buttonText(activity))
            gravity = Gravity.CENTER
            background = Ui.glassButton(activity, ACCENT)
            Ui.pressAnimation(this)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener {
                Haptics.perform(this)
                refreshStatus()
            }
        }
        btnRow.addView(checkBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        downloadBtn = TextView(activity).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.buttonText(activity))
            gravity = Gravity.CENTER
            background = Ui.glassButton(activity, ACCENT)
            Ui.pressAnimation(this)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener {
                Haptics.perform(this)
                startDownload()
            }
        }
        btnRow.addView(downloadBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        card.addView(btnRow)
        return card
    }

    // ==================== 工程管理卡 ====================

    private fun buildProjectCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 18f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val currentRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentRow.addView(TextView(activity).apply {
            text = "当前工程"
            textSize = 12f
            setTextColor(Ui.secondaryText(activity))
        })
        projectText = TextView(activity).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        currentRow.addView(projectText!!)
        currentRow.addView(TextView(activity).apply {
            text = "切换工程"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.buttonText(activity))
            gravity = Gravity.CENTER
            background = Ui.glassButton(activity, ACCENT)
            Ui.pressAnimation(this)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                Haptics.perform(this)
                showProjectManager()
            }
        })
        card.addView(currentRow)

        val opsStrip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                setColor(0x40FFFFFF)
                cornerRadius = dp(16).toFloat()
                setStroke(maxOf(1, dp(1)), 0x59FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val icons = arrayOf("＋", "🗑", "📦", "🧩")
        val labels = arrayOf("新建", "删除", "解压ROM", "插件")
        for (i in icons.indices) {
            val op = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    Haptics.perform(this)
                    when (i) {
                        0 -> showCreateProject()
                        1 -> showDeleteProject()
                        2 -> activity.startActivity(Intent(activity, com.mcai.ubuntudsu.core.dna.DnaUnzipActivity::class.java))
                        else -> activity.startActivity(Intent(activity, com.mcai.ubuntudsu.core.dna.DnaModuleActivity::class.java))
                    }
                }
            }
            op.addView(TextView(activity).apply {
                text = icons[i]; textSize = 15f; gravity = Gravity.CENTER
            })
            op.addView(TextView(activity).apply {
                text = labels[i]; textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
                gravity = Gravity.CENTER
            })
            opsStrip.addView(op, LinearLayout.LayoutParams(0, dp(54), 1f))
            if (i < icons.size - 1) {
                opsStrip.addView(View(activity).apply { setBackgroundColor(0x33173E5C) },
                    LinearLayout.LayoutParams(maxOf(1, dp(1)), dp(28)).apply { gravity = Gravity.CENTER_VERTICAL })
            }
        }
        card.addView(opsStrip)
        return card
    }

    // ==================== 四组功能入口（横排两列按钮卡片） ====================

    private fun buildFunctionSections(body: LinearLayout) {
        sectionTitle(body, "分解与提取", Color.parseColor("#35A8C4"))
        buildTwoColRow(body, listOf(
            item("🧬", "分解 bin") { anchor -> launchScaleUp(anchor, com.mcai.ubuntudsu.core.dna.DnaBinActivity::class.java) },
            item("⚡", "分解增量包") { anchor -> launchScaleUp(anchor, com.mcai.ubuntudsu.core.dna.DnaIncrementalActivity::class.java) },
            item("🧩", "分解 br") { anchor -> openMode("extract", "br") },
            item("🧾", "分解 dat") { anchor -> openMode("extract", "dat") },
            item("🧱", "分解 img") { anchor -> openMode("extract", "img") },
            item("🗂", "分解 super") { anchor -> openSuper() },
        ))

        sectionTitle(body, "合成与打包", Color.parseColor("#11998E"))
        buildTwoColRow(body, listOf(
            item("📦", "合成 img-dat-br") { anchor -> openMode("repack", null) },
            item("🧱", "合成 super.img") { anchor -> openMode("superP", null) },
        ))

        sectionTitle(body, "格式转换", Color.parseColor("#E07B39"))
        buildTwoColRow(body, listOf(
            item("🔁", "img-simg 互转") { anchor -> openMode("sparse", null) },
            item("🧾", "img-dat-br 转换") { anchor -> openMode("convert", null) },
            item("⚡", "zst-img 互转") { anchor -> openMode("zst", null) },
            item("🧩", "合并 Sparse 分段") { anchor -> openMode("chunk", null) },
        ))

        sectionTitle(body, "其他功能", Color.parseColor("#8E6FD8"))
        buildTwoColRow(body, listOf(
            item("🧬", "合并 my_ 分区") { anchor -> openMode("mergeMy", null) },
            item("🗂", "合并分段 super") { anchor -> openMode("mergeSuper", null) },
            item("📦", "合并其他分区") { anchor -> openMode("mergePart", null) },
            item("🛡", "SELinux 功能") { anchor -> launchScaleUp(anchor, com.mcai.ubuntudsu.core.dna.DnaSelinuxActivity::class.java) },
            item("🔒", "禁用 AVB / dm-verity") { anchor -> launchScaleUp(anchor, com.mcai.ubuntudsu.core.dna.DnaVbmetaActivity::class.java) },
        ))
    }

    private fun item(icon: String, label: String, action: (View) -> Unit): Pair<Pair<String, String>, (View) -> Unit> =
        Pair(Pair(icon, label), action)

    private fun launchScaleUp(anchor: View, target: Class<out Activity>) {
        launchScaleUpIntent(anchor, Intent(activity, target))
    }

    private fun launchScaleUpIntent(anchor: View, intent: Intent) {
        if (anchor.width > 0 && anchor.height > 0) {
            runCatching {
                val options = android.app.ActivityOptions.makeScaleUpAnimation(anchor, 0, 0, anchor.width, anchor.height)
                activity.startActivity(intent, options.toBundle())
                return
            }
        }
        activity.startActivity(intent)
    }

    /** 横排两列按钮卡片行：按行 2 个填充，最后一个奇数项占满整行 */
    private fun buildTwoColRow(body: LinearLayout, items: List<Pair<Pair<String, String>, (View) -> Unit>>) {
        var row: LinearLayout? = null
        items.forEachIndexed { index, entry ->
            val (icon, label) = entry.first
            val action = entry.second
            val isLast = index == items.size - 1
            val isOdd = index % 2 == 0
            if (isOdd || isLast) {
                row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                }
                body.addView(row!!)
            }
            val fillLast = isLast && !isOdd
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = RippleDrawable(
                    ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed)), intArrayOf(0x3335A8C4)),
                    Ui.glassSurface(activity, 18f),
                    null,
                )
                isClickable = true
                setOnClickListener {
                    Haptics.perform(it)
                    action(it)
                }
            }
            cell.addView(TextView(activity).apply {
                text = icon; textSize = 18f
                setPadding(0, 0, dp(10), 0)
            })
            cell.addView(TextView(activity).apply {
                text = label
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
            })
            val lp = LinearLayout.LayoutParams(
                if (fillLast) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (fillLast) 0f else 1f,
            )
            if (!isOdd && !fillLast) lp.leftMargin = dp(8)
            row!!.addView(cell, lp)
        }
    }

    // ==================== 复用组件 ====================

    private fun sectionTitle(body: LinearLayout, title: String, color: Int) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14); bottomMargin = dp(8) }
        }
        row.addView(View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }, LinearLayout.LayoutParams(dp(8), dp(8)))
        row.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(dp(8), 0, dp(10), 0)
        })
        row.addView(View(activity).apply {
            setBackgroundColor(0x55FFFFFF)
        }, LinearLayout.LayoutParams(0, maxOf(1, dp(1)), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        body.addView(row)
    }

    private fun menuCard(body: LinearLayout, icon: String, title: String, subtitle: String, onClick: () -> Unit) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = RippleDrawable(
                ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed)), intArrayOf(0x3335A8C4)),
                Ui.glassSurface(activity, 18f),
                null,
            )
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                Haptics.perform(this)
                onClick()
            }
        }
        val badge = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF35A8C4.toInt(), 0xFF0E7D95.toInt())
                setStroke(maxOf(1, dp(1)), 0xB3FFFFFF.toInt())
            }
        }
        badge.addView(TextView(activity).apply {
            text = icon; textSize = 17f; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        card.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)))

        val textBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textBox.addView(TextView(activity).apply {
            text = title
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        textBox.addView(TextView(activity).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, dp(2), 0, 0)
        })
        card.addView(textBox)
        card.addView(TextView(activity).apply {
            text = "›"; textSize = 22f; setTextColor(Ui.secondaryText(activity))
        })
        body.addView(card)
    }

    // ==================== 导航 ====================

    private fun openMode(mode: String, filter: String?) {
        if (mode == "superU") { openSuper(); return }
        val i = Intent(activity, com.mcai.ubuntudsu.core.dna.DnaActivity::class.java)
        i.putExtra(com.mcai.ubuntudsu.core.dna.DnaActivity.EXTRA_MODE, mode)
        if (filter != null) i.putExtra(com.mcai.ubuntudsu.core.dna.DnaActivity.EXTRA_FILTER, filter)
        activity.startActivity(i)
    }

    private fun openSuper() {
        val cur = DnaTools.currentProject(activity)
        if (cur == null) {
            toast("请先选择工程（需包含 super.img）")
            showProjectManager()
            return
        }
        toast("正在检测 super.img ...")
        Thread {
            val path = "${DnaTools.WORK_ROOT}/$cur/super.img"
            val has = RootShell.exec(
                "[ -f ${DnaTools.quote(path)} ] && echo __YES__ || echo __NO__", timeoutMs = 10000
            ).stdout.contains("__YES__")
            handler.post {
                if (activity.isFinishing) return@post
                if (has) {
                    val si = Intent(activity, com.mcai.ubuntudsu.core.dna.DnaSuperActivity::class.java)
                    si.putExtra(com.mcai.ubuntudsu.core.dna.DnaSuperActivity.EXTRA_SUPER, path)
                    activity.startActivity(si)
                } else {
                    toast("当前工程未检测到 super.img，请先解压 ROM 或导入 super.img")
                }
            }
        }.start()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ==================== 状态刷新 / 下载 ====================

    private fun refreshStatus() {
        val text = statusText ?: return
        text.text = "检测中 …"
        text.setTextColor(Ui.secondaryText(activity))
        downloadBtn?.text = "下载工具包"
        downloadBtn?.visibility = View.VISIBLE
        Thread {
            val ready = DnaTools.isReady(activity)
            val root = RootShell.available()
            handler.post {
                if (activity.isFinishing) return@post
                val ok = ready && root
                text.text = if (ok) "✓ 就绪（ROOT 可用）" else if (ready) "工具已就绪（需 ROOT 授权）" else "未就绪（需下载并授权 ROOT）"
                text.setTextColor(if (ok) readyGreen() else notReadyRed())
                downloadBtn?.text = if (ready) "重新下载" else "下载并解压"
            }
        }.start()
    }

    private fun refreshProject() {
        val cur = DnaTools.currentProject(activity)
        projectText?.text = cur ?: "未选择"
        projectText?.setTextColor(
            if (cur != null) Ui.primaryText(activity) else notReadyRed()
        )
    }

    private fun startDownload() {
        cancelled = AtomicBoolean(false)
        progressBar?.let {
            it.visibility = View.VISIBLE
            it.progress = 0
            it.isIndeterminate = false
        }
        logText?.visibility = View.VISIBLE
        logText?.text = "开始下载…"
        downloadBtn?.isEnabled = false
        downloadBtn?.alpha = 0.6f
        val onProgress: (Int) -> Unit = { p -> handler.post { progressBar?.progress = p } }
        val onLog: (String) -> Unit = { line ->
            handler.post {
                val cur = logText?.text ?: ""
                logText?.text = if (cur.isBlank()) line else "$cur\n$line"
                statusText?.text = "下载中"
            }
        }
        Thread {
            val ok = DnaTools.ensureTools(activity, onProgress, onLog, { cancelled.get() })
            handler.post {
                if (!activity.isFinishing) {
                    downloadBtn?.isEnabled = true
                    downloadBtn?.alpha = 1f
                    progressBar?.visibility = View.GONE
                    if (ok) {
                        // 下载成功：直接同步刷新为就绪状态，避免再走 refreshStatus 的异步线程导致卡在“下载中”
                        val ready = DnaTools.isReady(activity)
                        val root = RootShell.available()
                        val okReady = ready && root
                        statusText?.text = if (okReady) "✓ 就绪（ROOT 可用）"
                            else if (ready) "工具已就绪（需 ROOT 授权）"
                            else "未就绪（需授权 ROOT）"
                        statusText?.setTextColor(
                            if (okReady) readyGreen() else notReadyRed()
                        )
                        downloadBtn?.text = if (ready) "重新下载" else "下载并解压"
                        val cur = logText?.text ?: ""
                        logText?.text = if (cur.isBlank()) "工具就绪" else "$cur\n工具就绪"
                    } else if (!cancelled.get()) {
                        statusText?.text = "下载失败"
                        val cur = logText?.text ?: ""
                        logText?.text = "$cur\n所有线路失败，请稍后重试。"
                    }
                }
            }
        }.start()
    }

    // ==================== 工程管理对话框 ====================

    private fun showProjectManager() {
        val projects = DnaTools.listProjects()
        val current = DnaTools.currentProject(activity)
        val items = projects.toTypedArray()
        if (items.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("选择工程")
                .setMessage("暂无工程，请先「新建」。")
                .setPositiveButton("新建") { _, _ -> showCreateProject() }
                .setNegativeButton("关闭", null)
                .show()
            return
        }
        val labels = items.map { (if (it == current) "● " else "○ ") + it }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("选择工程")
            .setItems(labels) { _, which ->
                DnaTools.setCurrentProject(activity, items[which])
                refreshProject()
                toast("已切换工程：${items[which]}")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showCreateProject() {
        val input = EditText(activity).apply {
            hint = "例如：MyROM"
            textSize = 15f
            isSingleLine = true
        }
        val pad = dp(18)
        val wrap = FrameLayout(activity).apply { setPadding(pad, dp(8), pad, 0) }
        wrap.addView(input, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        AlertDialog.Builder(activity)
            .setTitle("新建工程")
            .setMessage("工程名将添加 PDNA_ 前缀，创建于 /sdcard/Linux-Dsu/PDNA/ 与 /data/Linux-Dsu/PDNA/")
            .setView(wrap)
            .setPositiveButton("创建并使用") { _, _ ->
                val name = input.text?.toString()?.trim() ?: ""
                if (name.isEmpty()) { toast("请输入工程名"); return@setPositiveButton }
                Thread {
                    val (created, error) = DnaTools.createProject(name)
                    handler.post {
                        if (error == null && created.isNotEmpty()) {
                            DnaTools.setCurrentProject(activity, created)
                            refreshProject()
                            toast("已创建工程：$created")
                        } else {
                            toast("创建失败：${error ?: "未知错误"}")
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteProject() {
        val projects = DnaTools.listProjects()
        if (projects.isEmpty()) { toast("暂无工程可删除"); return }
        val items = projects.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("删除工程")
            .setItems(items) { _, which ->
                val name = items[which]
                AlertDialog.Builder(activity)
                    .setTitle("确认删除")
                    .setMessage("将删除工程 $name（/sdcard/Linux-Dsu/PDNA 与 /data/Linux-Dsu/PDNA 下的目录），不可恢复。")
                    .setPositiveButton("删除") { _, _ ->
                        Thread {
                            val ok = DnaTools.deleteProject(name)
                            handler.post {
                                if (DnaTools.currentProject(activity) == name) {
                                    // 当前工程被删，清空显示
                                }
                                refreshProject()
                                toast(if (ok) "已删除：$name" else "删除失败")
                            }
                        }.start()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
