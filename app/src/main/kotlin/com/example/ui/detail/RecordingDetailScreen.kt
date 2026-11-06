package com.example.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.PdfExporter
import com.example.data.model.ProcessingStatus
import com.example.data.model.Recording
import com.example.data.model.TranscriptSegment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    recording: Recording,
    isPlaying: Boolean,
    currentPositionMs: Long,
    playbackDurationMs: Long,
    playbackSpeed: Float,
    isAiGenerating: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onReRunAi: () -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    onUpdateSegmentText: (String, String) -> Unit,
    onUpdateNotes: (String) -> Unit,
    onToggleActionItem: (String) -> Unit,
    onAddTag: (String) -> Unit = {},
    onRemoveTag: (String) -> Unit = {},
    onReDiarize: (Int) -> Unit = {},
    onToggleSegmentSpeaker: (String) -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    // Tag dialog state
    var isAddTagDialogOpen by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }

    // Speaker rename dialog state
    var renameTargetSpeaker by remember { mutableStateOf<String?>(null) }
    var renameNewSpeakerName by remember { mutableStateOf("") }

    // Segment text editing state
    var editingSegment by remember { mutableStateOf<TranscriptSegment?>(null) }
    var editingSegmentText by remember { mutableStateOf("") }

    // Notes editor state
    var isEditingNotes by remember { mutableStateOf(false) }
    var notesDraft by remember { mutableStateOf(recording.customNotes) }

    fun copyToClipboard(text: String, label: String = "Текст") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    fun exportToPdf() {
        val file = PdfExporter.exportRecordingToPdf(context, recording)
        if (file != null) {
            Toast.makeText(context, "PDF успешно создан: ${file.name}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Ошибка при создании PDF", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = recording.title,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "${recording.formattedDate} • ${recording.formattedDuration}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("detail_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { exportToPdf() },
                        modifier = Modifier.testTag("export_pdf_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "Экспорт в PDF",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onReRunAi,
                        modifier = Modifier.testTag("rerun_ai_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Пересобрать конспект",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.testTag("detail_favorite_button")
                    ) {
                        Icon(
                            imageVector = if (recording.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "В избранное",
                            tint = if (recording.isFavorite) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("detail_delete_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Удалить запись",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Audio Player Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val currentSec = currentPositionMs / 1000
                    val totalSec = (if (playbackDurationMs > 0) playbackDurationMs else recording.durationMs) / 1000
                    val curMin = currentSec / 60
                    val curRem = currentSec % 60
                    val totMin = totalSec / 60
                    val totRem = totalSec % 60

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format("%02d:%02d", curMin, curRem),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = String.format("%02d:%02d", totMin, totRem),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val totalDuration = if (playbackDurationMs > 0) playbackDurationMs else recording.durationMs.coerceAtLeast(1L)
                    Slider(
                        value = currentPositionMs.toFloat().coerceIn(0f, totalDuration.toFloat()),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..totalDuration.toFloat(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("audio_player_slider")
                    )

                    // Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onSkipBackward,
                            modifier = Modifier.testTag("skip_backward_button")
                        ) {
                            Icon(Icons.Default.FastRewind, contentDescription = "-5 сек")
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        FilledIconButton(
                            onClick = { if (isPlaying) onPause() else onPlay() },
                            modifier = Modifier
                                .size(50.dp)
                                .testTag("play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        IconButton(
                            onClick = onSkipForward,
                            modifier = Modifier.testTag("skip_forward_button")
                        ) {
                            Icon(Icons.Default.FastForward, contentDescription = "+5 сек")
                        }
                    }

                    // Playback speed selector
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            FilterChip(
                                selected = playbackSpeed == speed,
                                onClick = { onSetSpeed(speed) },
                                label = { Text("${speed}x", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Category Tags Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                recording.tags.forEach { tag ->
                    FilterChip(
                        selected = true,
                        onClick = { onRemoveTag(tag) },
                        label = { Text("#$tag", fontSize = 11.sp) },
                        trailingIcon = {
                            Icon(Icons.Default.Clear, contentDescription = "Удалить тег", modifier = Modifier.size(12.dp))
                        }
                    )
                }

                FilterChip(
                    selected = false,
                    onClick = {
                        newTagName = ""
                        isAddTagDialogOpen = true
                    },
                    label = { Text("+ Категория", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.testTag("add_category_tag_button")
                )
            }

            // Tabs: 1. Стенограмма, 2. Конспект и выжимка, 3. Чистый Markdown
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Стенограмма", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Notes, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Конспект", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Markdown", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }

            // Tab Content
            when (selectedTab) {
                0 -> TranscriptTab(
                    recording = recording,
                    currentPositionMs = currentPositionMs,
                    onSegmentClick = { onSeek(it.startMs) },
                    onSpeakerClick = { speaker ->
                        renameTargetSpeaker = speaker
                        renameNewSpeakerName = speaker
                    },
                    onToggleSpeaker = onToggleSegmentSpeaker,
                    onReDiarize = onReDiarize,
                    onEditSegmentText = { segment ->
                        editingSegment = segment
                        editingSegmentText = segment.text
                    },
                    onCopy = { copyToClipboard(it, "Стенограмма") }
                )
                1 -> SummaryAndNotesTab(
                    recording = recording,
                    isEditingNotes = isEditingNotes,
                    notesDraft = notesDraft,
                    onStartEditNotes = {
                        notesDraft = recording.customNotes
                        isEditingNotes = true
                    },
                    onDraftChange = { notesDraft = it },
                    onSaveNotes = {
                        onUpdateNotes(notesDraft)
                        isEditingNotes = false
                        Toast.makeText(context, "Заметки сохранены", Toast.LENGTH_SHORT).show()
                    },
                    onCancelEditNotes = { isEditingNotes = false },
                    onToggleActionItem = onToggleActionItem,
                    onCopySummary = { copyToClipboard(recording.summary, "Выжимка") }
                )
                2 -> MarkdownTab(
                    markdown = recording.toMarkdown(),
                    onCopy = { copyToClipboard(it, "Markdown") }
                )
            }
        }
    }

    // Add Tag Dialog
    if (isAddTagDialogOpen) {
        AlertDialog(
            onDismissRequest = { isAddTagDialogOpen = false },
            title = { Text("Добавить категорию (тег)") },
            text = {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    label = { Text("Название тега") },
                    placeholder = { Text("Например: Матанализ, Лекция") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("new_tag_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTagName.isNotBlank()) {
                            onAddTag(newTagName.trim().removePrefix("#"))
                            isAddTagDialogOpen = false
                        }
                    }
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = {
                TextButton(onClick = { isAddTagDialogOpen = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Rename Speaker Dialog
    renameTargetSpeaker?.let { oldSpeaker ->
        AlertDialog(
            onDismissRequest = { renameTargetSpeaker = null },
            title = { Text("Переименовать спикера") },
            text = {
                Column {
                    Text("Изменить «$oldSpeaker» во всем диалоге:", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameNewSpeakerName,
                        onValueChange = { renameNewSpeakerName = it },
                        label = { Text("Имя спикера") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameSpeaker(oldSpeaker, renameNewSpeakerName.trim())
                        renameTargetSpeaker = null
                        Toast.makeText(context, "Спикер обновлен", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetSpeaker = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Edit Segment Text Dialog
    editingSegment?.let { seg ->
        AlertDialog(
            onDismissRequest = { editingSegment = null },
            title = { Text("Редактировать реплику") },
            text = {
                Column {
                    Text("${seg.formattedTime} • ${seg.speaker}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editingSegmentText,
                        onValueChange = { editingSegmentText = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateSegmentText(seg.id, editingSegmentText.trim())
                        editingSegment = null
                        Toast.makeText(context, "Текст обновлен", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSegment = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun TranscriptTab(
    recording: Recording,
    currentPositionMs: Long,
    onSegmentClick: (TranscriptSegment) -> Unit,
    onSpeakerClick: (String) -> Unit,
    onToggleSpeaker: (String) -> Unit,
    onReDiarize: (Int) -> Unit,
    onEditSegmentText: (TranscriptSegment) -> Unit,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Стенограмма по спикерам",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "${recording.speakersCount} спикера в диалоге",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = false,
                    onClick = { onReDiarize(2) },
                    label = { Text("Разделить ⇄", fontSize = 10.sp) },
                    modifier = Modifier.testTag("rediarize_button")
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { onCopy(recording.fullTranscript) },
                    modifier = Modifier.testTag("copy_transcript_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать текст")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (recording.segments.isEmpty()) {
                item {
                    Text(
                        text = if (recording.fullTranscript.isNotBlank()) recording.fullTranscript else "Стенограмма пуста.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(recording.segments) { segment ->
                    val isHighlighted = currentPositionMs in segment.startMs..segment.endMs

                    val speakerColors = listOf(
                        Color(0xFF7C3AED), // Violet
                        Color(0xFF0284C7), // Sky
                        Color(0xFF059669), // Emerald
                        Color(0xFFD97706)  // Amber
                    )
                    val speakerIndex = recording.speakersList.indexOf(segment.speaker).coerceAtLeast(0)
                    val speakerColor = speakerColors[speakerIndex % speakerColors.size]

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSegmentClick(segment) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isHighlighted) {
                                speakerColor.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { onSpeakerClick(segment.speaker) }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(speakerColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = segment.speaker.take(1).uppercase(),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = segment.speaker,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = speakerColor
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Изменить имя",
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(speakerColor.copy(alpha = 0.15f))
                                            .clickable { onToggleSpeaker(segment.id) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "Сменить ⇄",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = speakerColor
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = segment.formattedTime,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { onEditSegmentText(segment) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Редактировать реплику",
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = segment.text,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryAndNotesTab(
    recording: Recording,
    isEditingNotes: Boolean,
    notesDraft: String,
    onStartEditNotes: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSaveNotes: () -> Unit,
    onCancelEditNotes: () -> Unit,
    onToggleActionItem: (String) -> Unit,
    onCopySummary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Конспект и ключевые тезисы", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    IconButton(onClick = onCopySummary, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (recording.summary.isNotBlank()) recording.summary else "Конспект формируется на основе текста записи...",
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }

        // Action Items Card
        if (recording.actionItems.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Задачи и вопросы для закрепления", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    recording.actionItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleActionItem(item.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = item.isCompleted,
                                onCheckedChange = { onToggleActionItem(item.id) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = item.text,
                                fontSize = 13.sp,
                                fontWeight = if (item.isCompleted) FontWeight.Normal else FontWeight.Medium,
                                color = if (item.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Custom User Notes Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Личные заметки к записи", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (!isEditingNotes) {
                        IconButton(onClick = onStartEditNotes, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Редактировать", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (isEditingNotes) {
                    OutlinedTextField(
                        value = notesDraft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Введите ваши мысли, дополнения к лекции или выводы...") },
                        shape = RoundedCornerShape(12.dp),
                        minLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onCancelEditNotes) { Text("Отмена") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onSaveNotes) { Text("Сохранить") }
                    }
                } else {
                    Text(
                        text = if (recording.customNotes.isNotBlank()) recording.customNotes else "Нажмите на карандаш, чтобы добавить свои примечания.",
                        fontSize = 13.sp,
                        color = if (recording.customNotes.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTab(
    markdown: String,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Чистый Markdown для экспорта", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            IconButton(onClick = { onCopy(markdown) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Копировать Markdown")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = markdown,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
