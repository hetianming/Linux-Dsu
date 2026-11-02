package com.mcai.ubuntudsu

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import com.gaurav.avnc.ui.vnc.startVncActivity
import com.gaurav.avnc.vnc.VncUri
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.Ui

/** ② 启动配置 独立页面：每个参数可改，底部保存；含内置 VNC 显示器入口。 */
class GunyahBootConfigActivity : GunyahSubActivity() {
    private lateinit var cfg: GunyahManager.Config

    override fun pageTitle() = "启动配置"

    override fun buildContent(parent: LinearLayout) {
        cfg = GunyahManager.loadConfig(this)
        val c = card("② 启动配置")

        val memEdit = editRow("内存 MEM(MB)", cfg.memMB.toString(), InputType.TYPE_CLASS_NUMBER, c)
        val cpuEdit = editRow("CPU 核心数", cfg.cpus.toString(), InputType.TYPE_CLASS_NUMBER, c)
        val nameEdit = editRow("实例名称", cfg.name, parent = c)
        c.addView(toggleRow("GPU 加速", cfg.gpu) { cfg.gpu = it })
        val gpuEdit = editRow("GPU 参数", cfg.gpuArg, parent = c)
        c.addView(toggleRow("VNC 服务", cfg.vnc) { cfg.vnc = it })
        val hostEdit = editRow("VNC 主机", cfg.vncHost, parent = c)
        val portEdit = editRow("VNC 端口", cfg.vncPort.toString(), InputType.TYPE_CLASS_NUMBER, c)
        val capEdit = editRow("VNC_CAP(可选)", cfg.vncCap, parent = c)

        fun collect() {
            cfg.memMB = memEdit.text.toString().toIntOrNull() ?: cfg.memMB
            cfg.cpus = cpuEdit.text.toString().toIntOrNull() ?: cfg.cpus
            cfg.name = nameEdit.text.toString().ifBlank { "unixnu" }
            cfg.gpuArg = gpuEdit.text.toString()
            cfg.vncHost = hostEdit.text.toString().ifBlank { "127.0.0.1" }
            cfg.vncPort = portEdit.text.toString().toIntOrNull() ?: cfg.vncPort
            cfg.vncCap = capEdit.text.toString()
        }

        c.addView(primaryButton("🖥  打开内置显示器") {
            collect()
            GunyahManager.saveConfig(this, cfg)
            val host = cfg.vncHost.ifBlank { "127.0.0.1" }
            val port = cfg.vncPort
            val ok = runCatching {
                startVncActivity(this, VncUri("vnc://$host:$port"))
            }.isSuccess
            if (!ok) toast("内置 VNC 查看器启动失败")
        }.also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(10, d)
        })
        c.addView(hintText("使用 APP 内嵌 VNC 查看器连接上面的主机/端口"))
        parent.addView(c)
        // 记录 collect 供保存用
        this.collectFn = ::collect
    }

    private var collectFn: (() -> Unit)? = null

    override fun buildActionBar(): LinearLayout? {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(12, d))
        }
        bar.addView(actionButton("💾  保存配置", Ui.buttonPrimary(this)) {
            collectFn?.invoke()
            GunyahManager.saveConfig(this, cfg)
            toast("配置已保存")
        })
        return bar
    }
}
