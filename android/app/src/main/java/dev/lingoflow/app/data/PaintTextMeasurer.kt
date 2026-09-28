package dev.lingoflow.app.data

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import dev.lingoflow.app.core.model.TextMeasurer

/**
 * Real font metrics from the platform.
 *
 * Values are returned in density-independent pixels (dp) because the length
 * budgets in `lingoflow.config.json` describe design sizes (a 96dp button), and
 * the simulated UI renders them with `Modifier.width(budget.dp)`.
 */
class PaintTextMeasurer(context: Context) : TextMeasurer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cache = HashMap<String, Float>()
    private val density = context.resources.displayMetrics.scaledDensity

    override fun widthPx(text: String, sizeSp: Float, weight: Int): Float {
        val key = "$sizeSp|$weight|$text"
        cache[key]?.let { return it }
        paint.textSize = sizeSp * density
        paint.typeface = if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val width = paint.measureText(text) / density
        if (cache.size > 4096) cache.clear()
        cache[key] = width
        return width
    }
}
