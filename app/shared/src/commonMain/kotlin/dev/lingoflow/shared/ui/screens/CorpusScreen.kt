package dev.lingoflow.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import dev.lingoflow.shared.domain.doc.ExportFile
import dev.lingoflow.shared.domain.doc.Exporters
import dev.lingoflow.shared.ui.AppViewModel
import dev.lingoflow.shared.ui.Chip
import dev.lingoflow.shared.ui.EmptyState
import dev.lingoflow.shared.ui.Tone
import dev.lingoflow.shared.ui.UiState

/**
 * Parallel corpus: every human edit is captured here and reused automatically
 * when a similar sentence appears again (the "越用越贴合" loop).
 */
@Composable
fun CorpusScreen(state: UiState, viewModel: AppViewModel) {
    var showAdd by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }

    val filtered = state.corpus.filter {
        search.isBlank() || it.source.contains(search, true) || it.target.contains(search, true)
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Parallel corpus", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${state.corpus.count { it.confirmed }} reviewed · ${state.corpus.count { !it.confirmed }} machine · reused automatically for similar sentences",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                val csv = StringBuilder("source,target,confirmed,origin\n")
                state.corpus.forEach { entry ->
                    csv.append('"').append(entry.source.replace("\"", "\"\"")).append("\",")
                        .append('"').append(entry.target.replace("\"", "\"\"")).append("\",")
                        .append(entry.confirmed).append(',')
                        .append("project").append('\n')
                }
                viewModel.export(ExportFile("lingoflow-corpus.csv", "text/csv", csv.toString()))
            }) { Text("Export CSV") }
            Button(onClick = { showAdd = true }) { Text("Add pair") }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search corpus") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (filtered.isEmpty()) {
            EmptyState(
                "Corpus is empty",
                "Translate a document, then fix any segment by hand — the reviewed pair lands here and is suggested the next time a similar sentence shows up.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered.take(400), key = { it.id }) { entry ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.source, style = MaterialTheme.typography.bodySmall)
                                Text(entry.target, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Chip(if (entry.confirmed) "reviewed" else "machine", if (entry.confirmed) Tone.ok else Tone.warn)
                                    Chip("${entry.sourceLang} → ${entry.targetLang}")
                                    if (entry.hits > 0) Chip("reused ${entry.hits}×", Tone.accent)
                                }
                            }
                            TextButton(onClick = { viewModel.deleteCorpusEntry(entry) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add corpus pair") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(source, { source = it }, label = { Text("Source sentence") }, minLines = 2)
                    OutlinedTextField(target, { target = it }, label = { Text("Approved translation") }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = source.isNotBlank() && target.isNotBlank(),
                    onClick = {
                        viewModel.addCorpusEntry(source, target)
                        source = ""
                        target = ""
                        showAdd = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}
