package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ProcessingStatus {
    IDLE,
    PROCESSING,
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
    val customNotes: String = ""
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

    val speakersList: List<String>
        get() = segments.map { it.speaker }.distinct()

    val speakersCount: Int
        get() = speakersList.size.coerceAtLeast(1)

    fun toMarkdown(): String {
        return buildString {
            appendLine("# $title")
            appendLine("**Дата:** $formattedDate | **Длительность:** $formattedDuration")
            if (speakersList.isNotEmpty()) {
                appendLine("**Участники:** ${speakersList.joinToString(", ")}")
            }
            appendLine()

            if (summary.isNotBlank()) {
                appendLine("## Конспект и выжимка")
                appendLine(summary)
                appendLine()
            }

            if (actionItems.isNotEmpty()) {
                appendLine("## Задачи и ключевые моменты")
                actionItems.forEach { item ->
                    val check = if (item.isCompleted) "[x]" else "[ ]"
                    appendLine("- $check ${item.text}")
                }
                appendLine()
            }

            if (customNotes.isNotBlank()) {
                appendLine("## Заметки")
                appendLine(customNotes)
                appendLine()
            }

            if (segments.isNotEmpty()) {
                appendLine("## Стенограмма")
                segments.forEach { segment ->
                    appendLine("**[${segment.formattedTime}] ${segment.speaker}:**")
                    appendLine(segment.text)
                    appendLine()
                }
            } else if (fullTranscript.isNotBlank()) {
                appendLine("## Текст записи")
                appendLine(fullTranscript)
            }
        }
    }
}
