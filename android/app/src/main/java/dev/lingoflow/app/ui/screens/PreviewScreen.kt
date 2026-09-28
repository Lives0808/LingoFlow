package dev.lingoflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lingoflow.app.core.translate.PseudoEngine
import dev.lingoflow.app.core.validate.LengthCheck
import dev.lingoflow.app.data.PaintTextMeasurer
import dev.lingoflow.app.ui.components.LengthBar
import dev.lingoflow.app.ui.theme.Tone
import dev.lingoflow.app.vm.MainViewModel
import dev.lingoflow.app.vm.UiState

/**
 * UI fit screen: every string is rendered inside its widget width using real
 * font metrics, so truncation is visible before the app ships. Toggle the
 * pseudo-locale to test +40% expansion before translating anything.
 */
@Composable
fun PreviewScreen(state: UiState, viewModel: MainViewModel) {
    val session = state.session ?: return
    val locale = state.selectedLocale ?: session.locales.firstOrNull { it != session.sourceLocale } ?: session.sourceLocale
    val context = LocalContext.current
    val measurer = remember { PaintTextMeasurer(context) }

    val rows = session.sourceEntries.entries.map { (key, entry) ->
        val source = entry.value.orEmpty()
        val target = session.valuesFor(locale)[key]
        val display = when {
            state.pseudoPreview -> PseudoEngine.localize(source)
            target.isNullOrBlank() -> source
            else -> target
        }
        val (_, metrics) = LengthCheck.run(
            key = key,
            locale = locale,
            source = source,
            target = display,
            config = session.config.length,
            measurer = measurer,
            entry = entry,
            sourceLocale = session.sourceLocale,
        )
        PreviewRow(key, source, display, target.isNullOrBlank(), metrics, metrics.px > metrics.containerPx)
    }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Switch(checked = state.pseudoPreview, onCheckedChange = { viewModel.togglePseudoPreview() })
                    Text(
                        "  Pseudo-locale (+40%) — reveals layout breakage before translation",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Widths come from your length rules; measurements use the device font at ${session.config.length.fontSize.toInt()}sp.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rows) { row -> PreviewCard(row) }
    }
}

data class PreviewRow(
    val key: String,
    val source: String,
    val display: String,
    val missing: Boolean,
    val metrics: dev.lingoflow.app.core.validate.LengthMetrics,
    val overflow: Boolean,
)

@Composable
private fun PreviewCard(row: PreviewRow) {
    val overflowColor = if (row.overflow) Tone.error else MaterialTheme.colorScheme.outlineVariant
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.key, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Text(
                    "${row.metrics.widget} · ${row.metrics.containerPx.toInt()}px",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                row.source,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier
                    .width(row.metrics.containerPx.dp)
                    .heightIn(min = 28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        width = if (row.overflow) 1.5.dp else 1.dp,
                        color = overflowColor,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .background(
                        if (row.missing) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        else Color.Transparent,
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    row.display,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    maxLines = if (row.overflow) 2 else 3,
                    fontWeight = FontWeight.Normal,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LengthBar(row.metrics.chars.toFloat(), row.metrics.budget.max?.toFloat(), Modifier.weight(1f))
                Text(
                    "  ${row.metrics.chars}/${row.metrics.budget.max ?: "–"} · ×${row.metrics.ratio}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.overflow) Tone.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.missing) {
                Text("missing translation", style = MaterialTheme.typography.labelSmall, color = Tone.warn)
            } else if (row.overflow) {
                Text(
                    "does not fit (${row.metrics.px.toInt()}px in ${row.metrics.containerPx.toInt()}px)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tone.error,
                )
            }
        }
    }
}
