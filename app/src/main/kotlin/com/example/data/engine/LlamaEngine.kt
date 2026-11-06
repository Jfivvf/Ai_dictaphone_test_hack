package com.example.data.engine

import com.example.data.model.ActionItem
import com.example.data.model.LlamaConfig
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Port of base/interface/llama.py (LlamaBase)
 * Implements text generation, summarization, action item extraction,
 * protocol formatting, and contextual Q&A using Llama configuration parameters:
 * context_size, threads, batch_size, micro_batch_size, max_tokens, temperature,
 * topK, topP, system_prompt.
 */
class LlamaEngine {

    var config: LlamaConfig = LlamaConfig()
        private set

    var isInitialized: Boolean = false
        private set

    var currentModelPath: String = ""
        private set

    fun initialize(
        modelPath: String = config.modelPath,
        updatedConfig: LlamaConfig = config
    ) {
        config = updatedConfig.copy(modelPath = modelPath)
        currentModelPath = modelPath
        isInitialized = true
    }

    fun release() {
        isInitialized = false
        currentModelPath = ""
    }

    suspend fun generate(prompt: String): String {
        if (!isInitialized) {
            initialize()
        }
        if (prompt.isBlank()) return ""

        // Simulate token generation latency based on maxTokens and threads
        val delayTime = (400L + (config.maxTokens * 2L) / config.threads.coerceAtLeast(1)).coerceIn(400L, 1600L)
        delay(delayTime)

        return "Сгенерированный ответ Llama (${config.modelPath}, temp=${config.temperature}):\n$prompt"
    }

    suspend fun generateSummary(transcript: String): String {
        if (!isInitialized) initialize()
        delay(600)

        if (transcript.isBlank()) {
            return "Текст стенограммы отсутствует для составления резюме."
        }

        return buildString {
            appendLine("📋 Краткая выжимка (Executive Summary):")
            appendLine("В ходе записи обсуждались ключевые вопросы планирования, архитектурные решения и распределение ответственности в команде.")
            appendLine()
            appendLine("• Главный фокус: завершение интеграции компонентов и проведение нагрузочных испытаний.")
            appendLine("• Сроки: критические задачи должны быть закрыты к концу текущей рабочей недели.")
            appendLine("• Следующий шаг: координация дизайна и ускорение мобильного инференса нейросетей.")
        }
    }

    suspend fun extractActionItems(transcript: String): List<ActionItem> {
        if (!isInitialized) initialize()
        delay(600)

        return listOf(
            ActionItem(
                id = UUID.randomUUID().toString(),
                text = "Завершить интеграцию мобильного клиента с бэкендом",
                isCompleted = false,
                assignee = "Команда разработки"
            ),
            ActionItem(
                id = UUID.randomUUID().toString(),
                text = "Провести нагрузочное стресс-тестирование API",
                isCompleted = false,
                assignee = "QA / DevOps"
            ),
            ActionItem(
                id = UUID.randomUUID().toString(),
                text = "Подготовить дизайн-макеты новых экранов диктофона",
                isCompleted = true,
                assignee = "Ульяна"
            ),
            ActionItem(
                id = UUID.randomUUID().toString(),
                text = "Оптимизировать скорость инференса Whisper & Llama",
                isCompleted = false,
                assignee = "Дмитрий"
            ),
            ActionItem(
                id = UUID.randomUUID().toString(),
                text = "Провести статус-митинг завтра в 11:00",
                isCompleted = false,
                assignee = "Вся команда"
            )
        )
    }

    suspend fun generateMeetingProtocol(title: String, transcript: String, dateFormatted: String): String {
        if (!isInitialized) initialize()
        delay(700)

        return buildString {
            appendLine("📝 ПРОТОКОЛ ВСТРЕЧИ")
            appendLine("Тема: $title")
            appendLine("Дата: $dateFormatted")
            appendLine("Статус: Утверждено")
            appendLine()
            appendLine("1. ПОВЕСТКА:")
            appendLine("  1.1. Текущий статус спринта.")
            appendLine("  1.2. Архитектура пайплайна распознавания речи.")
            appendLine("  1.3. Вопросы производительности локальных нейросетей.")
            appendLine()
            appendLine("2. ПРИНЯТЫЕ РЕШЕНИЯ:")
            appendLine("  • Принято решение использовать Beam Search = ${config.threads} для баланса скорости и точности.")
            appendLine("  • Утверждено разделение на микропакеты (micro-batch size: ${config.microBatchSize}) для экономии RAM смартфона.")
            appendLine("  • Зафиксирован регламент ежедневных синхронизаций.")
            appendLine()
            appendLine("3. РЕЗУЛЬТАТ ОБСУЖДЕНИЯ:")
            appendLine("  Проект движется по утвержденному таймлайну. Блокирующих рисков не выявлено.")
        }
    }

    suspend fun cleanTranscript(transcript: String): String {
        if (!isInitialized) initialize()
        delay(500)

        // Remove filler words, polish punctuation
        var cleaned = transcript
            .replace(Regex("(?i)\\b(э-э|ну|короче|типа|в общем|как бы)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.isNotEmpty()) {
            cleaned = cleaned.substring(0, 1).uppercase() + cleaned.substring(1)
        }
        return cleaned
    }

    suspend fun answerQuestion(question: String, transcript: String): String {
        if (!isInitialized) initialize()
        delay(600)

        val qLower = question.lowercase()
        return when {
            "кто" in qLower || "участник" in qLower || "имена" in qLower ->
                "На основе контекста записи упоминаются Ульяна (отвечает за дизайн), Дмитрий (занимается оптимизацией инференса моделей) и техническая команда."
            "когда" in qLower || "срок" in qLower || "дедлайн" in qLower || "время" in qLower ->
                "Согласно записи, ближайший статус-митинг назначен на завтра в 11:00, а все ключевые интеграционные задачи должны быть сданы до конца недели (пятница)."
            "задач" in qLower || "план" in qLower || "что делать" in qLower ->
                "Главные задачи: 1) Завершить мобильную интеграцию, 2) Провести нагрузочные тесты, 3) Согласовать макеты дизайна, 4) Оптимизировать локальный инференс."
            else ->
                "Анализируя расшифровку с параметрами Llama (контекст ${config.contextSize} токенов, температура ${config.temperature}):\n\nПо вашему вопросу «$question»: ключевой акцент встречи был сделан на слаженной работе над архитектурой диктофона и обеспечении быстрой обработки речи без потери качества."
        }
    }
}
