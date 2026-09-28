package dev.lingoflow.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.lingoflow.app.core.model.Severity
import dev.lingoflow.app.core.validate.LengthCheck
import dev.lingoflow.app.core.validate.Placeholders
import dev.lingoflow.app.data.PaintTextMeasurer
import dev.lingoflow.app.ui.components.CodeChip
import dev.lingoflow.app.ui.components.LengthBar
import dev.lingoflow.app.ui.components.SeverityChip
import dev.lingoflow.app.vm.MainViewModel
import dev.lingoflow.app.vm.UiState
import androidx.compose.ui.platform.LocalContext

/**
 * The translation editor: live placeholder/ICU/length validation while typing,
 * plus approve & freeze so reviewed wording never gets overwritten.
 */
@Composable
fun EditorScreen(state: UiState, viewModel: MainViewModel) {
    val session = state.session ?: return
    val locale = state.selectedLocale ?: session.locales.firstOrNull { it != session.sourceLocale } ?: session.sourceLocale
    val isSource = locale == session.sourceLocale
    val context = LocalContext.current
    val measurer = remember { PaintTextMeasurer(context) }
    var dirty by remember { mutableStateOf(setOf<String>()) }

    val keys = session.sourceEntries.keys.filter { key ->
        state.search.isBlank() || key.contains(state.search, true) ||
            session.sourceEntries[key]?.value?.contains(state.search, true) == true
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            session.locales.forEach { item ->
                FilterChip(
                    selected = item == locale,
                    onClick = { viewModel.selectLocale(item) },
                    label = { Text(item) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::setSearch,
                label = { Text("Search keys") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.saveLocale(locale); dirty = emptySet() }) {
                Icon(Icons.Default.Save, contentDescription = "Save $locale")
            }
        }
        Text(
            "${keys.size} keys · ${dirty.size} edited · ${if (isSource) "source locale" else "target locale"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(keys) { key ->
                val sourceEntry = session.sourceEntries[key]
                val source = sourceEntry?.value.orEmpty()
                val value = session.valuesFor(locale)[key].orEmpty()
                val issues = remember(key, value, locale) { viewModel.quickIssues(locale, key, value) }
                val (lengthIssues, metrics) = remember(key, value, locale) {
                    LengthCheck.run(
                        key = key,
                        locale = locale,
                        source = source,
                        target = value,
                        config = session.config.length,
                        measurer = measurer,
                        entry = sourceEntry,
                        sourceLocale = session.sourceLocale,
                    )
                }
                val allIssues = issues + lengthIssues
                val placeholders = remember(source) { Placeholders.keys(source) }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (allIssues.any { it.severity == Severity.ERROR }) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CodeChip(key)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (allIssues.isNotEmpty()) SeverityChip(allIssues.first().severity)
                                IconButton(onClick = {
                                    viewModel.approve(locale, key, freeze = true)
                                    dirty = dirty - key
                                }) {
                                    Icon(Icons.Default.Lock, contentDescription = "Approve & freeze", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Text(
                            source,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (placeholders.isNotEmpty()) {
                            Text(
                                "placeholders: ${placeholders.joinToString(" ")}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = value,
                            onValueChange = {
                                viewModel.updateValue(locale, key, it)
                                dirty = dirty + key
                            },
                            label = { Text(if (isSource) "Source text" else "Translation") },
                            modifier = Modifier.fillMaxWidth(),
                            isError = allIssues.any { it.severity == Severity.ERROR },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${metrics.chars}/${metrics.budget.max ?: "–"} ${metrics.budget.unit.name.lowercase()} · ×${metrics.ratio}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Spacer(Modifier.weight(1f))
                            Text(
                                metrics.widget,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LengthBar(metrics.chars.toFloat(), metrics.budget.max?.toFloat())
                        allIssues.take(3).forEach { issue ->
                            Text(
                                "• ${issue.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveHint() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
        Text(" saved", style = MaterialTheme.typography.labelSmall)
    }
}
