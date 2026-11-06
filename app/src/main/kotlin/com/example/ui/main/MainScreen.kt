package com.example.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.detail.RecordingDetailScreen
import com.example.ui.profile.UserProfileSheet
import com.example.ui.recording.AudioPreconfigSheet
import com.example.ui.recording.LiveRecordingSheet
import com.example.ui.recordings.RecordingsListScreen
import com.example.ui.settings.ModelsSettingsDialog
import com.example.ui.viewmodel.DictaphoneViewModel

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: DictaphoneViewModel = viewModel()
) {
    val context = LocalContext.current
    val recordings by viewModel.filteredRecordings.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val selectedRecording by viewModel.selectedRecording.collectAsStateWithLifecycle()
    val isRecordingSheetOpen by viewModel.isRecordingSheetOpen.collectAsStateWithLifecycle()
    val isSettingsDialogOpen by viewModel.isSettingsDialogOpen.collectAsStateWithLifecycle()
    val isPreconfigSheetOpen by viewModel.isPreconfigSheetOpen.collectAsStateWithLifecycle()
    val isProfileSheetOpen by viewModel.isProfileSheetOpen.collectAsStateWithLifecycle()

    val isRecording by viewModel.audioRecorder.isRecording.collectAsStateWithLifecycle()
    val isRecordingPaused by viewModel.audioRecorder.isPaused.collectAsStateWithLifecycle()
    val recordingDurationMs by viewModel.audioRecorder.recordingDurationMs.collectAsStateWithLifecycle()
    val recordingAmplitudes by viewModel.audioRecorder.amplitudes.collectAsStateWithLifecycle()
    val liveRecognizedText by viewModel.liveRecognizedText.collectAsStateWithLifecycle()
    val activeSpeaker by viewModel.activeSpeaker.collectAsStateWithLifecycle()
    val liveSegments by viewModel.liveSegments.collectAsStateWithLifecycle()

    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsStateWithLifecycle()
    val currentPositionMs by viewModel.audioPlayer.currentPositionMs.collectAsStateWithLifecycle()
    val playbackDurationMs by viewModel.audioPlayer.durationMs.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.audioPlayer.playbackSpeed.collectAsStateWithLifecycle()
    val currentPlayingId by viewModel.audioPlayer.currentPlayingId.collectAsStateWithLifecycle()

    val whisperConfig by viewModel.repository.whisperConfig.collectAsStateWithLifecycle()
    val llamaConfig by viewModel.repository.llamaConfig.collectAsStateWithLifecycle()
    val audioPreconfig by viewModel.audioPreconfig.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val modelsList by viewModel.modelsList.collectAsStateWithLifecycle()
    val isAiGenerating by viewModel.isAiGenerating.collectAsStateWithLifecycle()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.openRecordingSheet()
        if (isGranted) {
            viewModel.startRecording()
        }
    }

    fun handleRecordClick() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            viewModel.openRecordingSheet()
            viewModel.startRecording()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = selectedRecording,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen_transition"
        ) { activeRecording ->
            if (activeRecording != null) {
                RecordingDetailScreen(
                    recording = activeRecording,
                    isPlaying = isPlaying && currentPlayingId == activeRecording.id,
                    currentPositionMs = if (currentPlayingId == activeRecording.id) currentPositionMs else 0L,
                    playbackDurationMs = if (currentPlayingId == activeRecording.id) playbackDurationMs else activeRecording.durationMs,
                    playbackSpeed = playbackSpeed,
                    isAiGenerating = isAiGenerating,
                    onPlay = { viewModel.playRecording(activeRecording) },
                    onPause = { viewModel.pausePlayback() },
                    onSeek = { viewModel.seekPlaybackTo(it) },
                    onSkipForward = { viewModel.skipPlaybackForward() },
                    onSkipBackward = { viewModel.skipPlaybackBackward() },
                    onSetSpeed = { viewModel.setPlaybackSpeed(it) },
                    onToggleFavorite = { viewModel.toggleFavorite(activeRecording.id) },
                    onDelete = { viewModel.deleteRecording(activeRecording.id) },
                    onReRunAi = {
                        viewModel.runAiProcessingPipeline(
                            activeRecording.id,
                            activeRecording.segments,
                            null,
                            activeRecording.filePath,
                            activeRecording.durationMs,
                            activeRecording.title
                        )
                    },
                    onRenameSpeaker = { oldName, newName ->
                        viewModel.renameSpeaker(activeRecording.id, oldName, newName)
                    },
                    onUpdateSegmentText = { segmentId, newText ->
                        viewModel.updateSegmentText(activeRecording.id, segmentId, newText)
                    },
                    onUpdateNotes = { notes ->
                        viewModel.updateNotes(activeRecording.id, notes)
                    },
                    onToggleActionItem = { viewModel.toggleActionItem(activeRecording.id, it) },
                    onAddTag = { tag -> viewModel.addTag(activeRecording.id, tag) },
                    onRemoveTag = { tag -> viewModel.removeTag(activeRecording.id, tag) },
                    onReDiarize = { count -> viewModel.reDiarize(activeRecording.id, count) },
                    onToggleSegmentSpeaker = { segmentId -> viewModel.toggleSegmentSpeaker(activeRecording.id, segmentId) },
                    onBack = { viewModel.selectRecording(null) }
                )
            } else {
                RecordingsListScreen(
                    recordings = recordings,
                    searchQuery = searchQuery,
                    selectedTag = selectedTag,
                    currentPlayingId = currentPlayingId,
                    isPlaying = isPlaying,
                    onSearchChange = { viewModel.onSearchQueryChange(it) },
                    onTagSelect = { viewModel.onTagSelect(it) },
                    onRecordingClick = { viewModel.selectRecording(it.id) },
                    onQuickPlayToggle = { rec ->
                        if (isPlaying && currentPlayingId == rec.id) {
                            viewModel.pausePlayback()
                        } else {
                            viewModel.playRecording(rec)
                        }
                    },
                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                    onImportAudio = { uri -> viewModel.importAudioFile(uri, context) },
                    onOpenPreconfig = { viewModel.openPreconfigSheet() },
                    onOpenProfile = { viewModel.openProfileSheet() },
                    onOpenRecordSheet = { handleRecordClick() },
                    onOpenSettings = { viewModel.openSettingsDialog() }
                )
            }
        }

        // Live recording bottom sheet
        if (isRecordingSheetOpen) {
            LiveRecordingSheet(
                isRecording = isRecording,
                isPaused = isRecordingPaused,
                durationMs = recordingDurationMs,
                amplitudes = recordingAmplitudes,
                liveText = liveRecognizedText,
                activeSpeaker = activeSpeaker,
                liveSegments = liveSegments,
                onSwitchSpeaker = { viewModel.switchRecordingSpeaker() },
                onStart = { viewModel.startRecording() },
                onPause = { viewModel.pauseRecording() },
                onResume = { viewModel.resumeRecording() },
                onFinish = { customTitle, customText, customTag ->
                    viewModel.finishRecording(customTitle, customText, customTag)
                },
                onCancel = { viewModel.closeRecordingSheet() },
                onDismiss = { viewModel.closeRecordingSheet() }
            )
        }

        // Audio preconfig bottom sheet
        if (isPreconfigSheetOpen) {
            AudioPreconfigSheet(
                currentConfig = audioPreconfig,
                onSaveAndRecord = { updated ->
                    viewModel.updateAudioPreconfig(updated)
                    viewModel.closePreconfigSheet()
                    handleRecordClick()
                },
                onDismiss = { viewModel.closePreconfigSheet() }
            )
        }

        // User profile sheet
        if (isProfileSheetOpen) {
            UserProfileSheet(
                profile = userProfile,
                models = modelsList,
                onDownloadModel = { viewModel.downloadModel(it) },
                onDismiss = { viewModel.closeProfileSheet() }
            )
        }

        // Models settings dialog
        if (isSettingsDialogOpen) {
            ModelsSettingsDialog(
                whisperConfig = whisperConfig,
                llamaConfig = llamaConfig,
                onSaveWhisper = { viewModel.updateWhisperConfig(it) },
                onSaveLlama = { viewModel.updateLlamaConfig(it) },
                onDismiss = { viewModel.closeSettingsDialog() }
            )
        }
    }
}
