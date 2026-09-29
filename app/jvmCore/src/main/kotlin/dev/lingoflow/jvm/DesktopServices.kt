package dev.lingoflow.jvm

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Desktop platform services (macOS / Windows / Linux).
 *
 * File pickers use AWT so no extra dependency is needed; exports land in
 * `~/Downloads/lingoflow` (or a folder the user picks) and the clipboard uses
 * the system clipboard.
 */
class DesktopServices(
    private val rootDir: File = defaultRoot(),
) : PlatformServices {

    override val name: String = "Desktop"
    override val store: WorkspaceStore = JvmWorkspaceStore(rootDir)
    override val ocr: OcrEngine = TesseractOcr()
    override val binaryImporter: dev.lingoflow.shared.domain.doc.BinaryImporter = JvmBinaryImporter()
    override val supportsHover: Boolean = true

    override suspend fun pickDocument(): ImportedFile? = chooseFile(
        title = "Import document (Markdown, DOCX, PDF, TXT, SRT)",
        extensions = listOf("md", "markdown", "txt", "docx", "pdf", "srt", "vtt"),
    )

    override suspend fun pickImage(): ImportedFile? = chooseFile(
        title = "Choose an image for OCR",
        extensions = listOf("png", "jpg", "jpeg", "webp", "bmp"),
    )

    private suspend fun chooseFile(title: String, extensions: List<String>): ImportedFile? = withContext(Dispatchers.Default) {
        var result: ImportedFile? = null
        EventQueue.invokeAndWait {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(".$it") } }
            dialog.isVisible = true
            val directory = dialog.directory
            val file = dialog.file
            if (directory != null && file != null) {
                val target = File(directory, file)
                val bytes = runCatching { target.readBytes() }.getOrNull()
                if (bytes != null) {
                    val text = if (extensions.contains(target.extension.lowercase()) && target.extension.lowercase() in setOf("md", "markdown", "txt", "srt", "vtt")) {
                        bytes.decodeToString()
                    } else {
                        null
                    }
                    result = ImportedFile(target.name, bytes, text)
                }
            }
        }
        result
    }

    override suspend fun exportFile(file: ExportFile): String = withContext(Dispatchers.Default) {
        val target = File(System.getProperty("user.home"), "Downloads/lingoflow").apply { mkdirs() }
        val output = File(target, file.fileName)
        output.writeText(file.content)
        "Saved to ${output.absolutePath}"
    }

    override fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    override fun clipboardText(): String = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: ""
    }.getOrDefault("")

    override suspend fun createEngine(settings: AppSettings): ChatEngine = when {
        settings.offlineOnly || settings.engine == EngineKind.OFFLINE -> OfflineChatEngine()
        else -> OpenAiCompatibleChat(settings.baseUrl, settings.model, store.apiKey(), settings.engine)
    }

    companion object {
        fun defaultRoot(): File {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            return when {
                os.contains("win") -> File(System.getenv("APPDATA") ?: home, "LingoFlow")
                os.contains("mac") -> File(home, "Library/Application Support/LingoFlow")
                else -> File(home, ".local/share/lingoflow")
            }
        }
    }
}

/**
 * Optional OCR on the desktop: uses the `tesseract` CLI when it is installed,
 * otherwise reports clearly that OCR is unavailable (no hidden downloads).
 */
class TesseractOcr(
    private val executable: String = System.getenv("LINGOFLOW_TESSERACT") ?: "tesseract",
) : OcrEngine {

    private val detected: String? = runCatching {
        val process = ProcessBuilder(executable, "--version").redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor()
        executable
    }.getOrNull()

    override val available: Boolean get() = detected != null

    override val description: String
        get() = if (available) {
            "Tesseract found at $executable"
        } else {
            "OCR needs Tesseract on this platform: install it (brew install tesseract tesseract-lang) or use the Android app for on-device OCR."
        }

    override suspend fun recognize(imageBytes: ByteArray): OcrResult = withContext(Dispatchers.IO) {
        val tool = detected ?: return@withContext OcrResult("")
        val temp = File.createTempFile("lingoflow-ocr", ".png")
        temp.writeBytes(imageBytes)
        try {
            val process = ProcessBuilder(tool, temp.absolutePath, "stdout", "-l", "eng+chi_sim+jpn", "--psm", "3")
                .redirectErrorStream(false)
                .start()
            val text = process.inputStream.readBytes().decodeToString()
            process.waitFor()
            OcrResult(text.trim(), text.lines().filter { it.isNotBlank() })
        } finally {
            temp.delete()
        }
    }
}
