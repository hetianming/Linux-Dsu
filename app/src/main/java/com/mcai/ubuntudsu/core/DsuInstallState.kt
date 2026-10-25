package com.mcai.ubuntudsu.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * DSU 安装任务的全局状态。
 *
 * 安装由前台服务 [com.mcai.ubuntudsu.service.DsuInstallService] 执行，
 * 页面（DsuPage）与顶部灵动岛（MainActivity）都只是订阅者：
 * 离开 APP / 页面销毁都不会中断安装，重新进入时可通过当前快照恢复进度显示。
 */
object DsuInstallState {

    enum class Phase { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val percent: Int = 0,
        val message: String = "",
        val fileName: String = "",
    )

    fun interface Listener {
        fun onState(state: Snapshot)
    }

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    val running: Boolean get() = snapshot.phase == Phase.RUNNING

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** 注册监听并立即回调当前状态，便于页面重建后恢复进度显示 */
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
        runCatching { listener.onState(snapshot) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun publish(phase: Phase, percent: Int, message: String, fileName: String = snapshot.fileName) {
        val state = Snapshot(phase, percent.coerceIn(0, 100), message, fileName)
        snapshot = state
        listeners.forEach { runCatching { it.onState(state) } }
    }

    fun reset() {
        publish(Phase.IDLE, 0, "", "")
    }
}
