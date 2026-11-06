package com.example.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AudioPreconfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPreconfigSheet(
    currentConfig: AudioPreconfig,
    onSaveAndRecord: (AudioPreconfig) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var sampleRate by remember { mutableIntStateOf(currentConfig.sampleRateHz) }
    var audioSource by remember { mutableStateOf(currentConfig.audioSource) }
    var noiseSuppression by remember { mutableStateOf(currentConfig.noiseSuppression) }
    var echoCancellation by remember { mutableStateOf(currentConfig.echoCancellation) }
    var speakers by remember { mutableIntStateOf(currentConfig.expectedSpeakers) }
    var autoDiarization by remember { mutableStateOf(currentConfig.autoDiarization) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SettingsVoice,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Виджет преднастройки аудио",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sample rate
            Text("Частота дискретизации:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                FilterChip(
                    selected = sampleRate == 44100,
                    onClick = { sampleRate = 44100 },
                    label = { Text("44.1 kHz (Studio)") }
                )
                FilterChip(
                    selected = sampleRate == 16000,
                    onClick = { sampleRate = 16000 },
                    label = { Text("16 kHz (Whisper STT)") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Audio Source
            Text("Источник аудиовхода:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                listOf("MIC" to "Микрофон", "VOICE_RECOGNITION" to "Речь", "CAMCORDER" to "Направленный").forEach { (code, label) ->
                    FilterChip(
                        selected = audioSource == code,
                        onClick = { audioSource = code },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Expected speakers for diarization
            Text("Ожидаемое количество спикеров (Диаризация):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                listOf(2, 3, 4).forEach { count ->
                    FilterChip(
                        selected = speakers == count,
                        onClick = { speakers = count },
                        label = { Text("$count говорящих") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Noise suppression & Echo cancellation toggles
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Шумоподавление (Noise Suppression)", fontSize = 13.sp)
                        Switch(checked = noiseSuppression, onCheckedChange = { noiseSuppression = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Эхоподавление (Acoustic Echo Canceler)", fontSize = 13.sp)
                        Switch(checked = echoCancellation, onCheckedChange = { echoCancellation = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Автоматическая диаризация реплик", fontSize = 13.sp)
                        Switch(checked = autoDiarization, onCheckedChange = { autoDiarization = it })
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val updated = AudioPreconfig(
                            sampleRateHz = sampleRate,
                            audioSource = audioSource,
                            noiseSuppression = noiseSuppression,
                            echoCancellation = echoCancellation,
                            expectedSpeakers = speakers,
                            autoDiarization = autoDiarization
                        )
                        onSaveAndRecord(updated)
                    },
                    modifier = Modifier.testTag("apply_preconfig_record_button")
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Применить и записать")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
