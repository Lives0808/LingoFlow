package dev.lingoflow.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import java.io.File
import dev.lingoflow.shared.ui.AppViewModel
import dev.lingoflow.shared.ui.LingoFlowApp

class MainActivity : ComponentActivity() {

    private val bridge = PickerBridge()
    private val cropController = CropController()
    private lateinit var services: AndroidServices
    private var pendingCameraUri: Uri? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        bridge.documentResult(uri)
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        bridge.imageResult(uri)
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        bridge.imageResult(if (success) pendingCameraUri else null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        services = AndroidServices(applicationContext, bridge, cropController)
        bridge.launchDocument = { mimeTypes -> openDocument.launch(mimeTypes) }
        bridge.launchImage = {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        bridge.launchCamera = {
            val directory = File(cacheDir, "camera").apply { mkdirs() }
            val file = File(directory, "photo-${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingCameraUri = uri
            takePicture.launch(uri)
        }

        setContent {
            val viewModel = remember { AppViewModel(services) }
            DisposableEffect(Unit) { onDispose { viewModel.close() } }
            val cropRequest by cropController.request.collectAsState()

            Surface(Modifier.fillMaxSize()) {
                if (cropRequest != null) {
                    CropEditor(cropRequest!!) { edited -> cropController.complete(edited) }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        LingoFlowApp(services, viewModel)
                    }
                }
            }
        }
    }
}
