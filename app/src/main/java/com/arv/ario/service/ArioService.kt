package com.arv.ario.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.arv.ario.MainActivity
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import com.arv.ario.R
import com.arv.ario.data.ChatRequest
import com.arv.ario.data.List_Message
import com.arv.ario.data.RetrofitClient
import com.arv.ario.viewmodel.ArioState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.arv.ario.data.ArioResponse
import com.arv.ario.utils.CommandProcessor
import com.arv.ario.utils.DebugLogger
import com.google.gson.Gson
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay

class ArioService : LifecycleService(), TextToSpeech.OnInitListener, RecognitionListener {

    private val _uiState = MutableStateFlow(ArioState.IDLE)
    val uiState: StateFlow<ArioState> = _uiState.asStateFlow()

    private lateinit var overlayManager: OverlayManager
    
    // --- Vosk (Offline Wake Word) ---
    private var voskModel: Model? = null
    private var voskService: SpeechService? = null
    
    // --- Speech to Text (Online) ---
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent

    // --- Text to Speech ---
    private var tts: TextToSpeech? = null
    
    // --- Audio Feedback ---
    private var toneGenerator: ToneGenerator? = null

    private fun sendDebugLog(message: String) {
        DebugLogger.log(message)
        val intent = Intent("ARIO_DEBUG_LOG")
        intent.putExtra("message", message)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onCreate() {
        super.onCreate()
        sendDebugLog("Service Starting...")
        startForegroundService()
        
        overlayManager = OverlayManager(this)
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        
        initVosk()
        initSpeechRecognizer()
        tts = TextToSpeech(this, this)
    }

    private fun startForegroundService() {
        val channelId = "ario_service_channel"
        val channelName = "Ario Background Service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ario is listening...")
            .setContentText("Say \"Hey Ario\" to activate")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
    }

    private fun initVosk() {
        val destDir = File(applicationContext.getExternalFilesDir(null), "model")
        sendDebugLog("Checking model at: ${destDir.absolutePath}")

        // Check if model exists and is valid (contains uuid)
        if (destDir.exists() && findModelDirectory(destDir) != null) {
            sendDebugLog("Model found on disk. Loading directly...")
            loadModelFromDir(destDir)
        } else {
            sendDebugLog("Model not found or invalid. Unpacking from assets...")
            StorageService.unpack(this, "model", "model",
                { model ->
                    sendDebugLog("Unpacking complete.")
                    loadModelFromDir(destDir)
                },
                { exception ->
                    sendDebugLog("Vosk Unpack Error: ${exception.message}")
                }
            )
        }
    }

    private fun loadModelFromDir(parentDir: File) {
        val actualModelDir = findModelDirectory(parentDir)
        if (actualModelDir != null) {
            try {
                voskModel = Model(actualModelDir.absolutePath)
                sendDebugLog("Vosk Model Loaded Successfully from ${actualModelDir.absolutePath}")
                startVoskListening()
            } catch (e: Exception) {
                sendDebugLog("Failed to load model: ${e.message}")
            }
        } else {
            sendDebugLog("CRITICAL: UUID file missing in ${parentDir.absolutePath}")
        }
    }

    private fun findModelDirectory(parentDir: File): File? {
        if (!parentDir.exists() || !parentDir.isDirectory) return null

        // Check if this directory contains the "uuid" file
        val uuidFile = File(parentDir, "uuid")
        if (uuidFile.exists()) {
            return parentDir
        }

        // Recursively check subdirectories
        val children = parentDir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory) {
                val result = findModelDirectory(child)
                if (result != null) return result
            }
        }
        return null
    }

    private fun startVoskListening() {
        if (voskModel != null && _uiState.value == ArioState.IDLE) {
            try {
                val recognizer = Recognizer(voskModel, 16000.0f)
                voskService = SpeechService(recognizer, 16000.0f)
                voskService?.startListening(this)
                sendDebugLog("Vosk Listening for Wake Word...")
            } catch (e: Exception) {
                sendDebugLog("Failed to start Vosk: ${e.message}")
            }
        }
    }

    private fun stopVoskListening() {
        voskService?.stop()
        voskService?.shutdown()
        voskService = null
        sendDebugLog("Vosk Stopped")
    }

    // --- Vosk RecognitionListener ---
    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis != null && (hypothesis.contains("hey ario") || hypothesis.contains("hario") || hypothesis.contains("hey rio"))) {
             if (_uiState.value == ArioState.IDLE) {
                 sendDebugLog("Wake Word Detected: $hypothesis")
                 startSession()
             }
        }
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis != null && (hypothesis.contains("hey ario") || hypothesis.contains("hario"))) {
             if (_uiState.value == ArioState.IDLE) {
                 startSession()
             }
        }
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {
        sendDebugLog("Vosk Error: ${exception?.message}")
    }
    override fun onTimeout() {}

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer.setRecognitionListener(androidRecognitionListener)
    }

    private fun startSession() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                stopVoskListening()
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP)
                
                _uiState.value = ArioState.LISTENING
                overlayManager.showOverlay(uiState)
                
                delay(100)
                
                speechRecognizer.startListening(speechIntent)
            } catch (e: Exception) {
                sendDebugLog("Error starting session: ${e.message}")
                resetToIdle()
            }
        }
    }

    private val androidRecognitionListener = object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            sendDebugLog("Speech error: $error")
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK)
            resetToIdle()
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotEmpty()) {
                processQuery(text)
            } else {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK)
                resetToIdle()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processQuery(query: String) {
        _uiState.value = ArioState.THINKING
        sendDebugLog("Processing: $query")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val systemPrompt = """
                    You are Ario, an Android Assistant. You must reply in a STRICT JSON format. Do not output any plain text outside the JSON.

                    Output Schema:
                    {
                      "type": "chat" | "command",
                      "action": "open_app" | "play_media" | "open_url" | "none",
                      "payload": "package_name" | "search_query" | "url" | null,
                      "speak": "Short voice response"
                    }
                    Only return the JSON.
                """.trimIndent()

                val messages = listOf(
                    List_Message("system", systemPrompt),
                    List_Message("user", query)
                )
                
                val response = RetrofitClient.api.generateResponse(ChatRequest(messages = messages))
                val content = response.choices.firstOrNull()?.message?.content
                
                if (content != null) {
                    try {
                        val arioResponse = Gson().fromJson(content, ArioResponse::class.java)
                        
                        launch(Dispatchers.Main) {
                            CommandProcessor.execute(this@ArioService, arioResponse)
                        }
                        
                        speakResponse(arioResponse.speak)
                    } catch (e: Exception) {
                        DebugLogger.logError(e)
                        speakResponse(content) 
                    }
                } else {
                    speakResponse("I couldn't think of a response.")
                }
                
            } catch (e: Exception) {
                DebugLogger.logError(e)
                speakResponse("Sorry, I'm having trouble connecting.")
            }
        }
    }

    private fun speakResponse(text: String) {
        _uiState.value = ArioState.SPEAKING
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "ARIO_RESPONSE")
        
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "ARIO_RESPONSE")
        
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            
            override fun onDone(utteranceId: String?) {
                resetToIdle()
            }

            override fun onError(utteranceId: String?) {
                resetToIdle()
            }
        })
    }

    private fun resetToIdle() {
        lifecycleScope.launch(Dispatchers.Main) {
            _uiState.value = ArioState.IDLE
            overlayManager.hideOverlay()
            startVoskListening()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoskListening()
        speechRecognizer.destroy()
        tts?.stop()
        tts?.shutdown()
        overlayManager.hideOverlay()
    }
}
