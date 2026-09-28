package dev.lingoflow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lingoflow.app.core.model.Severity
import dev.lingoflow.app.ui.theme.Tone

@Composable
fun SeverityDot(severity: Severity, modifier: Modifier = Modifier) {
    val color = when (severity) {
        Severity.ERROR -> Tone.error
        Severity.WARN -> Tone.warn
        Severity.INFO -> Tone.info
    }
    Box(
        modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

@Composable
fun SeverityChip(severity: Severity) {
    val color = when (severity) {
        Severity.ERROR -> Tone.error
        Severity.WARN -> Tone.warn
        Severity.INFO -> Tone.info
    }
    val label = when (severity) {
        Severity.ERROR -> "error"
        Severity.WARN -> "warn"
        Severity.INFO -> "info"
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CodeChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun CoverageBar(coverage: Double, modifier: Modifier = Modifier) {
    Column(modifier) {
        LinearProgressIndicator(
            progress = { coverage.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(99.dp)),
            color = if (coverage >= 1.0) Tone.ok else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
fun LengthBar(used: Float, limit: Float?, modifier: Modifier = Modifier) {
    val ratio = if (limit == null || limit <= 0f) 0f else (used / limit).coerceIn(0f, 1.4f)
    val color = when {
        ratio > 1f -> Tone.error
        ratio > 0.9f -> Tone.warn
        else -> Tone.ok
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(ratio.coerceAtMost(1f))
                .height(6.dp)
                .background(color),
        )
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LoadingOverlay(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

val severityColor: (Severity) -> Color = { severity ->
    when (severity) {
        Severity.ERROR -> Tone.error
        Severity.WARN -> Tone.warn
        Severity.INFO -> Tone.info
    }
}
