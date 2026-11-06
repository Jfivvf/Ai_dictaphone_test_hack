package com.example.data.repository

import android.content.Context
import com.example.data.model.ActionItem
import com.example.data.model.AiChatMessage
import com.example.data.model.LlamaConfig
import com.example.data.model.ProcessingStatus
import com.example.data.model.Recording
import com.example.data.model.TranscriptSegment
import com.example.data.model.WhisperConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class DictaphoneRepository(private val context: Context) {

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    private val _whisperConfig = MutableStateFlow(WhisperConfig())
    val whisperConfig: StateFlow<WhisperConfig> = _whisperConfig.asStateFlow()

    private val _llamaConfig = MutableStateFlow(LlamaConfig())
    val llamaConfig: StateFlow<LlamaConfig> = _llamaConfig.asStateFlow()

    init {
        loadInitialRecordings()
    }

    private fun loadInitialRecordings() {
        val sample1 = Recording(
            id = "sample_sprint_meeting",
            title = "Синхронизация по релизу AI Dictaphone",
            createdAt = System.currentTimeMillis() - (3600 * 1000 * 4),
            durationMs = 28000L,
            isSample = true,
            tags = listOf("Встреча", "План", "AI"),
            isFavorite = true,
            status = ProcessingStatus.COMPLETED,
            fullTranscript = "Привет всем, давайте начнем обсуждение нашего спринта и ключевых задач на следующую неделю. По архитектуре бэкенда мы зафиксировали переход на микросервисы и шину событий для сообщений. Главный приоритет до пятницы — закрыть интеграцию с мобильным клиентом и провести нагрузочное тестирование. Также нужно подготовить финальную документацию по API и настроить мониторинг метрик. Ульяна подготовит дизайн-макеты для новых экранов диктофона и согласует их с командой. Дмитрий возьмет на себя оптимизацию времени инференса моделей Whisper и Llama на устройстве. Встречаемся завтра в 11:00 на статус-митинге для сверки прогресса и решения блокеров.",
            segments = listOf(
                TranscriptSegment(
                    id = "s1_1",
                    startMs = 0L,
                    endMs = 4500L,
                    text = "Привет всем, давайте начнем обсуждение нашего спринта и ключевых задач на следующую неделю.",
                    speaker = "Алексей (Лид)"
                ),
                TranscriptSegment(
                    id = "s1_2",
                    startMs = 4500L,
                    endMs = 9200L,
                    text = "По архитектуре бэкенда мы зафиксировали переход на микросервисы и шину событий для сообщений.",
                    speaker = "Михаил (Бэкенд)"
                ),
                TranscriptSegment(
                    id = "s1_3",
                    startMs = 9200L,
                    endMs = 14500L,
                    text = "Главный приоритет до пятницы — закрыть интеграцию с мобильным клиентом и провести нагрузочное тестирование.",
                    speaker = "Алексей (Лид)"
                ),
                TranscriptSegment(
                    id = "s1_4",
                    startMs = 14500L,
                    endMs = 18800L,
                    text = "Также нужно подготовить финальную документацию по API и настроить мониторинг метрик.",
                    speaker = "Михаил (Бэкенд)"
                ),
                TranscriptSegment(
                    id = "s1_5",
                    startMs = 18800L,
                    endMs = 23200L,
                    text = "Ульяна подготовит дизайн-макеты для новых экранов диктофона и согласует их с командой.",
                    speaker = "Ульяна (Дизайн)"
                ),
                TranscriptSegment(
                    id = "s1_6",
                    startMs = 23200L,
                    endMs = 28000L,
                    text = "Дмитрий возьмет на себя оптимизацию времени инференса моделей Whisper и Llama на устройстве. Встречаемся завтра в 11:00.",
                    speaker = "Дмитрий (ML)"
                )
            ),
            summary = "Командная встреча по подготовке релиза умного диктофона. Принято решение завершить мобильную интеграцию, подготовить финальные макеты дизайна (Ульяна) и оптимизировать локальный инференс моделей Whisper/Llama (Дмитрий). Следующий созвон назначен на завтра в 11:00.",
            actionItems = listOf(
                ActionItem("a1_1", "Завершить интеграцию с мобильным клиентом", isCompleted = false, assignee = "Алексей"),
                ActionItem("a1_2", "Подготовить дизайн-макеты экранов диктофона", isCompleted = true, assignee = "Ульяна"),
                ActionItem("a1_3", "Оптимизировать скорость локального инференса Whisper & Llama", isCompleted = false, assignee = "Дмитрий"),
                ActionItem("a1_4", "Настроить мониторинг метрик и нагрузочное тестирование", isCompleted = false, assignee = "Михаил")
            ),
            meetingProtocol = "ПРОТОКОЛ СОВЕЩАНИЯ\nТема: Релиз AI Dictaphone\nУчастники: Алексей, Михаил, Ульяна, Дмитрий.\n\nРешения:\n1. Зафиксировать микросервисный подход и протоколы обмена данными.\n2. Утвердить порог задержки инференса Whisper до 800мс.\n3. Финализировать UX волновой диаграммы и кнопок паузы/воспроизведения.",
            cleanedText = "Привет всем, давайте начнем обсуждение нашего спринта и ключевых задач на следующую неделю. По архитектуре бэкенда мы зафиксировали переход на микросервисы и шину событий для сообщений.",
            chatMessages = listOf(
                AiChatMessage("m1_1", isUser = false, text = "Здравствуйте! Я изучил запись встречи. Спрашивайте любые детали по решениям, срокам и задачам!")
            )
        )

        val sample2 = Recording(
            id = "sample_ai_lecture",
            title = "Конспект лекции: Введение в Whisper и LLM",
            createdAt = System.currentTimeMillis() - (3600 * 1000 * 24),
            durationMs = 22000L,
            isSample = true,
            tags = listOf("Обучение", "ML", "Лекция"),
            isFavorite = false,
            status = ProcessingStatus.COMPLETED,
            fullTranscript = "Архитектура модели Whisper основана на стандартном энкодер-декодер трансформере. Аудиосигнал преобразуется в 80-канальную спектрограмму мел-шкалы с окном 25 миллисекунд. Затем энкодер извлекает акустические признаки, а авторегрессионный декодер предсказывает текстовые токены с учетом контекста и временных меток.",
            segments = listOf(
                TranscriptSegment("s2_1", 0L, 7000L, "Архитектура модели Whisper основана на стандартном энкодер-декодер трансформере.", "Профессор"),
                TranscriptSegment("s2_2", 7000L, 14000L, "Аудиосигнал преобразуется в 80-канальную спектрограмму мел-шкалы с окном 25 миллисекунд.", "Профессор"),
                TranscriptSegment("s2_3", 14000L, 22000L, "Затем энкодер извлекает акустические признаки, а авторегрессионный декодер предсказывает текстовые токены.", "Профессор")
            ),
            summary = "Лекция об устройстве speech-to-text модели Whisper. Рассмотрен процесс предобработки звука в мел-спектрограмму и механизм работы энкодера/декодера трансформера.",
            actionItems = listOf(
                ActionItem("a2_1", "Повторить формулу вычисления Mel-спектрограммы", isCompleted = false),
                ActionItem("a2_2", "Изучить параметры beam search и temperature в Whisper", isCompleted = true)
            ),
            meetingProtocol = "КОНСПЕКТ ЗАНЯТИЯ\nТема: Акустические модели и декодирование речи\nКлючевые термины: Мел-спектрограмма, Энкодер, Декодер, Авторегрессия.",
            cleanedText = "Архитектура модели Whisper основана на стандартном энкодер-декодер трансформере. Аудиосигнал преобразуется в 80-канальную спектрограмму мел-шкалы.",
            chatMessages = listOf(
                AiChatMessage("m2_1", isUser = false, text = "Готов ответить на вопросы по конспекту лекции по архитектуре Whisper.")
            )
        )

        val sample3 = Recording(
            id = "sample_interview",
            title = "Интервью с продакт-менеджером",
            createdAt = System.currentTimeMillis() - (3600 * 1000 * 48),
            durationMs = 18000L,
            isSample = true,
            tags = listOf("Интервью", "Продукт"),
            isFavorite = true,
            status = ProcessingStatus.COMPLETED,
            fullTranscript = "Пользователям диктофона критически важно сразу видеть результат расшифровки и иметь возможность экспортировать краткие тезисы встречи прямо в мессенджер или таск-трекер.",
            segments = listOf(
                TranscriptSegment("s3_1", 0L, 8000L, "Пользователям диктофона критически важно сразу видеть результат расшифровки.", "Интервьюер"),
                TranscriptSegment("s3_2", 8000L, 18000L, "И иметь возможность экспортировать краткие тезисы встречи прямо в мессенджер или таск-трекер.", "Респондент")
            ),
            summary = "Пользовательское исследование: ключевая ценность — скорость получения выжимки и бесшовная отправка тезисов в рабочие чаты.",
            actionItems = listOf(
                ActionItem("a3_1", "Добавить кнопку быстрого копирования и экспорта выжимки", isCompleted = true),
                ActionItem("a3_2", "Провести коридорное тестирование интерфейса плеера", isCompleted = false)
            ),
            meetingProtocol = "ОТЧЕТ ИНТЕРВЬЮ\nВывод: высокий спрос на автоматическое выделение задач и поручений из аудиозаписей.",
            cleanedText = "Пользователям диктофона критически важно сразу видеть результат расшифровки и иметь возможность экспортировать краткие тезисы встречи.",
            chatMessages = emptyList()
        )

        _recordings.value = listOf(sample1, sample2, sample3)
    }

    fun addRecording(recording: Recording) {
        val current = _recordings.value.toMutableList()
        current.add(0, recording)
        _recordings.value = current
    }

    fun updateRecording(recording: Recording) {
        val current = _recordings.value.map {
            if (it.id == recording.id) recording else it
        }
        _recordings.value = current
    }

    fun deleteRecording(recordingId: String) {
        _recordings.value = _recordings.value.filter { it.id != recordingId }
    }

    fun toggleFavorite(recordingId: String) {
        _recordings.value = _recordings.value.map {
            if (it.id == recordingId) it.copy(isFavorite = !it.isFavorite) else it
        }
    }

    fun toggleActionItem(recordingId: String, actionItemId: String) {
        _recordings.value = _recordings.value.map { rec ->
            if (rec.id == recordingId) {
                val updatedItems = rec.actionItems.map { item ->
                    if (item.id == actionItemId) item.copy(isCompleted = !item.isCompleted) else item
                }
                rec.copy(actionItems = updatedItems)
            } else rec
        }
    }

    fun addChatMessage(recordingId: String, message: AiChatMessage) {
        _recordings.value = _recordings.value.map { rec ->
            if (rec.id == recordingId) {
                rec.copy(chatMessages = rec.chatMessages + message)
            } else rec
        }
    }

    fun updateWhisperConfig(config: WhisperConfig) {
        _whisperConfig.value = config
    }

    fun updateLlamaConfig(config: LlamaConfig) {
        _llamaConfig.value = config
    }
}
