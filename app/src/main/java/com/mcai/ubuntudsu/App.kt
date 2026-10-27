package com.mcai.ubuntudsu

import android.app.Application
import com.mcai.ubuntudsu.core.RomCache

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 后台预热 ROM 缓存：进「ROM固件」页时直接命中内存，不必等网络
        runCatching { RomCache.warmUp(this) }
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
