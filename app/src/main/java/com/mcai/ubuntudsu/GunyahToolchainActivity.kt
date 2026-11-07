package com.mcai.ubuntudsu

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.core.RootShell
import kotlin.concurrent.thread
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import com.mcai.ubuntudsu.ui.GunTheme

/**
 * ① 工具链 独立页面：
 * 在线下载（DNA 同款：多线路并发测速 + 慢线自动切换）+ 本地导入 + 实时状态。
 * ② 大页内存模块（GH-Hugepage-Reserve）：从 GitHub(Droid-VM/gh-hugepage-reserve) 下载最新版，
 *    直接安装为 Magisk / KernelSU / APatch 模块，开机即预留 2MB 大页池，根治「凑不出 2MB 连续页」。
 */
class GunyahToolchainActivity : GunyahSubActivity() {
    private lateinit var stateText: TextView
    private lateinit var progressText: TextView
    private lateinit var downloadBtn: TextView
    private lateinit var importBtn: TextView
    private lateinit var hpStateText: TextView
    private lateinit var hpInstallBtn: TextView
    private lateinit var hpProg: ProgressBar

    override fun pageTitle() = "工具链"

    override fun buildContent(parent: LinearLayout) {
        val c = card("① 工具链")
        c.addView(hintText("crosvm / edk2 固件 / libc++ → APP 私有目录"))
        stateText = TextView(this).apply {
            text = "状态：检测中…"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(GunTheme.TEXT2)
            setPadding(0, 0, 0, GunTheme.dp(4, d))
        }
        c.addView(stateText)
        progressText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(GunTheme.TEXT2)
            setPadding(0, 0, 0, GunTheme.dp(8, d))
        }
        c.addView(progressText)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        downloadBtn = actionButton("⬇  在线下载", GunTheme.ACCENT) { download() }
        importBtn = actionButton("📁  本地导入", GunTheme.CHIP) {
            openFilePicker("选择 unixnu-bin.zip") { p -> import(p) }
        }
        row.addView(downloadBtn)
        row.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(GunTheme.dp(8, d), 1)
        })
        row.addView(importBtn)
        c.addView(row)
        parent.addView(c)

        // ---------------- ② 大页内存模块（GH-Hugepage-Reserve） ----------------
        val hpc = card("② 大页内存模块（GH-Hugepage-Reserve）")
        hpc.addView(hintText(
            "根治 Gunyah 启动「凑不出 2MB 连续页 / MEM_ACCEPT 0x9」：开机即预留 2MB 大页池。\n" +
            "来自 Droid-VM/gh-hugepage-reserve（GitHub 最新版），安装为 Magisk / KernelSU / APatch 模块。"
        ))
        hpStateText = TextView(this).apply {
            text = "状态：检测中…"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(GunTheme.TEXT2)
            setPadding(0, 0, 0, GunTheme.dp(4, d))
        }
        hpc.addView(hpStateText)
        hpProg = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, GunTheme.dp(6, d)
            ).apply { topMargin = GunTheme.dp(6, d); bottomMargin = GunTheme.dp(8, d) }
            max = 100
            progress = 0
            isIndeterminate = false
        }
        hpc.addView(hpProg)
        val hpRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hpInstallBtn = actionButton("⬇  安装大页池模块", GunTheme.ACCENT) { installHugepageModule() }
        hpRow.addView(hpInstallBtn)
        hpc.addView(hpRow)
        parent.addView(hpc)
    }

    override fun onCreateContent() { refresh(); refreshHp() }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshHp()
    }

    // ============================ ① 工具链下载/导入（原有逻辑） ============================

    private fun refresh() {
        thread {
            val bin = GunyahManager.detectBinDir(this)
            runOnUiThread {
                if (!::stateText.isInitialized) return@runOnUiThread
                if (bin != null) {
                    stateText.text = "已就绪"
                    stateText.setTextColor(GunTheme.ACCENT)
                    progressText.text = bin
                } else {
                    stateText.text = "未下载"
                    stateText.setTextColor(GunTheme.WARN)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        downloadBtn.isEnabled = !busy
        importBtn.isEnabled = !busy
        downloadBtn.alpha = if (busy) 0.6f else 1f
        importBtn.alpha = if (busy) 0.6f else 1f
    }

    private fun download() {
        if (!downloadBtn.isEnabled) return
        setBusy(true)
        progressText.text = "正在并发测速线路…"
        thread {
            val (ok, msg) = GunyahManager.downloadAndExtract(
                this,
                onProgress = { done, total ->
                    runOnUiThread {
                        progressText.text = if (total > 0) {
                            val pct = done * 100 / total
                            "下载中… $pct%  ${done / 1048576}MB/${total / 1048576}MB"
                        } else {
                            "下载中… ${done / 1048576}MB"
                        }
                    }
                },
                onState = { st -> runOnUiThread { progressText.text = st } },
            )
            runOnUiThread {
                setBusy(false)
                progressText.text = msg
                toast(msg)
                refresh()
            }
        }
    }

    private fun import(path: String) {
        setBusy(true)
        progressText.text = "正在导入本地 zip…"
        thread {
            val (ok, msg) = GunyahManager.importToolchain(this, path)
            runOnUiThread {
                setBusy(false)
                progressText.text = msg
                toast(msg)
                refresh()
            }
        }
    }

    // ============================ ② 大页内存模块安装 ============================

    private fun refreshHp() {
        thread {
            val installed = isModuleInstalled()
            val loaded = isModuleLoaded()
            runOnUiThread {
                hpStateText.text = when {
                    loaded -> "状态：已安装并加载 ✓（2MB 大页池已就绪）"
                    installed -> "状态：已安装（重启手机后生效）"
                    else -> "状态：未安装（虚拟机会因凑不出 2MB 连续页而启动失败）"
                }
                hpStateText.setTextColor(
                    if (loaded) GunTheme.ACCENT else if (installed) GunTheme.TEXT2 else GunTheme.WARN
                )
            }
        }
    }

    private fun isModuleInstalled(): Boolean {
        val r = RootShell.exec(
            "ls -d /data/adb/modules/*hugepage* 2>/dev/null; ls /sys/module/gh_hugepage_reserve 2>/dev/null; true"
        )
        return r.stdout.contains("hugepage") || r.stdout.contains("gh_hugepage_reserve")
    }

    private fun isModuleLoaded(): Boolean {
        val r = RootShell.exec("ls /sys/module/gh_hugepage_reserve >/dev/null 2>&1 && echo L || true")
        return r.stdout.contains("L")
    }

    private fun installHugepageModule() {
        val act = this
        setHpBusy(true)
        thread {
            try {
                setHpStatus("正在查询 GitHub 最新版本…")
                val release = queryLatestRelease()
                if (release == null) {
                    setHpStatus("查询失败：无法连接 GitHub（请检查网络 / 代理）")
                    return@thread
                }
                val (tag, apkUrl) = release
                setHpStatus("已找到 $tag，开始下载（并发测速线路中…）")
                val target = File(act.cacheDir, "gh-hugepage-reserve.zip")
                runCatching { target.delete() }
                hpProg.isIndeterminate = false
                hpProg.progress = 0
                val ok = GunyahManager.downloadFileTo(
                    act, apkUrl, target,
                    onProgress = { done, total ->
                        runOnUiThread {
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                hpProg.progress = pct
                                setHpStatus("下载中 $pct%　${done / 1048576}MB / ${total / 1048576}MB")
                            } else {
                                hpProg.isIndeterminate = true
                                setHpStatus("下载中…　${done / 1048576}MB")
                            }
                        }
                    },
                    onState = { s -> runOnUiThread { setHpStatus(s) } },
                )
                if (!ok || !target.isFile) {
                    hpProg.progress = 0
                    setHpStatus("下载失败：所有线路均不可用，请检查网络或稍后重试")
                    return@thread
                }
                hpProg.progress = 100
                setHpStatus("下载完成，正在安装模块…")
                val wasInstalled = isModuleInstalled()
                val okInstall = installModule(target)
                if (!okInstall) {
                    setHpStatus("安装失败：请确认已获取 root 权限")
                    return@thread
                }
                // 关闭 APP 自带的大页模块自动加载，避免与模块池冲突（APP 的 rmmod 会清掉池子）
                disableAppAutoLoad()
                if (!wasInstalled) {
                    runOnUiThread { askReboot() }
                } else {
                    setHpStatus("已更新，建议重启手机使模块生效")
                }
            } finally {
                setHpBusy(false)
            }
        }
    }

    /** 查询 Droid-VM/gh-hugepage-reserve 最新 release，返回 (tag, zip下载链接)。 */
    private fun queryLatestRelease(): Pair<String, String>? {
        val base = "https://api.github.com/repos/Droid-VM/gh-hugepage-reserve/releases/latest"
        val candidates = listOf(base, "https://gh-proxy.com/$base", "https://ghfast.top/$base")
        for (u in candidates) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "")
                val assets = json.optJSONArray("assets") ?: continue
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name", "")
                    val dl = a.optString("browser_download_url", "")
                    if (name.endsWith(".zip", true) && dl.isNotEmpty()) return tag to dl
                }
            } catch (_: Exception) {
                // 该线路失败，尝试下一条
            }
        }
        return null
    }

    /** 安装为 root 模块：优先 magisk / ksud 官方命令，否则直接解压到模块目录（通用）。 */
    private fun installModule(zip: File): Boolean {
        val zipPath = zip.absolutePath.replace("'", "'\\''")
        // 1) Magisk
        var r = RootShell.exec(
            "command -v magisk >/dev/null 2>&1 && magisk --install-module '$zipPath'",
            timeoutMs = 60000,
        )
        if (r.success && isModuleInstalled()) return true
        // 2) KernelSU
        r = RootShell.exec(
            "command -v ksud >/dev/null 2>&1 && ksud module install '$zipPath'",
            timeoutMs = 60000,
        )
        if (r.success && isModuleInstalled()) return true
        // 3) 兜底：解压到模块目录（Magisk / KernelSU / APatch 通用）
        val id = RootShell.exec(
            "unzip -p '$zipPath' module.prop 2>/dev/null | grep -m1 '^id=' | cut -d= -f2 | tr -d ' \r'"
        ).stdout.trim()
        val dir = if (id.isNotEmpty()) "/data/adb/modules/$id" else "/data/adb/modules/gh-hugepage-reserve"
        r = RootShell.exec(
            "mkdir -p '$dir' && unzip -o '$zipPath' -d '$dir' >/dev/null 2>&1 && echo OK",
            timeoutMs = 60000,
        )
        return r.stdout.contains("OK") && isModuleInstalled()
    }

    /** 关闭 APP 自带的「运行内存适配」自动加载，交给模块池统一接管。 */
    private fun disableAppAutoLoad() {
        runCatching {
            val cfg = GunyahManager.loadConfig(this)
            if (cfg.hugepageAuto) {
                cfg.hugepageAuto = false
                GunyahManager.saveConfig(this, cfg)
            }
        }
    }

    /** 首次安装需要重启才能生效。 */
    private fun askReboot() {
        AlertDialog.Builder(this)
            .setTitle("需要重启")
            .setMessage("首次安装大页内存模块需要重启手机才能生效。\n是否立即重启？")
            .setPositiveButton("立即重启") { _, _ ->
                RootShell.exec("reboot", timeoutMs = 8000)
            }
            .setNegativeButton("稍后重启") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun setHpStatus(s: String) = runOnUiThread { hpStateText.text = s }

    private fun setHpBusy(busy: Boolean) {
        runOnUiThread {
            if (!::hpInstallBtn.isInitialized) return@runOnUiThread
            hpInstallBtn.isEnabled = !busy
            hpInstallBtn.alpha = if (busy) 0.6f else 1f
        }
    }
}
