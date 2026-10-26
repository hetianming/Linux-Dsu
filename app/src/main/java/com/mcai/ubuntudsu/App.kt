package com.mcai.ubuntudsu

import android.app.Application
import com.mcai.ubuntudsu.core.FluidCloud

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 流体云 / 系统级灵动岛：媒体会话在 Application 级常驻，跨服务与页面共享
        FluidCloud.init(this)
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
