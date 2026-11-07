package com.mcai.ubuntudsu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcai.ubuntudsu.core.GunyahManager

/**
 * 开机自启：补上 Gunyah 依赖的内核模块。
 *
 * Android 没有 init.d / Magisk 模块接管，手机一重启 /proc/modules 就是空的：
 * gunyah-host-share 不回来 → /dev/gunyah 不存在 → crosvm 直接起不来。
 *
 * 这里只加载「本机 uname -r 对应的 KMI 目录」下的 .ko，别的版本一律不碰
 * （不同内核 ABI 不兼容，insmod 错版本会直接内核 panic / 蓝屏）。
 *
 * 冷启动兜底在 App.onCreate 里，两处调用是幂等的（已加载的会跳过）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val app = context.applicationContext
        Thread {
            // 开机后 su / 目录要等一会儿才就绪，太早调用会白跑
            runCatching { Thread.sleep(12_000) }
            runCatching { GunyahManager.autoLoadModules(app) }
        }.start()
    }
}
