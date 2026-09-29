package dev.lingoflow.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lingoflow.shared.domain.PlatformServices
import dev.lingoflow.shared.ui.screens.CorpusScreen
import dev.lingoflow.shared.ui.screens.DocumentsScreen
import dev.lingoflow.shared.ui.screens.ProjectsScreen
import dev.lingoflow.shared.ui.screens.SettingsScreen
import dev.lingoflow.shared.ui.screens.TermsScreen
import dev.lingoflow.shared.ui.screens.TranslateScreen
import dev.lingoflow.shared.ui.screens.VocabularyScreen

private val Indigo = Color(0xFF4F46E5)
private val IndigoDark = Color(0xFF8B8CF7)
private val Teal = Color(0xFF0E9F9F)

private val darkScheme = darkColorScheme(
    primary = IndigoDark,
    secondary = Teal,
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A21),
    surfaceVariant = Color(0xFF262B35),
    error = Color(0xFFF87171),
)

private val lightScheme = lightColorScheme(
    primary = Indigo,
    secondary = Teal,
    background = Color(0xFFF6F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEEF6),
    error = Color(0xFFDC2626),
)

object Tone {
    val error = Color(0xFFDC2626)
    val warn = Color(0xFFD97706)
    val info = Color(0xFF0891B2)
    val ok = Color(0xFF16A34A)
    val accent = Indigo
}

data class NavItem(val screen: Screen, val label: String, val glyph: String)

private val navItems = listOf(
    NavItem(Screen.PROJECTS, "Projects", "⌂"),
    NavItem(Screen.DOCUMENTS, "Documents", "≡"),
    NavItem(Screen.TRANSLATE, "Translate", "▶"),
    NavItem(Screen.TERMS, "Terms", "★"),
    NavItem(Screen.CORPUS, "Corpus", "⇄"),
    NavItem(Screen.VOCABULARY, "Words", "♥"),
    NavItem(Screen.SETTINGS, "Settings", "⚙"),
)

/**
 * Root composable shared by Android and desktop.
 *
 * Wide windows get a navigation rail (desktop), narrow ones a bottom bar
 * (phone) — the same screens and state either way.
 */
@Composable
fun LingoFlowApp(services: PlatformServices, viewModel: AppViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message, state.error) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.message(null)
        }
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.error(null)
        }
    }

    MaterialTheme(colorScheme = if (state.settings.darkTheme) darkScheme else lightScheme) {
        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    if (services.supportsHover) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                            Spacer(Modifier.height(12.dp))
                            navItems.forEach { item ->
                                NavigationRailItem(
                                    selected = state.screen == item.screen,
                                    onClick = { viewModel.go(item.screen) },
                                    icon = { Text(item.glyph) },
                                    label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.weight(1f)) {
                            when (state.screen) {
                                Screen.PROJECTS -> ProjectsScreen(state, viewModel)
                                Screen.DOCUMENTS -> DocumentsScreen(state, viewModel)
                                Screen.TRANSLATE -> TranslateScreen(state, viewModel, services)
                                Screen.TERMS -> TermsScreen(state, viewModel)
                                Screen.CORPUS -> CorpusScreen(state, viewModel)
                                Screen.VOCABULARY -> VocabularyScreen(state, viewModel)
                                Screen.SETTINGS -> SettingsScreen(state, viewModel)
                            }
                        }
                        if (!services.supportsHover) {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                navItems.take(5).forEach { item ->
                                    NavigationBarItem(
                                        selected = state.screen == item.screen,
                                        onClick = { viewModel.go(item.screen) },
                                        icon = { Text(item.glyph) },
                                        label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.loading || state.progress != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                            Text(state.busyLabel.ifBlank { "Working…" }, style = MaterialTheme.typography.bodyMedium)
                            state.progress?.let { (done, total) ->
                                Text(
                                    "$done / $total segments",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }

    state.ocrText?.let { text ->
        AlertDialog(
            onDismissRequest = { viewModel.clearOcrText() },
            title = { Text("Recognised text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text.take(1200),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Imported as one segment per paragraph.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importText("OCR ${System.currentTimeMillis()}", text)
                    viewModel.clearOcrText()
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { viewModel.clearOcrText() }) { Text("Cancel") } },
        )
    }
}

/** Small labelled chip used across screens. */
@Composable
fun Chip(text: String, tone: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = tone, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyState(title: String, hint: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
