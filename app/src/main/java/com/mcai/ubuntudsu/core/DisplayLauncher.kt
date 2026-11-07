package com.mcai.ubuntudsu.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.gaurav.avnc.ui.vnc.startVncActivity
import com.gaurav.avnc.vnc.VncUri
import com.mcai.ubuntudsu.GunyahBootActivity
import com.mcai.ubuntudsu.VncViewerActivity

/**
 * v2.0：原生显示器已内置进主程序，不再依赖独立安装的 display_app。
 *
 * 启动脚本在 displayBackend=android 时：
 *   1. 以 root 起 4 个 touchd（bind unix socket），顺序在 crosvm 之前 —— 输入通道就绪；
 *   2. 给 crosvm 加 --android-display-service crosvm_display 和 4 个 --input；
 *   3. crosvm 注册 binder 服务、connect 输入 socket。
 *
 * 本类只负责把用户送进主程序自己的 NativeDisplayActivity —— 那个页面用主程序
 * 自有 root 服务拿 binder、用 TCP 直连 touchd 发输入。拉起失败一律回退内置
 * AVNC，绝不卡住用户。
 *
 * v2.0.7：把「进入显示器」的完整路由（原生显示 / 启动中控台 / 未运行提示 / VNC）
 * 收敛到 [enter]，VM 主界面的「显示器」按钮直接调用它，跳过中间的 GunyahDisplayActivity
 * 预览页，点一下就进画面（UEFI/GRUB 引导界面），不再多一次点击。
 */
object DisplayLauncher {

    const val DISPLAY_SERVICE = "crosvm_display"

    fun wantsNative(cfg: GunyahManager.Config): Boolean = cfg.displayBackend == "android"

    /** @return true 表示已成功进入内置原生显示器 */
    fun launch(ctx: Context): Boolean = try {
        val intent = Intent(ctx, com.mcai.ubuntudsu.NativeDisplayActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 进入显示器：VM 主界面「显示器」按钮与 GunyahDisplayActivity「进入桌面」共用同一路由。
     * 选了「Android 原生显示」就【直接进内置显示器】——画面即 UEFI/GRUB 引导界面，
     * 用户可立即用方向键/鼠标操作（原生显示自带 25s 兜底，crosvm 未就绪自动落回 VNC）。
     * 否则按状态回退：启动中→控制台实时日志；未运行→提示先启动；否则进内置 AVNC。
     *
     * @return true 表示已处理（已跳转或已提示），调用方无需再动作。
     */
    fun enter(ctx: Activity): Boolean {
        val cfg = GunyahManager.loadConfig(ctx)
        val host = cfg.vncHost.ifBlank { "127.0.0.1" }
        val port = GunyahManager.actualVncPort(ctx) ?: cfg.vncPort
        // 原生显示：直接进，绝不中途拐去控制台。
        if (wantsNative(cfg) && launch(ctx)) return true
        // 还在启动流程中：先给控制台（实时日志），别让用户盯着黑屏等。
        val st = GunyahManager.vmStatus(ctx)
        if (st.isBooting) {
            ctx.startActivity(Intent(ctx, GunyahBootActivity::class.java))
            return true
        }
        // VM 没起来时 VNC 只有空 surface（黑屏 + 一个鼠标），直接拦下并给出原因。
        if (!GunyahManager.isVmRunning()) {
            val s = GunyahManager.vmState(ctx)
            val msg = if (s.startsWith("failed")) "虚拟机启动失败：" + s.removePrefix("failed|")
                      else "虚拟机未运行，请先在主界面点「启动」"
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            return true
        }
        val ok = runCatching {
            startVncActivity(ctx, VncUri("vnc://$host:$port"))
        }.isSuccess
        if (!ok) {
            runCatching {
                ctx.startActivity(Intent(ctx, VncViewerActivity::class.java).apply {
                    putExtra(VncViewerActivity.EXTRA_HOST, host)
                    putExtra(VncViewerActivity.EXTRA_PORT, port)
                })
            }.onFailure {
                Toast.makeText(ctx, "内置 AVNC 查看器启动失败", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }
}
