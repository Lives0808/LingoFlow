package dev.lingoflow.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.lingoflow.jvm.JvmBinaryImporter
import dev.lingoflow.jvm.JvmWorkspaceStore
import dev.lingoflow.jvm.OpenAiCompatibleChat
import dev.lingoflow.jvm.SecretVault
import dev.lingoflow.shared.domain.AppSettings
import dev.lingoflow.shared.domain.ChatEngine
import dev.lingoflow.shared.domain.EngineKind
import dev.lingoflow.shared.domain.ImportedFile
import dev.lingoflow.shared.domain.OcrEngine
import dev.lingoflow.shared.domain.OcrResult
import dev.lingoflow.shared.domain.OfflineChatEngine
import dev.lingoflow.shared.domain.PlatformServices
import dev.lingoflow.shared.domain.WorkspaceStore
import dev.lingoflow.shared.domain.doc.ExportFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/** Bridges ActivityResult launchers (registered by the Activity) to suspend calls. */
class PickerBridge {
    var launchDocument: ((Array<String>) -> Unit)? = null
    var launchImage: (() -> Unit)? = null
    var launchCamera: (() -> Unit)? = null

    private var documentRequest: CompletableDeferred<Uri?>? = null
    private var imageRequest: CompletableDeferred<Uri?>? = null

    suspend fun pickDocument(mimeTypes: Array<String>): Uri? {
        val launcher = launchDocument ?: return null
        val deferred = CompletableDeferred<Uri?>()
        documentRequest = deferred
        launcher(mimeTypes)
        return deferred.await()
    }

    suspend fun pickImage(camera: Boolean): Uri? {
        val launcher = (if (camera) launchCamera else launchImage) ?: return null
        val deferred = CompletableDeferred<Uri?>()
        imageRequest = deferred
        launcher()
        return deferred.await()
    }

    fun documentResult(uri: Uri?) {
        documentRequest?.complete(uri)
        documentRequest = null
    }

    fun imageResult(uri: Uri?) {
        imageRequest?.complete(uri)
        imageRequest = null
    }
}

/** Android services: SAF file access, on-device ML Kit OCR, keystore secrets. */
class AndroidServices(
    private val context: Context,
    private val bridge: PickerBridge,
    private val cropController: CropController = CropController(),
) : PlatformServices {

    override val name: String = "Android"

    override val store: WorkspaceStore = JvmWorkspaceStore(
        root = File(context.filesDir, "workspace"),
        vault = KeystoreVault(context),
    )

    override val ocr: OcrEngine = MlKitOcr()

    override val binaryImporter: dev.lingoflow.shared.domain.doc.BinaryImporter = JvmBinaryImporter()

    override val supportsHover: Boolean = false

    override suspend fun pickDocument(): ImportedFile? {
        val uri = bridge.pickDocument(
            arrayOf(
                "text/*",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/octet-stream",
            ),
        ) ?: return null
        return read(uri)
    }

    override suspend fun pickImage(): ImportedFile? {
        val uri = bridge.pickImage(camera = false) ?: return null
        val file = read(uri) ?: return null
        // Crop & erase before OCR (simple image editor, same module family as LexScore).
        return cropController.edit(file)
    }

    override suspend fun exportFile(file: ExportFile): String = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "exports").apply { mkdirs() }
        val target = File(directory, file.fileName)
        target.writeText(file.content)
        target.absolutePath
    }

    override fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LingoFlow", text))
    }

    override suspend fun createEngine(settings: AppSettings): ChatEngine = when {
        settings.offlineOnly || settings.engine == EngineKind.OFFLINE -> OfflineChatEngine()
        else -> OpenAiCompatibleChat(settings.baseUrl, settings.model, store.apiKey(), settings.engine)
    }

    private suspend fun read(uri: Uri): ImportedFile? = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        val name = queryName(uri) ?: "document"
        val isText = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "txt", "srt", "vtt")
        ImportedFile(name, bytes, if (isText) bytes.decodeToString() else null)
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else uri.lastPathSegment
        }
    }.getOrNull()

    /** ML Kit text recognition: the Chinese model also reads Latin text. */
    inner class MlKitOcr : OcrEngine {
        override val available: Boolean = true
        override val description: String = "ML Kit on-device text recognition (Chinese + Latin)"

        override suspend fun recognize(imageBytes: ByteArray): OcrResult {
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            } ?: return OcrResult("")
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = recognizeWith(recognizer, bitmap)
            val text = if (result.isBlank()) recognizeWith(latinRecognizer, bitmap) else result
            recognizer.close()
            latinRecognizer.close()
            return OcrResult(text, text.lines().filter { it.isNotBlank() })
        }

        private suspend fun recognizeWith(recognizer: com.google.mlkit.vision.text.TextRecognizer, bitmap: Bitmap): String =
            suspendCancellableCoroutine { continuation ->
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { text -> continuation.resume(text.text) }
                    .addOnFailureListener { error -> continuation.resume("") }
            }
    }

    companion object {
        /** Encodes a bitmap as PNG bytes for OCR. */
        fun toPng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }
}

/**
 * API keys are encrypted with an Android Keystore key before they touch
 * SharedPreferences, so a backup of the app data cannot leak them.
 */
class KeystoreVault(private val context: Context) : SecretVault {
    private val prefs = context.getSharedPreferences("lingoflow", Context.MODE_PRIVATE)

    override fun read(): String {
        val stored = prefs.getString("apiKey", "").orEmpty()
        if (stored.isEmpty()) return ""
        if (stored.startsWith("plain:")) return stored.removePrefix("plain:")
        if (!stored.startsWith("v1:")) return stored
        return runCatching {
            val combined = Base64.decode(stored.removePrefix("v1:"), Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val payload = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(payload).decodeToString()
        }.getOrDefault("")
    }

    override fun write(value: String) {
        val encrypted = runCatching {
            if (value.isEmpty()) return@runCatching ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val payload = cipher.doFinal(value.toByteArray())
            "v1:" + Base64.encodeToString(cipher.iv + payload, Base64.NO_WRAP)
        }.getOrDefault(if (value.isEmpty()) "" else "plain:$value")
        prefs.edit().putString("apiKey", encrypted).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry("lingoflow-secrets", null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder("lingoflow-secrets", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
