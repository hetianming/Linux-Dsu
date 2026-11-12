package com.mcai.ubuntudsu

import android.app.Application
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.core.RomCache

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // v2.0.38：语言状态在冷启动时恢复（跟随系统 / 手动固定）
        Lang.init(this)
        // 后台预热 ROM 缓存：进「ROM固件」页时直接命中内存，不必等网络
        runCatching { RomCache.warmUp(this) }
        // 手机重启后内核模块是空的（Android 没有 init.d，gunyah-host-share 不会自己回来），
        // 这里在 APP 冷启动时静默补一次，省掉手动去「内核模块」页一个个点「加载」。
        // 已加载的会跳过，所以多进程/多次启动都没有副作用。
        Thread {
            runCatching { Thread.sleep(2500) }   // 等 su 授权与目录就绪
            runCatching { GunyahManager.autoLoadModules(this) }
        }.start()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                java.io.File(filesDir, "crash.log").writeText(
                    "time: ${java.util.Date()}\nthread: ${thread.name}\n\n${throwable.stackTraceToString()}"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
