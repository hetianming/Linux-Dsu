package com.mcai.ubuntudsu

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.Ui
import kotlin.concurrent.thread

/**
 * ④ 内核模块 独立页面：
 * 自动检测 uname -r 与 modules.ko 目录，按版本分组列出，
 * 每个模块显示加载状态，可手动加载/卸载（失败原因直接提示）。
 */
class GunyahModuleActivity : GunyahSubActivity() {
    private lateinit var kernelText: TextView
    private lateinit var listWrap: LinearLayout

    override fun pageTitle() = "内核模块"

    override fun buildContent(parent: LinearLayout) {
        val c = card("④ 内核模块")
        kernelText = TextView(this).apply {
            text = "内核版本：检测中…"
            textSize = 11f
            setTextColor(Ui.secondaryText(this@GunyahModuleActivity))
            setPadding(0, 0, 0, Ui.dp(8, d))
        }
        c.addView(kernelText)
        listWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        c.addView(listWrap)
        parent.addView(c)
    }

    override fun onCreateContent() { refresh() }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 重新检测：内核版本 + 模块列表 + 已加载集合（自动检测状态） */
    private fun refresh() {
        kernelText.text = "内核版本：检测中…"
        thread {
            val kernel = GunyahManager.detectKernelVersion()
            val modules = GunyahManager.scanModules(this)
            val loaded = GunyahManager.loadedModules()
            runOnUiThread { render(kernel, modules, loaded) }
        }
    }

    private fun render(kernel: String, modules: List<GunyahManager.KoModule>, loaded: Set<String>) {
        kernelText.text = "内核版本：${kernel.ifBlank { "-" }}　已加载 ${modules.count { GunyahManager.isLoaded(it, loaded) }}/${modules.size}"
        listWrap.removeAllViews()
        if (modules.isEmpty()) {
            listWrap.addView(muted(if (kernelText.text.contains("已加载 0/0")) "未检测到 modules.ko，请先在「工具链」页下载" else "modules.ko 下未找到 .ko 文件"))
            return
        }
        modules.groupBy { it.versionFolder }.forEach { (folder, list) ->
            val match = GunyahManager.versionMatches(kernel, folder)
            listWrap.addView(TextView(this).apply {
                text = if (match) "▸ $folder（适配当前内核）" else "▸ $folder"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (match) Ui.buttonSuccess(this@GunyahModuleActivity) else Ui.secondaryText(this@GunyahModuleActivity))
                setPadding(0, Ui.dp(6, d), 0, Ui.dp(2, d))
            })
            list.forEach { m -> listWrap.addView(moduleRow(m, GunyahManager.isLoaded(m, loaded))) }
        }
    }

    private fun moduleRow(m: GunyahManager.KoModule, isLoaded: Boolean): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.glassButton(this@GunyahModuleActivity)
            setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(4, d) }
        }
        row.addView(TextView(this).apply {
            text = m.fileName
            textSize = 12f
            setTextColor(Ui.primaryText(this@GunyahModuleActivity))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val state = TextView(this).apply {
            text = if (isLoaded) "已加载" else "未加载"
            textSize = 10f
            setTextColor(if (isLoaded) Ui.buttonSuccess(this@GunyahModuleActivity) else Ui.secondaryText(this@GunyahModuleActivity))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = Ui.dp(8, d) }
        }
        row.addView(state)
        val btn = TextView(this).apply {
            text = if (isLoaded) "卸载" else "加载"
            textSize = 12f
            setTextColor(Ui.buttonText(this@GunyahModuleActivity))
            setTypeface(null, Typeface.BOLD)
            background = Ui.glassButton(
                this@GunyahModuleActivity,
                if (isLoaded) Ui.buttonDanger(this@GunyahModuleActivity) else Ui.buttonSuccess(this@GunyahModuleActivity),
            )
            setPadding(Ui.dp(14, d), Ui.dp(5, d), Ui.dp(14, d), Ui.dp(5, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = Ui.dp(8, d) }
            setOnClickListener {
                isEnabled = false; alpha = 0.6f
                thread {
                    val (ok, err) = if (isLoaded) GunyahManager.rmmod(GunyahManager.modKey(m.moduleName)) else GunyahManager.insmod(m.path)
                    val loadedNow = GunyahManager.loadedModules()
                    runOnUiThread {
                        isEnabled = true; alpha = 1f
                        toast(
                            if (ok) (if (isLoaded) "已卸载 ${m.moduleName}" else "已加载 ${m.moduleName}")
                            else "失败：$err",
                        )
                        // 成功与否都重扫一遍，状态徽标与按钮文案同步刷新
                        refresh()
                    }
                }
            }
        }
        row.addView(btn)
        return row
    }

    private fun muted(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 11f
        setTextColor(Ui.secondaryText(this@GunyahModuleActivity))
        setPadding(0, Ui.dp(4, d), 0, 0)
    }
}
