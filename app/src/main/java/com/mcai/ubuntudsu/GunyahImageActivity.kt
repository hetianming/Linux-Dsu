package com.mcai.ubuntudsu

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.core.Qcow2Converter
import com.mcai.ubuntudsu.ui.GunTheme
import kotlin.concurrent.thread

/**
 * ③ 镜像映射（盘位模型）：
 * 启动盘 / 光盘 / 系统盘 / 数据盘 四个固定盘位 + 「新增硬盘」动态扩展。
 * 每个盘位选中镜像后徽章点亮「已激活」并立即写入配置——下次启动脚本即生效。
 *
 * 格式不做限制：crosvm 的 --block 按文件头 magic 自动分派 raw / qcow2，
 * 这里只负责识别并标注，启动盘/系统盘/新增盘可切换「只读 / 可写」，
 * qcow2 增量盘要保存改动就必须可写。
 */
class GunyahImageActivity : GunyahSubActivity() {
    private lateinit var cfg: GunyahManager.Config
    /** 「新增硬盘」列表容器，新建磁盘后要就地刷新 */
    private lateinit var extrasList: LinearLayout

    /** 写模式：固定只读 / 固定读写 / 可切换 */
    private val RO_FIXED = 0
    private val RW_FIXED = 1
    private val RW_TOGGLE = 2

    override fun pageTitle() = "镜像映射"

    override fun buildContent(parent: LinearLayout) {
        cfg = GunyahManager.loadConfig(this)

        val c = card("③ 盘位映射")
        c.addView(hintText("支持 raw(.img) / qcow2 / ISO，按文件头自动识别。qcow2 会自动以可写方式挂载（crosvm 打开时要重建 refcount 表）；选中即生效"))

        addSlot(
            c, "启动盘", "引导磁盘", RW_TOGGLE,
            get = { cfg.bootDisk },
            setPath = { cfg.bootDisk = it },
            getRo = { cfg.bootDiskRo },
            setRo = { cfg.bootDiskRo = it },
        )
        addSlot(
            c, "光盘", "ISO / IMG", RO_FIXED,
            get = { cfg.cdRom },
            setPath = { cfg.cdRom = it },
            getRo = { true },
            setRo = {},
        )
        addSlot(
            c, "系统盘", "OS 系统镜像", RW_TOGGLE,
            get = { cfg.systemDisk },
            setPath = { cfg.systemDisk = it },
            getRo = { cfg.systemDiskRo },
            setRo = { cfg.systemDiskRo = it },
        )
        addSlot(
            c, "数据盘", "数据存储", RW_FIXED,
            get = { cfg.dataDisk },
            setPath = { cfg.dataDisk = it },
            getRo = { false },
            setRo = {},
        )
        parent.addView(c)

        parent.addView(buildNewDiskCard())
        parent.addView(buildExtraCard())
        parent.addView(buildConvertCard())
    }

    // ------------------------- 固定盘位 -------------------------

    private fun addSlot(
        parent: LinearLayout,
        label: String,
        hint: String,
        writeMode: Int,
        get: () -> String,
        setPath: (String) -> Unit,
        getRo: () -> Boolean,
        setRo: (Boolean) -> Unit,
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GunTheme.cardAlt(12f, d)
            setPadding(GunTheme.dp(10, d), GunTheme.dp(8, d), GunTheme.dp(10, d), GunTheme.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(7, d) }
        }

        // 行 1：盘位名 + 提示 + 激活徽章
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = label; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.TEXT)
        })
        head.addView(TextView(this).apply {
            text = "  $hint"; textSize = 9.5f
            setTextColor(GunTheme.MUTED)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val badge = TextView(this).apply {
            textSize = 9.5f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(GunTheme.dp(8, d), GunTheme.dp(3, d), GunTheme.dp(8, d), GunTheme.dp(3, d))
        }
        head.addView(badge)
        box.addView(head)

        // 行 2：路径 + 选择 / 清除
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, GunTheme.dp(5, d), 0, 0)
        }
        val pathView = TextView(this).apply {
            textSize = 10f
            setTextColor(GunTheme.TEXT2)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, 0, GunTheme.dp(6, d), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 格式徽标需在选择回调里刷新，先于按钮声明
        val fmtView = TextView(this).apply {
            text = "—"; textSize = 9f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.MUTED)
            background = GunTheme.pill(d, GunTheme.CHIP)
            setPadding(GunTheme.dp(7, d), GunTheme.dp(2, d), GunTheme.dp(7, d), GunTheme.dp(2, d))
        }
        // 格式检测在后台完成，回调晚于按钮声明：先用可赋值 lambda 占位
        var lastFmt = ""
        var refreshMode: () -> Unit = {}
        row.addView(pathView)
        row.addView(slotButton("选择", GunTheme.ACCENT) {
            openFilePicker("选择 $label") { p ->
                setPath(p)
                GunyahManager.saveConfig(this, cfg)
                refreshRow(pathView, badge, get())
                refreshMeta(fmtView, get()) { f ->
                    lastFmt = f
                    if (f == "QCOW2" && getRo()) setRo(false)
                    refreshMode()
                    GunyahManager.saveConfig(this, cfg)
                }
                toast(describe(label, p, if (GunyahManager.imageFormat(p) == "QCOW2") false else getRo()))
            }
        })
        row.addView(slotButton("清除", GunTheme.CHIP) {
            setPath("")
            GunyahManager.saveConfig(this, cfg)
            refreshRow(pathView, badge, get())
            lastFmt = ""
            refreshMode()
            refreshMeta(fmtView, "")
        })
        box.addView(row)

        // 行 3：格式 + 挂载方式
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, GunTheme.dp(5, d), 0, 0)
        }
        meta.addView(fmtView)
        meta.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        // 挂载方式：固定只读 / 固定读写 / 可切换
        val modeView = TextView(this).apply {
            textSize = 9.5f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(GunTheme.dp(9, d), GunTheme.dp(3, d), GunTheme.dp(9, d), GunTheme.dp(3, d))
        }
        refreshMode = {
            val qcow = lastFmt == "QCOW2"
            val ro = getRo() && !qcow
            modeView.text = when {
                qcow -> "可写（qcow2 必需）"
                writeMode == RO_FIXED -> "只读"
                ro -> "只读 ▸"
                else -> "可写 ▸"
            }
            modeView.setTextColor(if (ro) GunTheme.TEXT2 else GunTheme.ON_ACCENT)
            modeView.background = GunTheme.pill(d, if (ro) GunTheme.CHIP else GunTheme.ACCENT)
            modeView.isEnabled = writeMode == RW_TOGGLE && !qcow
        }
        modeView.setOnClickListener {
            if (writeMode != RW_TOGGLE) return@setOnClickListener
            setRo(!getRo())
            GunyahManager.saveConfig(this, cfg)
            refreshMode()
            val p = get()
            if (p.isNotBlank()) toast(describe(label, p, getRo()))
        }
        meta.addView(modeView)
        box.addView(meta)
        refreshMode()

        refreshRow(pathView, badge, get())
        refreshMeta(fmtView, get())
        parent.addView(box)
    }

    private fun describe(label: String, path: String, ro: Boolean): String {
        val fmt = GunyahManager.imageFormat(path)
        val mode = if (ro) "只读" else "可写"
        return when {
            fmt == "QCOW2" -> "$label 已激活（QCOW2 · 自动可写：crosvm 打开时要重建 refcount 表）"
            else -> "$label 已激活（$mode）"
        }
    }

    private fun refreshRow(pathView: TextView, badge: TextView, path: String) {
        val active = path.isNotBlank()
        pathView.text = if (active) path else "（未选择）"
        badge.text = if (active) "已激活" else "未激活"
        badge.setTextColor(if (active) GunTheme.ON_ACCENT else GunTheme.TEXT2)
        badge.background = GunTheme.pill(d, if (active) GunTheme.ACCENT else GunTheme.CARD_ALT)
    }

    /** 读文件头标注 QCOW2 / RAW（后台读，避免 FUSE 上卡 UI）。 */
    private fun refreshMeta(fmtView: TextView, path: String, onFmt: (String) -> Unit = {}) {
        if (path.isBlank()) {
            fmtView.text = "—"
            fmtView.setTextColor(GunTheme.MUTED)
            fmtView.background = GunTheme.pill(d, GunTheme.CHIP)
            return
        }
        fmtView.text = "…"
        thread {
            val f = GunyahManager.imageFormat(path)
            runOnUiThread {
                when (f) {
                    "QCOW2" -> {
                        fmtView.text = "QCOW2"
                        fmtView.setTextColor(GunTheme.ON_ACCENT)
                        fmtView.background = GunTheme.pill(d, GunTheme.ACCENT_SOFT)
                    }
                    "RAW" -> {
                        fmtView.text = "RAW"
                        fmtView.setTextColor(GunTheme.TEXT2)
                        fmtView.background = GunTheme.pill(d, GunTheme.CHIP)
                    }
                    else -> {
                        fmtView.text = "—"
                        fmtView.setTextColor(GunTheme.MUTED)
                        fmtView.background = GunTheme.pill(d, GunTheme.CHIP)
                    }
                }
                onFmt(f)
            }
        }
    }

    private fun slotButton(text: String, bg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text; textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (bg == GunTheme.ACCENT) GunTheme.ON_ACCENT else GunTheme.TEXT)
            gravity = Gravity.CENTER
            background = GunTheme.chip(d, bg)
            setPadding(GunTheme.dp(11, d), GunTheme.dp(6, d), GunTheme.dp(11, d), GunTheme.dp(6, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = GunTheme.dp(5, d) }
            setOnClickListener { onClick() }
            GunTheme.pressAnimation(this)
        }

    // ------------------------- 新建空白磁盘 -------------------------

    /**
     * 装系统必备：没有空闲磁盘就没法给 Windows 准备安装目标。
     * 手机端拿不到「64GB 空镜像」这种文件，所以这里直接造一个稀疏盘。
     */
    private fun buildNewDiskCard(): LinearLayout {
        val c = card("新建空白磁盘")
        c.addView(hintText("给虚拟机造一块新硬盘（raw 稀疏盘：逻辑容量立刻生效，实际占用随写入增长）。建好后自动加到下面「新增硬盘」并设为可写"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, GunTheme.dp(6, d), 0, 0)
        }
        listOf(48, 64, 96).forEach { gb ->
            row.addView(slotButton("${gb}GB", GunTheme.ACCENT) { confirmNewDisk(gb) })
        }
        c.addView(row)
        val free = GunyahManager.freeBytes(this) / (1024L * 1024L * 1024L)
        c.addView(hintText("当前可用空间约 ${free}GB —— Windows 11 装完实际占用约 20~30GB"))
        return c
    }

    private fun confirmNewDisk(gb: Int) {
        AlertDialog.Builder(this)
            .setTitle("新建 ${gb}GB 磁盘")
            .setMessage(
                "将在 APP 私有目录创建 raw 稀疏盘，逻辑容量 ${gb}GB。\n\n" +
                    "稀疏盘不会立刻占用 ${gb}GB：只有虚拟机真正写进去的数据才算占用，装好 Windows 大约占用 20~30GB。\n\n" +
                    "创建后这块盘会作为「新增硬盘（可写）」挂给虚拟机。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ -> doCreateDisk(gb) }
            .show()
    }

    private fun doCreateDisk(gb: Int) {
        toast("正在创建 ${gb}GB 稀疏盘…")
        thread {
            val r = GunyahManager.createBlankDisk(this, "win11-${gb}g", gb)
            runOnUiThread {
                r.onSuccess { p ->
                    val n = cfg.extraDisks.size + 1
                    cfg.extraDisks.add(GunyahManager.ExtraDisk("硬盘$n", p, ro = false))
                    GunyahManager.saveConfig(this, cfg)
                    renderExtras(extrasList)
                    toast("已创建并挂为 硬盘$n（可写）")
                }.onFailure { e -> toast("创建失败：${e.message}") }
            }
        }
    }

    // ------------------------- 新增硬盘 -------------------------

    private fun buildExtraCard(): LinearLayout {
        val c = card("新增硬盘")
        c.addView(hintText("附加镜像动态增删（root.apfs / system.apfs / qcow2 等），raw 默认只读可切可写，qcow2 固定可写"))
        extrasList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        renderExtras(extrasList)
        c.addView(extrasList)
        c.addView(TextView(this).apply {
            text = "＋  新增硬盘"
            textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.ON_ACCENT)
            gravity = Gravity.CENTER
            background = GunTheme.chip(d, GunTheme.ACCENT)
            setPadding(GunTheme.dp(12, d), GunTheme.dp(11, d), GunTheme.dp(12, d), GunTheme.dp(11, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(8, d) }
            setOnClickListener {
                val n = cfg.extraDisks.size + 1
                cfg.extraDisks.add(GunyahManager.ExtraDisk("硬盘$n", "", ro = true))
                GunyahManager.saveConfig(this@GunyahImageActivity, cfg)
                renderExtras(extrasList)
            }
            GunTheme.pressAnimation(this)
        })
        return c
    }

    private fun renderExtras(container: LinearLayout) {
        container.removeAllViews()
        cfg.extraDisks.forEachIndexed { i, disk ->
            container.addView(extraRow(disk) {
                cfg.extraDisks.removeAt(i)
                GunyahManager.saveConfig(this, cfg)
                renderExtras(container)
                toast("已移除 ${disk.label}")
            })
        }
        if (cfg.extraDisks.isEmpty()) {
            container.addView(hintText("暂无附加硬盘，点下方按钮添加"))
        }
    }

    private fun extraRow(disk: GunyahManager.ExtraDisk, onDelete: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GunTheme.cardAlt(12f, d)
            setPadding(GunTheme.dp(10, d), GunTheme.dp(7, d), GunTheme.dp(10, d), GunTheme.dp(7, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(6, d) }
        }
        // 格式徽标需在选择回调里刷新，先于按钮声明
        val fmtView = TextView(this).apply {
            text = "—"; textSize = 9f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.MUTED)
            background = GunTheme.pill(d, GunTheme.CHIP)
            setPadding(GunTheme.dp(7, d), GunTheme.dp(2, d), GunTheme.dp(7, d), GunTheme.dp(2, d))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val badge = TextView(this).apply {
            textSize = 9.5f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(GunTheme.dp(8, d), GunTheme.dp(3, d), GunTheme.dp(8, d), GunTheme.dp(3, d))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = disk.label; textSize = 12f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.TEXT)
        })
        titleRow.addView(badge)
        info.addView(titleRow)
        val pathView = TextView(this).apply {
            text = disk.path.ifBlank { "（未选择）" }
            textSize = 10f; setTextColor(GunTheme.TEXT2)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, GunTheme.dp(2, d), 0, 0)
        }
        info.addView(pathView)
        top.addView(info)
        top.addView(slotButton("选择", GunTheme.ACCENT) {
            openFilePicker("选择 ${disk.label}") { p ->
                disk.path = p
                pathView.text = p
                refreshBadge(badge, true)
                refreshMeta(fmtView, p)
                GunyahManager.saveConfig(this, cfg)
                toast(describe(disk.label, p, disk.ro))
            }
        })
        top.addView(slotButton("移除", GunTheme.DANGER) { onDelete() })
        box.addView(top)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, GunTheme.dp(5, d), 0, 0)
        }
        meta.addView(fmtView)
        meta.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        var extraFmt = ""
        val mode = TextView(this)
        fun paintMode() {
            val qcow = extraFmt == "QCOW2"
            val ro = disk.ro && !qcow
            mode.text = when {
                qcow -> "可写（qcow2 必需）"
                ro -> "只读 ▸"
                else -> "可写 ▸"
            }
            mode.textSize = 9.5f
            mode.setTypeface(null, Typeface.BOLD)
            mode.gravity = Gravity.CENTER
            mode.setTextColor(if (ro) GunTheme.TEXT2 else GunTheme.ON_ACCENT)
            mode.background = GunTheme.pill(d, if (ro) GunTheme.CHIP else GunTheme.ACCENT)
            mode.setPadding(GunTheme.dp(9, d), GunTheme.dp(3, d), GunTheme.dp(9, d), GunTheme.dp(3, d))
            mode.isEnabled = !qcow
        }
        paintMode()
        mode.setOnClickListener {
            disk.ro = !disk.ro
            paintMode()
            GunyahManager.saveConfig(this@GunyahImageActivity, cfg)
            if (disk.path.isNotBlank()) toast(describe(disk.label, disk.path, disk.ro))
        }
        meta.addView(mode)
        box.addView(meta)

        refreshBadge(badge, disk.path.isNotBlank())
        refreshMeta(fmtView, disk.path) { f ->
            extraFmt = f
            if (f == "QCOW2" && disk.ro) {
                disk.ro = false
                GunyahManager.saveConfig(this, cfg)
            }
            paintMode()
        }
        return box
    }

    private fun refreshBadge(badge: TextView, active: Boolean) {
        badge.text = if (active) "已激活" else "未激活"
        badge.setTextColor(if (active) GunTheme.ON_ACCENT else GunTheme.TEXT2)
        badge.background = GunTheme.pill(d, if (active) GunTheme.ACCENT else GunTheme.CARD_ALT)
    }

    // ------------------------- raw ↔ qcow2 格式互转 -------------------------

    private lateinit var convertStatus: TextView

    private fun buildConvertCard(): LinearLayout {
        val c = card("镜像格式互转 (raw ↔ qcow2)")
        c.addView(hintText("手机端没有 qemu-img，这里内置纯 Kotlin 转换器：选一个镜像自动判断方向。raw→qcow2 生成标准 v2 容器（全零簇不分配，稀疏盘不膨胀）；qcow2→raw 按 L1/L2 表逐簇导出为稀疏文件。输出放在源镜像同目录，绝不覆盖原文件。"))
        convertStatus = TextView(this).apply {
            text = "未开始"
            textSize = 11f
            setTextColor(GunTheme.MUTED)
            setPadding(0, GunTheme.dp(6, d), 0, GunTheme.dp(4, d))
        }
        c.addView(convertStatus)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, GunTheme.dp(4, d), 0, 0)
        }
        row.addView(slotButton("选择镜像 → 自动转换", GunTheme.ACCENT) { pickAndConvert() })
        c.addView(row)
        c.addView(hintText("大镜像转换可能要几分钟，别退页面。失败会自动删掉半成品，不会留下坏盘。带 backing file / 加密 / 压缩簇的 qcow2 会明确提示不支持。"))
        return c
    }

    private fun pickAndConvert() {
        openFilePicker("选择 raw 或 qcow2 镜像") { path ->
            val src = java.io.File(path)
            if (!src.isFile || !src.canRead()) {
                toast("读不到这个文件")
                return@openFilePicker
            }
            val fmt = GunyahManager.imageFormat(path)
            if (fmt.isEmpty()) {
                toast("文件为空或不可读")
                return@openFilePicker
            }
            val dst = Qcow2Converter.suggestedOutput(src)
            val dir = if (fmt == "QCOW2") "QCOW2 → RAW" else "RAW → QCOW2"
            AlertDialog.Builder(this)
                .setTitle("镜像格式转换")
                .setMessage("$dir\n\n源：${src.name}（${prettySize(src.length())}）\n输出：${dst.name}\n\n确认开始？")
                .setPositiveButton("开始") { _, _ -> doConvert(src, dst) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun doConvert(src: java.io.File, dst: java.io.File) {
        convertStatus.text = "转换中… 0%"
        convertStatus.setTextColor(GunTheme.TEXT2)
        thread {
            val r = Qcow2Converter.convert(src, dst, progress = { pct, text ->
                runOnUiThread { convertStatus.text = "$pct%  $text" }
            })
            runOnUiThread {
                convertStatus.text = if (r.ok) "✓ ${r.message}" else "✗ ${r.message}"
                convertStatus.setTextColor(if (r.ok) GunTheme.ACCENT else GunTheme.DANGER)
                toast(r.message)
            }
        }
    }

    private fun prettySize(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> String.format("%.2f GB", n / 1073741824.0)
        n >= 1024L * 1024 -> String.format("%.1f MB", n / 1048576.0)
        else -> String.format("%.1f KB", n / 1024.0)
    }

    override fun buildActionBar(): LinearLayout? {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(GunTheme.dp(12, d), GunTheme.dp(8, d), GunTheme.dp(12, d), GunTheme.dp(12, d))
        }
        bar.addView(actionButton("保存映射", GunTheme.ACCENT) {
            GunyahManager.saveConfig(this, cfg)
            saveAndGoHome("盘位映射已保存（启动时生效）")
        })
        return bar
    }
}
