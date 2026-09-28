package dev.lingoflow.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.unit.dp
import dev.lingoflow.app.vm.MainViewModel
import dev.lingoflow.app.vm.ReportFormat
import dev.lingoflow.app.vm.UiState
import java.io.File

/** Engine configuration, privacy switches and report export. */
@Composable
fun SettingsScreen(state: UiState, viewModel: MainViewModel, onShareReport: (File, String) -> Unit) {
    val prefs = viewModel.prefs()
    var engine by remember { mutableStateOf(prefs.engine) }
    var offlineOnly by remember { mutableStateOf(prefs.offlineOnly) }
    var endpoint by remember { mutableStateOf(prefs.endpointUrl) }
    var model by remember { mutableStateOf(prefs.model) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var status by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Translation engine", style = MaterialTheme.typography.titleMedium)
                    EngineOption("offline", "Built-in offline", "Glossary + memory + seed dictionary. Nothing leaves the phone.", engine) {
                        engine = it
                        prefs.engine = it
                    }
                    EngineOption("pseudo", "Pseudo-locale", "Accented +40% text for layout testing.", engine) {
                        engine = it
                        prefs.engine = it
                    }
                    EngineOption("openai", "OpenAI-compatible", "Cloud or a local server (Ollama, LM Studio, vLLM).", engine) {
                        engine = it
                        prefs.engine = it
                    }
                    EngineOption("libretranslate", "LibreTranslate", "Self-hosted machine translation.", engine) {
                        engine = it
                        prefs.engine = it
                    }
                    EngineOption("custom", "Custom HTTP", "Template driven request to your own service.", engine) {
                        engine = it
                        prefs.engine = it
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = offlineOnly, onCheckedChange = {
                            offlineOnly = it
                            prefs.offlineOnly = it
                        })
                        Text("  Block every network engine", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "When enabled, remote engines are refused outright — the privacy guarantee is enforced in code, not in a settings screen.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!offlineOnly) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Endpoint", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it; prefs.endpointUrl = it },
                            label = { Text("Base URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (engine == "openai") {
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it; prefs.model = it },
                                label = { Text("Model") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it; prefs.apiKey = it },
                            label = { Text("API key (keystore encrypted)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Tip: point the OpenAI-compatible engine at http://localhost:11434/v1 for a fully local model.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reports", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {
                            val file = viewModel.exportReport(ReportFormat.HTML)
                            status = if (file != null) "HTML report written" else "Nothing to export"
                        }, label = { Text("HTML") })
                        AssistChip(onClick = {
                            val file = viewModel.exportReport(ReportFormat.MARKDOWN)
                            status = if (file != null) "Markdown report written" else "Nothing to export"
                        }, label = { Text("Markdown") })
                        AssistChip(onClick = {
                            val file = viewModel.exportReport(ReportFormat.JSON)
                            status = if (file != null) "JSON report written" else "Nothing to export"
                        }, label = { Text("JSON") })
                        AssistChip(onClick = {
                            val file = viewModel.exportReport(ReportFormat.HTML)
                            if (file != null) onShareReport(file, "text/html") else status = "Nothing to export"
                        }, label = { Text("Share") })
                    }
                    if (status.isNotEmpty()) {
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "LingoFlow for Android — the mobile companion to the LingoFlow CLI. Same config, same formats (JSON, YAML, .properties, gettext PO, ARB, .strings, CSV, TS/JS), same validation rules.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "No telemetry, no accounts, no network calls unless you configure an engine. The translation memory and rules live in the app's private storage.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = { viewModel.closeProject() }, label = { Text("Close project") })
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineOption(id: String, title: String, description: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == id, onClick = { onSelect(id) })
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
