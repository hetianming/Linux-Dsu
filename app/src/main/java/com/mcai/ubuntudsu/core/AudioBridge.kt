package com.mcai.ubuntudsu.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/**
 * 宿主侧音频桥：从 rootfs 内的 PCM FIFO（容器内 PulseAudio module-pipe-sink 的写入端）
 * 读取 s16le / 44100Hz / 立体声数据并送 AudioTrack 播放。
 * rootfs 位于应用数据目录内，应用身份即可直接打开该 FIFO（chmod 666）。
 */
class AudioBridge(private val pipe: java.io.File) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    @Volatile private var currentStream: FileInputStream? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
        executor.execute {
            runCatching { playLoop() }
                .onFailure { android.util.Log.w("AudioBridge", "音频桥接线程退出：${it.message}") }
        }
    }

    private fun playLoop() {
        ensureFifo()
        val minBuffer = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(minBuffer.coerceAtLeast(16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (audio.state != AudioTrack.STATE_INITIALIZED) {
            audio.release()
            android.util.Log.w("AudioBridge", "AudioTrack 初始化失败")
            return
        }
        track = audio
        audio.play()
        val buffer = ByteArray(16384)
        try {
            while (running) {
                try {
                    FileInputStream(pipe).use { input ->
                        currentStream = input
                        while (running) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) audio.write(buffer, 0, count)
                        }
                    }
                } catch (_: FileNotFoundException) {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    break
                }
                // 写端关闭（EOF）后稍候再重开，避免 open/read 热循环
                if (running) Thread.sleep(40)
            }
        } finally {
            currentStream = null
            runCatching { audio.stop() }
            runCatching { audio.release() }
            track = null
        }
    }

    private fun ensureFifo() {
        if (pipe.exists()) return
        runCatching {
            pipe.parentFile?.let { parent -> if (!parent.isDirectory) parent.mkdirs() }
            val mkfifo = Runtime.getRuntime().exec(arrayOf("/system/bin/toybox", "mkfifo", pipe.absolutePath))
            mkfifo.waitFor()
            pipe.setReadable(true, false)
            pipe.setWritable(true, false)
        }
    }

    fun stop() {
        running = false
        runCatching { currentStream?.close() }
        track?.pause()
        // 关闭流无法解除阻塞中的 open()/read()：用 O_RDWR 短暂打开 FIFO（对 FIFO 永不阻塞），
        // 唤醒卡住的旧读线程使其立刻返回，随后旧线程因 running=false 退出
        runCatching {
            val escaped = pipe.absolutePath.replace("'", "'\\''")
            Runtime.getRuntime().exec(arrayOf("/system/bin/toybox", "sh", "-c", "exec 3<> '$escaped'")).waitFor()
        }
        executor.shutdownNow()
    }
}
