package dev.lingoflow.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.lingoflow.shared.domain.EngineKind
import dev.lingoflow.shared.domain.StylePresets
import dev.lingoflow.shared.ui.AppViewModel
import dev.lingoflow.shared.ui.Chip
import dev.lingoflow.shared.ui.Tone
import dev.lingoflow.shared.ui.UiState

/**
 * Settings: own API key, model, style preset, context window and privacy
 * switches. The key is stored with the platform secret store (Android Keystore
 * on mobile, an owner-only file on the desktop) and never leaves the device
 * except as the caller's own API request.
 */
@Composable
fun SettingsScreen(state: UiState, viewModel: AppViewModel) {
    val settings = state.settings
    var apiKey by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Translation engine", style = MaterialTheme.typography.titleMedium)
                EngineKind.entries.forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = settings.engine == kind,
                            onClick = {
                                viewModel.saveSettings(settings.copy(engine = kind, offlineOnly = kind == EngineKind.OFFLINE))
                                if (kind == EngineKind.DEEPSEEK) {
                                    endpoint = "https://api.deepseek.com/v1"
                                    model = "deepseek-chat"
                                    viewModel.saveSettings(settings.copy(engine = kind, baseUrl = endpoint, model = model, offlineOnly = false))
                                }
                            },
                        )
                        Column {
                            Text(kind.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when (kind) {
                                    EngineKind.OFFLINE -> "Dictionary + your glossary and corpus. Nothing leaves the device."
                                    EngineKind.OPENAI -> "OpenAI or any OpenAI-compatible gateway."
                                    EngineKind.DEEPSEEK -> "DeepSeek official endpoint (your own key)."
                                    EngineKind.CUSTOM -> "Your own HTTP endpoint, OpenAI chat-completions shape."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (settings.engine != EngineKind.OFFLINE) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Model endpoint", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model (e.g. gpt-4o-mini, deepseek-chat, qwen2.5:7b)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(if (state.apiKeySet) "API key (stored — type to replace)" else "API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            viewModel.saveSettings(settings.copy(baseUrl = endpoint, model = model, offlineOnly = false))
                            if (apiKey.isNotBlank()) viewModel.saveApiKey(apiKey.trim())
                            apiKey = ""
                        }) { Text("Save") }
                        AssistChip(
                            onClick = { apiKey = ""; viewModel.saveApiKey("") },
                            label = { Text("Clear stored key") },
                        )
                    }
                    Text(
                        "Every request is made with your key from this device. LingoFlow ships no shared key and no telemetry. Local endpoints such as http://localhost:11434/v1 (Ollama) work too.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Translation style", style = MaterialTheme.typography.titleMedium)
                StylePresets.all.forEach { preset ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = settings.stylePresetId == preset.id,
                            onClick = { viewModel.saveSettings(settings.copy(stylePresetId = preset.id)) },
                        )
                        Column {
                            Text(preset.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                preset.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("context window: ${settings.contextWindow} segments", Tone.accent)
                    Chip("batch: ${settings.maxSegmentsPerRequest} segments")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = settings.contextWindow.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { viewModel.saveSettings(settings.copy(contextWindow = it.coerceIn(0, 50))) }
                        },
                        label = { Text("Context window") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = settings.maxSegmentsPerRequest.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { viewModel.saveSettings(settings.copy(maxSegmentsPerRequest = it.coerceIn(1, 40))) }
                        },
                        label = { Text("Batch size") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Context memory: each request also receives the previous segments (source and translation) plus the closest pairs from your corpus, so names and tone stay consistent across the document.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Privacy & appearance", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = settings.offlineOnly,
                        onCheckedChange = { viewModel.saveSettings(settings.copy(offlineOnly = it)) },
                    )
                    Text("  Block every network engine", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = settings.darkTheme,
                        onCheckedChange = { viewModel.saveSettings(settings.copy(darkTheme = it)) },
                    )
                    Text("  Dark theme", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = settings.explainSegments,
                        onCheckedChange = { viewModel.saveSettings(settings.copy(explainSegments = it)) },
                    )
                    Text("  Keep AI notes for segments", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Workspace location: ${viewModel.workspaceLocation()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Back up or move a project by copying its folder — terms, corpus, vocabulary and documents are plain JSON files.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About LingoFlow", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Kotlin Multiplatform translation workflow · Android, macOS and Windows share one UI and one core. Built for developers and students: code-aware i18n CLI plus a document translation studio.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "No accounts · no telemetry · your key, your data",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}
