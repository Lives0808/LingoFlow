package dev.lingoflow.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import dev.lingoflow.app.ui.components.CodeChip
import dev.lingoflow.app.vm.MainViewModel
import dev.lingoflow.app.vm.UiState

/**
 * Private knowledge base: translation memory, glossary, learned corrections and
 * style rules. "Learn from edits" turns reviewer fixes into reusable rules —
 * the anti-rework loop.
 */
@Composable
fun MemoryScreen(state: UiState, viewModel: MainViewModel) {
    var showAddTerm by remember { mutableStateOf(false) }
    var newSource by remember { mutableStateOf("") }
    var newTarget by remember { mutableStateOf("") }
    val locale = state.selectedLocale ?: state.session?.locales?.firstOrNull { it != state.session.sourceLocale }
    val memoryRows = remember(state.memoryStats, locale) { viewModel.memoryRows(locale) }
    val rules = state.rules

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Entries", "${state.memoryStats?.entries ?: 0}", Modifier.weight(1f))
                Stat("Approved", "${state.memoryStats?.approved ?: 0}", Modifier.weight(1f))
                Stat("Frozen", "${state.memoryStats?.frozen ?: 0}", Modifier.weight(1f))
                Stat("Rules", "${state.memoryStats?.rules ?: 0}", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { viewModel.learn(includeGlossary = false) },
                    label = { Text("Learn from edits") },
                )
                AssistChip(
                    onClick = { viewModel.learn(includeGlossary = true) },
                    label = { Text("Mine glossary") },
                )
                AssistChip(
                    onClick = { showAddTerm = true },
                    label = { Text("Add term") },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) },
                )
            }
        }
        item {
            Text(
                "Frozen entries are never overwritten by translation runs — not even with force.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { Text("Rules", style = MaterialTheme.typography.titleMedium) }
        items(rules, key = { it.id }) { rule ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CodeChip(rule.kind.wire)
                            CodeChip(rule.locale ?: "*")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = rule.enabled, onCheckedChange = { viewModel.toggleRule(rule) })
                            IconButton(onClick = { viewModel.deleteRule(rule) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete rule", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Text(
                        rule.pattern,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        rule.value.take(120),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${rule.origin} · confidence ${(rule.confidence * 100).toInt()}%" + (rule.note?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Text(
                "Translation memory${locale?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(memoryRows, key = { it.id }) { row ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.source.take(60), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.target.take(60), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${row.engine} · ${(row.confidence * 100).toInt()}%" + if (row.approved) " · approved" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.toggleFreeze(row.id, !row.frozen) }) {
                        Icon(
                            if (row.frozen) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (row.frozen) "Unfreeze" else "Freeze",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    if (showAddTerm) {
        AlertDialog(
            onDismissRequest = { showAddTerm = false },
            title = { Text("Add glossary term") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newSource, { newSource = it }, label = { Text("Source term") }, singleLine = true)
                    OutlinedTextField(newTarget, { newTarget = it }, label = { Text("Target term") }, singleLine = true)
                    Text(
                        "Applies to ${locale ?: "every locale"}. Use `*` semantics by leaving the locale selection as is.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newSource.isNotBlank() && newTarget.isNotBlank()) {
                        viewModel.addGlossaryTerm(newSource.trim(), newTarget.trim(), locale)
                    }
                    newSource = ""
                    newTarget = ""
                    showAddTerm = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddTerm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

