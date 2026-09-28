import type { RunReport } from './model';
import { HOMEPAGE, VERSION } from '../version';

interface SarifResult {
  ruleId: string;
  level: 'error' | 'warning' | 'note';
  message: { text: string };
  locations?: Array<{ physicalLocation: { artifactLocation: { uri: string } } }>;
  properties?: Record<string, unknown>;
}

/**
 * SARIF 2.1.0 output so CI can annotate exactly which locale file or key broke.
 * Works with GitHub code scanning and most code review tools.
 */
export function renderSarifReport(report: RunReport): string {
  const rules = new Map<string, { id: string; name: string; shortDescription: { text: string }; defaultConfiguration: { level: string } }>();
  const results: SarifResult[] = [];

  for (const issue of report.issues) {
    if (!rules.has(issue.code)) {
      rules.set(issue.code, {
        id: issue.code,
        name: issue.code.replace(/[^\w]/gu, '_'),
        shortDescription: { text: issue.code },
        defaultConfiguration: { level: issue.severity === 'error' ? 'error' : issue.severity === 'warn' ? 'warning' : 'note' },
      });
    }
    const file = fileForIssue(report, issue.locale);
    results.push({
      ruleId: issue.code,
      level: issue.severity === 'error' ? 'error' : issue.severity === 'warn' ? 'warning' : 'note',
      message: { text: `[${issue.locale}] ${issue.key}: ${issue.message}` },
      ...(file ? { locations: [{ physicalLocation: { artifactLocation: { uri: file } } }] } : {}),
      properties: { locale: issue.locale, key: issue.key, ...(issue.detail ? { detail: issue.detail } : {}) },
    });
  }

  const sarif = {
    $schema: 'https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json',
    version: '2.1.0',
    runs: [
      {
        tool: {
          driver: {
            name: 'LingoFlow',
            informationUri: HOMEPAGE,
            version: VERSION,
            rules: [...rules.values()],
          },
        },
        results,
        invocations: [
          {
            executionSuccessful: true,
            commandLine: `lingoflow ${report.command}`,
            startTimeUtc: report.startedAt,
            endTimeUtc: report.finishedAt,
          },
        ],
      },
    ],
  };
  return `${JSON.stringify(sarif, null, 2)}\n`;
}

function fileForIssue(report: RunReport, locale: string): string | null {
  const entry = report.locales.find((item) => item.locale === locale);
  return entry?.files[0] ?? null;
}
