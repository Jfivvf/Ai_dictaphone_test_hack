package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.engine.AudioPlayerManager
import com.example.data.engine.AudioRecorderManager
import com.example.data.engine.LlamaEngine
import com.example.data.engine.PdfExporter
import com.example.data.engine.SpeechRecognizerManager
import com.example.data.engine.WhisperEngine
import com.example.data.model.AudioPreconfig
import com.example.data.model.LlamaConfig
import com.example.data.model.OnDeviceModel
import com.example.data.model.ProcessingStatus
import com.example.data.model.Recording
import com.example.data.model.UserProfile
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
import java.io.FileOutputStream
import java.util.UUID

class DictaphoneViewModel @JvmOverloads constructor(
    application: Application,
    val repository: DictaphoneRepository = DictaphoneRepository(application),
    val audioRecorder: AudioRecorderManager = AudioRecorderManager(application),
    val audioPlayer: AudioPlayerManager = AudioPlayerManager(application),
    val speechRecognizer: SpeechRecognizerManager = SpeechRecognizerManager(application),
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

    private val _isPreconfigSheetOpen = MutableStateFlow(false)
    val isPreconfigSheetOpen: StateFlow<Boolean> = _isPreconfigSheetOpen.asStateFlow()

    private val _isProfileSheetOpen = MutableStateFlow(false)
    val isProfileSheetOpen: StateFlow<Boolean> = _isProfileSheetOpen.asStateFlow()

    private val _isAiGenerating = MutableStateFlow(false)
    val isAiGenerating: StateFlow<Boolean> = _isAiGenerating.asStateFlow()

    val liveRecognizedText: StateFlow<String> = speechRecognizer.livePartialText
    val activeSpeaker: StateFlow<String> = speechRecognizer.activeSpeaker
    val liveSegments: StateFlow<List<com.example.data.model.TranscriptSegment>> = speechRecognizer.collectedSegments
    val audioPreconfig: StateFlow<AudioPreconfig> = repository.audioPreconfig
    val userProfile: StateFlow<UserProfile> = repository.userProfile
    val modelsList: StateFlow<List<OnDeviceModel>> = repository.modelsList

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
                rec.tags.any { it.contains(query, ignoreCase = true) } ||
                rec.speakersList.any { it.contains(query, ignoreCase = true) }

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

    fun openPreconfigSheet() {
        _isPreconfigSheetOpen.value = true
    }

    fun closePreconfigSheet() {
        _isPreconfigSheetOpen.value = false
    }

    fun openProfileSheet() {
        _isProfileSheetOpen.value = true
    }

    fun closeProfileSheet() {
        _isProfileSheetOpen.value = false
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
        // Start real-time microphone speech recognition
        val lang = if (repository.whisperConfig.value.language == "en") "en-US" else "ru-RU"
        speechRecognizer.startListening(lang)
    }

    fun pauseRecording() {
        audioRecorder.pauseRecording()
    }

    fun resumeRecording() {
        audioRecorder.resumeRecording()
    }

    fun cancelRecording() {
        speechRecognizer.stopListening()
        val (file, _) = audioRecorder.stopRecording()
        file?.delete()
    }

    fun finishRecording(customTitle: String? = null, customText: String? = null, customTag: String? = null) {
        val capturedSegments = speechRecognizer.stopListening()
        val (file, durationMs) = audioRecorder.stopRecording()
        _isRecordingSheetOpen.value = false

        val title = if (!customTitle.isNullOrBlank()) {
            customTitle
        } else {
            "Запись ${repository.recordings.value.size + 1}"
        }

        val tagsList = if (!customTag.isNullOrBlank()) {
            listOf(customTag.trim().removePrefix("#"))
        } else {
            emptyList()
        }

        val newRec = Recording(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            durationMs = durationMs.coerceAtLeast(4000L),
            filePath = file?.absolutePath,
            isSample = false,
            tags = tagsList,
            status = ProcessingStatus.PROCESSING
        )

        repository.addRecording(newRec)
        _selectedRecordingId.value = newRec.id

        // Process audio & text with automatic speaker diarization
        runAiProcessingPipeline(
            recordingId = newRec.id,
            realSegments = capturedSegments,
            userText = customText,
            audioPath = file?.absolutePath,
            durationMs = newRec.durationMs,
            title = title
        )
    }

    fun switchRecordingSpeaker() {
        speechRecognizer.switchSpeaker()
    }

    fun addTag(recordingId: String, tag: String) {
        repository.addTagToRecording(recordingId, tag)
    }

    fun removeTag(recordingId: String, tag: String) {
        repository.removeTagFromRecording(recordingId, tag)
    }

    fun reDiarize(recordingId: String, speakerCount: Int = 2) {
        repository.reDiarizeRecording(recordingId, speakerCount)
    }

    fun toggleSegmentSpeaker(recordingId: String, segmentId: String) {
        repository.toggleSegmentSpeaker(recordingId, segmentId)
    }

    fun importAudioFile(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
                val destDir = File(context.filesDir, "imported_audio").apply { if (!exists()) mkdirs() }
                val destFile = File(destDir, "import_${System.currentTimeMillis()}.m4a")
                val outputStream = FileOutputStream(destFile)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()

                val newRec = Recording(
                    id = UUID.randomUUID().toString(),
                    title = "Импортированное аудио #${repository.recordings.value.size + 1}",
                    createdAt = System.currentTimeMillis(),
                    durationMs = 30000L,
                    filePath = destFile.absolutePath,
                    isSample = false,
                    tags = emptyList(),
                    status = ProcessingStatus.PROCESSING
                )
                repository.addRecording(newRec)
                _selectedRecordingId.value = newRec.id
                runAiProcessingPipeline(newRec.id, null, null, destFile.absolutePath, 30000L, newRec.title)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun runAiProcessingPipeline(
        recordingId: String,
        realSegments: List<com.example.data.model.TranscriptSegment>?,
        userText: String?,
        audioPath: String?,
        durationMs: Long,
        title: String
    ) {
        viewModelScope.launch {
            _isAiGenerating.value = true
            val rec = repository.recordings.value.find { it.id == recordingId } ?: return@launch

            repository.updateRecording(rec.copy(status = ProcessingStatus.PROCESSING))

            // 1. Process transcription & speaker diarization
            val (fullTranscript, segments) = whisperEngine.processOrTranscribe(
                realSegments,
                userText,
                audioPath,
                durationMs,
                title
            )

            // 2. Generate summary, action items, and protocol dynamically from the actual text!
            val summary = llamaEngine.generateSummary(fullTranscript, rec.title)
            val actionItems = llamaEngine.extractActionItems(fullTranscript)
            val protocol = llamaEngine.generateMeetingProtocol(rec.title, fullTranscript, rec.formattedDate)

            val completedRec = rec.copy(
                fullTranscript = fullTranscript,
                segments = segments,
                summary = summary,
                actionItems = actionItems,
                meetingProtocol = protocol,
                status = ProcessingStatus.COMPLETED
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

    fun renameSpeaker(recordingId: String, oldName: String, newName: String) {
        repository.renameSpeaker(recordingId, oldName, newName)
    }

    fun updateSegmentText(recordingId: String, segmentId: String, newText: String) {
        repository.updateSegmentText(recordingId, segmentId, newText)
    }

    fun updateNotes(recordingId: String, notes: String) {
        repository.updateCustomNotes(recordingId, notes)
    }

    fun exportPdf(recording: Recording, context: Context): File? {
        return PdfExporter.exportRecordingToPdf(context, recording)
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

    fun updateAudioPreconfig(preconfig: AudioPreconfig) {
        repository.updateAudioPreconfig(preconfig)
    }

    fun updateUserProfile(profile: UserProfile) {
        repository.updateUserProfile(profile)
    }

    fun downloadModel(modelId: String) {
        repository.downloadModel(modelId)
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        audioRecorder.stopRecording()
        speechRecognizer.stopListening()
        whisperEngine.release()
        llamaEngine.release()
    }
}
