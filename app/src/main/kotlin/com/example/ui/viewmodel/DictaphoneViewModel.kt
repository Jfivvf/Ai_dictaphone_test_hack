package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.engine.AudioPlayerManager
import com.example.data.engine.AudioRecorderManager
import com.example.data.engine.LlamaEngine
import com.example.data.engine.WhisperEngine
import com.example.data.model.AiChatMessage
import com.example.data.model.LlamaConfig
import com.example.data.model.ProcessingStatus
import com.example.data.model.Recording
import com.example.data.model.WhisperConfig
import com.example.data.repository.DictaphoneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class DictaphoneViewModel(
    application: Application,
    val repository: DictaphoneRepository = DictaphoneRepository(application),
    val audioRecorder: AudioRecorderManager = AudioRecorderManager(application),
    val audioPlayer: AudioPlayerManager = AudioPlayerManager(application),
    val whisperEngine: WhisperEngine = WhisperEngine(),
    val llamaEngine: LlamaEngine = LlamaEngine()
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    private val _selectedRecordingId = MutableStateFlow<String?>(null)
    val selectedRecordingId: StateFlow<String?> = _selectedRecordingId.asStateFlow()

    private val _isRecordingSheetOpen = MutableStateFlow(false)
    val isRecordingSheetOpen: StateFlow<Boolean> = _isRecordingSheetOpen.asStateFlow()

    private val _isSettingsDialogOpen = MutableStateFlow(false)
    val isSettingsDialogOpen: StateFlow<Boolean> = _isSettingsDialogOpen.asStateFlow()

    private val _isAiGenerating = MutableStateFlow(false)
    val isAiGenerating: StateFlow<Boolean> = _isAiGenerating.asStateFlow()

    init {
        whisperEngine.initialize()
        llamaEngine.initialize()
    }

    // Filtered recordings
    val filteredRecordings: StateFlow<List<Recording>> = combine(
        repository.recordings,
        _searchQuery,
        _selectedTag
    ) { recordings, query, tag ->
        recordings.filter { rec ->
            val matchesQuery = query.isBlank() ||
                rec.title.contains(query, ignoreCase = true) ||
                rec.fullTranscript.contains(query, ignoreCase = true) ||
                rec.tags.any { it.contains(query, ignoreCase = true) }

            val matchesTag = tag == null || tag == "Все" || rec.tags.contains(tag)
            matchesQuery && matchesTag
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedRecording: StateFlow<Recording?> = combine(
        repository.recordings,
        _selectedRecordingId
    ) { recordings, selectedId ->
        recordings.find { it.id == selectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onTagSelect(tag: String?) {
        _selectedTag.value = if (_selectedTag.value == tag) null else tag
    }

    fun selectRecording(id: String?) {
        _selectedRecordingId.value = id
    }

    fun openRecordingSheet() {
        _isRecordingSheetOpen.value = true
    }

    fun closeRecordingSheet() {
        if (audioRecorder.isRecording.value) {
            cancelRecording()
        }
        _isRecordingSheetOpen.value = false
    }

    fun openSettingsDialog() {
        _isSettingsDialogOpen.value = true
    }

    fun closeSettingsDialog() {
        _isSettingsDialogOpen.value = false
    }

    // Recording actions
    fun startRecording() {
        audioPlayer.stop()
        audioRecorder.startRecording()
    }

    fun pauseRecording() {
        audioRecorder.pauseRecording()
    }

    fun resumeRecording() {
        audioRecorder.resumeRecording()
    }

    fun cancelRecording() {
        val (file, _) = audioRecorder.stopRecording()
        file?.delete()
    }

    fun finishRecording(customTitle: String? = null) {
        val (file, durationMs) = audioRecorder.stopRecording()
        _isRecordingSheetOpen.value = false

        val title = if (!customTitle.isNullOrBlank()) {
            customTitle
        } else {
            "Запись ${repository.recordings.value.size + 1}"
        }

        val newRec = Recording(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            durationMs = durationMs.coerceAtLeast(3000L),
            filePath = file?.absolutePath,
            isSample = false,
            tags = listOf("Заметка", "Голос"),
            status = ProcessingStatus.TRANSCRIBING,
            whisperModelUsed = whisperEngine.config.modelPath,
            llamaModelUsed = llamaEngine.config.modelPath
        )

        repository.addRecording(newRec)
        _selectedRecordingId.value = newRec.id

        // Run full AI pipeline
        runAiProcessingPipeline(newRec.id, file?.absolutePath, newRec.durationMs)
    }

    fun runAiProcessingPipeline(recordingId: String, audioPath: String?, durationMs: Long) {
        viewModelScope.launch {
            _isAiGenerating.value = true
            val rec = repository.recordings.value.find { it.id == recordingId } ?: return@launch

            // 1. Whisper Transcription
            repository.updateRecording(rec.copy(status = ProcessingStatus.TRANSCRIBING))
            val (fullTranscript, segments) = whisperEngine.transcribe(audioPath, durationMs)

            val transcribedRec = rec.copy(
                fullTranscript = fullTranscript,
                segments = segments,
                status = ProcessingStatus.GENERATING_AI
            )
            repository.updateRecording(transcribedRec)

            // 2. Llama Text Generation (Summary, Action Items, Protocol, Cleaned Text)
            val summary = llamaEngine.generateSummary(fullTranscript)
            val actionItems = llamaEngine.extractActionItems(fullTranscript)
            val protocol = llamaEngine.generateMeetingProtocol(rec.title, fullTranscript, rec.formattedDate)
            val cleaned = llamaEngine.cleanTranscript(fullTranscript)

            val welcomeChatMsg = AiChatMessage(
                id = UUID.randomUUID().toString(),
                isUser = false,
                text = "Привет! Запись успешно обработана моделями Whisper и Llama. Вы можете задать мне любые вопросы по содержанию!"
            )

            val completedRec = transcribedRec.copy(
                summary = summary,
                actionItems = actionItems,
                meetingProtocol = protocol,
                cleanedText = cleaned,
                status = ProcessingStatus.COMPLETED,
                chatMessages = listOf(welcomeChatMsg)
            )
            repository.updateRecording(completedRec)
            _isAiGenerating.value = false
        }
    }

    // Playback actions
    fun playRecording(recording: Recording) {
        audioPlayer.play(recording.id, recording.filePath, recording.durationMs)
    }

    fun pausePlayback() {
        audioPlayer.pause()
    }

    fun resumePlayback() {
        audioPlayer.resume()
    }

    fun seekPlaybackTo(ms: Long) {
        audioPlayer.seekTo(ms)
    }

    fun skipPlaybackForward() {
        audioPlayer.skipForward(5000L)
    }

    fun skipPlaybackBackward() {
        audioPlayer.skipBackward(5000L)
    }

    fun setPlaybackSpeed(speed: Float) {
        audioPlayer.setSpeed(speed)
    }

    // Interactive Q&A chat
    fun sendChatMessage(recordingId: String, text: String) {
        if (text.isBlank()) return
        val userMsg = AiChatMessage(
            id = UUID.randomUUID().toString(),
            isUser = true,
            text = text
        )
        repository.addChatMessage(recordingId, userMsg)

        viewModelScope.launch {
            val rec = repository.recordings.value.find { it.id == recordingId }
            val transcript = rec?.fullTranscript ?: ""
            val answer = llamaEngine.answerQuestion(text, transcript)
            val botMsg = AiChatMessage(
                id = UUID.randomUUID().toString(),
                isUser = false,
                text = answer
            )
            repository.addChatMessage(recordingId, botMsg)
        }
    }

    fun toggleActionItem(recordingId: String, actionItemId: String) {
        repository.toggleActionItem(recordingId, actionItemId)
    }

    fun toggleFavorite(recordingId: String) {
        repository.toggleFavorite(recordingId)
    }

    fun deleteRecording(recordingId: String) {
        if (audioPlayer.currentPlayingId.value == recordingId) {
            audioPlayer.stop()
        }
        if (_selectedRecordingId.value == recordingId) {
            _selectedRecordingId.value = null
        }
        repository.deleteRecording(recordingId)
    }

    fun updateWhisperConfig(config: WhisperConfig) {
        whisperEngine.initialize(config.modelPath, config)
        repository.updateWhisperConfig(config)
    }

    fun updateLlamaConfig(config: LlamaConfig) {
        llamaEngine.initialize(config.modelPath, config)
        repository.updateLlamaConfig(config)
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        audioRecorder.stopRecording()
        whisperEngine.release()
        llamaEngine.release()
    }
}
