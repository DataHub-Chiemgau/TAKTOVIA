import type { ReactNode } from 'react';

/**
 * Horizontal scrollbarer Behälter für breite Tabellen: Die Seite bleibt bei 320 CSS-Pixeln ohne Überbreite, der Behälter ist
 * per Tastatur fokussierbar und benannt (WCAG 2.1.1, axe `scrollable-region-focusable`).
 */
export function ScrollRegion({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="tk-table-wrap" role="region" aria-label={label} tabIndex={0}>
      {children}
    </div>
  );
}
