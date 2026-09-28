import type { RunReport } from './model';

/** Compact markdown summary, handy for PR comments and CI logs. */
export function renderMarkdownReport(report: RunReport): string {
  const lines: string[] = [];
  lines.push(`# ${report.product} report — ${report.command}`);
  lines.push('');
  lines.push(
    `**Source:** \`${report.sourceLocale}\` · **Engine:** \`${report.engine}\` · **Keys:** ${report.summary.keys} · **Duration:** ${(report.durationMs / 1000).toFixed(1)}s`,
  );
  lines.push('');
  lines.push('| Locale | Coverage | Missing | Empty | Errors | Warnings | Frozen |');
  lines.push('| --- | --- | --- | --- | --- | --- | --- |');
  for (const locale of report.locales) {
    const errors = locale.issues.filter((issue) => issue.severity === 'error').length;
    const warnings = locale.issues.filter((issue) => issue.severity === 'warn').length;
    lines.push(
      `| \`${locale.locale}\` | ${(locale.coverage * 100).toFixed(1)}% | ${locale.missing} | ${locale.empty} | ${errors} | ${warnings} | ${locale.frozen} |`,
    );
  }
  lines.push('');
  if (report.issues.length > 0) {
    lines.push('## Issues');
    lines.push('');
    lines.push('| Severity | Code | Locale | Key | Message |');
    lines.push('| --- | --- | --- | --- | --- |');
    for (const issue of report.issues.slice(0, 200)) {
      lines.push(
        `| ${issue.severity} | \`${issue.code}\` | ${issue.locale} | \`${issue.key}\` | ${issue.message.replace(/\|/gu, '\\|')} |`,
      );
    }
    if (report.issues.length > 200) lines.push(`| … | | | | ${report.issues.length - 200} more |`);
    lines.push('');
  }
  if (report.summary.written.length > 0) {
    lines.push('## Files written');
    lines.push('');
    for (const file of report.summary.written) lines.push(`- \`${file}\``);
    lines.push('');
  }
  if (report.learned) {
    lines.push(
      `## Learned this run`,
      '',
      `glossary ${report.learned.glossary} · corrections ${report.learned.corrections} · style ${report.learned.styles}`,
      '',
    );
  }
  lines.push(
    `## Translation memory`,
    '',
    `${report.memory.entries} entries (${report.memory.approved} approved, ${report.memory.frozen} frozen) · driver \`${report.memory.driver}\` · ${report.memory.rules} rules`,
    '',
  );
  return lines.join('\n');
}
