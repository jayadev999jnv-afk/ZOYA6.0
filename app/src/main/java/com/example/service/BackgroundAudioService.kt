package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.SocketState
import com.example.ZoyaState
import com.example.ZoyaStateController
import com.example.audio.WakeWordDetector
import com.example.gemini.LiveSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class BackgroundAudioService : Service() {
    private val TAG = "BackgroundAudioService"
    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "zoya_voice_assistant_channel"

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var liveSessionManager: LiveSessionManager
    private val wakeWordDetector = WakeWordDetector()

    private var micThread: Thread? = null
    @Volatile private var isRecording = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BackgroundAudioService Created")
        createNotificationChannel()
        liveSessionManager = LiveSessionManager(this, scope)

        // Start Foreground immediately to satisfy OS rules
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Start listening to mic thread
        startMicThread()

        // Observe assistant state changes to update the notification dynamically
        scope.launch {
            ZoyaStateController.assistantState
                .collect {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification())
                }
        }

        // Listen for actions from the UI
        scope.launch {
            ZoyaStateController.actionEvent.collect { event ->
                when (event) {
                    "start_session" -> liveSessionManager.startSession()
                    "stop_session" -> liveSessionManager.stopSession()
                    "toggle_session" -> {
                        if (liveSessionManager.isConnected()) {
                            liveSessionManager.stopSession()
                        } else {
                            liveSessionManager.startSession()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand Triggered")
        // Return STICKY so the OS attempts to recreate the service if killed
        return START_STICKY
    }

    private fun startMicThread() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start mic recording thread: RECORD_AUDIO permission missing!")
            return
        }

        isRecording = true
        micThread = Thread {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = (minBufSize * 2).coerceAtLeast(2048)

            try {
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Failed to initialize AudioRecord")
                    return@Thread
                }

                audioRecord.startRecording()
                val shortBuffer = ShortArray(1024)
                val byteBuffer = ByteArray(2048)

                Log.d(TAG, "🎙️ Unified MicRecordingThread is now running...")

                while (isRecording) {
                    val shortsRead = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                    if (shortsRead > 0) {
                        // Calculate real-time input amplitude
                        val rms = computeShortRms(shortBuffer, shortsRead)
                        
                        val isConnected = liveSessionManager.isConnected()
                        if (isConnected) {
                            ZoyaStateController.micAmplitude.value = rms
                            
                            // Check if user is interrupting (high voice amplitude)
                            if (rms > 0.08f) {
                                // Real-time user speech detected while speaker plays -> trigger local interruption check
                                if (ZoyaStateController.assistantState.value == ZoyaState.SPEAKING) {
                                    scope.launch(Dispatchers.Main) {
                                        Log.d(TAG, "🎙️ Local interruption trigger due to user speech amplitude.")
                                        ZoyaStateController.actionEvent.emit("interrupted")
                                    }
                                }
                            }

                            // Convert to byte array for streaming
                            for (i in 0 until shortsRead) {
                                val s = shortBuffer[i]
                                byteBuffer[i * 2] = (s.toInt() and 0x00FF).toByte()
                                byteBuffer[i * 2 + 1] = ((s.toInt() and 0xFF00) shr 8).toByte()
                            }
                            liveSessionManager.sendAudioFrame(byteBuffer.sliceArray(0 until shortsRead * 2))
                        } else {
                            ZoyaStateController.micAmplitude.value = 0f
                            // Run local wake-word detector on mic feed
                            val detected = wakeWordDetector.feedAudioFrame(shortBuffer, shortsRead)
                            if (detected) {
                                scope.launch(Dispatchers.Main) {
                                    Log.d(TAG, "✨ Wake word matched! Instantly awakening Zoya live session.")
                                    liveSessionManager.startSession()
                                }
                            }
                        }
                    }
                }

                audioRecord.stop()
                audioRecord.release()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException during mic recording: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error in mic thread: ${e.message}")
            }
        }.apply { start() }
    }

    private fun computeShortRms(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) {
            sum += buffer[i] * buffer[i]
        }
        val rms = sqrt(sum / size)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zoya Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs Zoya's background wake-word listener and live session"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stateText = when (ZoyaStateController.assistantState.value) {
            ZoyaState.IDLE -> "Listening for wake-word \"Zoya\"..."
            ZoyaState.LISTENING -> "Zoya is listening..."
            ZoyaState.THINKING -> "Zoya is processing..."
            ZoyaState.SPEAKING -> "Zoya is talking..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zoya Assistant")
            .setContentText(stateText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "BackgroundAudioService Destroyed")
        isRecording = false
        micThread?.join(500)
        liveSessionManager.stopSession()
        scope.cancel()
        super.onDestroy()
    }
}
