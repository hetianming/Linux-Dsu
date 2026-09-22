package com.mcai.ubuntudsu.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

object ToolInstaller {

    private const val TAG = "ToolInstaller"
    private const val TOOLS_DIR = "tools"
    private const val PREFS_KEY = "tools_installed_version"

    // 内嵌工具清单：assets 中的名字（.gz 压缩） → 安装后的可执行名
    private val TOOLS = mapOf(
        "adb_arm64.gz" to "adb",
        "fastboot_arm64.gz" to "fastboot",
    )

    /** APK 内嵌工具的版本标识（修改工具时同步更新此处以触发重新安装）。 */
    private const val BUNDLED_VERSION = 3

    fun ensureInstalled(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("tools_install", Context.MODE_PRIVATE)
        // 兼容旧版本（Boolean）和新版本（Integer）
        val installedVersion = try {
            prefs.getInt(PREFS_KEY, -1)
        } catch (e: ClassCastException) {
            // 旧版本存的是 Boolean，清除后重新安装
            android.util.Log.w(TAG, "清除旧的 Boolean 偏好: ${e.message}")
            prefs.edit().remove(PREFS_KEY).apply()
            -1
        } catch (e: Exception) {
            android.util.Log.w(TAG, "读取偏好失败: ${e.message}")
            -1
        }
        if (installedVersion == BUNDLED_VERSION) return true
        val ok = installAll(ctx)
        if (ok) prefs.edit().putInt(PREFS_KEY, BUNDLED_VERSION).apply()
        return ok
    }

    /** 将内嵌工具（.gz 压缩）解压到 app-private 目录并设置可执行权限。 */
    fun installAll(ctx: Context): Boolean {
        val dir = toolsDir(ctx)
        dir.mkdirs()
        var ok = true
        TOOLS.forEach { (assetName, installName) ->
            if (!ensureExecutable(File(dir, installName), assetName, ctx)) ok = false
        }
        // 验证文件存在且可执行
        TOOLS.values.forEach { name ->
            val f = File(dir, name)
            if (!f.exists()) {
                Log.e(TAG, "工具安装后文件缺失: ${f.absolutePath}")
                ok = false
            } else if (!f.canExecute()) {
                Log.e(TAG, "工具安装后无执行权限: ${f.absolutePath}")
                ok = false
            }
        }
        return ok
    }

    /** 从 assets 解压 gzip 压缩文件，chmod 755。 */
    private fun ensureExecutable(dest: File, assetName: String, ctx: Context): Boolean {
        if (dest.exists() && dest.canExecute()) return true
        return runCatching {
            // 解压 gzip 压缩文件（Android 内置 GZIPInputStream）
            ctx.assets.open("tools/$assetName").use { input ->
                dest.parentFile?.mkdirs()
                if (!dest.exists()) dest.createNewFile()
                GZIPInputStream(input).use { gzInput ->
                    gzInput.copyTo(dest.outputStream())
                }
            }
            dest.setExecutable(true, false)
            dest.setReadable(true, false)
            Log.i(TAG, "安装工具: $assetName → ${dest.absolutePath}")
            dest.canExecute()
        }.getOrElse {
            Log.e(TAG, "解压工具失败: $assetName", it)
            false
        }
    }

    fun toolsDir(ctx: Context): File = File(ctx.filesDir, TOOLS_DIR).apply { mkdirs() }

    fun toolPath(ctx: Context, name: String): String? {
        val candidate = File(toolsDir(ctx), name)
        return if (candidate.exists() && candidate.canExecute()) candidate.absolutePath else null
    }
}
