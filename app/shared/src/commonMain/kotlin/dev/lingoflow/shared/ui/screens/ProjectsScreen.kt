package dev.lingoflow.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lingoflow.shared.ui.AppViewModel
import dev.lingoflow.shared.ui.Chip
import dev.lingoflow.shared.ui.EmptyState
import dev.lingoflow.shared.ui.Tone
import dev.lingoflow.shared.ui.UiState

/** Project list: every translation project keeps its own terms and corpus. */
@Composable
fun ProjectsScreen(state: UiState, viewModel: AppViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var sourceLang by remember { mutableStateOf("en") }
    var targetLang by remember { mutableStateOf("zh-CN") }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LingoFlow", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Privacy-first translation workflow · your terminology, your corpus, your keys — all on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { showCreate = true }) { Text("New project") }
        }

        if (state.projects.isEmpty()) {
            EmptyState(
                "No projects yet",
                "Create a project, then import a Markdown / DOCX / PDF / TXT document. Terms, context memory and the parallel corpus are stored per project — nothing is uploaded.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.projects, key = { it.id }) { project ->
                    Card(
                        onClick = { viewModel.openProject(project) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Chip("${project.sourceLang} → ${project.targetLang}", Tone.accent)
                                    Chip("seed: ${viewModel.seed(project)}")
                                }
                            }
                            TextButton(onClick = { viewModel.openProject(project) }) { Text("Open") }
                            TextButton(onClick = { viewModel.deleteProject(project) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New translation project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Project name (e.g. README, IELTS reading)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = sourceLang,
                            onValueChange = { sourceLang = it },
                            label = { Text("Source language") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = targetLang,
                            onValueChange = { targetLang = it },
                            label = { Text("Target language") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "Examples: \"雅思阅读\" (en → zh-CN), \"OSS README\" (zh-CN → en). Each project gets its own glossary and corpus.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        viewModel.createProject(name.trim(), sourceLang.trim(), targetLang.trim())
                        name = ""
                        showCreate = false
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreate = false }) { Text("Cancel") }
            },
        )
    }
}

private fun AppViewModel.seed(project: dev.lingoflow.shared.domain.TranslationProject): String =
    "local only"
