package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.PayloadOnlineActivity
import com.mcai.ubuntudsu.core.ota.CancelledException
import com.mcai.ubuntudsu.core.ota.RemotePackage
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 「在线提取」：远程固件包 → 分区 img，**全程不下载整包**。
 *
 * 流程：
 *  1. 用 HTTP Range 读 zip 目录，定位 payload.bin（几百 KB 流量）；
 *  2. 读 payload 头部 + manifest，解析分区表与每个分区的安装操作（几百 KB）；
 *  3. 勾选分区（全选 / 取消全选 / 反选），实时显示「预计下载量」；
 *  4. 只拉取所选分区的数据块，边下边解压（xz / bz2 / 原样）边写 img，并按 payload 声明的 sha256 校验。
 *
 * 一个 12.5 GB 的卡刷包，只取 boot/dtbo/vbmeta 时通常只有几十 MB 流量。
 */
class PayloadOnlinePage(
    private val activity: Activity,
    private val onBack: () -> Unit,
    private val scope: CoroutineScope,
) {

    private val d get() = activity.resources.displayMetrics.density

    private enum class Stage { CONNECT, LIST, EXTRACT, DONE }

    private val title: String
    private val fileName: String
    private val url: String
    private val device: String

    private val cancelFlag = AtomicBoolean(false)

    private lateinit var sourceText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var percentText: TextView
    private lateinit var statusText: TextView
    private lateinit var estimateText: TextView
    private lateinit var logText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var actionCard: LinearLayout
    private lateinit var selectRow: LinearLayout
    private lateinit var selectCount: TextView
    private lateinit var actionRow: LinearLayout
    private lateinit var startBtn: TextView
    private lateinit var cancelTaskBtn: TextView
    private lateinit var openDirBtn: TextView
    private lateinit var selectAllBtn: TextView
    private lateinit var clearBtn: TextView
    private lateinit var invertBtn: TextView
    private lateinit var statsText: TextView

    private var pkg: RemotePackage? = null
    private var partitions = listOf<RemotePackage.Partition>()
    private val selected = LinkedHashSet<String>()
    private val rowBoxes = LinkedHashMap<String, CheckBox>()
    private var outDir: File? = null
    private var stage: Stage = Stage.CONNECT

    private var extractBase = 0L
    private var extractTotal = 0L
    private val lastSpeedBytes = AtomicLong(0)
    private var lastSpeedAt = 0L

    init {
        val intent = activity.intent
        title = intent.getStringExtra(PayloadOnlineActivity.EXTRA_TITLE) ?: "在线提取"
        fileName = intent.getStringExtra(PayloadOnlineActivity.EXTRA_FILENAME) ?: "固件包"
        url = intent.getStringExtra(PayloadOnlineActivity.EXTRA_URL) ?: ""
        // 机型目录：优先取显式传入的设备名，兜底从「设备 · 版本」标题里截取
        device = run {
            val dev = intent.getStringExtra(PayloadOnlineActivity.EXTRA_DEVICE)?.trim() ?: ""
            val name = if (dev.isNotBlank()) dev else title.substringBefore(" · ").trim()
            sanitizeDir(name).ifBlank { "ROM" }
        }
    }

    fun build(): View {
        val root = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(8, d))
        }
        root.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        page.addView(buildTitleRow())
        page.addView(buildSourceCard())
        page.addView(buildProgressCard())
        page.addView(buildPartitionSection())
        page.addView(buildLogCard())
        // 操作卡固定在最底部：勾选 / 开始提取 / 取消任务 / 打开目录
        page.addView(buildActionCard())

        start()
        return root
    }

    // ========== UI ==========

    private fun buildTitleRow(): View = FrameLayout(activity).apply {
        setPadding(0, 0, 0, Ui.dp(10, d))
        addView(TextView(activity).apply {
            text = "在线提取"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })
        addView(TextView(activity).apply {
            text = "‹ 返回"
            textSize = 13f
            setTextColor(Ui.primaryText(activity))
            Ui.pressAnimation(this)
            setOnClickListener { Haptics.perform(this); onBack() }
        })
    }

    private fun buildSourceCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 16f)
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        card.addView(TextView(activity).apply {
            text = "卡刷包"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
        })
        sourceText = TextView(activity).apply {
            text = "$title\n$fileName"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
            setLineSpacing(3f, 1f)
        }
        card.addView(sourceText)
        estimateText = TextView(activity).apply {
            text = "远程直读：只读取所选分区的数据，不下载整个卡刷包"
            textSize = 10.5f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(6, d), 0, 0)
            setLineSpacing(3f, 1f)
        }
        card.addView(estimateText)
        return card
    }

    private fun buildProgressCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 16f)
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressDrawable = Ui.pillProgressDrawable(activity)
        }
        card.addView(progress)
        percentText = Ui.percentTextView(activity).apply {
            setPadding(0, Ui.dp(6, d), 0, 0)
        }
        card.addView(percentText)
        statsText = TextView(activity).apply {
            text = ""
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        }
        card.addView(statsText)
        statusText = TextView(activity).apply {
            text = "准备中…"
            textSize = 11.5f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
            setLineSpacing(3f, 1f)
        }
        card.addView(statusText)
        return card
    }

    /**
     * 底部固定操作卡：勾选工具 + 已选统计 + 主操作按钮。
     * 放到页面最下方，滑动分区列表时始终可见，一手可点。
     */
    private fun buildActionCard(): View {
        actionCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 18f)
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(8, d) }
            visibility = View.GONE
        }

        selectRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        selectAllBtn = smallButton("全选") { toggleAll(true) }
        clearBtn = smallButton("取消全选") { toggleAll(false) }
        invertBtn = smallButton("反选") { invertSelection() }
        selectRow.addView(
            selectAllBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        selectRow.addView(
            clearBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(8, d)
            },
        )
        selectRow.addView(
            invertBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(8, d)
            },
        )
        actionCard.addView(selectRow)

        selectCount = TextView(activity).apply {
            text = ""
            textSize = 11.5f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(8, d), 0, 0)
        }
        actionCard.addView(selectCount)

        actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(8, d), 0, 0)
        }
        startBtn = TextView(activity).apply {
            text = "开始提取"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonPrimary(activity))
            Ui.pressAnimation(this)
            visibility = View.GONE
            setPadding(Ui.dp(16, d), Ui.dp(9, d), Ui.dp(16, d), Ui.dp(9, d))
            setOnClickListener { Haptics.perform(this); startExtraction() }
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        actionRow.addView(startBtn)
        cancelTaskBtn = smallButton("取消任务") { cancelFlag.set(true) }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = Ui.dp(10, d) }
        }
        actionRow.addView(cancelTaskBtn)
        openDirBtn = TextView(activity).apply {
            text = "打开目录"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonSuccess(activity))
            Ui.pressAnimation(this)
            visibility = View.GONE
            setPadding(Ui.dp(14, d), Ui.dp(8, d), Ui.dp(14, d), Ui.dp(8, d))
            setOnClickListener { Haptics.perform(this); openOutDir() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = Ui.dp(10, d) }
        }
        actionRow.addView(openDirBtn)
        actionCard.addView(actionRow)
        return actionCard
    }

    /** 主按钮占位（0dp + weight=1），让单个按钮撑满整行 */
    private fun fillRow(v: View) {
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.width = 0
        lp.weight = 1f
        lp.marginStart = 0
        v.layoutParams = lp
    }

    private fun smallButton(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Ui.buttonText(activity))
        background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
        Ui.pressAnimation(this)
        setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
        setOnClickListener { Haptics.perform(this); onClick() }
    }

    private fun buildPartitionSection(): View {
        val wrap = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        wrap.addView(listContainer)
        return wrap
    }

    private fun buildLogCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 14f)
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(72, d),
            ).apply { topMargin = Ui.dp(8, d) }
        }
        logText = TextView(activity).apply {
            textSize = 10.5f
            setTextColor(Ui.secondaryText(activity))
            setLineSpacing(2f, 1f)
            movementMethod = ScrollingMovementMethod()
        }
        card.addView(logText)
        return card
    }

    // ========== 流程 ==========

    private fun start() {
        if (url.isBlank()) {
            setStatus("没有可用的固件直链")
            percentText.text = "无链接"
            return
        }
        if (!hasStoragePermission()) {
            setStatus("需要「所有文件访问权限」才能把 img 保存到 /sdcard/Linux-Dsu/ROM")
            AlertDialog.Builder(activity)
                .setTitle("需要存储权限")
                .setMessage("提取出的 img 会保存到 /sdcard/Linux-Dsu/ROM/$device，需要「所有文件访问权限」。")
                .setPositiveButton("去授权") { _, _ -> requestStoragePermission() }
                .setNegativeButton("返回") { _, _ -> onBack() }
                .setOnCancelListener { onBack() }
                .show()
            return
        }
        stage = Stage.CONNECT
        progress.isIndeterminate = true
        percentText.text = ""
        log("▶ 目标固件：$fileName")
        scope.launch(Dispatchers.IO) {
            try {
                val p = RemotePackage.open(url) { msg -> setStatus(msg) }
                pkg = p
                val list = p.partitions.sortedBy { it.name }
                withContext(Dispatchers.Main) {
                    partitions = list
                    progress.isIndeterminate = false
                    progress.progress = 0
                    percentText.text = "共 ${list.size} 个分区"
                    setStatus("已解析 ${list.size} 个分区，勾选需要提取的 img")
                    estimateText.text = "全量 ${fmt(p.packageSize)} · 只拉取所选分区的数据，不下载整个卡刷包"
                    log("✓ 只读取了包结构，未下载整包（全量 ${fmt(p.packageSize)}）")
                    renderPartitions()
                    stage = Stage.LIST
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.isIndeterminate = false
                    percentText.text = "解析失败"
                    setStatus(e.message ?: "读取固件包失败")
                    log("✗ ${e.message}")
                }
            }
        }
    }

    private fun hasStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${activity.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }.onFailure {
                Toast.makeText(activity, "请手动在系统设置中开启存储权限", Toast.LENGTH_LONG).show()
            }
        } else {
            activity.requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                1003,
            )
        }
    }

    /** 渲染分区列表（名称 + 镜像大小 + 需下载流量） */
    private fun renderPartitions() {
        listContainer.removeAllViews()
        rowBoxes.clear()
        val cardBg = Ui.glassSurface(activity, 12f)
        for (p in partitions) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg.constantState?.newDrawable() ?: cardBg
                setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = Ui.dp(5, d) }
            }
            val box = CheckBox(activity).apply {
                isChecked = false
                isEnabled = p.supported
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(p.name) else selected.remove(p.name)
                    updateSelectCount()
                }
            }
            rowBoxes[p.name] = box
            row.addView(box, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            val textCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(activity).apply {
                text = p.name
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (p.supported) Ui.primaryText(activity) else Ui.secondaryText(activity))
            })
            textCol.addView(TextView(activity).apply {
                text = buildString {
                    append(if (p.size > 0) fmt(p.size) else "大小未知")
                    append("　↓ 需下载 ")
                    append(fmt(p.downloadBytes))
                    if (!p.supported) append("　⚠ 不支持在线提取")
                }
                textSize = 10.5f
                setTextColor(if (p.supported) Ui.secondaryText(activity) else Ui.buttonWarning(activity))
                setPadding(0, Ui.dp(1, d), 0, 0)
            })
            row.addView(textCol, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginStart = Ui.dp(6, d) })
            if (p.supported) {
                Ui.pressAnimation(row)
                row.setOnClickListener { box.isChecked = !box.isChecked; Haptics.perform(row) }
            }
            listContainer.addView(row)
        }
        actionCard.visibility = View.VISIBLE
        selectRow.visibility = View.VISIBLE
        startBtn.visibility = View.VISIBLE
        // 默认勾选常见可刷分区
        val common = listOf(
            "boot", "init_boot", "vendor_boot", "dtbo", "vbmeta",
            "vbmeta_system", "vbmeta_vendor", "recovery",
        )
        common.forEach { n ->
            val p = partitions.firstOrNull { it.name == n }
            if (p != null && p.supported) {
                selected.add(n)
                rowBoxes[n]?.isChecked = true
            }
        }
        updateSelectCount()
    }

    private fun toggleAll(check: Boolean) {
        selected.clear()
        if (check) partitions.filter { it.supported }.forEach { selected.add(it.name) }
        rowBoxes.forEach { (name, box) -> box.isChecked = selected.contains(name) }
        updateSelectCount()
    }

    private fun invertSelection() {
        val next = LinkedHashSet<String>()
        partitions.filter { it.supported && it.name !in selected }.forEach { next.add(it.name) }
        selected.clear()
        selected.addAll(next)
        rowBoxes.forEach { (name, box) -> box.isChecked = selected.contains(name) }
        updateSelectCount()
    }

    private fun updateSelectCount() {
        val picked = partitions.filter { it.name in selected }
        val bytes = picked.sumOf { it.downloadBytes }
        selectCount.text = "已选 ${picked.size} / ${partitions.size} · 需下载 ${fmt(bytes)}"
    }

    /** 阶段：提取（只拉取所选分区的数据块） */
    private fun startExtraction() {
        val p = pkg ?: return
        val ordered = partitions.filter { it.name in selected && it.supported }
        if (ordered.isEmpty()) {
            Toast.makeText(activity, "请至少勾选一个分区", Toast.LENGTH_SHORT).show()
            return
        }
        // 输出目录：/sdcard/Linux-Dsu/ROM/<机型目录>/（机型目录按传入设备名自动生成）
        val dir = File("/sdcard/Linux-Dsu/ROM/$device")
        if (!dir.exists()) dir.mkdirs()
        outDir = dir
        stage = Stage.EXTRACT
        cancelFlag.set(false)
        startBtn.visibility = View.GONE
        selectRow.visibility = View.GONE
        cancelTaskBtn.visibility = View.VISIBLE
        fillRow(cancelTaskBtn)
        openDirBtn.visibility = View.GONE
        progress.isIndeterminate = false
        progress.progress = 0
        percentText.text = "0 %"
        extractBase = p.reader.downloaded.get()
        extractTotal = ordered.sumOf { it.downloadBytes }
        lastSpeedBytes.set(extractBase)
        lastSpeedAt = System.currentTimeMillis()
        setStatus("准备提取 ${ordered.size} 个分区（预计下载 ${fmt(extractTotal)}）→ ${dir.absolutePath}")
        log("▶ 开始提取 ${ordered.size} 个分区，预计下载 ${fmt(extractTotal)}")

        scope.launch(Dispatchers.Main) {
            while (stage == Stage.EXTRACT) {
                updateDownloadProgress()
                delay(400)
            }
        }

        scope.launch(Dispatchers.IO) {
            val ok = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for ((index, part) in ordered.withIndex()) {
                if (cancelFlag.get()) break
                setStatus("正在提取 ${part.name}（${index + 1}/${ordered.size}）…")
                val out = File(dir, "${part.name}.img")
                try {
                    p.extract(part, out) { cancelFlag.get() }
                    val verify = runCatching { p.verify(part, out) }.getOrNull()
                    ok.add(part.name)
                    val note = when (verify) {
                        true -> " · sha256 校验通过"
                        false -> " · ⚠ 校验不一致"
                        null -> ""
                    }
                    log("✓ ${part.name}.img（${fmt(out.length())}）$note")
                } catch (e: CancelledException) {
                    log("… 已取消")
                    break
                } catch (e: Exception) {
                    failed.add(part.name)
                    log("✗ ${part.name}：${e.message?.take(140) ?: "提取失败"}")
                }
            }
            withContext(Dispatchers.Main) { finishExtraction(ok, failed, ordered.size) }
        }
    }

    private fun updateDownloadProgress() {
        val p = pkg ?: return
        if (extractTotal <= 0) return
        val done = (p.reader.downloaded.get() - extractBase).coerceAtLeast(0)
        val percent = (done * 100 / extractTotal).toInt().coerceIn(0, 100)
        progress.progress = percent
        val now = System.currentTimeMillis()
        val dt = now - lastSpeedAt
        var speedText = ""
        if (dt >= 900) {
            val delta = (p.reader.downloaded.get() - lastSpeedBytes.get()).coerceAtLeast(0)
            speedText = " · ${fmt((delta * 1000 / dt).coerceAtLeast(0))}/s"
            lastSpeedBytes.set(p.reader.downloaded.get())
            lastSpeedAt = now
        }
        percentText.text = "$percent %"
        statsText.text = "已下载 ${fmt(done)} / ${fmt(extractTotal)}$speedText"
    }

    private fun finishExtraction(ok: List<String>, failed: List<String>, total: Int) {
        stage = Stage.DONE
        cancelTaskBtn.visibility = View.GONE
        openDirBtn.visibility = View.VISIBLE
        fillRow(openDirBtn)
        if (cancelFlag.get()) {
            percentText.text = "任务已取消"
            setStatus("已完成 ${ok.size} / $total；${failed.size} 个失败或未完成")
            return
        }
        if (ok.size == total) progress.progress = 100
        percentText.text = "完成 ${ok.size} / $total"
        setStatus(
            buildString {
                append("提取完成 ${ok.size} 个")
                if (failed.isNotEmpty()) append("，失败 ${failed.size} 个：${failed.joinToString(", ").take(120)}")
                outDir?.let { append("\n输出目录：${it.absolutePath}") }
            },
        )
        openOutDir()
    }

    private fun openOutDir() {
        val dir = outDir ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(dir.absolutePath), "resource/folder")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { activity.startActivity(intent) }.isFailure) {
            Toast.makeText(activity, "输出目录：${dir.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    // ========== 工具 ==========

    private fun mainThread(block: () -> Unit) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) block()
        }
    }

    private fun setStatus(text: String) {
        mainThread { statusText.text = text }
    }

    private fun log(line: String) {
        mainThread {
            val cur = logText.text?.toString() ?: ""
            val next = (cur + line + "\n").lines().takeLast(300).joinToString("\n")
            logText.text = next
        }
    }

    private fun fmt(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format("%.2f %s", v, units[i])
    }

    /** 把任意设备名清理成合法目录名（去掉 / \ : * ? " < > | 等，压缩空白） */
    private fun sanitizeDir(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length > 48) cleaned.substring(0, 48).trim() else cleaned
    }

    fun cleanup() {
        cancelFlag.set(true)
    }
}
