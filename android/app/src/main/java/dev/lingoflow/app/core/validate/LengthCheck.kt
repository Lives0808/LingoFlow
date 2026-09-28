package dev.lingoflow.app.core.validate

import dev.lingoflow.app.core.catalog.Keys
import dev.lingoflow.app.core.config.LengthConfig
import dev.lingoflow.app.core.config.LengthDefaults
import dev.lingoflow.app.core.model.CatalogEntry
import dev.lingoflow.app.core.model.Issue
import dev.lingoflow.app.core.model.LengthBudget
import dev.lingoflow.app.core.model.Severity
import dev.lingoflow.app.core.model.TextMeasurer
import dev.lingoflow.app.core.model.Unit3
import dev.lingoflow.app.core.model.Widgets
import kotlin.math.roundToInt

/** Length + UI fit metrics for a single key. */
data class LengthMetrics(
    val chars: Int,
    val px: Float,
    val sourceChars: Int,
    val sourcePx: Float,
    val ratio: Double,
    val containerPx: Float,
    val widget: String,
    val lines: Int,
    val budget: LengthBudget,
)

object LengthCheck {

    fun resolveBudget(config: LengthConfig, key: String, entry: CatalogEntry?): LengthBudget {
        entry?.maxLength?.takeIf { it > 0 }?.let { max ->
            val widget = Widgets.of(config.defaults.widget)
            return LengthBudget(
                min = config.defaults.min,
                max = max,
                hard = config.defaults.hard,
                unit = Unit3.CHARS,
                widget = widget.name,
                containerPx = widget.px,
                origin = "comment",
            )
        }
        config.rules.firstOrNull { Keys.matches(it.match, key) }?.let { rule ->
            val widget = Widgets.of(rule.widget ?: config.defaults.widget)
            return LengthBudget(
                min = rule.min ?: config.defaults.min,
                max = rule.max ?: config.defaults.max,
                hard = rule.hard ?: config.defaults.hard,
                unit = if (config.unit == "px") Unit3.PX else Unit3.CHARS,
                widget = widget.name,
                containerPx = widget.px,
                origin = "rule:${rule.match}",
            )
        }
        val defaults: LengthDefaults = config.defaults
        val widget = Widgets.of(defaults.widget)
        return LengthBudget(
            min = defaults.min,
            max = defaults.max,
            hard = defaults.hard,
            unit = if (config.unit == "px") Unit3.PX else Unit3.CHARS,
            widget = widget.name,
            containerPx = widget.px,
            origin = "default",
        )
    }

    fun expansionLimit(config: LengthConfig, locale: String): Double =
        config.expansion[locale]
            ?: config.expansion[locale.lowercase().substringBefore('-').substringBefore('_')]
            ?: 1.3

    fun run(
        key: String,
        locale: String,
        source: String,
        target: String,
        config: LengthConfig,
        measurer: TextMeasurer,
        entry: CatalogEntry?,
        sourceLocale: String,
    ): Pair<List<Issue>, LengthMetrics> {
        val budget = resolveBudget(config, key, entry)
        val targetPx = measurer.widthPx(target, config.fontSize, config.fontWeight)
        val sourcePx = measurer.widthPx(source, config.fontSize, config.fontWeight)
        val chars = target.codePointCount(0, target.length)
        val sourceChars = source.codePointCount(0, source.length)
        val ratio = if (sourcePx <= 0f) 1.0 else targetPx.toDouble() / sourcePx
        val lines = estimateLines(target, config, budget.containerPx, measurer)
        val metrics = LengthMetrics(
            chars = chars,
            px = targetPx,
            sourceChars = sourceChars,
            sourcePx = sourcePx,
            ratio = (ratio * 100).roundToInt() / 100.0,
            containerPx = budget.containerPx,
            widget = budget.widget,
            lines = lines,
            budget = budget,
        )
        if (!config.enabled) return emptyList<Issue>() to metrics

        val issues = mutableListOf<Issue>()
        val used = if (budget.unit == Unit3.PX) targetPx else chars.toFloat()
        val limit = budget.max?.toFloat()
        if (limit != null && used > limit) {
            issues += Issue(
                code = "length.tooLong",
                severity = if (budget.hard) Severity.ERROR else Severity.WARN,
                locale = locale,
                key = key,
                message = "Too long for ${budget.widget}: ${used.roundToInt()} ${if (budget.unit == Unit3.PX) "px" else "chars"} > ${budget.max}",
                target = target,
                fixable = true,
            )
        }
        if (budget.min > 0 && used < budget.min) {
            issues += Issue(
                "length.tooShort",
                Severity.INFO,
                locale,
                key,
                "Shorter than expected: ${used.roundToInt()} < ${budget.min}",
                target = target,
            )
        }
        val expansionLimit = expansionLimit(config, locale)
        if (locale != sourceLocale && sourcePx > 0f && ratio > expansionLimit) {
            issues += Issue(
                "length.expansion",
                Severity.INFO,
                locale,
                key,
                "Expands ${((ratio - 1) * 100).roundToInt()}% vs source (budget ${((expansionLimit - 1) * 100).roundToInt()}%)",
                target = target,
            )
        }
        if (lines > 1 && sourceChars > 0 && chars > sourceChars) {
            issues += Issue(
                "length.wrap",
                Severity.INFO,
                locale,
                key,
                "Likely wraps to $lines lines in a ${budget.widget} (${budget.containerPx.roundToInt()}px)",
                target = target,
            )
        }
        return issues to metrics
    }

    /** Lines the string occupies inside a container of [containerPx]. */
    fun estimateLines(text: String, config: LengthConfig, containerPx: Float, measurer: TextMeasurer): Int {
        if (containerPx <= 0f) return 1
        return text.split('\n').sumOf { segment ->
            val width = measurer.widthPx(segment, config.fontSize, config.fontWeight)
            maxOf(1, kotlin.math.ceil(width / containerPx).toInt())
        }
    }
}
