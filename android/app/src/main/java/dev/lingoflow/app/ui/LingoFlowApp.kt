package dev.lingoflow.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.lingoflow.app.ui.screens.DashboardScreen
import dev.lingoflow.app.ui.screens.EditorScreen
import dev.lingoflow.app.ui.screens.HomeScreen
import dev.lingoflow.app.ui.screens.IssuesScreen
import dev.lingoflow.app.ui.screens.MemoryScreen
import dev.lingoflow.app.ui.screens.PreviewScreen
import dev.lingoflow.app.ui.screens.SettingsScreen
import dev.lingoflow.app.vm.MainViewModel
import dev.lingoflow.app.vm.UiState
import java.io.File

enum class Destination(val label: String) {
    DASHBOARD("Overview"),
    ISSUES("Issues"),
    EDITOR("Editor"),
    PREVIEW("UI fit"),
    MEMORY("Memory"),
    SETTINGS("Settings"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LingoFlowApp(
    state: UiState,
    viewModel: MainViewModel,
    onPickFolder: () -> Unit,
    onShareReport: (File, String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var destination by remember { mutableStateOf(Destination.DASHBOARD) }

    LaunchedEffect(state.message, state.error) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (state.session == null) {
        HomeScreen(
            recent = viewModel.prefs().recentProjects(),
            onPickFolder = onPickFolder,
            onOpenDemo = { viewModel.openDemo() },
            onOpenRecent = { id, name ->
                val file = File(id)
                if (file.exists()) viewModel.openSource(dev.lingoflow.app.data.LocalFileSource(file, name))
            },
            onForget = { id -> viewModel.prefs().forgetProject(id) },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.projectName ?: "LingoFlow", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.analysis?.keys ?: 0} keys · ${state.session.locales.size} locales · ${viewModel.engineLabel()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.runChecks() }) {
                        Icon(Icons.Default.BugReport, contentDescription = "Run checks")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    Destination.DASHBOARD -> Icons.Default.Dashboard
                                    Destination.ISSUES -> Icons.Default.BugReport
                                    Destination.EDITOR -> Icons.Default.EditNote
                                    Destination.PREVIEW -> Icons.Default.Straighten
                                    Destination.MEMORY -> Icons.Default.Memory
                                    Destination.SETTINGS -> Icons.Default.Settings
                                },
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp)
                    Text(
                        state.busyLabel.ifBlank { "Working…" },
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                when (destination) {
                    Destination.DASHBOARD -> DashboardScreen(state, viewModel)
                    Destination.ISSUES -> IssuesScreen(state, viewModel)
                    Destination.EDITOR -> EditorScreen(state, viewModel)
                    Destination.PREVIEW -> PreviewScreen(state, viewModel)
                    Destination.MEMORY -> MemoryScreen(state, viewModel)
                    Destination.SETTINGS -> SettingsScreen(state, viewModel, onShareReport)
                }
            }
        }
    }

    if (state.plan.isNotEmpty()) {
        val actionable = state.plan.count { it.action != dev.lingoflow.app.core.project.PlannedJob.Action.SKIP }
        AlertDialog(
            onDismissRequest = { viewModel.dismissPlan() },
            title = { Text("Translation plan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$actionable string(s) will be processed", style = MaterialTheme.typography.bodyMedium)
                    val counts = state.plan.groupBy { it.action }.mapValues { it.value.size }
                    counts.entries.sortedByDescending { it.value }.take(6).forEach { (action, count) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(action.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                            Text("$count", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    val byLocale = state.plan.filter { it.action != dev.lingoflow.app.core.project.PlannedJob.Action.SKIP }
                        .groupBy { it.locale }
                        .mapValues { it.value.size }
                    if (byLocale.isNotEmpty()) {
                        Text(
                            byLocale.entries.joinToString(" · ") { "${it.key} ${it.value}" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Frozen entries are never overwritten.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.runPlan() }) { Text("Translate") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissPlan() }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ActionChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun TranslateButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(" Translate", style = MaterialTheme.typography.labelLarge)
    }
}
