package dev.lingoflow.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.lingoflow.shared.domain.PlatformServices
import dev.lingoflow.shared.domain.PolishPresets
import dev.lingoflow.shared.domain.SegmentOps
import dev.lingoflow.shared.domain.doc.BlockKind
import dev.lingoflow.shared.domain.doc.DocFormat
import dev.lingoflow.shared.domain.doc.Exporters
import dev.lingoflow.shared.domain.doc.Segment
import dev.lingoflow.shared.ui.AppViewModel
import dev.lingoflow.shared.ui.Chip
import dev.lingoflow.shared.ui.EmptyState
import dev.lingoflow.shared.ui.PresetHolder
import dev.lingoflow.shared.ui.Tone
import dev.lingoflow.shared.ui.UiState

/**
 * Bilingual view: source and translation side by side, per-segment editing with
 * automatic corpus capture, term highlighting, hover/tap explanations and the
 * export menu.
 */
@Composable
fun TranslateScreen(state: UiState, viewModel: AppViewModel, services: PlatformServices) {
    val document = state.document
    if (document == null) {
        EmptyState("No document opened", "Import a document in the project view, then translate it here.")
        return
    }
    var showExport by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Header(document, state, viewModel, services, onExport = { showExport = true })
        if (state.progress != null) {
            val (done, total) = state.progress
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(document.segments, key = { it.id }) { segment ->
                SegmentCard(segment, state, viewModel, services)
            }
        }
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportRow("Bilingual Markdown") { viewModel.export(Exporters.bilingualMarkdown(document, includeNotes = true)) }
                    ExportRow("Target language Markdown") { viewModel.export(Exporters.targetMarkdown(document)) }
                    ExportRow("Plain text") { viewModel.export(Exporters.plainText(document)) }
                    ExportRow("HTML (print → PDF)") { viewModel.export(Exporters.html(document)) }
                    ExportRow("CSV (id, source, target)") { viewModel.export(Exporters.csv(document)) }
                    ExportRow("JSON") { viewModel.export(Exporters.json(document)) }
                    ExportRow("SRT subtitles") { viewModel.export(Exporters.srt(document)) }
                }
            },
            confirmButton = { TextButton(onClick = { showExport = false }) { Text("Done") } },
        )
    }

    if (state.showImportDialog) {
        var title by remember { mutableStateOf("Pasted text") }
        var text by remember { mutableStateOf("") }
        var format by remember { mutableStateOf(DocFormat.MARKDOWN) }
        AlertDialog(
            onDismissRequest = { viewModel.setShowImportDialog(false) },
            title = { Text("Paste text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Document title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Text (Markdown keeps headings, lists and code fences)") },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { format = DocFormat.MARKDOWN }, label = { Text("Markdown") })
                        AssistChip(onClick = { format = DocFormat.TEXT }, label = { Text("Plain text") })
                        AssistChip(onClick = { format = DocFormat.SUBTITLE }, label = { Text("SRT") })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = { viewModel.importText(title.ifBlank { "Pasted text" }, text, format) },
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { viewModel.setShowImportDialog(false) }) { Text("Cancel") } },
        )
    }

}

@Composable
private fun Header(
    document: dev.lingoflow.shared.domain.doc.Document,
    state: UiState,
    viewModel: AppViewModel,
    services: PlatformServices,
    onExport: () -> Unit,
) {
    var showPolish by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(document.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("${document.sourceLang} → ${document.targetLang}", Tone.accent)
                    Chip("${document.translatedCount}/${document.translatableSegments.size} translated")
                    Chip(document.format.name.lowercase())
                    Chip(if (state.settings.engine == dev.lingoflow.shared.domain.EngineKind.OFFLINE) "offline engine" else state.settings.model)
                }
            }
            TextButton(onClick = { viewModel.backToDocuments() }) { Text("Documents") }
            TextButton(onClick = onExport) { Text("Export") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.translateDocument(polish = false) }) {
                Text("▶")
                Text("  Translate missing")
            }
            TextButton(onClick = { showPolish = true }) { Text("Polish ✨") }
            TextButton(onClick = { viewModel.extractCandidates() }) { Text("Extract terms") }
            if (!services.ocr.available) {
                Text(
                    "OCR unavailable here",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showPolish) {
        AlertDialog(
            onDismissRequest = { showPolish = false },
            title = { Text("Polish mode") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Rewrites every unlocked segment instead of translating it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PolishPresets.all.forEach { preset ->
                        TextButton(onClick = {
                            PresetHolder.polishPreset = preset
                            showPolish = false
                            viewModel.translateDocument(polish = true)
                        }) {
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
                }
            },
            confirmButton = { TextButton(onClick = { showPolish = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SegmentCard(segment: Segment, state: UiState, viewModel: AppViewModel, services: PlatformServices) {
    val structural = SegmentOps.isStructural(segment)
    var editing by remember(segment.id) { mutableStateOf(segment.target.isBlank() && !structural) }
    var draft by remember(segment.id, segment.target) { mutableStateOf(segment.target) }
    var showExplain by remember(segment.id) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                structural -> MaterialTheme.colorScheme.surfaceVariant
                segment.locked -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Chips and actions wrap onto separate lines on narrow phones.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(segment.kind.name.lowercase(), Tone.info)
                if (segment.origin.isNotBlank()) Chip(segment.origin, Tone.ok)
                if (segment.matchedTerms.isNotEmpty()) Chip("${segment.matchedTerms.size} term(s)", Tone.accent)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!structural) {
                    TextButton(onClick = { viewModel.copy(segment.target.ifBlank { segment.source }) }) { Text("Copy") }
                    TextButton(onClick = {
                        viewModel.explainSegment(segment.id)
                        showExplain = true
                    }) { Text("AI note") }
                    TextButton(onClick = {
                        viewModel.addWord(
                            word = segment.source.split(Regex("\\s+")).firstOrNull()?.trim(' ', '.', ',') ?: segment.source,
                            phonetic = "",
                            meaning = segment.target.take(80),
                            example = segment.source.take(160),
                        )
                    }) { Text("★ word") }
                    TextButton(onClick = { viewModel.lockSegment(segment.id, !segment.locked) }) {
                        Text(if (segment.locked) "⊘" else "○")
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        highlighted(segment, state.terms),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Column(Modifier.weight(1f)) {
                    if (structural) {
                        Text(segment.source, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    } else if (editing) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            minLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focus ->
                                    if (!focus.isFocused && draft != segment.target) {
                                        viewModel.editSegment(segment.id, draft)
                                        editing = false
                                    }
                                },
                        )
                    } else {
                        Text(
                            segment.target.ifBlank { "— not translated —" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (segment.target.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                                .padding(8.dp),
                        )
                    }
                }
            }

            if (!structural) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        editing = !editing
                        draft = segment.target
                    }) { Text(if (editing) "Preview" else "Edit") }
                    if (segment.locked) Chip("frozen — automation skips it", Tone.warn)
                    if (state.settings.explainSegments && state.explanationFor == segment.id && state.explanation != null) {
                        Chip("AI note", Tone.accent)
                    }
                }
            }

            if (showExplain && state.explanationFor == segment.id && state.explanation != null) {
                Text(
                    state.explanation.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .padding(10.dp),
                )
            }
        }
    }
}

/** Highlights every glossary term inside the source text. */
@Composable
private fun highlighted(segment: Segment, terms: List<dev.lingoflow.shared.domain.Term>): AnnotatedString {
    val ranges = remember(segment.id, segment.source, terms) { SegmentOps.highlightTerms(segment, terms) }
    if (ranges.isEmpty()) return AnnotatedString(segment.source)
    val accent = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        var cursor = 0
        for ((start, end) in ranges) {
            if (start > cursor) withStyle(SpanStyle(color = base)) { append(segment.source.substring(cursor, start)) }
            withStyle(SpanStyle(color = accent, background = accent.copy(alpha = 0.12f), fontWeight = FontWeight.Medium)) {
                append(segment.source.substring(start, minOf(end, segment.source.length)))
            }
            cursor = minOf(end, segment.source.length)
        }
        if (cursor < segment.source.length) withStyle(SpanStyle(color = base)) { append(segment.source.substring(cursor)) }
    }
}

@Composable
private fun ExportRow(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}
