package com.mcai.ubuntudsu.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object ToolInstaller {

    private const val TAG = "ToolInstaller"
    private const val TOOLS_DIR = "tools"
    private const val PREFS_KEY = "tools_installed_version"
    private const val BUNDLED_ZIP = "tools.zip"

    // 工具清单：ZIP 内的文件名 → 安装后的可执行名
    private val TOOLS = listOf("adb" to "adb", "fastboot" to "fastboot")

    /** APK 内嵌工具的版本标识（修改工具时同步更新此处以触发重新安装）。 */
    private const val BUNDLED_VERSION = 4

    fun ensureInstalled(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("tools_install", Context.MODE_PRIVATE)
        // 兼容旧版本（Boolean）和新版本（Integer）
        val installedVersion = try {
            prefs.getInt(PREFS_KEY, -1)
        } catch (e: ClassCastException) {
            android.util.Log.w(TAG, "清除旧的 Boolean 偏好: ${e.message}")
            prefs.edit().remove(PREFS_KEY).apply()
            -1
        } catch (e: Exception) {
            android.util.Log.w(TAG, "读取偏好失败: ${e.message}")
            -1
        }
        if (installedVersion == BUNDLED_VERSION) {
            // 验证工具文件仍然存在
            return validateTools(ctx)
        }
        Log.i(TAG, "工具版本不匹配 (expected=$BUNDLED_VERSION, got=$installedVersion)，开始安装")
        val ok = installAll(ctx)
        if (ok) {
            prefs.edit().putInt(PREFS_KEY, BUNDLED_VERSION).apply()
            Log.i(TAG, "工具安装成功")
        } else {
            Log.e(TAG, "工具安装失败")
        }
        return ok
    }

    /** 验证已安装的工具是否仍然存在且可执行。 */
    private fun validateTools(ctx: Context): Boolean {
        var ok = true
        TOOLS.forEach { (_, name) ->
            val path = toolPath(ctx, name)
            if (path == null) {
                Log.w(TAG, "工具文件缺失或不可执行: $name，需要重新安装")
                ok = false
            }
        }
        return ok
    }

    /** 将 ZIP 中的工具解压到 app-private 目录并设置可执行权限。 */
    fun installAll(ctx: Context): Boolean {
        val dir = toolsDir(ctx)
        dir.mkdirs()
        var ok = true
        TOOLS.forEach { (zipName, installName) ->
            if (!extractTool(ctx, zipName, File(dir, installName))) ok = false
        }
        // 验证文件存在且可执行
        TOOLS.forEach { (_, name) ->
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

    /** 从 ZIP 中解压单个工具文件。 */
    private fun extractTool(ctx: Context, zipEntryName: String, dest: File): Boolean {
        if (dest.exists() && dest.canExecute()) return true
        return runCatching {
            Log.i(TAG, "开始解压: $zipEntryName → ${dest.absolutePath}")
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            if (!dest.createNewFile()) {
                throw IllegalStateException("无法创建文件: ${dest.absolutePath}")
            }
            // 从 ZIP 中提取
            ctx.assets.open("tools.zip").use { zipStream ->
                ZipInputStream(zipStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == zipEntryName && !entry.isDirectory) {
                            dest.outputStream().use { out ->
                                zis.copyTo(out)
                            }
                            break
                        }
                        entry = zis.nextEntry
                    }
                }
            }
            val actualSize = dest.length()
            Log.i(TAG, "解压完成: $zipEntryName, 大小=${actualSize} bytes")
            if (actualSize < 1000) {
                throw IllegalStateException("解压后文件过小: $actualSize")
            }
            dest.setExecutable(true, false)
            dest.setReadable(true, false)
            dest.setWritable(true, false)
            Log.i(TAG, "权限设置完成, canExecute=${dest.canExecute()}")
            dest.canExecute()
        }.getOrElse {
            Log.e(TAG, "解压工具失败: $zipEntryName", it)
            false
        }
    }

    fun toolsDir(ctx: Context): File = File(ctx.filesDir, TOOLS_DIR).apply { mkdirs() }

    fun toolPath(ctx: Context, name: String): String? {
        val candidate = File(toolsDir(ctx), name)
        return if (candidate.exists() && candidate.canExecute()) candidate.absolutePath else null
    }
}
