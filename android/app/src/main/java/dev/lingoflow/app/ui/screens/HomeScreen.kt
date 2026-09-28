package dev.lingoflow.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Landing screen: pick a folder, open the demo, or reopen a recent project. */
@Composable
fun HomeScreen(
    recent: List<Pair<String, String>>,
    onPickFolder: () -> Unit,
    onOpenDemo: () -> Unit,
    onOpenRecent: (String, String) -> Unit,
    onForget: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("LF", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Text("LingoFlow", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Code-aware i18n on your phone: validate placeholders, ICU and CJK typography, check UI length with real font metrics, review and edit translations, and learn rules from your own edits.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Text("  Open project folder")
        }
        OutlinedButton(onClick = onOpenDemo, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PlayCircle, contentDescription = null)
            Text("  Try the built-in demo project")
        }
        Text(
            "Pick the repository folder that contains lingoflow.config.json. Android will ask for folder access — nothing is uploaded.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (recent.isNotEmpty()) {
            Text("Recent projects", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent) { (id, name) ->
                    Card(
                        onClick = { onOpenRecent(id, name) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    id.substringAfterLast('/'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onForget(id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove from recents")
                            }
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Privacy", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "The built-in engine runs entirely on-device. API keys are stored in the Android keystore and never leave the phone. No accounts, no telemetry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
