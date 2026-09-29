package dev.lingoflow.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lingoflow.shared.ui.AppViewModel
import dev.lingoflow.shared.ui.Chip
import dev.lingoflow.shared.ui.EmptyState
import dev.lingoflow.shared.ui.Tone
import dev.lingoflow.shared.ui.UiState
import dev.lingoflow.shared.domain.doc.DocFormat
import dev.lingoflow.shared.domain.doc.SimpleDateLabel

/** Documents inside the opened project. */
@Composable
fun DocumentsScreen(state: UiState, viewModel: AppViewModel) {
    val project = state.project
    if (project == null) {
        EmptyState("No project opened", "Pick a project first — each project keeps its own terms, corpus and documents.")
        return
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("${project.sourceLang} → ${project.targetLang}", Tone.accent)
                    Chip("${state.documents.size} document(s)")
                    Chip("${state.terms.size} term(s)")
                    Chip("${state.corpus.size} corpus pair(s)")
                }
            }
            OutlinedButton(onClick = { viewModel.setShowImportDialog(true) }) { Text("Paste text") }
            TextButton(onClick = { viewModel.pickImport() }) { Text("Import file") }
            TextButton(onClick = { viewModel.runOcr() }) { Text("Photo OCR") }
        }

        LinearProgressIndicator(
            progress = { state.documents.map { if (it.segments == 0) 1f else it.translated.toFloat() / it.segments }.average().toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.documents.isEmpty()) {
            EmptyState(
                "No documents yet",
                "Import Markdown, DOCX, PDF, TXT or SRT — paragraph structure is preserved, and the bilingual export rebuilds the same document. Scanned PDFs need OCR.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.documents, key = { it.id }) { doc ->
                    Card(
                        onClick = { viewModel.openDocument(doc) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(doc.title, style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Chip(doc.format.name.lowercase(), Tone.info)
                                    Chip("${doc.translated}/${doc.segments} segments")
                                    Chip(SimpleDateLabel.format(doc.updatedAt))
                                }
                            }
                            TextButton(onClick = { viewModel.openDocument(doc) }) { Text("Translate") }
                            TextButton(onClick = { viewModel.deleteDocument(doc) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.extractCandidates() }) { Text("Auto-extract terms") }
            TextButton(onClick = { viewModel.go(dev.lingoflow.shared.ui.Screen.TERMS) }) { Text("Open glossary") }
            TextButton(onClick = { viewModel.go(dev.lingoflow.shared.ui.Screen.CORPUS) }) { Text("Open corpus") }
        }
        if (state.candidates.isNotEmpty()) {
            Text(
                "${state.candidates.size} term candidate(s) mined from your documents — review them in the glossary.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.settings.engine == dev.lingoflow.shared.domain.EngineKind.OFFLINE) {
            Text(
                "Engine: built-in offline. Add your own API key in Settings for model-quality translation — DeepSeek and OpenAI-compatible endpoints are supported.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Formats: ${DocFormat.entries.joinToString { it.name.lowercase() }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
