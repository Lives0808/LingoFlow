package dev.lingoflow.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.lingoflow.app.core.model.Issue
import dev.lingoflow.app.core.model.Severity
import dev.lingoflow.app.ui.components.CodeChip
import dev.lingoflow.app.ui.components.SeverityChip
import dev.lingoflow.app.vm.MainViewModel
import dev.lingoflow.app.vm.UiState

/** All validation findings with severity, locale and text filters. */
@Composable
fun IssuesScreen(state: UiState, viewModel: MainViewModel) {
    val locales = state.session?.locales.orEmpty()
    val all = state.analysis?.issues.orEmpty()
    val filtered = all.filter { issue ->
        (state.severityFilter == null || issue.severity == state.severityFilter) &&
            (state.selectedLocale == null || issue.locale == state.selectedLocale) &&
            (state.search.isBlank() || issue.key.contains(state.search, true) || issue.message.contains(state.search, true))
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.search,
            onValueChange = viewModel::setSearch,
            label = { Text("Search key or message") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.severityFilter == null,
                onClick = { viewModel.setSeverityFilter(null) },
                label = { Text("All ${all.size}") },
            )
            FilterChip(
                selected = state.severityFilter == Severity.ERROR,
                onClick = { viewModel.setSeverityFilter(Severity.ERROR) },
                label = { Text("Errors ${all.count { it.severity == Severity.ERROR }}") },
            )
            FilterChip(
                selected = state.severityFilter == Severity.WARN,
                onClick = { viewModel.setSeverityFilter(Severity.WARN) },
                label = { Text("Warnings ${all.count { it.severity == Severity.WARN }}") },
            )
        }
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.selectedLocale == null,
                        onClick = { viewModel.selectLocale(state.session?.sourceLocale ?: "") },
                        label = { Text("all locales") },
                    )
                    locales.take(4).forEach { locale ->
                        FilterChip(
                            selected = state.selectedLocale == locale,
                            onClick = { viewModel.selectLocale(locale) },
                            label = { Text(locale) },
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Text("No issues found 🎉", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(filtered) { issue -> IssueCard(issue) }
        }
    }
}

@Composable
private fun IssueCard(issue: Issue) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SeverityChip(issue.severity)
                    CodeChip(issue.code)
                }
                Text(issue.locale, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(issue.message, style = MaterialTheme.typography.bodyMedium)
            Text(
                issue.key,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            issue.target?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
