package dev.lingoflow.shared.core.report

import dev.lingoflow.shared.core.model.Issue
import dev.lingoflow.shared.core.model.Severity
import dev.lingoflow.shared.core.project.ProjectSession
import dev.lingoflow.shared.core.validate.LocaleValidation
import dev.lingoflow.shared.core.validate.Placeholders
import dev.lingoflow.shared.core.util.SimpleDate

/** Renders the on-device report in the same shape as the CLI (`report.html`). */
object Report {

    data class Snapshot(
        val session: ProjectSession,
        val validations: List<LocaleValidation>,
        val issues: List<Issue>,
        val generatedAt: Long = System.currentTimeMillis(),
        val engine: String = "offline",
    )

    fun json(snapshot: Snapshot): String {
        val builder = StringBuilder()
        builder.append("{\n")
        builder.append("  \"product\": \"LingoFlow\",\n")
        builder.append("  \"platform\": \"android\",\n")
        builder.append("  \"generatedAt\": \"${isoDate(snapshot.generatedAt)}\",\n")
        builder.append("  \"sourceLocale\": \"${escape(snapshot.session.sourceLocale)}\",\n")
        builder.append("  \"engine\": \"${escape(snapshot.engine)}\",\n")
        builder.append("  \"keys\": ${snapshot.session.sourceEntries.size},\n")
        builder.append("  \"locales\": [\n")
        builder.append(
            snapshot.validations.joinToString(",\n") { validation ->
                """    {"locale":"${escape(validation.locale)}","coverage":${formatDecimal(validation.coverage, 4)},"keys":${validation.keyCount},"translated":${validation.translated},"missing":${validation.missing},"empty":${validation.empty},"errors":${validation.errors},"warnings":${validation.warnings}}"""
            },
        )
        builder.append("\n  ],\n  \"issues\": [\n")
        builder.append(
            snapshot.issues.joinToString(",\n") { issue ->
                """    {"severity":"${issue.severity.name.lowercase()}","code":"${escape(issue.code)}","locale":"${escape(issue.locale)}","key":"${escape(issue.key)}","message":"${escape(issue.message)}"}"""
            },
        )
        builder.append("\n  ]\n}\n")
        return builder.toString()
    }

    fun markdown(snapshot: Snapshot): String = buildString {
        appendLine("# ${snapshot.session.config.report.title}")
        appendLine()
        appendLine("**Platform:** Android · **Engine:** `${snapshot.engine}` · **Keys:** ${snapshot.session.sourceEntries.size} · **${isoDate(snapshot.generatedAt)}**")
        appendLine()
        appendLine("| Locale | Coverage | Missing | Empty | Errors | Warnings |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        for (validation in snapshot.validations) {
            appendLine(
                "| `${validation.locale}` | ${formatDecimal(validation.coverage * 100, 1)}% | ${validation.missing} | ${validation.empty} | ${validation.errors} | ${validation.warnings} |",
            )
        }
        appendLine()
        if (snapshot.issues.isNotEmpty()) {
            appendLine("## Issues")
            appendLine()
            appendLine("| Severity | Code | Locale | Key | Message |")
            appendLine("| --- | --- | --- | --- | --- |")
            snapshot.issues.take(300).forEach { issue ->
                appendLine("| ${issue.severity.name.lowercase()} | `${issue.code}` | ${issue.locale} | `${issue.key}` | ${issue.message.replace("|", "\\|")} |")
            }
        } else {
            appendLine("No issues found. 🎉")
        }
    }

    fun html(snapshot: Snapshot, placeholderSamples: Map<String, List<String>> = emptyMap()): String {
        val issues = snapshot.issues
        val rows = issues.joinToString("\n") { issue ->
            val tone = when (issue.severity) {
                Severity.ERROR -> "error"
                Severity.WARN -> "warn"
                Severity.INFO -> "info"
            }
            """<tr class="row" data-severity="$tone" data-locale="${escape(issue.locale)}">
      <td><span class="sev $tone">${issue.severity.name.lowercase()}</span></td>
      <td><code>${escape(issue.code)}</code></td>
      <td>${escape(issue.locale)}</td>
      <td><code>${escape(issue.key)}</code></td>
      <td>${escape(issue.message)}${issue.detail?.let { "<br><span class=\"muted small\">${escape(it)}</span>" } ?: ""}</td>
      <td class="target">${escape(issue.target ?: "")}</td>
    </tr>"""
        }
        val localeCards = snapshot.validations.joinToString("\n") { validation ->
            val percent = (validation.coverage * 100).toInt()
            """<article class="card">
      <header><h3>${escape(validation.locale)}</h3><span class="pill ${if (percent == 100) "ok" else "warn"}">$percent%</span></header>
      <div class="bar"><span style="width:$percent%"></span></div>
      <dl>
        <div><dt>keys</dt><dd>${validation.keyCount}</dd></div>
        <div><dt>translated</dt><dd>${validation.translated}</dd></div>
        <div><dt>missing</dt><dd>${validation.missing}</dd></div>
        <div><dt>issues</dt><dd><span class="tonetext errorTone">${validation.errors}E</span> / <span class="tonetext warnTone">${validation.warnings}W</span></dd></div>
      </dl>
    </article>"""
        }
        val placeholders = placeholderSamples.entries.joinToString("\n") { (key, tokens) ->
            """<tr><td><code>${escape(key)}</code></td><td>${tokens.joinToString(" ") { "<code>${escape(it)}</code>" }}</td></tr>"""
        }
        val counts = issues.groupBy { it.code }.entries.sortedByDescending { it.value.size }

        return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(snapshot.session.config.report.title)}</title>
<style>$STYLES</style>
</head>
<body>
<header class="hero">
  <p class="eyebrow">LingoFlow · Android</p>
  <h1>${escape(snapshot.session.sourceLocale)} → ${snapshot.validations.size - 1} locale(s)</h1>
  <p class="muted">${isoDate(snapshot.generatedAt)} · keys ${snapshot.session.sourceEntries.size} · engine <code>${escape(snapshot.engine)}</code></p>
  <div class="cards totals">
    <div class="stat"><span>${issues.count { it.severity == Severity.ERROR }}</span><label>errors</label></div>
    <div class="stat"><span>${issues.count { it.severity == Severity.WARN }}</span><label>warnings</label></div>
    <div class="stat"><span>${issues.count { it.severity == Severity.INFO }}</span><label>infos</label></div>
  </div>
</header>
<main>
  <section>
    <h2>Locales</h2>
    <div class="grid">$localeCards</div>
  </section>
  <section>
    <h2>Validation <span class="muted small">${issues.size} issue(s)</span></h2>
    <ul class="chips">${counts.joinToString("") { "<li><code>${escape(it.key)}</code><span class=\"pill\">${it.value.size}</span></li>" }}</ul>
    <div class="tablewrap"><table><thead><tr><th>Severity</th><th>Code</th><th>Locale</th><th>Key</th><th>Message</th><th>Target</th></tr></thead><tbody>$rows</tbody></table></div>
  </section>
  ${if (placeholders.isNotEmpty()) """<section><h2>Placeholders per key</h2><div class="tablewrap"><table><thead><tr><th>Key</th><th>Tokens</th></tr></thead><tbody>$placeholders</tbody></table></div></section>""" else ""}
</main>
<footer><p class="muted small">Generated on device by LingoFlow · nothing was uploaded.</p></footer>
<script>
(function () {
  document.querySelectorAll('tr.row').forEach(function (row) {
    if (row.dataset.severity === 'info') row.classList.add('faint');
  });
})();
</script>
</body>
</html>
"""
    }

    fun placeholderSamples(session: ProjectSession, limit: Int = 60): Map<String, List<String>> =
        session.sourceEntries.entries.take(limit).mapNotNull { (key, entry) ->
            val tokens = entry.value?.let { Placeholders.keys(it) }.orEmpty()
            if (tokens.isEmpty()) null else key to tokens
        }.toMap()

    private fun isoDate(timestamp: Long): String = SimpleDate.format(timestamp)

    /** Locale-independent decimal formatting (common Kotlin has no String.format). */
    fun formatDecimal(value: Double, digits: Int): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        var factor = 1.0
        repeat(digits) { factor *= 10 }
        val scaled = kotlin.math.round(value * factor).toLong()
        val whole = scaled / factor.toLong()
        val fraction = kotlin.math.abs(scaled % factor.toLong())
        if (digits <= 0) return whole.toString()
        return "$whole.${fraction.toString().padStart(digits, '0')}".trimEnd('0').trimEnd('.')
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val STYLES = """
:root {
  color-scheme: light dark;
  --bg:#f6f7fb; --panel:#fff; --text:#14161a; --muted:#6b7280; --border:#e3e6ee;
  --accent:#4f46e5; --error:#dc2626; --warn:#d97706; --info:#0891b2; --ok:#16a34a;
}
@media (prefers-color-scheme: dark) {
  :root { --bg:#0f1115; --panel:#171a21; --text:#e8eaf0; --muted:#9aa3b2; --border:#262b35; --accent:#8b8cf7; }
}
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--text); font:15px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans SC",sans-serif; }
code { font-family: ui-monospace,SFMono-Regular,Menlo,monospace; font-size:.86em; background:color-mix(in srgb, var(--border) 45%, transparent); padding:.1em .35em; border-radius:4px; }
.hero { padding:24px clamp(14px,4vw,40px) 8px; }
h1 { margin:.1em 0 .2em; font-size:clamp(20px,3vw,26px); }
h2 { font-size:18px; margin:24px 0 10px; }
.eyebrow { text-transform:uppercase; letter-spacing:.12em; font-size:11px; color:var(--muted); margin:0; }
.muted { color:var(--muted); } .small { font-size:12px; }
.cards { display:grid; grid-template-columns:repeat(auto-fill,minmax(240px,1fr)); gap:12px; }
.totals { display:flex; gap:10px; margin:14px 0; }
.stat { background:var(--panel); border:1px solid var(--border); border-radius:12px; padding:8px 16px; display:flex; flex-direction:column; }
.stat span { font-size:20px; font-weight:600; }
.stat label { font-size:11px; text-transform:uppercase; letter-spacing:.08em; color:var(--muted); }
main { padding:0 clamp(14px,4vw,40px) 40px; }
.card { background:var(--panel); border:1px solid var(--border); border-radius:14px; padding:12px 14px; }
.card header { display:flex; justify-content:space-between; align-items:center; }
.card h3 { margin:0; font-size:16px; }
.bar { background:color-mix(in srgb, var(--border) 70%, transparent); border-radius:99px; height:7px; overflow:hidden; margin:8px 0; }
.bar span { display:block; height:100%; background:var(--accent); }
dl { display:grid; grid-template-columns:repeat(4,1fr); gap:4px; margin:0; }
dt { font-size:10px; color:var(--muted); text-transform:uppercase; letter-spacing:.06em; }
dd { margin:0; font-weight:600; font-size:14px; }
.pill { display:inline-block; font-size:11px; padding:1px 8px; border-radius:99px; background:color-mix(in srgb, var(--accent) 18%, transparent); }
.pill.ok { background:color-mix(in srgb, var(--ok) 22%, transparent); }
.pill.warn { background:color-mix(in srgb, var(--warn) 22%, transparent); }
.errorTone { color:var(--error); } .warnTone { color:var(--warn); }
.chips { display:flex; flex-wrap:wrap; gap:6px; list-style:none; padding:0; margin:8px 0 14px; }
.chips li { display:flex; gap:6px; align-items:center; background:var(--panel); border:1px solid var(--border); border-radius:99px; padding:2px 10px; font-size:12px; }
.tablewrap { overflow:auto; border:1px solid var(--border); border-radius:12px; background:var(--panel); }
table { border-collapse:collapse; width:100%; font-size:13px; }
th,td { padding:7px 9px; text-align:left; vertical-align:top; border-bottom:1px solid var(--border); }
th { font-size:11px; text-transform:uppercase; letter-spacing:.06em; color:var(--muted); }
tr.faint td { opacity:.72; }
.sev { font-weight:600; font-size:11px; text-transform:uppercase; }
.sev.error { color:var(--error); } .sev.warn { color:var(--warn); } .sev.info { color:var(--info); }
.target { color:var(--muted); }
footer { padding:0 clamp(14px,4vw,40px) 32px; }
"""
}
