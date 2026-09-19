package com.mcai.ubuntudsu.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.concurrent.Executors

class AudioBridge(private val pipe: java.io.File) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var track: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
        executor.execute {
            val minBuffer = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val audio = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(minBuffer.coerceAtLeast(16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = audio
            audio.play()
            runCatching {
                while (running) {
                    try {
                        FileInputStream(pipe).use { input ->
                            val buffer = ByteArray(16384)
                            while (running) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > 0) audio.write(buffer, 0, count)
                            }
                        }
                    } catch (_: FileNotFoundException) {
                        Thread.sleep(100)
                    }
                }
            }
            audio.stop()
            audio.release()
            track = null
        }
    }

    fun stop() {
        running = false
        track?.pause()
        executor.shutdownNow()
    }
}
