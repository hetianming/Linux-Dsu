package com.mcai.ubuntudsu.core

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.ref.WeakReference

object TerminalSessionStore {
    private val _sessions = mutableListOf<TerminalSession>()
    val sessions: List<TerminalSession> get() = ArrayList(_sessions)
    private var target = WeakReference<TerminalSessionClient>(null)

    fun setTarget(value: TerminalSessionClient?) {
        target = WeakReference(value)
    }

    val client: TerminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) { target.get()?.onTextChanged(session) }
        override fun onTitleChanged(session: TerminalSession) { target.get()?.onTitleChanged(session) }
        override fun onSessionFinished(session: TerminalSession) { target.get()?.onSessionFinished(session) }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) { target.get()?.onCopyTextToClipboard(session, text) }
        override fun onPasteTextFromClipboard(session: TerminalSession?) { target.get()?.onPasteTextFromClipboard(session) }
        override fun onBell(session: TerminalSession) { target.get()?.onBell(session) }
        override fun onColorsChanged(session: TerminalSession) { target.get()?.onColorsChanged(session) }
        override fun onTerminalCursorStateChange(state: Boolean) { target.get()?.onTerminalCursorStateChange(state) }
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) { target.get()?.setTerminalShellPid(session, pid) }
        override fun getTerminalCursorStyle(): Int? = target.get()?.getTerminalCursorStyle()
        override fun logError(tag: String, message: String) { target.get()?.logError(tag, message) }
        override fun logWarn(tag: String, message: String) { target.get()?.logWarn(tag, message) }
        override fun logInfo(tag: String, message: String) { target.get()?.logInfo(tag, message) }
        override fun logDebug(tag: String, message: String) { target.get()?.logDebug(tag, message) }
        override fun logVerbose(tag: String, message: String) { target.get()?.logVerbose(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { target.get()?.logStackTraceWithMessage(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { target.get()?.logStackTrace(tag, e) }
    }

    fun addSession(session: TerminalSession) {
        if (_sessions.none { it === session }) _sessions.add(session)
    }

    fun removeSession(session: TerminalSession) {
        _sessions.remove(session)
    }

    /** 清掉已结束（shell 退出）的会话，避免列表无限堆积死会话。 */
    fun pruneDead() {
        _sessions.removeAll { !it.isRunning }
    }

    fun runningSessions(): List<TerminalSession> = _sessions.filter { it.isRunning }

    /** 返回任意一个仍在运行的会话（供外部判断"终端是否已在运行"）。 */
    fun takeRunning(): TerminalSession? = runningSessions().firstOrNull()
}
