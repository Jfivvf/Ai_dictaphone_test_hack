package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LlamaConfig
import com.example.data.model.WhisperConfig

@Composable
fun ModelsSettingsDialog(
    whisperConfig: WhisperConfig,
    llamaConfig: LlamaConfig,
    onSaveWhisper: (WhisperConfig) -> Unit,
    onSaveLlama: (LlamaConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    // Whisper state
    var whisperModel by remember { mutableStateOf(whisperConfig.modelPath) }
    var whisperLang by remember { mutableStateOf(whisperConfig.language) }
    var whisperThreads by remember { mutableIntStateOf(whisperConfig.threads) }
    var whisperBeamSize by remember { mutableIntStateOf(whisperConfig.beamSize) }
    var whisperTemp by remember { mutableFloatStateOf(whisperConfig.temperature) }
    var whisperTranslate by remember { mutableStateOf(whisperConfig.translate) }
    var whisperTimestamps by remember { mutableStateOf(whisperConfig.timestamps) }
    var whisperNoContext by remember { mutableStateOf(whisperConfig.noContext) }

    // Llama state
    var llamaModel by remember { mutableStateOf(llamaConfig.modelPath) }
    var llamaContextSize by remember { mutableIntStateOf(llamaConfig.contextSize) }
    var llamaThreads by remember { mutableIntStateOf(llamaConfig.threads) }
    var llamaMaxTokens by remember { mutableIntStateOf(llamaConfig.maxTokens) }
    var llamaTemp by remember { mutableFloatStateOf(llamaConfig.temperature) }
    var llamaTopP by remember { mutableFloatStateOf(llamaConfig.topP) }
    var llamaTopK by remember { mutableIntStateOf(llamaConfig.topK) }
    var llamaSystemPrompt by remember { mutableStateOf(llamaConfig.systemPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Параметры нейросетей",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_settings_button")
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Whisper STT", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Llama LLM", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (selectedTab == 0) {
                        // WHISPER SETTINGS
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("WhisperBase Bridge активен", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Локальное распознавание речи ru/en", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("Модель Whisper:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        val whisperModels = listOf("whisper-tiny-ru.bin", "whisper-base-ru.bin", "whisper-small-ru.bin")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            whisperModels.forEach { m ->
                                FilterChip(
                                    selected = whisperModel == m,
                                    onClick = { whisperModel = m },
                                    label = { Text(m.replace(".bin", "").replace("whisper-", "")) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Язык распознавания (language):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        val langs = listOf("ru" to "Русский", "en" to "English", "auto" to "Авто")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            langs.forEach { (code, label) ->
                                FilterChip(
                                    selected = whisperLang == code,
                                    onClick = { whisperLang = code },
                                    label = { Text(label) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Потоки CPU (threads: $whisperThreads):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Slider(
                            value = whisperThreads.toFloat(),
                            onValueChange = { whisperThreads = it.toInt() },
                            valueRange = 1f..8f,
                            steps = 6
                        )

                        Text("Ширина Beam Search (beam_size: $whisperBeamSize):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Slider(
                            value = whisperBeamSize.toFloat(),
                            onValueChange = { whisperBeamSize = it.toInt() },
                            valueRange = 1f..10f,
                            steps = 8
                        )

                        Text("Температура (temperature: ${String.format("%.1f", whisperTemp)}):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Slider(
                            value = whisperTemp,
                            onValueChange = { whisperTemp = it },
                            valueRange = 0.0f..1.0f
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Временные метки (timestamps)", fontSize = 13.sp)
                            Switch(checked = whisperTimestamps, onCheckedChange = { whisperTimestamps = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Перевод на английский (translate)", fontSize = 13.sp)
                            Switch(checked = whisperTranslate, onCheckedChange = { whisperTranslate = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Изолировать контекст (no_context)", fontSize = 13.sp)
                            Switch(checked = whisperNoContext, onCheckedChange = { whisperNoContext = it })
                        }
                    } else {
                        // LLAMA SETTINGS
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("LlamaBase Bridge активен", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Генерация выжимок, задач и ответов", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("Модель Llama:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        val llamaModels = listOf("llama-3.2-3b-instruct-q4.gguf", "saiga-llama-3-8b.gguf")
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            llamaModels.forEach { m ->
                                FilterChip(
                                    selected = llamaModel == m,
                                    onClick = { llamaModel = m },
                                    label = { Text(m.replace(".gguf", "")) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Контекстное окно (context_size: $llamaContextSize токенов):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        val contexts = listOf(1024, 2048, 4096)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            contexts.forEach { c ->
                                FilterChip(
                                    selected = llamaContextSize == c,
                                    onClick = { llamaContextSize = c },
                                    label = { Text("$c") }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Макс. новых токенов (max_tokens: $llamaMaxTokens):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        val tokensList = listOf(256, 512, 1024)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tokensList.forEach { t ->
                                FilterChip(
                                    selected = llamaMaxTokens == t,
                                    onClick = { llamaMaxTokens = t },
                                    label = { Text("$t") }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Температура креативности (temp: ${String.format("%.2f", llamaTemp)}):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Slider(
                            value = llamaTemp,
                            onValueChange = { llamaTemp = it },
                            valueRange = 0.0f..1.2f
                        )

                        Text("Top-P выборка (topP: ${String.format("%.2f", llamaTopP)}):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Slider(
                            value = llamaTopP,
                            onValueChange = { llamaTopP = it },
                            valueRange = 0.1f..1.0f
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Системный промпт (system_prompt):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        OutlinedTextField(
                            value = llamaSystemPrompt,
                            onValueChange = { llamaSystemPrompt = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveWhisper(
                        whisperConfig.copy(
                            modelPath = whisperModel,
                            language = whisperLang,
                            threads = whisperThreads,
                            beamSize = whisperBeamSize,
                            temperature = whisperTemp,
                            translate = whisperTranslate,
                            timestamps = whisperTimestamps,
                            noContext = whisperNoContext
                        )
                    )
                    onSaveLlama(
                        llamaConfig.copy(
                            modelPath = llamaModel,
                            contextSize = llamaContextSize,
                            threads = llamaThreads,
                            maxTokens = llamaMaxTokens,
                            temperature = llamaTemp,
                            topP = llamaTopP,
                            topK = llamaTopK,
                            systemPrompt = llamaSystemPrompt
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.testTag("save_models_settings_button")
            ) {
                Text("Сохранить параметры")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
