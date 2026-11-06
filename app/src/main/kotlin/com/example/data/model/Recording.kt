package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ProcessingStatus {
    IDLE,
    TRANSCRIBING,
    TRANSCRIBED,
    GENERATING_AI,
    COMPLETED,
    ERROR
}

data class Recording(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val filePath: String? = null,
    val isSample: Boolean = false,
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val status: ProcessingStatus = ProcessingStatus.IDLE,
    val fullTranscript: String = "",
    val segments: List<TranscriptSegment> = emptyList(),
    val summary: String = "",
    val actionItems: List<ActionItem> = emptyList(),
    val meetingProtocol: String = "",
    val cleanedText: String = "",
    val chatMessages: List<AiChatMessage> = emptyList(),
    val whisperModelUsed: String = "whisper-base-ru.bin",
    val llamaModelUsed: String = "llama-3.2-3b-instruct-q4.gguf"
) {
    val formattedDuration: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("ru"))
            return sdf.format(Date(createdAt))
        }
}
