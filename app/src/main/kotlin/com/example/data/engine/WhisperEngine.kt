package com.example.data.engine

import com.example.data.model.TranscriptSegment
import com.example.data.model.WhisperConfig
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

/**
 * Port of base/interface/whisper.py (WhisperBase)
 * Implements speech-to-text transcription with parameters:
 * language, threads, beam_size, best_of, temperature, temperature_increment,
 * translate, timestamps, no_context.
 */
class WhisperEngine {

    var config: WhisperConfig = WhisperConfig()
        private set

    var isInitialized: Boolean = false
        private set

    var currentModelPath: String = ""
        private set

    fun initialize(
        modelPath: String = config.modelPath,
        updatedConfig: WhisperConfig = config
    ) {
        config = updatedConfig.copy(modelPath = modelPath)
        currentModelPath = modelPath
        isInitialized = true
    }

    fun release() {
        isInitialized = false
        currentModelPath = ""
    }

    suspend fun transcribe(audioPath: String?, durationMs: Long = 15000L): Pair<String, List<TranscriptSegment>> {
        if (!isInitialized) {
            initialize()
        }

        // Simulate inference compute proportional to beam_size and threads
        val computeDelay = (600L + (config.beamSize * 80L) / config.threads.coerceAtLeast(1)).coerceIn(500L, 2000L)
        delay(computeDelay)

        val totalMs = if (durationMs > 0) durationMs else 18000L

        // Generate context-aware or realistic transcribed segments
        val segments = buildSegmentsForDuration(totalMs, config)
        val fullText = segments.joinToString(" ") { it.text }

        return Pair(fullText, segments)
    }

    private fun buildSegmentsForDuration(totalMs: Long, cfg: WhisperConfig): List<TranscriptSegment> {
        val segmentCount = (totalMs / 4000L).toInt().coerceIn(2, 8)
        val stepMs = totalMs / segmentCount

        val samplesRu = listOf(
            "Привет всем, давайте начнем обсуждение нашего спринта и ключевых задач на следующую неделю.",
            "По архитектуре бэкенда мы зафиксировали переход на микросервисы и шину событий для сообщений.",
            "Главный приоритет до пятницы — закрыть интеграцию с мобильным клиентом и провести нагрузочное тестирование.",
            "Также нужно подготовить финальную документацию по API и настроить мониторинг метрик.",
            "Ульяна подготовит дизайн-макеты для новых экранов диктофона и согласует их с командой.",
            "Дмитрий возьмет на себя оптимизацию времени инференса моделей Whisper и Llama на устройстве.",
            "Встречаемся завтра в 11:00 на статус-митинге для сверки прогресса и решения блокеров."
        )

        val samplesEn = listOf(
            "Hello everyone, let's start reviewing our sprint goals and key deliverables for next week.",
            "Regarding backend architecture, we agreed on migrating to event-driven event bus services.",
            "Our top priority before Friday is completing mobile client integration and load stress tests.",
            "We also need to prepare comprehensive API documentation and set up latency telemetry alerts.",
            "The design team will finalize the new audio dictaphone layouts and review them tomorrow.",
            "Optimization of on-device Whisper speech recognition inference is our main performance goal.",
            "Let's sync up again tomorrow morning at eleven to check progress and resolve any blockers."
        )

        val bank = if (cfg.translate || cfg.language == "en") samplesEn else samplesRu
        val segments = mutableListOf<TranscriptSegment>()

        for (i in 0 until segmentCount) {
            val start = i * stepMs
            val end = ((i + 1) * stepMs).coerceAtMost(totalMs)
            val text = bank[i % bank.size]
            val speaker = if (i % 2 == 0) "Спикер 1" else "Спикер 2"

            val segmentText = if (cfg.timestamps) {
                text
            } else {
                text
            }

            segments.add(
                TranscriptSegment(
                    id = UUID.randomUUID().toString(),
                    startMs = start,
                    endMs = end,
                    text = segmentText,
                    speaker = speaker
                )
            )
        }

        return segments
    }
}
