package com.mcai.ubuntudsu

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.Ui
import kotlin.concurrent.thread

/**
 * ① 工具链 独立页面：
 * 在线下载（DNA 同款：多线路并发测速 + 慢线自动切换）+ 本地导入 + 实时状态。
 */
class GunyahToolchainActivity : GunyahSubActivity() {
    private lateinit var stateText: TextView
    private lateinit var progressText: TextView
    private lateinit var downloadBtn: TextView
    private lateinit var importBtn: TextView

    override fun pageTitle() = "工具链"

    override fun buildContent(parent: LinearLayout) {
        val c = card("① 工具链")
        c.addView(hintText("crosvm / edk2 固件 / libc++ → APP 私有目录"))
        stateText = TextView(this).apply {
            text = "状态：检测中…"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Ui.secondaryText(this@GunyahToolchainActivity))
            setPadding(0, 0, 0, Ui.dp(4, d))
        }
        c.addView(stateText)
        progressText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Ui.secondaryText(this@GunyahToolchainActivity))
            setPadding(0, 0, 0, Ui.dp(8, d))
        }
        c.addView(progressText)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        downloadBtn = actionButton("⬇  在线下载", Ui.buttonPrimary(this)) { download() }
        importBtn = actionButton("📁  本地导入", Ui.buttonSecondary(this)) {
            openFilePicker("选择 unixnu-bin.zip") { p -> import(p) }
        }
        row.addView(downloadBtn)
        row.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(8, d), 1)
        })
        row.addView(importBtn)
        c.addView(row)
        parent.addView(c)
    }

    override fun onCreateContent() { refresh() }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        thread {
            val bin = GunyahManager.detectBinDir(this)
            runOnUiThread {
                if (!::stateText.isInitialized) return@runOnUiThread
                if (bin != null) {
                    stateText.text = "已就绪"
                    stateText.setTextColor(Ui.buttonSuccess(this))
                    progressText.text = bin
                } else {
                    stateText.text = "未下载"
                    stateText.setTextColor(Ui.buttonWarning(this))
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
}
