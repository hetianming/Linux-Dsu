package com.mcai.ubuntudsu

import android.content.Intent
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.GunTheme
import kotlin.concurrent.thread

/**
 * ④ 内核模块 独立页面：
 *  - ④ 大页内存模块（gh-hugepage-reserve）管理：把 Droid-VM APP 设置里对该 Magisk 模块的管理
 *    搬到本页面 —— 启停、改池容量、开 CMA 蓄水池、开机自动采集、重新加载、采集大页、卸载。
 *  - ⑤ 模块清单：列出 modules.ko 下全部内核模块（按版本分组、本机 KMI 置顶），可手动加载/卸载。
 *
 * 注意：运行内存适配（gh_hugepage_reserve，2MB 大页池）已不再由本 APP 自行加载 ——
 * 它改由 Magisk 模块「gh-hugepage-reserve」在开机时统一预留。因此⑤清单里不再出现该模块
 * （会过滤掉），避免 APP 与 Magisk 模块抢同一个模块名；对它的管理统一收口在④卡。
 */
class GunyahModuleActivity : GunyahSubActivity() {
    private lateinit var kernelText: TextView
    private lateinit var listWrap: LinearLayout
    private lateinit var hpCard: LinearLayout
    private lateinit var hpBody: LinearLayout

    private var cfg = GunyahManager.Config()
    private var busy = false

    override fun pageTitle() = "内核模块"

    override fun buildContent(parent: LinearLayout) {
        cfg = GunyahManager.loadConfig(this)

        // ---------------- ④ 大页内存模块管理（原 Droid-VM APP 设置里的模块管理） ----------------
        hpCard = card("④ 大页内存模块（gh-hugepage-reserve）管理")
        hpCard.addView(hintText(
            "来自 Droid-VM/gh-hugepage-reserve 的 Magisk 模块：开机即预留 2MB 大页池，" +
                "根治 Gunyah 启动「凑不出 2MB 连续页 / MEM_ACCEPT 0x9」。以下管理与 Droid-VM APP 设置等价。"
        ))
        hpBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hpCard.addView(hpBody)
        parent.addView(hpCard)

        // ---------------- ⑤ 模块清单 ----------------
        val c = card("⑤ 模块清单")
        kernelText = TextView(this).apply {
            text = "内核版本：检测中…"
            textSize = 11f
            setTextColor(GunTheme.TEXT2)
            setPadding(0, 0, 0, GunTheme.dp(8, d))
        }
        c.addView(kernelText)
        c.addView(hintText(
            "Gunyah 依赖的内核模块（gunyah-host-share / gh-unmovable / nproc-guard / udmabuf 等），" +
                "按本机内核 KMI 分组；非本机内核的 .ko 一律禁用（强行加载会内核崩溃）。\n" +
                "2MB 大页池（gh_hugepage_reserve）已交由④卡管理，不在此清单内。",
        ))
        listWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(listWrap)

        // 内存不足时自动降档：与「工具链」无关，是启动时的内存兜底，保留其开关。
        c.addView(toggleRow("内存不足时自动降档启动内存", cfg.memAutoFit) {
            cfg.memAutoFit = it
            GunyahManager.saveConfig(this, cfg)
        })
        c.addView(hintText("开启后启动前先看可用内存：不够就回收缓存 + 压实，仍不够则把本次 VM 内存降到可用额度（不小于 1024 MB）。关掉则严格按设定值启动，内存紧时可能 MEM_ACCEPT 0x9"))

        parent.addView(c)
    }

    override fun onCreateContent() { refreshHp(); refresh() }

    override fun onResume() {
        super.onResume()
        refreshHp()
        refresh()
    }

    // ==================== ④ 大页内存模块管理 ====================

    private fun refreshHp() {
        thread {
            val info = GunyahManager.hpModuleInfo()
            val stat = if (info.loaded) GunyahManager.hugepageStat() else emptyMap()
            uiIf { renderHp(info, stat) }
        }
    }

    private fun renderHp(info: GunyahManager.HpModuleInfo, stat: Map<String, String>) {
        hpBody.removeAllViews()
        if (!info.installed) {
            hpBody.addView(hintText(
                "尚未安装大页内存模块。虚拟机需要 2MB 连续页才能启动，未安装时极易因" +
                    "「凑不出 2MB 连续页 / MEM_ACCEPT 0x9」而启动失败。建议先到「工具链」页一键安装。"
            ))
            hpBody.addView(primaryButton("前往「工具链」安装大页池模块") {
                runCatching { startActivity(Intent(this, GunyahToolchainActivity::class.java)) }
            })
            return
        }

        // ---- 状态行 ----
        val running = GunyahManager.isVmRunning()
        val ak = stat["acquire_active"] == "1"
        val vm = stat["active_vms"]?.toIntOrNull() ?: 0
        val status = buildString {
            append("版本 v${info.version.ifBlank { "?" }} · ")
            append(if (info.enabled) "已启用" else "已停用")
            append(" · ")
            append(if (info.loaded) "已加载 ✓" else "未加载")
            append("\n模块 .ko：${info.koKmi.ifBlank { "-" }}")
            append("　当前池 ${if (info.poolAvailMB >= 0) info.poolAvailMB.toString() else "-"} MB / 目标 ${info.poolWantMB} MB")
            if (info.loaded) {
                append("\n虚拟机数 $vm · 采集状态 ${if (ak) "进行中" else "空闲"}")
                val stop = stat["acquire_stop_reason"].orEmpty()
                if (stop.isNotBlank()) append(" · 上次停止：$stop")
            }
            if (running) append("\n⚠ 虚拟机正在运行：改池容量/重新加载会清掉大页池，请先停止 VM")
        }
        hpBody.addView(TextView(this).apply {
            text = status
            textSize = 11f
            setTextColor(if (info.loaded) GunTheme.ACCENT else GunTheme.WARN)
            setPadding(0, 0, 0, GunTheme.dp(6, d))
        })

        // ---- 启用 / 停用（对标 Droid-VM 设置里的 Enable） ----
        hpBody.addView(toggleRow("启用模块（停用则开机不再预留大页池）", info.enabled) { on ->
            hpSetBusy(true)
            thread {
                val (ok, err) = GunyahManager.hpModuleSetEnabled(on)
                uiIf {
                    hpSetBusy(false)
                    toast(if (ok) (if (on) "模块已启用并重新加载" else "模块已停用") else "操作失败：$err")
                    refreshHp()
                }
            }
        })
        hpBody.addView(hintText("停用 = 建 disable 文件并卸载模块（立即清空大页池）；启用 = 删 disable 文件并重新加载（立即生效）。"))

        // ---- 大页池容量（pool_want，MB） ----
        hpBody.addView(hintText("大页池容量（MB）：模块开机为 VM 预留的 2MB 大页总量，过小会 MEM_ACCEPT 0x9。"))
        val poolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(4, d) }
        }
        val poolEdit = EditText(this).apply {
            setText(info.poolWantMB.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 12.5f
            isSingleLine = true
            setTextColor(GunTheme.TEXT)
            background = GunTheme.cardAlt(10f, d)
            backgroundTintList = null
            setPadding(GunTheme.dp(10, d), GunTheme.dp(8, d), GunTheme.dp(10, d), GunTheme.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(0, GunTheme.dp(40, d), 1f)
        }
        poolRow.addView(poolEdit)
        poolRow.addView(TextView(this).apply {
            text = "应用"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(GunTheme.ON_ACCENT)
            gravity = Gravity.CENTER
            background = GunTheme.chip(d, GunTheme.ACCENT)
            setPadding(GunTheme.dp(16, d), GunTheme.dp(9, d), GunTheme.dp(16, d), GunTheme.dp(9, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, GunTheme.dp(40, d),
            ).apply { marginStart = GunTheme.dp(6, d) }
            setOnClickListener {
                val mb = poolEdit.text.toString().toIntOrNull() ?: 0
                if (mb < 256) { toast("容量过小，建议 ≥ 256 MB"); return@setOnClickListener }
                hpSetBusy(true)
                thread {
                    val (ok, err) = GunyahManager.writeHpSetting("pool_want", mb.toString())
                    var reloadMsg = ""
                    if (ok && !running) {
                        val (rok, rmsg) = GunyahManager.hpModuleReload(forceReinsmod = true)
                        reloadMsg = if (rok) "（已重新加载生效）" else "（写入成功，但重新加载失败：$rmsg）"
                    }
                    uiIf {
                        hpSetBusy(false)
                        toast(if (ok) "已设置 ${mb}MB $reloadMsg" else "设置失败：$err")
                        refreshHp()
                    }
                }
            }
        })
        hpBody.addView(poolRow)
        hpBody.addView(hintText("应用时会写回 settings.prop；若当前没有 VM 在跑会立即重新加载生效，否则需重启手机生效。"))

        // ---- CMA 蓄水池（pool_want_with_cma） ----
        hpBody.addView(toggleRow("开启 CMA 蓄水池（pool_want_with_cma）", info.cmaWith) { on ->
            hpSetBusy(true)
            thread {
                val (ok, err) = GunyahManager.writeHpSetting("pool_want_with_cma", if (on) "1" else "0")
                var reloadMsg = ""
                if (ok && !running) {
                    val (rok, rmsg) = GunyahManager.hpModuleReload(forceReinsmod = true)
                    reloadMsg = if (rok) "（已重新加载生效）" else "（写入成功，但重新加载失败：$rmsg）"
                }
                uiIf {
                    hpSetBusy(false)
                    toast(if (ok) "已${if (on) "开启" else "关闭"} CMA 蓄水池 $reloadMsg" else "设置失败：$err")
                    refreshHp()
                }
            }
        })
        hpBody.addView(hintText("在普通内存与 CMA 间动态挪页补充大页；内存紧张机型作用明显。需重新加载或重启生效。"))

        // ---- 开机自动采集（boot_acquire 0-3） ----
        selectorRow(
            "开机自动采集",
            listOf(
                "关闭" to "0",
                "轻量（仅连续块）" to "1",
                "标准（扫描+回收）" to "2",
                "彻底（扫描+逐窗驱逐）" to "3",
            ),
            info.bootAcquire.toString(),
            { v ->
                hpSetBusy(true)
                thread {
                    val (ok, err) = GunyahManager.writeHpSetting("boot_acquire", v)
                    uiIf {
                        hpSetBusy(false)
                        toast(if (ok) "已设置开机自动采集（下次开机生效）" else "设置失败：$err")
                    }
                }
            },
            hpBody,
        )
        hpBody.addView(hintText("开机时按档位自动为大页池加压补齐，0 = 关闭。下次重启生效。"))

        // ---- 操作按钮：重新加载 / 采集大页 / 卸载 ----
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(8, d) }
        }
        btnRow.addView(actionButton("重新加载", GunTheme.CHIP) {
            if (running) { toast("虚拟机正在运行，请先停止再重新加载"); return@actionButton }
            hpSetBusy(true)
            thread {
                val (ok, msg) = GunyahManager.hpModuleReload(forceReinsmod = true)
                uiIf { hpSetBusy(false); toast(if (ok) "已重新加载模块" else "重新加载失败：$msg"); refreshHp() }
            }
        })
        btnRow.addView(LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(GunTheme.dp(8, d), 1) })
        btnRow.addView(actionButton("采集大页", GunTheme.ACCENT) {
            if (!info.loaded) { toast("模块未加载，无法采集"); return@actionButton }
            hpSetBusy(true)
            thread {
                val (ok, msg) = GunyahManager.hugepageAcquire(mode = 3, waitSec = 90)
                uiIf { hpSetBusy(false); toast(msg); refreshHp() }
            }
        })
        hpBody.addView(btnRow)

        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(8, d) }
        }
        btnRow2.addView(actionButton("卸载模块", GunTheme.DANGER) {
            AlertDialog.Builder(this)
                .setTitle("卸载大页内存模块")
                .setMessage("将删除 /data/adb/modules/gh-hugepage-reserve。卸载后虚拟机将失去 2MB 大页池，可能再次启动失败。\n确定卸载？")
                .setPositiveButton("卸载") { _, _ ->
                    hpSetBusy(true)
                    thread {
                        val (ok, err) = GunyahManager.hpModuleUninstall()
                        uiIf {
                            hpSetBusy(false)
                            toast(if (ok) "已卸载大页内存模块" else "卸载失败：$err")
                            refreshHp()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        })
        hpBody.addView(btnRow2)
    }

    private fun hpSetBusy(b: Boolean) {
        runOnUiThread {
            if (isFinishing || isDestroyed || !::hpCard.isInitialized) return@runOnUiThread
            busy = b
            hpCard.isEnabled = !b
            hpCard.alpha = if (b) 0.6f else 1f
        }
    }

    // ==================== ⑤ 模块清单 ====================

    /** 后台线程回主线程的安全入口：Activity 已销毁时直接丢弃，避免操作已释放的视图 */
    private fun uiIf(block: () -> Unit) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            block()
        }
    }

    private fun refresh() {
        cfg = GunyahManager.loadConfig(this)
        kernelText.text = "内核版本：检测中…"
        thread {
            val modules = GunyahManager.scanModules(this).filterNot { isHugepage(it) }
            val loaded = GunyahManager.loadedModules()
            val kernel = GunyahManager.detectKernelVersion()
            uiIf { render(modules, loaded, kernel) }
        }
    }

    private fun isHugepage(m: GunyahManager.KoModule): Boolean =
        m.fileName.contains("hugepage", true) || m.versionFolder.contains("hugepage", true)

    private fun render(modules: List<GunyahManager.KoModule>, loaded: Set<String>, kernel: String) {
        val kmi = GunyahManager.kmiOf(kernel)
        // 只统计「本机 KMI」的那几份：其他版本就算躺在目录里也不算已加载，
        // 否则 6 个 android 版本会全部显示“已加载”，看着像装了一堆模块。
        val native = modules.filter { GunyahManager.isNativeModule(it, kernel) }
        val nativeLoaded = native.count { GunyahManager.isLoaded(it, loaded, kernel) }
        kernelText.text = "内核版本：${kernel.ifBlank { "-" }} · $kmi" +
            "　本机模块 $nativeLoaded/${native.size}"
        listWrap.removeAllViews()
        if (modules.isEmpty()) {
            listWrap.addView(muted("未检测到 modules.ko，请先在「工具链」页下载内核模块包"))
            return
        }
        val (matched, others) = modules.partition { GunyahManager.isNativeModule(it, kernel) }
        if (matched.isNotEmpty()) {
            listWrap.addView(TextView(this).apply {
                text = "▸ 适配本机内核（$kmi）"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(GunTheme.ACCENT)
                setPadding(0, GunTheme.dp(4, d), 0, GunTheme.dp(2, d))
            })
            matched.forEach { m ->
                listWrap.addView(moduleRow(m, GunyahManager.isLoaded(m, loaded, kernel), true, kernel))
            }
        }
        if (others.isNotEmpty()) {
            listWrap.addView(TextView(this).apply {
                text = "▸ 其他内核版本（不适用 · 不能加载）"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(GunTheme.MUTED)
                setPadding(0, GunTheme.dp(10, d), 0, GunTheme.dp(2, d))
            })
            listWrap.addView(muted("这些是给 android12 / 14 / 16 等别的内核编译的，本机是 $kmi。" +
                "不同内核的模块 ABI 不兼容，强行加载会直接内核崩溃（蓝屏重启），所以这里禁用了加载按钮。"))
            others.groupBy { it.versionFolder }.forEach { (folder, list) ->
                listWrap.addView(TextView(this).apply {
                    text = "· $folder"
                    textSize = 10.5f
                    setTextColor(GunTheme.MUTED)
                    setPadding(0, GunTheme.dp(6, d), 0, 0)
                })
                list.forEach { m ->
                    listWrap.addView(moduleRow(m, false, false, kernel))
                }
            }
        }
    }

    private fun moduleRow(
        m: GunyahManager.KoModule,
        isLoaded: Boolean,
        matched: Boolean,
        kernel: String,
    ): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GunTheme.cardAlt(10f, d)
            setPadding(GunTheme.dp(10, d), GunTheme.dp(8, d), GunTheme.dp(10, d), GunTheme.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(4, d) }
        }
        val nameWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameWrap.addView(TextView(this).apply {
            text = m.fileName
            textSize = 12f
            setTypeface(null, if (matched) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(GunTheme.TEXT)
        })
        nameWrap.addView(TextView(this).apply {
            text = when {
                matched -> "KMI ${m.kmi} · 与本机内核匹配"
                m.kmi.isNotBlank() -> "非本机（${m.kmi}）· 禁止加载"
                else -> "${m.versionFolder} · 非本机内核"
            }
            textSize = 9.5f
            setTextColor(if (matched) GunTheme.ACCENT else GunTheme.MUTED)
            setPadding(0, GunTheme.dp(1, d), 0, 0)
        })
        row.addView(nameWrap)
        row.addView(TextView(this).apply {
            text = when {
                !matched -> "不适用"
                isLoaded -> "已加载"
                else -> "未加载"
            }
            textSize = 10f
            setTextColor(
                when {
                    !matched -> GunTheme.MUTED
                    isLoaded -> GunTheme.ACCENT
                    else -> GunTheme.TEXT2
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = GunTheme.dp(8, d) }
        })
        row.addView(TextView(this).apply {
            text = when {
                !matched -> "非本机"
                isLoaded -> "卸载"
                else -> "加载"
            }
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(
                when {
                    !matched -> GunTheme.MUTED
                    isLoaded -> GunTheme.TEXT
                    else -> GunTheme.ON_ACCENT
                }
            )
            background = when {
                !matched -> GunTheme.chip(d, GunTheme.CARD_ALT)
                isLoaded -> GunTheme.chip(d, GunTheme.DANGER)
                else -> GunTheme.chip(d, GunTheme.ACCENT)
            }
            setPadding(GunTheme.dp(14, d), GunTheme.dp(5, d), GunTheme.dp(14, d), GunTheme.dp(5, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = GunTheme.dp(8, d) }
            // 非本机内核的 .ko 一律不可点：insmod 会内核 panic（蓝屏）
            isEnabled = matched
            alpha = if (matched) 1f else 0.55f
            setOnClickListener {
                if (!matched) {
                    toast("这是给 ${m.kmi.ifBlank { m.versionFolder }} 编译的模块，本机内核是 ${GunyahManager.kmiOf(kernel)}，加载会蓝屏")
                    return@setOnClickListener
                }
                isEnabled = false; alpha = 0.6f
                thread {
                    val (ok, err) = if (isLoaded) {
                        GunyahManager.rmmod(GunyahManager.modKey(m.moduleName))
                    } else {
                        GunyahManager.insmod(m.path)
                    }
                    val loadedNow = GunyahManager.loadedModules()
                    val all = GunyahManager.scanModules(this@GunyahModuleActivity).filterNot { isHugepage(it) }
                    uiIf {
                        isEnabled = true; alpha = 1f
                        toast(
                            if (ok) (if (isLoaded) "已卸载 ${m.fileName}" else "已加载 ${m.fileName}")
                            else "失败：$err",
                        )
                        render(all, loadedNow, kernel)
                    }
                }
            }
        })
        return row
    }

    private fun muted(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 11f
        setTextColor(GunTheme.TEXT2)
        setPadding(0, GunTheme.dp(4, d), 0, 0)
    }
}
