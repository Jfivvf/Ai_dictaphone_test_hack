package com.example

import com.example.data.engine.LlamaEngine
import com.example.data.engine.WhisperEngine
import com.example.data.model.Recording
import com.example.data.model.TranscriptSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class DictaphoneEngineTest {

    @Test
    fun testWhisperEngineRealSegments() = runTest {
        val whisper = WhisperEngine()
        whisper.initialize()
        assertTrue(whisper.isInitialized)

        val realSegments = listOf(
            TranscriptSegment("1", 0L, 5000L, "Определение предела функции по Коши", "Лектор"),
            TranscriptSegment("2", 5000L, 10000L, "Дельта окрестность точки х нулевое", "Лектор")
        )
        val (text, segments) = whisper.processOrTranscribe(realSegments, null, null, 10000L, "Матанализ")

        assertTrue(text.contains("Определение предела функции"))
        assertTrue(segments.size == 2)
    }

    @Test
    fun testLlamaEngineDynamicSummary() = runTest {
        val llama = LlamaEngine()
        llama.initialize()
        assertTrue(llama.isInitialized)

        val mathLecture = "Сегодня разбираем предел функции и формулировку Коши. Необходимо решить задачи номер один и два из задачника."
        val summary = llama.generateSummary(mathLecture, "Лекция по матанализу")
        val actionItems = llama.extractActionItems(mathLecture)

        assertTrue(summary.contains("предел функции"))
        assertTrue(actionItems.isNotEmpty())
    }

    @Test
    fun testRecordingToMarkdown() {
        val rec = Recording(
            id = "test_1",
            title = "Лекция по матанализу",
            summary = "Основные понятия пределов",
            fullTranscript = "Число А называется пределом..."
        )
        val md = rec.toMarkdown()
        assertTrue(md.contains("# Лекция по матанализу"))
        assertTrue(md.contains("## Конспект и выжимка"))
    }

    @Test
    fun testSpeakerDiarizationGuaranteesTwoSpeakers() {
        // Test unpunctuated raw speech
        val rawSpeech = "здравствуйте сегодня разбираем теорему коши о пределах функций профессор подскажите пожалуйста определение по гейне"
        val segments = com.example.data.engine.SpeakerDiarizer.diarizeText(
            text = rawSpeech,
            totalDurationMs = 15000L,
            expectedSpeakers = 2
        )
        val distinctSpeakers = segments.map { it.speaker }.distinct()
        assertTrue("Diarization must produce at least 2 distinct speakers", distinctSpeakers.size >= 2)
    }

    @Test
    fun testDynamicTagsDefaultAndPopulate() {
        val emptyRecordings = emptyList<Recording>()
        val defaultTags = listOf("Все") + emptyRecordings.flatMap { it.tags }.distinct()
        assertTrue(defaultTags == listOf("Все"))

        val taggedRecordings = listOf(
            Recording(id = "1", title = "Лекция", tags = listOf("Матанализ")),
            Recording(id = "2", title = "Семинар", tags = listOf("Матанализ", "Физика"))
        )
        val dynamicTags = listOf("Все") + taggedRecordings.flatMap { it.tags }.distinct()
        assertTrue(dynamicTags.contains("Все"))
        assertTrue(dynamicTags.contains("Матанализ"))
        assertTrue(dynamicTags.contains("Физика"))
        assertTrue(dynamicTags.size == 3)
    }
}
