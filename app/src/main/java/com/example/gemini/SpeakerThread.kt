package com.example.gemini

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.ZoyaState
import com.example.ZoyaStateController
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

class SpeakerThread : Thread() {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var isRunning = true
    private var audioTrack: AudioTrack? = null

    init {
        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000) // Gemini Live uses 24kHz
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(
                    AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playAudio(data: ByteArray) {
        queue.offer(data)
    }

    fun stopPlayback() {
        queue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        ZoyaStateController.speakerAmplitude.value = 0f
    }

    override fun run() {
        while (isRunning) {
            try {
                val data = queue.take() ?: continue
                
                // Compute amplitude (for animation)
                val rms = computeShortRms(data)
                ZoyaStateController.speakerAmplitude.value = rms
                ZoyaStateController.updateState(ZoyaState.SPEAKING)

                audioTrack?.write(data, 0, data.size)
                
                // If queue becomes empty, we are done speaking (transition back to listening)
                if (queue.isEmpty()) {
                    ZoyaStateController.speakerAmplitude.value = 0f
                    if (ZoyaStateController.assistantState.value == ZoyaState.SPEAKING) {
                        ZoyaStateController.updateState(ZoyaState.LISTENING)
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun shutdown() {
        isRunning = false
        interrupt()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun computeShortRms(data: ByteArray): Float {
        var sum = 0.0
        val size = data.size / 2
        if (size <= 0) return 0f
        for (i in 0 until size) {
            if (i * 2 + 1 >= data.size) break
            val sample = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xFF)).toShort()
            sum += sample * sample
        }
        val rms = sqrt(sum / size).toFloat()
        // Normalize to a 0f - 1f scale roughly
        return (rms / 32768f).coerceIn(0f, 1f)
    }
}
