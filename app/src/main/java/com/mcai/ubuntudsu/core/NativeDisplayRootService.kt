package com.mcai.ubuntudsu.core

import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * v2.0 内置原生显示：主程序自己的 root 服务（uid=0）。
 *
 * 职责（v2.0.10 起两件）：
 *   1) 查 ServiceManager 拿 crosvm 用 `--android-display-service crosvm_display`
 *      注册的 binder（显示）；
 *   2) 托管 4 个输入 unix socket（[InputSocketHost]），APP 通过 AIDL
 *      [INativeDisplayService.writeInput] 把 evdev 字节交给本进程直写 socket。
 *
 * v2.0.10 架构变更：废弃 native touchd + APP 直连 127.0.0.1 TCP 桥。untrusted_app
 * → 本地 TCP 在真机上被 SELinux 拦截，是连续多版「能显示不能操作」的根因。输入改由
 * 本 root 服务进程(uid=0 / permissive 域)在 crosvm 启动前 bind+listen，APP 只走已授权的
 * binder 把字节送进来，与已验证可用的 Crosvm-Android 参考实现一致。
 *
 * 为什么必须 root：crosvm 从 su/magisk 域注册服务，且输入 socket 需由 permissive 域
 * 持有并 accept（crosvm 为 connect 方）；untrusted_app 直接做这两件事都会被 SELinux 拒绝。
 *
 * 【不要 onUnbind/closeAll】crosvm 只在启动那一刻 connect 一次、之后永不重连（对已运行的
 * VM）。若 APP 解绑显示器就关掉 listener/socket，输入将永久失效。故 socket 常驻到 root
 * 服务进程结束，ensureListening() 幂等，APP 侧由 InputServiceClient 持有长连接保活。
 *
 * v2.0.4：查找方式对齐已验证的参考实现 —— 纯反射调 ServiceManager.checkService /
 * waitForService，HiddenApiBypass 仅作兜底；等待窗口放宽到 60 秒。
 */
class NativeDisplayRootService : RootService() {

    companion object {
        private const val TAG = "NativeDisplayRoot"
        private val smClass: Class<*> by lazy { Class.forName("android.os.ServiceManager") }

        /** 纯反射优先；个别 ROM 对反射也设限时再用 HiddenApiBypass 兜底。 */
        private fun smCall(method: String, name: String): IBinder? =
            runCatching {
                smClass.getMethod(method, String::class.java).invoke(null, name) as? IBinder
            }.getOrNull() ?: runCatching {
                HiddenApiBypass.invoke(smClass, null, method, name) as? IBinder
            }.getOrNull()

        /** waitForService 会一直阻塞到服务注册，必须在独立线程里加超时。 */
        private fun smWaitForService(name: String, timeoutMs: Long): IBinder? {
            var result: IBinder? = null
            val t = Thread { result = smCall("waitForService", name) }.apply {
                isDaemon = true; this.name = "WaitSvc-$name"
            }
            t.start()
            try { t.join(timeoutMs) } catch (_: InterruptedException) {}
            return result
        }
    }

    private fun lookup(name: String): IBinder? =
        smCall("checkService", name) ?: smCall("getService", name)

    private val bridge = object : INativeDisplayService.Stub() {
        override fun waitForDisplayBinder(name: String): IBinder? {
            lookup(name)?.let { return it }
            // 12 × 5s = 60s：crosvm 常在显示器页面之后才起，别 8 秒就放弃
            repeat(12) { i ->
                smWaitForService(name, 5000L)?.let { return it }
                lookup(name)?.let { return it }
                Log.d(TAG, "waiting for $name (${i + 1}/12)")
            }
            return lookup(name)
        }

        override fun diagnostics(name: String): String = buildString {
            append("uid=").append(android.os.Process.myUid())
            append(" svc=").append(name)
            append(" state=").append(if (lookup(name) != null) "FOUND" else "MISSING")
            append(" selinux=").append(runCatching {
                HiddenApiBypass.invoke(Class.forName("android.os.SELinux"), null, "isSELinuxEnabled")
            }.getOrElse { "?" })
        }

        // ---- v2.0.10 输入桥（root 进程托管 socket）----
        override fun ensureInputListening() {
            try { InputSocketHost.ensureListening() }
            catch (t: Throwable) { Log.e(TAG, "ensureInputListening failed", t) }
        }

        override fun getInputChannelsReady(): BooleanArray =
            try { InputSocketHost.readyChannels() }
            catch (t: Throwable) { Log.e(TAG, "readyChannels failed", t); BooleanArray(4) }

        override fun writeInput(channel: Int, data: ByteArray): Boolean =
            try { InputSocketHost.write(channel, data) }
            catch (t: Throwable) { Log.e(TAG, "writeInput failed", t); false }

        /** v2.0.11：把 root 侧真实链路统计透传给 APP 状态栏，替代原来的 fd!=null 假阳性。 */
        override fun inputDiagnostics(): String =
            try { InputSocketHost.diagnostics() }
            catch (t: Throwable) {
                Log.e(TAG, "inputDiagnostics failed", t)
                "diagnostics-error: ${t.javaClass.simpleName}"
            }
    }

    override fun onBind(intent: Intent): IBinder = bridge
}
