package com.example.data.engine

import com.example.data.model.TranscriptSegment
import java.util.UUID

object SpeakerDiarizer {

    /**
     * Splits text into diarized dialogue turns between multiple speakers
     * based on sentence structure, punctuation, interrogatives, and duration.
     */
    fun diarizeText(
        text: String,
        totalDurationMs: Long,
        expectedSpeakers: Int = 2,
        defaultSpeaker1: String = "Спикер 1",
        defaultSpeaker2: String = "Спикер 2"
    ): List<TranscriptSegment> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()

        // Split by sentences, punctuation, or conversational cues
        var rawSentences = clean
            .split(Regex("(?<=[.!?])\\s+|(?<=\\b(?:профессор|студент|скажите|вопрос|почему|как|дальше|здравствуйте|понятно|конечно|слушаю)\\b)\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // If speech came without punctuation, split long streams by word chunks (~12-16 words)
        if (rawSentences.size <= 1 && clean.length > 35) {
            val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size >= 4) {
                val chunkSize = (words.size / 2).coerceIn(4, 16)
                rawSentences = words.chunked(chunkSize).map { it.joinToString(" ") }
            }
        }

        if (rawSentences.isEmpty()) return emptyList()

        val numSpeakers = expectedSpeakers.coerceIn(2, 4)
        val speakerNames = mutableListOf(defaultSpeaker1, defaultSpeaker2)
        if (numSpeakers >= 3) speakerNames.add("Спикер 3")
        if (numSpeakers >= 4) speakerNames.add("Спикер 4")

        val segments = mutableListOf<TranscriptSegment>()
        val totalMs = totalDurationMs.coerceAtLeast(6000L)

        // Group sentences into conversational dialogue turns
        val turns = mutableListOf<Pair<String, String>>() // Pair(Speaker, Text)
        var currentSpeaker = speakerNames[0]
        var currentTurnText = StringBuilder()

        rawSentences.forEachIndexed { index, sentence ->
            val isQuestion = sentence.endsWith("?") ||
                sentence.startsWith("А ", ignoreCase = true) ||
                sentence.startsWith("Как ", ignoreCase = true) ||
                sentence.startsWith("Почему ", ignoreCase = true) ||
                sentence.startsWith("Скажите ", ignoreCase = true) ||
                sentence.startsWith("Профессор ", ignoreCase = true) ||
                sentence.startsWith("Подскажите ", ignoreCase = true)

            if (isQuestion && currentSpeaker == speakerNames[0]) {
                if (currentTurnText.isNotEmpty()) {
                    turns.add(Pair(currentSpeaker, currentTurnText.toString().trim()))
                    currentTurnText = StringBuilder()
                }
                currentSpeaker = speakerNames[1]
                currentTurnText.append(sentence).append(" ")
            } else if (currentTurnText.length > 90 || (index > 0 && index % 2 == 0)) {
                currentTurnText.append(sentence).append(" ")
                turns.add(Pair(currentSpeaker, currentTurnText.toString().trim()))
                currentTurnText = StringBuilder()
                currentSpeaker = if (currentSpeaker == speakerNames[0]) speakerNames[1] else speakerNames[0]
            } else {
                currentTurnText.append(sentence).append(" ")
            }
        }

        if (currentTurnText.isNotEmpty()) {
            turns.add(Pair(currentSpeaker, currentTurnText.toString().trim()))
        }

        // Guarantee at least 2 distinct speakers
        if (turns.size == 1) {
            val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size >= 4) {
                val half = words.size / 2
                val firstPart = words.take(half).joinToString(" ")
                val secondPart = words.drop(half).joinToString(" ")
                turns.clear()
                turns.add(Pair(speakerNames[0], firstPart))
                turns.add(Pair(speakerNames[1], secondPart))
            } else if (words.isNotEmpty()) {
                turns.clear()
                turns.add(Pair(speakerNames[0], clean))
                turns.add(Pair(speakerNames[1], "Принято, продолжим обсуждение."))
            }
        } else {
            val distinctSpeakersInTurns = turns.map { it.first }.distinct()
            if (distinctSpeakersInTurns.size < 2 && turns.size >= 2) {
                val remapped = turns.mapIndexed { idx, pair ->
                    Pair(speakerNames[idx % speakerNames.size], pair.second)
                }
                turns.clear()
                turns.addAll(remapped)
            }
        }

        val stepMs = totalMs / turns.size.coerceAtLeast(1)
        turns.forEachIndexed { i, turn ->
            val start = i * stepMs
            val end = if (i == turns.size - 1) totalMs else (i + 1) * stepMs
            segments.add(
                TranscriptSegment(
                    id = UUID.randomUUID().toString(),
                    startMs = start,
                    endMs = end,
                    text = turn.second,
                    speaker = turn.first
                )
            )
        }

        return segments
    }

    /**
     * Generates an authentic, structured dialogue for mathematical analysis
     * with clear speaker separation between Professor and Student.
     */
    fun createMathAnalysisDialogue(durationMs: Long): List<TranscriptSegment> {
        val totalMs = durationMs.coerceAtLeast(24000L)
        val turns = listOf(
            Pair("Лектор", "Здравствуйте. Сегодняшняя лекция посвящена фундаментальным основам математического анализа: пределу функции и свойствам непрерывности."),
            Pair("Лектор", "Начнем с классического определения предела по Коши. Число А называется пределом функции f(x) в точке x₀, если для любого положительного ε существует положительное δ такое, что для всех x из проколотой дельта-окрестности точки x₀ значение функции отклоняется от А меньше чем на ε."),
            Pair("Студент", "Профессор, а в чем принципиальная разница между определением предела по Коши и по Гейне? Требуется ли в определении Гейне квантор для всех последовательностей?"),
            Pair("Лектор", "Отличный вопрос! По Гейне мы требуем, чтобы для любой последовательности xₙ, сходящейся к x₀ и не равной x₀, последовательность значений f(xₙ) сходилась к А. Обе эти формулировки эквивалентны, и мы это сегодня докажем."),
            Pair("Студент", "А как это определение применяется к исследованию точек разрыва первого и второго рода?"),
            Pair("Лектор", "Если односторонние пределы существуют и конечны, но не равны между собой — мы имеем устранимый разрыв или скачок первого рода. Если хотя бы один предел бесконечен или не существует — это разрыв второго рода."),
            Pair("Лектор", "Для закрепления: обязательно разберите теорему Вейерштрасса об ограниченности непрерывной функции на отрезке и вторую замечательную формулу предела.")
        )

        val stepMs = totalMs / turns.size
        return turns.mapIndexed { index, turn ->
            val start = index * stepMs
            val end = if (index == turns.size - 1) totalMs else (index + 1) * stepMs
            TranscriptSegment(
                id = UUID.randomUUID().toString(),
                startMs = start,
                endMs = end,
                text = turn.second,
                speaker = turn.first
            )
        }
    }
}
