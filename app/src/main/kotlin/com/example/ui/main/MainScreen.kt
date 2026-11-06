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

    val isRecording by viewModel.audioRecorder.isRecording.collectAsStateWithLifecycle()
    val isRecordingPaused by viewModel.audioRecorder.isPaused.collectAsStateWithLifecycle()
    val recordingDurationMs by viewModel.audioRecorder.recordingDurationMs.collectAsStateWithLifecycle()
    val recordingAmplitudes by viewModel.audioRecorder.amplitudes.collectAsStateWithLifecycle()

    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsStateWithLifecycle()
    val currentPositionMs by viewModel.audioPlayer.currentPositionMs.collectAsStateWithLifecycle()
    val playbackDurationMs by viewModel.audioPlayer.durationMs.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.audioPlayer.playbackSpeed.collectAsStateWithLifecycle()
    val currentPlayingId by viewModel.audioPlayer.currentPlayingId.collectAsStateWithLifecycle()

    val whisperConfig by viewModel.repository.whisperConfig.collectAsStateWithLifecycle()
    val llamaConfig by viewModel.repository.llamaConfig.collectAsStateWithLifecycle()
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
                            activeRecording.filePath,
                            activeRecording.durationMs
                        )
                    },
                    onToggleActionItem = { viewModel.toggleActionItem(activeRecording.id, it) },
                    onSendMessage = { viewModel.sendChatMessage(activeRecording.id, it) },
                    onBack = { viewModel.selectRecording(null) }
                )
            } else {
                RecordingsListScreen(
                    recordings = recordings,
                    searchQuery = searchQuery,
                    selectedTag = selectedTag,
                    currentPlayingId = currentPlayingId,
                    isPlaying = isPlaying,
                    whisperModelName = whisperConfig.modelPath,
                    llamaModelName = llamaConfig.modelPath,
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
                onStart = { viewModel.startRecording() },
                onPause = { viewModel.pauseRecording() },
                onResume = { viewModel.resumeRecording() },
                onFinish = { customTitle -> viewModel.finishRecording(customTitle) },
                onCancel = { viewModel.closeRecordingSheet() },
                onDismiss = { viewModel.closeRecordingSheet() }
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
