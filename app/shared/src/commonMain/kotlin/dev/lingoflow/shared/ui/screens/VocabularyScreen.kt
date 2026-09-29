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
import dev.lingoflow.shared.domain.VocabEntry
import dev.lingoflow.shared.domain.doc.ExportFile
import dev.lingoflow.shared.domain.doc.Exporters
import dev.lingoflow.shared.ui.AppViewModel
import dev.lingoflow.shared.ui.Chip
import dev.lingoflow.shared.ui.EmptyState
import dev.lingoflow.shared.ui.Tone
import dev.lingoflow.shared.ui.UiState

/**
 * Bilingual vocabulary book. Words highlighted while translating land here with
 * a phonetic hint, a meaning and the sentence they came from; export as CSV or
 * Anki-ready TSV.
 */
@Composable
fun VocabularyScreen(state: UiState, viewModel: AppViewModel) {
    var showAdd by remember { mutableStateOf(false) }
    var word by remember { mutableStateOf("") }
    var phonetic by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    var example by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vocabulary", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${state.vocabulary.size} word(s) · stored locally with the project",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { viewModel.export(csv(state.vocabulary)) }) { Text("Export CSV") }
            TextButton(onClick = { viewModel.export(anki(state.vocabulary)) }) { Text("Export Anki TSV") }
            Button(onClick = { showAdd = true }) { Text("Add word") }
        }

        OutlinedTextField(
            value = example,
            onValueChange = { example = it },
            label = { Text("Hint: use ★ on a segment to capture a word with its sentence") },
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.vocabulary.isEmpty()) {
            EmptyState(
                "No words saved",
                "While translating, press ★ word on a segment to keep the term together with its sentence and translation.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.vocabulary, key = { it.id }) { entry ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.word, style = MaterialTheme.typography.titleSmall)
                                    if (entry.phonetic.isNotBlank()) Chip(entry.phonetic, Tone.info)
                                }
                                if (entry.meaning.isNotBlank()) {
                                    Text(entry.meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                                if (entry.example.isNotBlank()) {
                                    Text(entry.example, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = { viewModel.deleteWord(entry) }) {
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
            title = { Text("Add word") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(word, { word = it }, label = { Text("Word") }, singleLine = true)
                    OutlinedTextField(phonetic, { phonetic = it }, label = { Text("Phonetic (optional)") }, singleLine = true)
                    OutlinedTextField(meaning, { meaning = it }, label = { Text("Meaning / translation") }, singleLine = true)
                    OutlinedTextField(example, { example = it }, label = { Text("Example sentence") }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = word.isNotBlank(),
                    onClick = {
                        viewModel.addWord(word, phonetic, meaning, example)
                        word = ""; phonetic = ""; meaning = ""; example = ""
                        showAdd = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

private fun csv(entries: List<VocabEntry>): ExportFile {
    val builder = StringBuilder("word,phonetic,meaning,example\n")
    entries.forEach { entry ->
        builder.append(quote(entry.word)).append(',')
            .append(quote(entry.phonetic)).append(',')
            .append(quote(entry.meaning)).append(',')
            .append(quote(entry.example)).append('\n')
    }
    return ExportFile("lingoflow-vocabulary.csv", "text/csv", builder.toString())
}

private fun anki(entries: List<VocabEntry>): ExportFile {
    val builder = StringBuilder("#separator:tab\n#html:false\n#columns:word\tphonetic\tmeaning\texample\n")
    entries.forEach { entry ->
        builder.append(entry.word).append('\t')
            .append(entry.phonetic).append('\t')
            .append(entry.meaning).append('\t')
            .append(entry.example.replace('\n', ' ')).append('\n')
    }
    return ExportFile("lingoflow-vocabulary-anki.txt", "text/tab-separated-values", builder.toString())
}

private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
