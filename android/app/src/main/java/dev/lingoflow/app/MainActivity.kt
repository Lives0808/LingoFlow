package dev.lingoflow.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lingoflow.app.data.SafFileSource
import dev.lingoflow.app.ui.LingoFlowApp
import dev.lingoflow.app.ui.theme.LingoFlowTheme
import dev.lingoflow.app.vm.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Persist read/write access across restarts so the project reopens quickly.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Project"
        viewModel.openSource(SafFileSource(applicationContext, uri, name.ifBlank { "Project" }))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LingoFlowTheme {
                Surface {
                    LingoFlowApp(
                        state = state,
                        viewModel = viewModel,
                        onPickFolder = { openTree.launch(null) },
                        onShareReport = { file, mime ->
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = mime
                                putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                                    this@MainActivity,
                                    "${packageName}.fileprovider",
                                    file,
                                ))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(share, "Share LingoFlow report"))
                        },
                    )
                }
            }
        }
    }
}
