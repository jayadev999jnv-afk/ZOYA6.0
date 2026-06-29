package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow

enum class ZoyaState {
    IDLE,        // Slow, subtle breathing glow
    LISTENING,   // Active listening waveform responding to mic input
    THINKING,    // Pulsing neon ring
    SPEAKING     // Dynamic audio wave matching Zoya's output stream
}

enum class SocketState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

object ZoyaStateController {
    val assistantState = MutableStateFlow(ZoyaState.IDLE)
    val socketState = MutableStateFlow(SocketState.DISCONNECTED)
    
    // Transcripts for accessibility/immersive experience
    val userTranscript = MutableStateFlow("")
    val zoyaTranscript = MutableStateFlow("")
    
    // Real-time amplitude values for animations
    val micAmplitude = MutableStateFlow(0f)
    val speakerAmplitude = MutableStateFlow(0f)
    
    // Shared flow to trigger specific actions in UI or Service
    val actionEvent = MutableSharedFlow<String>(extraBufferCapacity = 10)
    
    fun updateState(state: ZoyaState) {
        assistantState.value = state
    }
    
    fun updateSocketState(state: SocketState) {
        socketState.value = state
    }
}
