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

    // 内嵌工具清单：assets 中的名字（.bin 包装的 gzip） → 安装后的可执行名
    private val TOOLS = mapOf(
        "adb_arm64.bin" to "adb",
        "fastboot_arm64.bin" to "fastboot",
    )

    /** APK 内嵌工具的版本标识（修改工具时同步更新此处以触发重新安装）。 */
    private const val BUNDLED_VERSION = 3

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
        TOOLS.values.forEach { name ->
            val path = toolPath(ctx, name)
            if (path == null) {
                Log.w(TAG, "工具文件缺失或不可执行: $name，需要重新安装")
                ok = false
            }
        }
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
            Log.i(TAG, "开始解压: $assetName → ${dest.absolutePath}")
            // 确保目标目录存在
            dest.parentFile?.mkdirs()
            // 删除旧文件（如果存在）
            if (dest.exists()) dest.delete()
            // 创建新文件
            if (!dest.createNewFile()) {
                throw IllegalStateException("无法创建文件: ${dest.absolutePath}")
            }
            // 从 assets 读取并解压 gzip
            val assetStream = ctx.assets.open("tools/$assetName")
            val bufferSize = 8192
            val buffer = ByteArray(bufferSize)
            GZIPInputStream(assetStream).use { gzInput ->
                dest.outputStream().use { output ->
                    var bytesRead: Int
                    while (gzInput.read(buffer, 0, bufferSize).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            // 验证文件大小
            val actualSize = dest.length()
            Log.i(TAG, "解压完成: $assetName, 大小=${actualSize} bytes")
            if (actualSize < 1000) {
                throw IllegalStateException("解压后文件过小: $actualSize")
            }
            // 设置权限
            dest.setExecutable(true, false)
            dest.setReadable(true, false)
            dest.setWritable(true, false)
            Log.i(TAG, "权限设置完成, canExecute=${dest.canExecute()}")
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
