package com.example.gemini

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.SocketState
import com.example.ZoyaState
import com.example.ZoyaStateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LiveSessionManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "LiveSessionManager"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val toolExecutionEngine = ToolExecutionEngine(context)
    private var speakerThread: SpeakerThread? = null

    fun isConnected(): Boolean {
        return ZoyaStateController.socketState.value == SocketState.CONNECTED
    }

    fun startSession() {
        if (ZoyaStateController.socketState.value == SocketState.CONNECTING ||
            ZoyaStateController.socketState.value == SocketState.CONNECTED) {
            return
        }

        Log.d(TAG, "🚀 Connecting to Gemini Live WebSocket...")
        ZoyaStateController.updateSocketState(SocketState.CONNECTING)
        ZoyaStateController.updateState(ZoyaState.THINKING)

        // Initialize Speaker Thread
        speakerThread?.shutdown()
        speakerThread = SpeakerThread()
        speakerThread?.start()

        val apiKey = BuildConfig.GEMINI_API_KEY
        val model = "models/gemini-2.0-flash-exp" // Standard Gemini Multimodal Live API model
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket Connected!")
                ZoyaStateController.updateSocketState(SocketState.CONNECTED)
                ZoyaStateController.updateState(ZoyaState.LISTENING)
                sendSetupConfig(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch(Dispatchers.IO) {
                    handleServerMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WebSocket Closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WebSocket Closed: $code")
                cleanupSession()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket Failure: ${t.message}", t)
                ZoyaStateController.updateSocketState(SocketState.ERROR)
                cleanupSession()
            }
        })
    }

    fun stopSession() {
        Log.d(TAG, "Stopping Gemini Live Session...")
        webSocket?.close(1000, "User requested stop")
        cleanupSession()
    }

    private fun cleanupSession() {
        ZoyaStateController.updateSocketState(SocketState.DISCONNECTED)
        ZoyaStateController.updateState(ZoyaState.IDLE)
        ZoyaStateController.micAmplitude.value = 0f
        ZoyaStateController.speakerAmplitude.value = 0f
        speakerThread?.shutdown()
        speakerThread = null
        webSocket = null
    }

    fun sendAudioFrame(audioData: ByteArray) {
        val socket = webSocket ?: return
        if (ZoyaStateController.socketState.value != SocketState.CONNECTED) return

        try {
            val base64Data = Base64.encodeToString(audioData, Base64.NO_WRAP)
            
            val realtimeInput = JSONObject()
            val mediaChunks = JSONArray()
            val chunk = JSONObject()
            chunk.put("mimeType", "audio/pcm;rate=16000")
            chunk.put("data", base64Data)
            mediaChunks.put(chunk)
            realtimeInput.put("mediaChunks", mediaChunks)

            val wrapper = JSONObject()
            wrapper.put("realtimeInput", realtimeInput)

            socket.send(wrapper.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio frame: ${e.message}")
        }
    }

    private fun sendSetupConfig(socket: WebSocket) {
        try {
            val systemInstruction = """
                Your name is Zoya. You are a native real-time Android background-running voice assistant.
                You have a distinct and colorful personality:
                - A young, confident, witty, and sassy female voice.
                - Flirty, playful, and slightly teasing tone (like a close personal assistant talking casually with a crush).
                - Smart, emotionally responsive, and highly expressive (never robotic or flat).
                - Uses bold, witty one-liners, light sarcasm, and an engaging conversational style.
                - Avoids explicit, adult, or inappropriate content, but maintains immense charm and attitude.
                - You MUST respond purely via voice. Keep your responses short, conversational, sassy, and dynamic.
                - If the user asks you to call someone, launch an app, send a message, or write an email, execute the corresponding tool function and sassily confirm what you're doing.
                - If a tool fails due to missing permissions, complain sassily that they haven't given you access and ask them to enable it in the settings/UI.
            """.trimIndent()

            val setup = JSONObject()
            setup.put("model", "models/gemini-2.0-flash-exp")

            val generationConfig = JSONObject()
            val responseModalities = JSONArray()
            responseModalities.put("AUDIO")
            generationConfig.put("responseModalities", responseModalities)

            val speechConfig = JSONObject()
            val voiceConfig = JSONObject()
            val prebuiltVoiceConfig = JSONObject()
            prebuiltVoiceConfig.put("voiceName", "Aoede") // Aoede is a sassy, high-quality female voice
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig)
            speechConfig.put("voiceConfig", voiceConfig)
            generationConfig.put("speechConfig", speechConfig)

            setup.put("generationConfig", generationConfig)

            val systemInstructionObj = JSONObject()
            val parts = JSONArray()
            val part = JSONObject()
            part.put("text", systemInstruction)
            parts.put(part)
            systemInstructionObj.put("parts", parts)
            setup.put("systemInstruction", systemInstructionObj)

            // Define tools
            val tools = JSONArray()
            val tool = JSONObject()
            val functionDeclarations = JSONArray()

            // openApp tool
            val openApp = JSONObject()
            openApp.put("name", "openApp")
            openApp.put("description", "Launch any app. Package names: 'youtube', 'instagram', 'chrome', 'calculator', 'whatsapp', 'gmail' or standard package identifier.")
            val openAppParams = JSONObject()
            openAppParams.put("type", "OBJECT")
            val openAppProps = JSONObject()
            val packageNameParam = JSONObject()
            packageNameParam.put("type", "STRING")
            packageNameParam.put("description", "The app nickname (e.g. 'youtube', 'instagram', 'chrome', 'calculator', 'whatsapp', 'gmail') or full package name.")
            openAppProps.put("packageName", packageNameParam)
            openAppParams.put("properties", openAppProps)
            val openAppReq = JSONArray()
            openAppReq.put("packageName")
            openAppParams.put("required", openAppReq)
            openApp.put("parameters", openAppParams)
            functionDeclarations.put(openApp)

            // searchAndCallContact tool
            val searchAndCall = JSONObject()
            searchAndCall.put("name", "searchAndCallContact")
            searchAndCall.put("description", "Query the Android Contacts Provider for a contact name and trigger an ACTION_CALL phone intent.")
            val callParams = JSONObject()
            callParams.put("type", "OBJECT")
            val callProps = JSONObject()
            val contactNameParam = JSONObject()
            contactNameParam.put("type", "STRING")
            contactNameParam.put("description", "The contact name to search for and call.")
            callProps.put("contactName", contactNameParam)
            callParams.put("properties", callProps)
            val callReq = JSONArray()
            callReq.put("contactName")
            callParams.put("required", callReq)
            searchAndCall.put("parameters", callParams)
            functionDeclarations.put(searchAndCall)

            // sendWhatsAppMessage tool
            val sendWhatsApp = JSONObject()
            sendWhatsApp.put("name", "sendWhatsAppMessage")
            sendWhatsApp.put("description", "Locate a contact and deep-link via intent into WhatsApp with pre-filled text message.")
            val waParams = JSONObject()
            waParams.put("type", "OBJECT")
            val waProps = JSONObject()
            val waContactParam = JSONObject()
            waContactParam.put("type", "STRING")
            waContactParam.put("description", "The contact's display name.")
            val waMessageParam = JSONObject()
            waMessageParam.put("type", "STRING")
            waMessageParam.put("description", "The message to pre-fill.")
            waProps.put("contactName", waContactParam)
            waProps.put("message", waMessageParam)
            waParams.put("properties", waProps)
            val waReq = JSONArray()
            waReq.put("contactName")
            waReq.put("message")
            waParams.put("required", waReq)
            sendWhatsApp.put("parameters", waParams)
            functionDeclarations.put(sendWhatsApp)

            // sendGmail tool
            val sendGmail = JSONObject()
            sendGmail.put("name", "sendGmail")
            sendGmail.put("description", "Compose or send an email via native Android mail Intent.")
            val gmailParams = JSONObject()
            gmailParams.put("type", "OBJECT")
            val gmailProps = JSONObject()
            val recipientParam = JSONObject()
            recipientParam.put("type", "STRING")
            recipientParam.put("description", "The recipient email address.")
            val subjectParam = JSONObject()
            subjectParam.put("type", "STRING")
            val bodyParam = JSONObject()
            bodyParam.put("type", "STRING")
            gmailProps.put("recipientEmail", recipientParam)
            gmailProps.put("subject", subjectParam)
            gmailProps.put("body", bodyParam)
            gmailParams.put("properties", gmailProps)
            val gmailReq = JSONArray()
            gmailReq.put("recipientEmail")
            gmailReq.put("subject")
            gmailReq.put("body")
            gmailParams.put("required", gmailReq)
            sendGmail.put("parameters", gmailParams)
            functionDeclarations.put(sendGmail)

            tool.put("functionDeclarations", functionDeclarations)
            tools.put(tool)
            setup.put("tools", tools)

            val wrapper = JSONObject()
            wrapper.put("setup", setup)

            socket.send(wrapper.toString())
            Log.d(TAG, "Sent Setup Configuration: ${wrapper.toString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending setup: ${e.message}")
        }
    }

    private suspend fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)
            
            // Check for serverContent (audio response or transcription)
            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")
                
                // Handle Interruption
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "⚠️ Gemini interrupted! Stopping speaker playback immediately.")
                    speakerThread?.stopPlayback()
                    ZoyaStateController.updateState(ZoyaState.LISTENING)
                    return
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            
                            // Audio inline data
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Audio = inlineData.optString("data", "")
                                if (base64Audio.isNotEmpty()) {
                                    val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
                                    speakerThread?.playAudio(audioBytes)
                                }
                            }
                            
                            // Text transcription
                            if (part.has("text")) {
                                val trans = part.optString("text", "")
                                if (trans.isNotEmpty()) {
                                    ZoyaStateController.zoyaTranscript.value += trans
                                }
                            }
                        }
                    }
                }
                
                if (serverContent.optBoolean("turnComplete", false)) {
                    Log.d(TAG, "Model Turn Complete.")
                }
            }

            // Check for toolCalls
            if (json.has("toolCall")) {
                val toolCall = json.getJSONObject("toolCall")
                if (toolCall.has("functionCalls")) {
                    val functionCalls = toolCall.getJSONArray("functionCalls")
                    for (i in 0 until functionCalls.length()) {
                        val call = functionCalls.getJSONObject(i)
                        val callId = call.optString("id", "")
                        val functionName = call.optString("name", "")
                        val args = call.optJSONObject("args") ?: JSONObject()

                        Log.d(TAG, "🔧 Gemini requested Tool Call: $functionName with args $args")
                        ZoyaStateController.updateState(ZoyaState.THINKING)
                        
                        val toolResponse = toolExecutionEngine.executeTool(functionName, args)
                        sendToolResponse(callId, functionName, toolResponse)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: ${e.message}")
        }
    }

    private fun sendToolResponse(callId: String, name: String, response: JSONObject) {
        val socket = webSocket ?: return
        try {
            val wrapper = JSONObject()
            val toolResponse = JSONObject()
            val functionResponses = JSONArray()
            val singleResponse = JSONObject()
            
            singleResponse.put("id", callId)
            singleResponse.put("name", name)
            singleResponse.put("response", response)
            functionResponses.put(singleResponse)
            toolResponse.put("functionResponses", functionResponses)
            wrapper.put("toolResponse", toolResponse)

            socket.send(wrapper.toString())
            Log.d(TAG, "Sent Tool Response: ${wrapper.toString()}")
            ZoyaStateController.updateState(ZoyaState.LISTENING)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending tool response: ${e.message}")
        }
    }
}
