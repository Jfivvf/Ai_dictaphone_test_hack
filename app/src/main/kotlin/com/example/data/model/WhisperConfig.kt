package com.example.data.model

data class WhisperConfig(
    val modelPath: String = "whisper-base-ru.bin",
    val language: String = "ru",
    val threads: Int = 4,
    val beamSize: Int = 5,
    val bestOf: Int = 3,
    val temperature: Float = 0.0f,
    val temperatureIncrement: Float = 0.2f,
    val translate: Boolean = false,
    val timestamps: Boolean = true,
    val noContext: Boolean = true
)

data class TranscriptSegment(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String = "Спикер"
) {
    val formattedTime: String
        get() {
            val startSec = startMs / 1000
            val endSec = endMs / 1000
            val startMin = startSec / 60
            val startRemSec = startSec % 60
            val endMin = endSec / 60
            val endRemSec = endSec % 60
            return String.format("%02d:%02d - %02d:%02d", startMin, startRemSec, endMin, endRemSec)
        }
}
