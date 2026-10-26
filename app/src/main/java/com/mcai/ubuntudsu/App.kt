package com.mcai.ubuntudsu

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
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
