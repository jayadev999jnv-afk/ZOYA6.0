package com.example.audio

import android.util.Log
import kotlin.math.sqrt

class WakeWordDetector {
    private val TAG = "WakeWordDetector"
    
    // Frame history to track phonetic sequences
    private val historySize = 15 // Roughly 1 second of audio at ~15 frames/sec (1024 samples frame at 16kHz is 64ms)
    private val zcrHistory = DoubleArray(historySize)
    private val rmsHistory = DoubleArray(historySize)
    private var historyIndex = 0
    private var framesFilled = 0

    // Thresholds
    private val RMS_SILENCE_THRESHOLD = 120.0 // Minimum energy to be considered speech
    private val Z_ZCR_THRESHOLD = 0.12         // High frequency fricative "Z" sound
    private val OYA_ZCR_MAX = 0.08             // Low frequency periodic vowel "oya" sound
    private val OYA_RMS_MIN = 350.0            // Distinct voiced vowel burst

    fun feedAudioFrame(buffer: ShortArray, size: Int): Boolean {
        if (size <= 0) return false

        // Calculate RMS
        var sum = 0.0
        for (i in 0 until size) {
            sum += buffer[i] * buffer[i]
        }
        val rms = sqrt(sum / size)

        // Calculate ZCR
        var crossings = 0
        for (i in 1 until size) {
            val prev = buffer[i - 1]
            val curr = buffer[i]
            if ((prev < 0 && curr >= 0) || (prev >= 0 && curr < 0)) {
                crossings++
            }
        }
        val zcr = crossings.toDouble() / size

        // Store in history
        zcrHistory[historyIndex] = zcr
        rmsHistory[historyIndex] = rms
        historyIndex = (historyIndex + 1) % historySize
        if (framesFilled < historySize) {
            framesFilled++
        }

        // Only search history once it's mostly filled
        if (framesFilled < 6) return false

        return analyzeHistory()
    }

    private fun analyzeHistory(): Boolean {
        // We look for a pattern of "Z" followed by "Oya" in the rolling buffer
        // "Z" block: 1 to 4 frames of high ZCR (> Z_ZCR_THRESHOLD) and moderate RMS (> RMS_SILENCE_THRESHOLD)
        // "Oya" block: 2 to 8 frames of low ZCR (< OYA_ZCR_MAX) and high RMS (> OYA_RMS_MIN)
        // The "Oya" block must follow the "Z" block within a gap of 1-3 frames.
        
        val startIndex = if (framesFilled < historySize) 0 else historyIndex
        val length = framesFilled
        
        var zSoundStartIndex = -1
        var zSoundEndIndex = -1
        
        for (j in 0 until length - 3) {
            val actualIndex = (startIndex + j) % historySize
            val zcr = zcrHistory[actualIndex]
            val rms = rmsHistory[actualIndex]
            
            if (zcr > Z_ZCR_THRESHOLD && rms > RMS_SILENCE_THRESHOLD) {
                if (zSoundStartIndex == -1) {
                    zSoundStartIndex = j
                }
                zSoundEndIndex = j
            } else if (zSoundStartIndex != -1) {
                // We exited the high ZCR segment. Check its duration.
                val zDuration = zSoundEndIndex - zSoundStartIndex + 1
                if (zDuration in 1..4) {
                    // This was a valid "Z" candidate. Now look for "Oya" starting near here.
                    for (gap in 1..3) {
                        val oyaStart = zSoundEndIndex + gap
                        if (oyaStart >= length) break
                        
                        var oyaFramesCount = 0
                        var oyaValid = true
                        
                        for (k in 0..4) {
                            val oIndex = oyaStart + k
                            if (oIndex >= length) break
                            
                            val frameIdx = (startIndex + oIndex) % historySize
                            val oZcr = zcrHistory[frameIdx]
                            val oRms = rmsHistory[frameIdx]
                            
                            if (oZcr < OYA_ZCR_MAX && oRms > OYA_RMS_MIN) {
                                oyaFramesCount++
                            } else {
                                if (k < 2) {
                                    oyaValid = false
                                }
                                break
                            }
                        }
                        
                        if (oyaValid && oyaFramesCount >= 2) {
                            Log.d(TAG, "🔥 Wake-word 'Zoya' DETECTED! Z duration: $zDuration frames, Oya duration: $oyaFramesCount frames")
                            clearHistory()
                            return true
                        }
                    }
                }
                zSoundStartIndex = -1
            }
        }
        return false
    }

    private fun clearHistory() {
        zcrHistory.fill(0.0)
        rmsHistory.fill(0.0)
        historyIndex = 0
        framesFilled = 0
    }
}
