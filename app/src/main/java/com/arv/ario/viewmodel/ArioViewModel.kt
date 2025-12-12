package com.arv.ario.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arv.ario.data.ChatRequest
import com.arv.ario.data.List_Message
import com.arv.ario.data.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class ArioState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

class ArioViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val _uiState = MutableStateFlow(ArioState.IDLE)
    val uiState: StateFlow<ArioState> = _uiState.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _aiResponse = MutableStateFlow("")
    val aiResponse: StateFlow<String> = _aiResponse.asStateFlow()

    // --- Speech to Text ---
    private val speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(application)
    private val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    // --- Text to Speech ---
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(application, this)
    }

    fun startListening() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                _uiState.value = ArioState.LISTENING
                _recognizedText.value = "Listening..."
                speechRecognizer.setRecognitionListener(recognitionListener)
                speechRecognizer.startListening(speechIntent)
            } catch (e: Exception) {
                Log.e("ArioViewModel", "Error starting listening: ${e.message}")
                resetToIdle()
            }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
             // Wait for results
        }

        override fun onError(error: Int) {
            Log.e("ArioViewModel", "Speech error: $error")
            _recognizedText.value = "Error listening"
            resetToIdle()
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotEmpty()) {
                _recognizedText.value = text
                processQuery(text)
            } else {
                resetToIdle()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processQuery(query: String) {
        _uiState.value = ArioState.THINKING
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val systemPrompt = "You are Ario, a witty and concise AI assistant. You speak naturally for voice output. Do not use markdown. Keep answers conversational and under 2 sentences."
                val messages = listOf(
                    List_Message("system", systemPrompt),
                    List_Message("user", query)
                )
                
                val response = RetrofitClient.api.generateResponse(ChatRequest(messages = messages))
                val aiText = response.choices.firstOrNull()?.message?.content ?: "I couldn't think of a response."
                
                _aiResponse.value = aiText
                speakResponse(aiText)
                
            } catch (e: Exception) {
                Log.e("ArioViewModel", "API Error: ${e.message}")
                val errorMsg = "Sorry, I'm having trouble connecting to my brain."
                _aiResponse.value = errorMsg
                speakResponse(errorMsg)
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
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.value = ArioState.IDLE
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        } else {
            Log.e("ArioViewModel", "TTS Init failed")
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}
