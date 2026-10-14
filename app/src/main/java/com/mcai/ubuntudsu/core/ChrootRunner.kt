package com.mcai.ubuntudsu.core

import android.content.Context
import java.io.File

object ChrootRunner {
    private const val SU = "/system/bin/su"
    private const val SH = "/system/bin/sh"

    // root chroot 启动规格（已验证可用）：
    // /system/bin/su 0 提权，toybox 挂载 /dev /proc /sys /tmp，伪造音频 FIFO，
    // toybox chroot 进 rootfs 起 bash。依赖 root（Magisk/su）。
    // 返回 (executable, args)，供 TerminalSession 直接 execve
    fun terminalLaunchSpec(ctx: Context, rootfs: File): Pair<String, Array<String>> {
        val script = File(rootfs.parentFile, ".linux-dsu-chroot.sh")
        script.writeText(rootCommand(rootfs), Charsets.UTF_8)
        script.setReadable(true, false)
        script.setExecutable(true, false)

        // 同步写入国内 apt 源配置脚本（host 侧），由 chroot 启动脚本调用
        val mirrorScript = File(rootfs.parentFile, ".linux-dsu-apt-mirror.sh")
        mirrorScript.writeText(aptMirrorScript(), Charsets.UTF_8)
        mirrorScript.setReadable(true, false)
        mirrorScript.setExecutable(true, false)

        val scriptRef = script.path.replace("'", "'\\''")
        return SU to arrayOf(SU, "0", "-c", "sh '$scriptRef'")
    }

    // 国内 apt 源配置脚本：识别 /etc/os-release 的 ID + VERSION_CODENAME，
    // 为 Ubuntu 写入清华 ubuntu-ports（arm64 必须用 ports 路径），为 Debian 写入清华 debian + debian-security。
    // 仅当 rootfs 里尚未包含国内镜像域名时写入，避免覆盖用户手动修改的源。
    // 兼容传统 sources.list；清掉 deb822 格式 .sources 文件（Ubuntu 23.10+/Debian 13+）避免冲突。
    private fun aptMirrorScript(): String {
        // ${'$'} = 字面 $ 符号，供 shell 变量使用
        return """
#!/system/bin/sh
# 用法: .linux-dsu-apt-mirror.sh <rootfs 绝对路径>
set -e
ROOTFS="${'$'}1"
[ -z "${'$'}ROOTFS" ] && exit 0
OS_RELEASE="${'$'}ROOTFS/etc/os-release"
[ -f "${'$'}OS_RELEASE" ] || OS_RELEASE="${'$'}ROOTFS/usr/lib/os-release"
[ -f "${'$'}OS_RELEASE" ] || exit 0

. "${'$'}OS_RELEASE"
DISTRO="${'$'}ID"
CODENAME="${'$'}VERSION_CODENAME"
[ -n "${'$'}CODENAME" ] || exit 0

# 若现有源文件已包含国内镜像域名，说明已配置过，直接退出
EXISTING="${'$'}ROOTFS/etc/apt/sources.list"
if [ -f "${'$'}EXISTING" ] && /system/bin/toybox grep -q -E 'tuna\.tsinghua\.edu\.cn|mirrors\.ustc\.edu\.cn' "${'$'}EXISTING" 2>/dev/null; then
    exit 0
fi

case "${'$'}DISTRO" in
    ubuntu)
        [ -f "${'$'}EXISTING" ] && /system/bin/toybox cp -f "${'$'}EXISTING" "${'$'}EXISTING.dsu.bak" 2>/dev/null || true
        /system/bin/toybox printf '%s\n' \
            "# Ubuntu ${'$'}CODENAME - China mirrors (TUNA, auto-configured)" \
            "deb [arch=arm64] https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ ${'$'}CODENAME main restricted universe multiverse" \
            "deb [arch=arm64] https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ ${'$'}CODENAME-updates main restricted universe multiverse" \
            "deb [arch=arm64] https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ ${'$'}CODENAME-backports main restricted universe multiverse" \
            "deb [arch=arm64] https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ ${'$'}CODENAME-security main restricted universe multiverse" \
            > "${'$'}EXISTING"
        /system/bin/toybox rm -f "${'$'}ROOTFS/etc/apt/sources.list.d/ubuntu.sources" 2>/dev/null || true
        ;;
    debian)
        [ -f "${'$'}EXISTING" ] && /system/bin/toybox cp -f "${'$'}EXISTING" "${'$'}EXISTING.dsu.bak" 2>/dev/null || true
        /system/bin/toybox printf '%s\n' \
            "# Debian ${'$'}CODENAME - China mirrors (TUNA, auto-configured)" \
            "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ ${'$'}CODENAME main" \
            "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ ${'$'}CODENAME-updates main" \
            "deb https://mirrors.tuna.tsinghua.edu.cn/debian-security/ ${'$'}CODENAME-security main" \
            > "${'$'}EXISTING"
        /system/bin/toybox rm -f "${'$'}ROOTFS/etc/apt/sources.list.d/debian.sources" 2>/dev/null || true
        ;;
    *)
        exit 0
        ;;
esac

# 清掉 sources.list.d/ 里的官方源 .list 文件（防止与 sources.list 冲突）
for f in "${'$'}ROOTFS/etc/apt/sources.list.d/"*.list; do
    [ -f "${'$'}f" ] || continue
    /system/bin/toybox grep -q -E 'archive\.ubuntu\.com|security\.ubuntu\.com|deb\.debian\.org' "${'$'}f" 2>/dev/null && /system/bin/toybox rm -f "${'$'}f"
done

exit 0
"""
    }

    fun terminalEnvironment(): Array<String> = arrayOf(
        "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin",
        "TERM=xterm-256color",
        "HOME=/root",
    )

    fun terminalSetupCommand(rootfs: File): ByteArray = ByteArray(0)

    /**
     * 确保 rootfs 内音频 FIFO 就绪（幂等，root 执行）。
     * 旧版本会话可能遗留：绝对路径死软链（chroot 内永远解析失败）或普通文件。
     * 必须在启动 VNC 前调用——root 脚本只在会话创建时执行一次，不能依赖它修复旧会话。
     * 成功时输出 __FIFO_OK__ 供调用方校验。
     */
    fun ensureAudioFifo(rootfs: File): ShellResult {
        val runDir = File(rootfs, "run").absolutePath.replace("'", "'\\''")
        val fifo = File(rootfs, "run/android-audio.pcm").absolutePath.replace("'", "'\\''")
        val script = """
            mkdir -p '$runDir'
            if [ -e '$fifo' ] || [ -L '$fifo' ]; then
              [ -p '$fifo' ] || rm -f '$fifo'
            fi
            [ -p '$fifo' ] || /system/bin/toybox mkfifo '$fifo'
            /system/bin/toybox chmod 666 '$fifo' 2>/dev/null || chmod 666 '$fifo'
            [ -p '$fifo' ] && echo __FIFO_OK__
        """.trimIndent()
        return RootShell.exec(script, timeoutMs = 10000)
    }

    private fun rootCommand(rootfs: File): String {
        return """
            export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin
            set +x
            ROOTFS=${q(rootfs.absolutePath)}
            HOST_FILES=${q(File(rootfs.absolutePath, "..").absolutePath)}

            cleanup() {
              /system/bin/toybox umount "${'$'}ROOTFS/dev/pts" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/dev" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/proc" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/sys" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/tmp" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/etc/resolv.conf" 2>/dev/null
            }
            trap cleanup EXIT INT TERM HUP

            if [ ! -x "${'$'}ROOTFS/bin/bash" ] && [ ! -x "${'$'}ROOTFS/usr/bin/bash" ]; then
              echo "Linux-Dsu: rootfs 中没有可执行的 /bin/bash" >&2
              exit 127
            fi

            # 挂载 /dev /dev/pts /proc /sys /tmp（toybox）
            /system/bin/toybox mkdir -p "${'$'}ROOTFS/dev/pts" "${'$'}ROOTFS/proc" "${'$'}ROOTFS/sys" "${'$'}ROOTFS/tmp" "${'$'}ROOTFS/root"
            /system/bin/toybox mount --bind /dev "${'$'}ROOTFS/dev" || { echo "挂载 /dev 失败" >&2; exit 125; }
            /system/bin/toybox mount --bind /dev/pts "${'$'}ROOTFS/dev/pts" || { echo "挂载 /dev/pts 失败" >&2; exit 125; }
            /system/bin/toybox mount -t proc proc "${'$'}ROOTFS/proc" || { echo "挂载 /proc 失败" >&2; exit 125; }
            /system/bin/toybox mount -t sysfs sysfs "${'$'}ROOTFS/sys" || { echo "挂载 /sys 失败" >&2; exit 125; }
            /system/bin/toybox mount -t tmpfs tmpfs "${'$'}ROOTFS/tmp" || { echo "挂载 /tmp 失败" >&2; exit 125; }
            # resolv.conf：bind host 的，缺失则写国内 DNS
            /system/bin/toybox rm -f "${'$'}ROOTFS/etc/resolv.conf"
            /system/bin/toybox touch "${'$'}ROOTFS/etc/resolv.conf"
            /system/bin/toybox mount --bind /etc/resolv.conf "${'$'}ROOTFS/etc/resolv.conf" 2>/dev/null || true
            if ! /system/bin/toybox test -s "${'$'}ROOTFS/etc/resolv.conf"; then
              /system/bin/toybox printf '%s\n' 'nameserver 114.114.114.114' 'nameserver 223.5.5.5' > "${'$'}ROOTFS/etc/resolv.conf"
            fi

            # 音频 FIFO：直接建在 rootfs /run 下（rootfs 位于应用数据目录内，
            # AudioBridge 以应用身份直读同一 FIFO；chroot 内外是同一 inode，无软链/绑定）。
            # 仅在缺失时创建：绝不能 unlink，否则仍持有旧 inode 的 PulseAudio sink 会写向死管道
            /system/bin/toybox mkdir -p "${'$'}ROOTFS/run"
            if [ ! -p "${'$'}ROOTFS/run/android-audio.pcm" ]; then
              /system/bin/toybox rm -f "${'$'}ROOTFS/run/android-audio.pcm" 2>/dev/null
              /system/bin/toybox mkfifo "${'$'}ROOTFS/run/android-audio.pcm" 2>/dev/null || true
            fi
            /system/bin/toybox chmod 666 "${'$'}ROOTFS/run/android-audio.pcm" 2>/dev/null || true

            # bashrc：pulse 音频 + VNC 桌面环境变量
            /system/bin/toybox printf '%s\n' \
              'if [ -r /usr/lib/os-release ]; then . /usr/lib/os-release; else . /etc/os-release 2>/dev/null; fi' \
              'export PS1="root@${'$'}{PRETTY_NAME:-Linux}:\\w# "' \
              'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' \
              'export XDG_RUNTIME_DIR=/run/user/$(id -u); mkdir -p "${'$'}XDG_RUNTIME_DIR/pulse" 2>/dev/null || true; export PULSE_SERVER=unix:${'$'}XDG_RUNTIME_DIR/pulse/native; export PULSE_SINK=android_audio; export LANG=zh_CN.UTF-8; export LANGUAGE=zh_CN:en; export LC_ALL=' \
              > "${'$'}ROOTFS/root/.bashrc"
            /system/bin/toybox printf '%s\n' \
              '. /root/.bashrc' \
              > "${'$'}ROOTFS/root/.bash_profile"

            # 配置国内 apt 源（仅当源尚未配置为国内镜像时写入，避免覆盖用户手动修改）
            # 识别 /etc/os-release 的 ID 和 VERSION_CODENAME，写入清华源（Ubuntu 用 ubuntu-ports）
            /system/bin/toybox sh "${'$'}HOST_FILES/.linux-dsu-apt-mirror.sh" "${'$'}ROOTFS" 2>/dev/null || true

            # 修复 Tigervnc 默认 session wrapper：它引用 host 的 /system/bin/sh，chroot 里不存在
            /system/bin/toybox printf '%s\n' \
              '#!/bin/sh' \
              'unset SESSION_MANAGER' \
              'unset DBUS_SESSION_BUS_ADDRESS' \
              'export HOME=/root' \
              'export XDG_VNC_SESSION=1' \
              'export DISPLAY=${'$'}{DISPLAY:-:1}' \
              'export PULSE_SERVER=unix:${'$'}{XDG_RUNTIME_DIR:-/run/pulse}/pulse/native' \
              'export PULSE_SINK=android_audio' \
              'export LANG=zh_CN.UTF-8' \
              'export LANGUAGE=zh_CN:en' \
              'export LC_ALL=' \
              'if [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ] && command -v dbus-launch >/dev/null 2>&1; then eval "dbus-launch sh -c \\"exec ${'$'}0 "$"; fi' \
              'if [ -x /root/.vnc/xstartup ]; then exec /root/.vnc/xstartup; else for c in startxfce4 startplasma-x11 gnome-shell; do command -v "${'$'}c" >/dev/null 2>&1 && { exec "${'$'}c"; }; done; echo "no desktop" >&2; exit 1; fi' \
              > "${'$'}ROOTFS/etc/X11/Xtigervnc-session" 2>/dev/null || true
            /system/bin/toybox chmod +x "${'$'}ROOTFS/etc/X11/Xtigervnc-session" 2>/dev/null || true

            echo -e "\033[?25l"
            cd "${'$'}ROOTFS" || exit 1
            /system/bin/toybox chroot "${'$'}ROOTFS" /usr/bin/env \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              HOME=/root \
              TERM="${'$'}TERM" \
              LANG=zh_CN.UTF-8 \
              LANGUAGE=zh_CN:en \
              PULSE_SERVER=unix:/run/user/0/pulse/native \
              PULSE_SINK=android_audio \
              /bin/bash --login
            status=${'$'}?
            exit "${'$'}status"
        """.trimIndent()
    }

    private fun q(value: String): String = "'${value.replace("'", "'\\''")}'"
}
