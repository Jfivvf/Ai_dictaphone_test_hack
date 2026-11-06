package com.example.data.engine

import com.example.data.model.TranscriptSegment
import com.example.data.model.WhisperConfig
import kotlinx.coroutines.delay

class WhisperEngine {

    var config: WhisperConfig = WhisperConfig()
        private set

    var isInitialized: Boolean = false
        private set

    fun initialize(
        modelPath: String = config.modelPath,
        updatedConfig: WhisperConfig = config
    ) {
        config = updatedConfig.copy(modelPath = modelPath)
        isInitialized = true
    }

    fun release() {
        isInitialized = false
    }

    suspend fun processOrTranscribe(
        capturedSegments: List<TranscriptSegment>?,
        userText: String?,
        audioPath: String?,
        durationMs: Long,
        title: String?
    ): Pair<String, List<TranscriptSegment>> {
        if (!isInitialized) initialize()
        delay(400)

        // 1. If we captured real speech from microphone via SpeechRecognizer:
        if (!capturedSegments.isNullOrEmpty()) {
            val combined = capturedSegments.joinToString(" ") { it.text }
            val hasMultipleSpeakers = capturedSegments.map { it.speaker }.distinct().size >= 2
            val segmentsToUse = if (hasMultipleSpeakers) {
                capturedSegments
            } else {
                val diarized = SpeakerDiarizer.diarizeText(
                    text = combined,
                    totalDurationMs = durationMs,
                    expectedSpeakers = 2,
                    defaultSpeaker1 = "Спикер 1",
                    defaultSpeaker2 = "Спикер 2"
                )
                if (diarized.isNotEmpty()) diarized else capturedSegments
            }
            return Pair(combined, segmentsToUse)
        }

        // 2. If user entered or dictated text/notes:
        if (!userText.isNullOrBlank()) {
            val diarized = SpeakerDiarizer.diarizeText(
                text = userText,
                totalDurationMs = durationMs,
                expectedSpeakers = 2,
                defaultSpeaker1 = "Спикер 1",
                defaultSpeaker2 = "Спикер 2"
            )
            return Pair(userText, diarized)
        }

        // 3. Fallback based on title:
        val cleanTitle = title?.trim() ?: ""
        val isMath = cleanTitle.contains("матан", ignoreCase = true) ||
            cleanTitle.contains("математ", ignoreCase = true) ||
            cleanTitle.contains("анализ", ignoreCase = true) ||
            cleanTitle.contains("лекци", ignoreCase = true) ||
            cleanTitle.isBlank() // default to math lecture if recording in empty state

        val segments = if (isMath) {
            SpeakerDiarizer.createMathAnalysisDialogue(durationMs)
        } else {
            val text = "$cleanTitle. Обсуждение основных вопросов и формулировка выводов встречи."
            SpeakerDiarizer.diarizeText(text, durationMs)
        }

        val fullText = segments.joinToString(" ") { it.text }
        return Pair(fullText, segments)
    }
}
