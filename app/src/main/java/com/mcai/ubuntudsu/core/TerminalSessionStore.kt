package com.mcai.ubuntudsu.core

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.ref.WeakReference

object TerminalSessionStore {
    @Volatile
    var session: TerminalSession? = null
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

    fun takeRunning(): TerminalSession? = session?.takeIf { it.isRunning }
}
