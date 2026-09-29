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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.lingoflow.shared.domain.Term
import dev.lingoflow.shared.ui.AppViewModel
import dev.lingoflow.shared.ui.Chip
import dev.lingoflow.shared.ui.EmptyState
import dev.lingoflow.shared.ui.Tone
import dev.lingoflow.shared.ui.UiState

/**
 * Local glossary: manual terms plus auto-extracted candidates. Terms are forced
 * during translation, so a project keeps its own vocabulary forever.
 */
@Composable
fun TermsScreen(state: UiState, viewModel: AppViewModel) {
    var showManual by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var candidateTargets by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Glossary", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Stored locally with the project · forced during translation and highlighted in the bilingual view",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { viewModel.extractCandidates() }) { Text("Auto-extract from documents") }
            Button(onClick = { showManual = true }) { Text("Add term") }
        }

        if (state.candidates.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Candidates (${state.candidates.size})", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Mined from your own documents by frequency and capitalisation. Give the ones you want a translation.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.candidates.take(12).forEach { candidate ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                                Text(
                                    "×${candidate.count} · ${candidate.kind.name.lowercase()} · score ${(candidate.score * 10).toInt() / 10.0}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedTextField(
                                value = candidateTargets[candidate.text].orEmpty(),
                                onValueChange = { candidateTargets = candidateTargets + (candidate.text to it) },
                                label = { Text("Translation") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = !candidateTargets[candidate.text].isNullOrBlank(),
                                onClick = {
                                    viewModel.acceptCandidate(candidate, candidateTargets[candidate.text].orEmpty())
                                    candidateTargets = candidateTargets - candidate.text
                                },
                            ) { Text("Add") }
                            TextButton(onClick = { viewModel.dismissCandidate(candidate) }) { Text("Skip") }
                        }
                    }
                }
            }
        }

        if (state.terms.isEmpty()) {
            EmptyState("No terms yet", "Add them by hand, or import a document and let LingoFlow mine the recurring names.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.terms, key = { it.id }) { term ->
                    TermRow(term, viewModel)
                }
            }
        }
    }

    if (showManual) {
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("Add glossary term") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(source, { source = it }, label = { Text("Source term") }, singleLine = true)
                    OutlinedTextField(target, { target = it }, label = { Text("Fixed translation") }, singleLine = true)
                    Text(
                        "The translation is forced in every document of this project.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = source.isNotBlank() && target.isNotBlank(),
                    onClick = {
                        viewModel.addTerm(source, target)
                        source = ""
                        target = ""
                        showManual = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TermRow(term: Term, viewModel: AppViewModel) {
    var editing by remember(term.id) { mutableStateOf(false) }
    var draft by remember(term.id, term.target) { mutableStateOf(term.target) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(term.source, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Text("→", style = MaterialTheme.typography.bodySmall)
                    if (editing) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(term.target, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(term.origin, Tone.info)
                    if (term.locale != null) Chip(term.locale!!)
                    if (!term.enabled) Chip("disabled", Tone.warn)
                }
            }
            if (editing) {
                TextButton(onClick = {
                    viewModel.updateTerm(term.copy(target = draft))
                    editing = false
                }) { Text("Save") }
            } else {
                TextButton(onClick = { editing = true }) { Text("Edit") }
            }
            TextButton(onClick = { viewModel.updateTerm(term.copy(enabled = !term.enabled)) }) {
                Text(if (term.enabled) "Disable" else "Enable")
            }
            TextButton(onClick = { viewModel.deleteTerm(term) }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
