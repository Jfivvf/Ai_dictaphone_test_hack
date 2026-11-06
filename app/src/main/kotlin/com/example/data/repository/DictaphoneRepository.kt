package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.engine.SpeakerDiarizer
import com.example.data.model.ActionItem
import com.example.data.model.AudioPreconfig
import com.example.data.model.LlamaConfig
import com.example.data.model.OnDeviceModel
import com.example.data.model.ProcessingStatus
import com.example.data.model.Recording
import com.example.data.model.TranscriptSegment
import com.example.data.model.UserProfile
import com.example.data.model.WhisperConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class DictaphoneRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("dictaphone_vault_prefs", Context.MODE_PRIVATE)

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    private val _whisperConfig = MutableStateFlow(WhisperConfig())
    val whisperConfig: StateFlow<WhisperConfig> = _whisperConfig.asStateFlow()

    private val _llamaConfig = MutableStateFlow(LlamaConfig())
    val llamaConfig: StateFlow<LlamaConfig> = _llamaConfig.asStateFlow()

    private val _audioPreconfig = MutableStateFlow(AudioPreconfig())
    val audioPreconfig: StateFlow<AudioPreconfig> = _audioPreconfig.asStateFlow()

    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _modelsList = MutableStateFlow<List<OnDeviceModel>>(emptyList())
    val modelsList: StateFlow<List<OnDeviceModel>> = _modelsList.asStateFlow()

    init {
        loadInitialData()
        loadAvailableModels()
    }

    private fun loadInitialData() {
        _recordings.value = emptyList()
    }

    private fun loadAvailableModels() {
        _modelsList.value = listOf(
            OnDeviceModel("m1", "Whisper Tiny (RU)", "Распознавание речи", 75, true, "Быстрое локальное распознавание"),
            OnDeviceModel("m2", "Whisper Base (RU)", "Распознавание речи", 142, true, "Оптимальная точность"),
            OnDeviceModel("m3", "Llama 3.2 1B", "Анализ текста", 780, false, "Быстрая суммаризация"),
            OnDeviceModel("m4", "Llama 3.2 3B", "Анализ текста", 1950, true, "Подробный конспект и задачи")
        )
    }

    fun addRecording(recording: Recording) {
        val current = _recordings.value.toMutableList()
        current.add(0, recording)
        _recordings.value = current
        saveEmergencyBackup()
    }

    fun updateRecording(recording: Recording) {
        val current = _recordings.value.map {
            if (it.id == recording.id) recording else it
        }
        _recordings.value = current
        saveEmergencyBackup()
    }

    fun deleteRecording(recordingId: String) {
        _recordings.value = _recordings.value.filter { it.id != recordingId }
        saveEmergencyBackup()
    }

    fun addTagToRecording(recordingId: String, tag: String) {
        val normalized = tag.trim().removePrefix("#")
        if (normalized.isBlank()) return
        _recordings.value = _recordings.value.map { recording ->
            if (recording.id == recordingId && normalized !in recording.tags) {
                recording.copy(tags = recording.tags + normalized)
            } else recording
        }
        saveEmergencyBackup()
    }

    fun removeTagFromRecording(recordingId: String, tag: String) {
        _recordings.value = _recordings.value.map { recording ->
            if (recording.id == recordingId) recording.copy(tags = recording.tags - tag) else recording
        }
        saveEmergencyBackup()
    }

    fun toggleSegmentSpeaker(recordingId: String, segmentId: String) {
        _recordings.value = _recordings.value.map { recording ->
            if (recording.id != recordingId) return@map recording
            val speakers = recording.speakersList
            if (speakers.size < 2) return@map recording
            val segments = recording.segments.map { segment ->
                if (segment.id != segmentId) segment else {
                    val currentIndex = speakers.indexOf(segment.speaker).coerceAtLeast(0)
                    segment.copy(speaker = speakers[(currentIndex + 1) % speakers.size])
                }
            }
            recording.copy(segments = segments)
        }
        saveEmergencyBackup()
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

    fun renameSpeaker(recordingId: String, oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        _recordings.value = _recordings.value.map { rec ->
            if (rec.id == recordingId) {
                val updatedSegments = rec.segments.map { seg ->
                    if (seg.speaker == oldName) seg.copy(speaker = newName) else seg
                }
                rec.copy(segments = updatedSegments)
            } else rec
        }
    }

    fun updateSegmentText(recordingId: String, segmentId: String, newText: String) {
        _recordings.value = _recordings.value.map { rec ->
            if (rec.id == recordingId) {
                val updated = rec.segments.map { seg ->
                    if (seg.id == segmentId) seg.copy(text = newText) else seg
                }
                rec.copy(
                    segments = updated,
                    fullTranscript = updated.joinToString(" ") { it.text }
                )
            } else rec
        }
    }

    fun updateCustomNotes(recordingId: String, notes: String) {
        _recordings.value = _recordings.value.map { rec ->
            if (rec.id == recordingId) rec.copy(customNotes = notes) else rec
        }
    }

    fun updateWhisperConfig(config: WhisperConfig) {
        _whisperConfig.value = config
    }

    fun updateLlamaConfig(config: LlamaConfig) {
        _llamaConfig.value = config
    }

    fun updateAudioPreconfig(preconfig: AudioPreconfig) {
        _audioPreconfig.value = preconfig
    }

    fun updateUserProfile(profile: UserProfile) {
        _userProfile.value = profile
    }

    fun downloadModel(modelId: String) {
        _modelsList.value = _modelsList.value.map { m ->
            if (m.id == modelId) m.copy(isDownloaded = true) else m
        }
    }

    private fun saveEmergencyBackup() {
        try {
            val backupFile = File(context.filesDir, "emergency_backup.json")
            backupFile.writeText("{\"count\": ${_recordings.value.size}, \"timestamp\": ${System.currentTimeMillis()}}")
        } catch (ignored: Exception) {}
    }
}
