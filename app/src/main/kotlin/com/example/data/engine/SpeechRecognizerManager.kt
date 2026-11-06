package com.example.data.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.data.model.TranscriptSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

class SpeechRecognizerManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var recordingStartTimeMs = 0L
    private var lastSegmentEndTimeMs = 0L
    private var currentSpeakerIndex = 1

    private val _activeSpeaker = MutableStateFlow("Спикер 1")
    val activeSpeaker: StateFlow<String> = _activeSpeaker.asStateFlow()

    private val _livePartialText = MutableStateFlow("")
    val livePartialText: StateFlow<String> = _livePartialText.asStateFlow()

    private val _collectedSegments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val collectedSegments: StateFlow<List<TranscriptSegment>> = _collectedSegments.asStateFlow()

    fun switchSpeaker() {
        currentSpeakerIndex = if (currentSpeakerIndex == 1) 2 else 1
        _activeSpeaker.value = "Спикер $currentSpeakerIndex"
    }

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening(languageCode: String = "ru-RU") {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                }

                isListening = true
                recordingStartTimeMs = System.currentTimeMillis()
                lastSegmentEndTimeMs = 0L
                currentSpeakerIndex = 1
                _activeSpeaker.value = "Спикер 1"
                _collectedSegments.value = emptyList()
                _livePartialText.value = ""

                setupAndStartListening(languageCode)
            } catch (e: Exception) {
                Log.e("SpeechRecognizerManager", "Failed to start speech recognizer", e)
            }
        }
    }

    private fun setupAndStartListening(languageCode: String) {
        if (!isListening) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.d("SpeechRecognizerManager", "Speech recognition error: $error")
                if (isListening) {
                    mainHandler.postDelayed({
                        if (isListening) setupAndStartListening(languageCode)
                    }, 500)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = matches?.firstOrNull()?.trim()

                if (!recognizedText.isNullOrBlank()) {
                    val now = System.currentTimeMillis() - recordingStartTimeMs
                    val startMs = lastSegmentEndTimeMs
                    val endMs = now.coerceAtLeast(startMs + 1000L)
                    lastSegmentEndTimeMs = endMs

                    val speakerName = _activeSpeaker.value
                    val newSegment = TranscriptSegment(
                        id = UUID.randomUUID().toString(),
                        startMs = startMs,
                        endMs = endMs,
                        text = recognizedText,
                        speaker = speakerName
                    )

                    val updated = _collectedSegments.value + newSegment
                    _collectedSegments.value = updated
                    _livePartialText.value = ""

                    // Automatically toggle to next speaker for the subsequent dialogue turn
                    currentSpeakerIndex = if (currentSpeakerIndex == 1) 2 else 1
                    _activeSpeaker.value = "Спикер $currentSpeakerIndex"
                }

                // Continue listening for next speech turn
                if (isListening) {
                    mainHandler.post {
                        if (isListening) setupAndStartListening(languageCode)
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    _livePartialText.value = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("SpeechRecognizerManager", "Error in startListening", e)
        }
    }

    fun stopListening(): List<TranscriptSegment> {
        isListening = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e("SpeechRecognizerManager", "Error stopping speech recognizer", e)
            }
        }
        val segments = _collectedSegments.value
        _livePartialText.value = ""
        return segments
    }
}
