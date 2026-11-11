import axe from 'axe-core';
import type { AxeResults } from 'axe-core';

/**
 * Fuehrt den axe-core-Regelsatz (WCAG 2.x A/AA, Best Practices) auf einem Element aus
 * und liefert die Verstoesse. Wird von allen Paketen als Testbaustein genutzt.
 */
export async function runAxe(container: Element): Promise<AxeResults['violations']> {
  const results = await axe.run(container, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice'] },
    // jsdom rendert kein Layout; Farbkontrast kann hier nicht geprueft werden.
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
  });
  return results.violations;
}

export function formatViolations(violations: AxeResults['violations']): string {
  return violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length} Knoten)`).join('\n');
}
