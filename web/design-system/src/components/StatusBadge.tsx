import type { ReactNode } from 'react';

export type StatusTone = 'ok' | 'warning' | 'error' | 'info' | 'neutral';

/** Symbol je Tonwert, damit Status nie nur über Farbe erkennbar ist (Kap. 06 §6.1). */
const ICONS: Record<StatusTone, string> = {
  ok: '✓',
  warning: '▲',
  error: '✕',
  info: 'ℹ',
  neutral: '●',
};

export interface StatusBadgeProps {
  tone: StatusTone;
  children: ReactNode;
}

/** Statusanzeige mit Text und Symbol; Farbe ist nur Verstärkung. */
export function StatusBadge({ tone, children }: StatusBadgeProps) {
  return (
    <span className="tk-status" data-tone={tone}>
      <span data-icon aria-hidden="true">
        {ICONS[tone]}
      </span>
      <span>{children}</span>
    </span>
  );
}
