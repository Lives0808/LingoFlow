package dev.lingoflow.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lingoflow.jvm.DesktopServices
import dev.lingoflow.shared.ui.AppViewModel
import dev.lingoflow.shared.ui.LingoFlowApp
import dev.lingoflow.shared.ui.Screen

/**
 * Desktop entry point.
 *
 * Keyboard shortcuts (⭐ for power users):
 *   ⌘/Ctrl + 1..5   switch screen
 *   ⌘/Ctrl + T      translate the open document
 *   ⌘/Ctrl + E      export the open document
 *   ⌘/Ctrl + O      import a document
 *   ⌘/Ctrl + K      open the glossary
 */
fun main() = application {
    val services = remember { DesktopServices() }
    val viewModel = remember { AppViewModel(services) }
    DisposableEffect(Unit) { onDispose { viewModel.close() } }

    val windowState = rememberWindowState(size = DpSize(1280.dp, 860.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "LingoFlow — privacy-first translation workflow",
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@Window false
            val accel = event.isMetaPressed || event.isCtrlPressed
            if (!accel) return@Window false
            when (event.key) {
                Key.One -> { viewModel.go(Screen.PROJECTS); true }
                Key.Two -> { viewModel.go(Screen.DOCUMENTS); true }
                Key.Three -> { viewModel.go(Screen.TRANSLATE); true }
                Key.Four -> { viewModel.go(Screen.TERMS); true }
                Key.Five -> { viewModel.go(Screen.CORPUS); true }
                Key.T -> { viewModel.translateDocument(); true }
                Key.O -> { viewModel.pickImport(); true }
                Key.K -> { viewModel.go(Screen.TERMS); true }
                Key.E -> { viewModel.extractCandidates(); true }
                else -> false
            }
        },
    ) {
        LingoFlowApp(services, viewModel)
    }
}

