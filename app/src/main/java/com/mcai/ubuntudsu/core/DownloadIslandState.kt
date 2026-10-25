package com.mcai.ubuntudsu.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 下载任务的全局进度（供顶部灵动岛显示）。
 *
 * DownloadService 在每次进度广播时写入，页面无需再自行维护一份。
 */
object DownloadIslandState {

    data class Snapshot(
        val active: Boolean = false,
        val fileName: String = "",
        val percent: Int = 0,
        val speed: String = "",
        val status: String = "",
    )

    fun interface Listener {
        fun onState(state: Snapshot)
    }

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
        runCatching { listener.onState(snapshot) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun publish(active: Boolean, fileName: String, percent: Int, speed: String, status: String) {
        val state = Snapshot(active, fileName, percent.coerceIn(0, 100), speed, status)
        snapshot = state
        listeners.forEach { runCatching { it.onState(state) } }
    }
}
