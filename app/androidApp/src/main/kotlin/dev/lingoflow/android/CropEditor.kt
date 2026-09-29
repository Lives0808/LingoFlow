package dev.lingoflow.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.lingoflow.shared.domain.ImportedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pending crop/erase request handed from the shared OCR flow to the Android UI. */
data class CropRequest(val image: ImportedFile, val deferred: CompletableDeferred<ImportedFile?>)

class CropController {
    private val _request = MutableStateFlow<CropRequest?>(null)
    val request: StateFlow<CropRequest?> = _request.asStateFlow()

    suspend fun edit(image: ImportedFile): ImportedFile? {
        val deferred = CompletableDeferred<ImportedFile?>()
        _request.value = CropRequest(image, deferred)
        val result = try {
            deferred.await()
        } finally {
            _request.value = null
        }
        return result
    }

    fun complete(edited: ImportedFile?) {
        _request.value?.deferred?.complete(edited)
        _request.value = null
    }
}

private data class EraseStroke(val points: List<Offset>, val width: Float)

/**
 * Simple image editor for OCR: crop with sliders and erase with a brush
 * (paint white), then hand the cleaned PNG back to the recogniser. Deliberately
 * small — the heavy lifting stays in the OCR engine.
 */
@Composable
fun CropEditor(request: CropRequest, onDone: (ImportedFile?) -> Unit) {
    val bitmap = remember(request.image) {
        BitmapFactory.decodeByteArray(request.image.bytes, 0, request.image.bytes.size)
    }
    if (bitmap == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Cannot decode this image", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { onDone(null) }) { Text("Back") }
        }
        return
    }

    var left by remember { mutableStateOf(0f) }
    var top by remember { mutableStateOf(0f) }
    var right by remember { mutableStateOf(1f) }
    var bottom by remember { mutableStateOf(1f) }
    var eraseMode by remember { mutableStateOf(false) }
    var brushSize by remember { mutableStateOf(24f) }
    var strokes by remember { mutableStateOf(listOf<EraseStroke>()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Crop & erase before OCR", style = MaterialTheme.typography.titleMedium)
        Text(
            "Erase stamps, watermarks or noise; crop away headers and page numbers. The result is recognised on-device.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { canvasSize = it }
                .pointerInput(eraseMode) {
                    if (!eraseMode) return@pointerInput
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDragEnd = {
                            if (current.isNotEmpty()) strokes = strokes + EraseStroke(current, brushSize)
                            current = emptyList()
                        },
                    ) { change, _ ->
                        current = current + change.position
                    }
                },
        ) {
            if (canvasSize.width > 0) {
                val scale = minOf(
                    canvasSize.width.toFloat() / bitmap.width,
                    canvasSize.height.toFloat() / bitmap.height,
                )
                val drawWidth = bitmap.width * scale
                val drawHeight = bitmap.height * scale
                val offsetX = (canvasSize.width - drawWidth) / 2f
                val offsetY = (canvasSize.height - drawHeight) / 2f

                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                ComposeCanvas(Modifier.fillMaxSize()) {
                    // Crop frame
                    val rectLeft = offsetX + left * drawWidth
                    val rectTop = offsetY + top * drawHeight
                    val rectRight = offsetX + right * drawWidth
                    val rectBottom = offsetY + bottom * drawHeight
                    drawRect(
                        color = ComposeColor(0xAA4F46E5),
                        topLeft = Offset(rectLeft, rectTop),
                        size = androidx.compose.ui.geometry.Size(rectRight - rectLeft, rectBottom - rectTop),
                        style = DrawStroke(width = 3f),
                    )
                    // Erase strokes
                    (strokes + listOfNotNull(current.takeIf { it.isNotEmpty() }?.let { EraseStroke(it, brushSize) })).forEach { stroke ->
                        stroke.points.zipWithNext().forEach { (from, to) ->
                            drawLine(
                                color = ComposeColor.White,
                                start = from,
                                end = to,
                                strokeWidth = stroke.width,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                        }
                        stroke.points.forEach { point ->
                            drawCircle(ComposeColor.White, radius = stroke.width / 2f, center = point)
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = eraseMode, onCheckedChange = { eraseMode = it })
            Text(if (eraseMode) "Erase brush" else "Crop mode", style = MaterialTheme.typography.bodyMedium)
            if (eraseMode) {
                Text("size", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = brushSize,
                    onValueChange = { brushSize = it },
                    valueRange = 8f..80f,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { strokes = emptyList() }) { Text("Clear brush") }
            }
        }

        if (!eraseMode) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CropSlider("Left", left) { left = it.coerceIn(0f, right - 0.05f) }
                CropSlider("Top", top) { top = it.coerceIn(0f, bottom - 0.05f) }
                CropSlider("Right", right) { right = it.coerceIn(left + 0.05f, 1f) }
                CropSlider("Bottom", bottom) { bottom = it.coerceIn(top + 0.05f, 1f) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val edited = applyEdits(bitmap, left, top, right, bottom, strokes, canvasSize, eraseMode)
                onDone(ImportedFile(request.image.name, AndroidServices.toPng(edited)))
            }) { Text("Use image") }
            OutlinedButton(onClick = { onDone(request.image) }) { Text("Skip editing") }
            TextButton(onClick = { onDone(null) }) { Text("Cancel") }
        }
    }
}

@Composable
private fun CropSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
        Slider(value = value, onValueChange = onChange, modifier = Modifier.weight(1f))
        Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
    }
}

/** Crops, paints the erase strokes white and returns a fresh bitmap. */
private fun applyEdits(
    source: Bitmap,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    strokes: List<EraseStroke>,
    canvasSize: IntSize,
    eraseMode: Boolean,
): Bitmap {
    val crop = Rect(
        (left * source.width).toInt().coerceIn(0, source.width - 1),
        (top * source.height).toInt().coerceIn(0, source.height - 1),
        (right * source.width).toInt().coerceIn(1, source.width),
        (bottom * source.height).toInt().coerceIn(1, source.height),
    )
    val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
    if (strokes.isEmpty() || canvasSize.width == 0) return cropped

    val scale = minOf(
        canvasSize.width.toFloat() / source.width,
        canvasSize.height.toFloat() / source.height,
    )
    val offsetX = (canvasSize.width - source.width * scale) / 2f
    val offsetY = (canvasSize.height - source.height * scale) / 2f
    val result = cropped.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (stroke in strokes) {
        paint.strokeWidth = stroke.width / scale
        val points = stroke.points.map { point ->
            Offset((point.x - offsetX) / scale + crop.left, (point.y - offsetY) / scale + crop.top)
        }
        if (points.size == 1) {
            canvas.drawCircle(points[0].x, points[0].y, paint.strokeWidth / 2f, paint.apply { style = Paint.Style.FILL })
            paint.style = Paint.Style.STROKE
        } else {
            for (index in 1 until points.size) {
                canvas.drawLine(points[index - 1].x, points[index - 1].y, points[index].x, points[index].y, paint)
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") eraseMode
    return result
}
