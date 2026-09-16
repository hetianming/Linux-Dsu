package com.mcai.ubuntudsu.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ShellResult(val code: Int, val stdout: String, val stderr: String) {
    val success: Boolean get() = code == 0
}

object RootShell {
    @Volatile
    var lastLog: ((String) -> Unit)? = null

    fun available(): Boolean {
        val result = exec("id -u", timeoutMs = 15000)
        return result.success && result.stdout.trim().endsWith("0")
    }

    fun exec(script: String, timeoutMs: Long = 30000, log: ((String) -> Unit)? = null): ShellResult {
        val sink = log ?: lastLog
        try {
            val process = ProcessBuilder("su").start()
            process.outputStream.use { stream ->
                stream.write((script.trimEnd() + "\n").toByteArray())
                stream.write("echo __RC_$?__\n".toByteArray())
                stream.write("exit\n".toByteArray())
                stream.flush()
            }
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val done = CountDownLatch(1)
            val t1 = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                        synchronized(stdout) { stdout.appendLine(line) }
                        sink?.invoke(line)
                    }
                }
            }
            val t2 = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.errorStream)).forEachLine { line ->
                        synchronized(stderr) { stderr.appendLine(line) }
                        sink?.invoke(line)
                    }
                }
            }
            t1.start(); t2.start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return ShellResult(-1, stdout.toString(), "timeout after ${timeoutMs}ms")
            }
            t1.join(3000); t2.join(3000)
            val out = synchronized(stdout) { stdout.toString() }
            val err = synchronized(stderr) { stderr.toString() }
            val code = Regex("__RC_(\\d+)__").find(out)?.groupValues?.get(1)?.toIntOrNull()
                ?: process.exitValue().let { if (it == 0) -1 else it }
            return ShellResult(code, out.replace(Regex("__RC_\\d+__\n?"), ""), err)
        } catch (e: Exception) {
            return ShellResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    fun getprop(name: String): String =
        exec("getprop $name", timeoutMs = 10000).stdout.trim()

    // root 流式读取文件（su cat）：app 无直读权限的大文件（GSI zip 等）零拷贝读取
    // 关闭流时同时结束 su 进程，避免残留
    fun openStream(path: String): java.io.InputStream {
        val process = ProcessBuilder("su").start()
        process.outputStream.use { output ->
            output.write("cat \"$path\"\n".toByteArray())
            output.flush()
        }
        return object : java.io.FilterInputStream(process.inputStream) {
            override fun close() {
                runCatching { super.close() }
                runCatching { process.destroy() }
            }
        }
    }
}
