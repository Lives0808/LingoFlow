package dev.lingoflow.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Indigo = Color(0xFF4F46E5)
private val IndigoLight = Color(0xFF8B8CF7)
private val Teal = Color(0xFF0E9F9F)

private val lightScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E1FF),
    onPrimaryContainer = Color(0xFF16165B),
    secondary = Teal,
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEEF6),
    error = Color(0xFFDC2626),
)

private val darkScheme = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF14143D),
    primaryContainer = Color(0xFF2B2B6B),
    onPrimaryContainer = Color(0xFFE0E1FF),
    secondary = Color(0xFF5FD3D3),
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A21),
    surfaceVariant = Color(0xFF262B35),
    error = Color(0xFFF87171),
)

/** Severity colours shared by every screen. */
object Tone {
    val error = Color(0xFFDC2626)
    val warn = Color(0xFFD97706)
    val info = Color(0xFF0891B2)
    val ok = Color(0xFF16A34A)
}

@Composable
fun LingoFlowTheme(useDarkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (useDarkTheme) darkScheme else lightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
