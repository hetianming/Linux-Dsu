package com.mcai.ubuntudsu.core

import java.io.File

object ChrootRunner {
    private const val SH = "/system/bin/sh"

    fun terminalLaunchSpec(rootfs: File): Pair<String, Array<String>> {
        val script = File(rootfs.parentFile, ".linux-dsu-chroot.sh")
        script.writeText(rootCommand(rootfs), Charsets.UTF_8)
        script.setReadable(true, false)
        script.setExecutable(true, false)
        return "/system/bin/su" to arrayOf("/system/bin/su", "0", "/system/bin/sh", script.path)
    }

    fun terminalEnvironment(): Array<String> = arrayOf(
        "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin",
        "TERM=xterm-256color",
        "HOME=/root",
    )

    fun terminalSetupCommand(rootfs: File): ByteArray = ByteArray(0)

    fun rootCommand(rootfs: File): String {
        return """
            export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin
            set +x
            ROOTFS=${q(rootfs.path)}
            MOUNT_LIST="${'$'}ROOTFS/.xsh_mounts"
            HOST_FILES="${'$'}{ROOTFS%/rootfs/ubuntu}"

            cleanup() {
              if [ -f "${'$'}MOUNT_LIST" ]; then
                while read -r mnt; do
                  [ -n "${'$'}mnt" ] && /system/bin/toybox umount "${'$'}mnt" 2>/dev/null
                done < "${'$'}MOUNT_LIST"
                /system/bin/toybox rm -f "${'$'}MOUNT_LIST"
              fi
              /system/bin/toybox umount "${'$'}ROOTFS/dev/pts" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/dev" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/proc" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/sys" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/tmp" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/etc/resolv.conf" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/run/android-audio.pcm" 2>/dev/null
            }

            trap cleanup EXIT INT TERM HUP
            if [ ! -x "${'$'}ROOTFS/bin/bash" ] && [ ! -x "${'$'}ROOTFS/usr/bin/bash" ]; then
              echo "Linux-Dsu: rootfs 中没有可执行的 /bin/bash" >&2
              exit 127
            fi
            if [ "${'$'}(/system/bin/id -u 2>/dev/null)" != "0" ]; then
              echo "Linux-Dsu: 当前 shell 没有 root 权限，无法挂载和 chroot" >&2
              exit 126
            fi

            : > "${'$'}MOUNT_LIST" || exit 1
            /system/bin/toybox mkdir -p "${'$'}ROOTFS/dev/pts" "${'$'}ROOTFS/proc" "${'$'}ROOTFS/sys" "${'$'}ROOTFS/tmp" "${'$'}ROOTFS/root"
            /system/bin/toybox mkdir -p "${'$'}ROOTFS/run"
            /system/bin/toybox rm -f "${'$'}ROOTFS/run/android-audio.pcm"
            /system/bin/toybox mkfifo "${'$'}ROOTFS/run/android-audio.pcm" || { echo "音频错误: 无法创建 rootfs FIFO" >&2; exit 124; }
            /system/bin/toybox chmod 0666 "${'$'}ROOTFS/run/android-audio.pcm" || { echo "音频错误: 无法设置 FIFO 权限" >&2; exit 124; }
            /system/bin/toybox test -p "${'$'}ROOTFS/run/android-audio.pcm" || { echo "音频错误: chroot 路径不是 FIFO" >&2; exit 124; }

            /system/bin/toybox mount --bind /dev "${'$'}ROOTFS/dev" || { echo "挂载 /dev 失败" >&2; exit 125; }
            echo "${'$'}ROOTFS/dev" >> "${'$'}MOUNT_LIST"
            /system/bin/toybox mount --bind /dev/pts "${'$'}ROOTFS/dev/pts" || { echo "挂载 /dev/pts 失败" >&2; exit 125; }
            echo "${'$'}ROOTFS/dev/pts" >> "${'$'}MOUNT_LIST"
            /system/bin/toybox mount -t proc proc "${'$'}ROOTFS/proc" || { echo "挂载 /proc 失败" >&2; exit 125; }
            echo "${'$'}ROOTFS/proc" >> "${'$'}MOUNT_LIST"
            /system/bin/toybox mount -t sysfs sysfs "${'$'}ROOTFS/sys" || { echo "挂载 /sys 失败" >&2; exit 125; }
            echo "${'$'}ROOTFS/sys" >> "${'$'}MOUNT_LIST"
            /system/bin/toybox mount -t tmpfs tmpfs "${'$'}ROOTFS/tmp" || { echo "挂载 /tmp 失败" >&2; exit 125; }
            echo "${'$'}ROOTFS/tmp" >> "${'$'}MOUNT_LIST"

            /system/bin/toybox rm -f "${'$'}ROOTFS/etc/resolv.conf"
            /system/bin/toybox touch "${'$'}ROOTFS/etc/resolv.conf"
            /system/bin/toybox mount --bind /etc/resolv.conf "${'$'}ROOTFS/etc/resolv.conf" 2>/dev/null || true
            if ! /system/bin/toybox test -s "${'$'}ROOTFS/etc/resolv.conf"; then
            /system/bin/toybox printf '%s\n' \
              'nameserver 114.114.114.114' \
              'nameserver 223.5.5.5' \
              > "${'$'}ROOTFS/etc/resolv.conf"
            fi

            /system/bin/toybox printf '%s\n' \
              'if [ -r /usr/lib/os-release ]; then . /usr/lib/os-release; else . /etc/os-release 2>/dev/null; fi' \
              'export PS1="root@${'$'}{PRETTY_NAME:-Linux}:\\w# "' \
              'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' \
              'export XDG_RUNTIME_DIR=/run/user/0; mkdir -p /run/user/0 /run/pulse; export PULSE_SERVER=unix:/run/pulse/native; if command -v pulseaudio >/dev/null 2>&1 && ! pulseaudio --check >/dev/null 2>&1; then pulseaudio --daemonize=true --exit-idle-time=-1 --load="module-native-protocol-unix socket=/run/pulse/native auth-anonymous=1" >/dev/null 2>&1 || true; fi; if command -v pactl >/dev/null 2>&1; then for attempt in 1 2 3 4 5; do pactl --server=unix:/run/pulse/native info >/dev/null 2>&1 && break; sleep 0.2; done; pactl --server=unix:/run/pulse/native load-module module-pipe-sink sink_name=android_audio format=s16le rate=44100 channels=2 file=/run/android-audio.pcm >/dev/null 2>&1 || true; pactl --server=unix:/run/pulse/native set-default-sink android_audio >/dev/null 2>&1 || true; fi; export PULSE_SINK=android_audio' \
              > "${'$'}ROOTFS/root/.bashrc"
            /system/bin/toybox printf '%s\n' \
              '. /root/.bashrc' \
              > "${'$'}ROOTFS/root/.bash_profile"

            cd "${'$'}ROOTFS" || exit 1
            /system/bin/toybox chroot "${'$'}ROOTFS" /usr/bin/env \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              HOME=/root \
              TERM="${'$'}TERM" \
              /bin/bash --login
            status=${'$'}?
            [ "${'$'}status" -ne 0 ] && exit "${'$'}status"
            exit ${'$'}status
        """.trimIndent()
    }

    private fun q(value: String): String = "'${value.replace("'", "'\\''")}'"
}
