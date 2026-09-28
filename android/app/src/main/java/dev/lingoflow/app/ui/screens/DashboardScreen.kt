package dev.lingoflow.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lingoflow.app.ui.components.CoverageBar
import dev.lingoflow.app.ui.theme.Tone
import dev.lingoflow.app.vm.MainViewModel
import dev.lingoflow.app.vm.UiState

/** Overview: coverage per locale, issue counts and the main actions. */
@Composable
fun DashboardScreen(state: UiState, viewModel: MainViewModel) {
    val analysis = state.analysis
    LazyColumn(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Keys", "${analysis?.keys ?: 0}", Modifier.weight(1f))
                StatCard("Errors", "${analysis?.errors ?: 0}", Modifier.weight(1f), Tone.error)
                StatCard("Warnings", "${analysis?.warnings ?: 0}", Modifier.weight(1f), Tone.warn)
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { viewModel.runChecks() },
                    label = { Text("Run checks") },
                    leadingIcon = { Icon(Icons.Default.BugReport, null, Modifier.size(16.dp)) },
                )
                AssistChip(
                    onClick = { viewModel.planTranslations(force = false, locale = null) },
                    label = { Text("Translate missing") },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) },
                )
                AssistChip(
                    onClick = { viewModel.exportReport(dev.lingoflow.app.vm.ReportFormat.HTML) },
                    label = { Text("HTML report") },
                    leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(16.dp)) },
                )
            }
        }
        val warnings = state.session?.warnings.orEmpty()
        if (warnings.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Notes", style = MaterialTheme.typography.titleSmall)
                        warnings.take(3).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { Text("Locales", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
        items(analysis?.validations.orEmpty()) { validation ->
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(validation.locale, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (validation.locale == state.session?.sourceLocale) {
                                Text(
                                    "  source",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "${(validation.coverage * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (validation.coverage >= 1.0) Tone.ok else Tone.warn,
                        )
                    }
                    CoverageBar(validation.coverage)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${validation.translated}/${validation.keyCount} translated · ${validation.missing} missing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${validation.errors}E · ${validation.warnings}W",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            viewModel.selectLocale(validation.locale)
                            viewModel.planTranslations(force = false, locale = validation.locale)
                        }) { Text("Translate") }
                        TextButton(onClick = { viewModel.selectLocale(validation.locale) }) { Text("Open editor") }
                    }
                }
            }
        }
        if (analysis != null && analysis.errors > 0) {
            item {
                Text(
                    "Tip: fix placeholder and ICU errors first — they break the app at runtime.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { LinearProgressIndicator(progress = { 0f }, modifier = Modifier.size(0.dp)) }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color? = null) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint ?: MaterialTheme.colorScheme.onSurface,
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
