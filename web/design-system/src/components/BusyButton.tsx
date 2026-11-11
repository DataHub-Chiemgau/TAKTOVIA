import { useRef, useState } from 'react';
import type { ButtonHTMLAttributes, MouseEvent } from 'react';
import { useI18n } from '../i18n';

export interface BusyButtonProps extends Omit<
  ButtonHTMLAttributes<HTMLButtonElement>,
  'onClick' | 'disabled'
> {
  /** Die Operation; solange sie läuft, ist die Schaltfläche gesperrt (Doppelklickschutz, Kap. 06 §6.3). */
  onAction: () => unknown;
  onError?: (error: unknown) => void;
  /** Von außen gesetzte Sperre, z. B. weil eine andere Operation läuft. */
  locked?: boolean;
}

/**
 * Schaltfläche mit Mehrfachabsendeschutz. Gesperrt wird per `aria-disabled`, nicht `disabled`: Der Fokus bleibt erhalten,
 * Screenreader hören den Zustand. Die serverseitige Idempotenz bleibt zusätzlich erforderlich.
 */
export function BusyButton({
  onAction,
  onError,
  locked = false,
  children,
  type = 'button',
  ...rest
}: BusyButtonProps) {
  const [busy, setBusy] = useState(false);
  const running = useRef(false);
  const { t } = useI18n();

  const handle = (event: MouseEvent<HTMLButtonElement>) => {
    if (running.current || locked) {
      event.preventDefault();
      return;
    }
    running.current = true;
    setBusy(true);
    const finish = () => {
      running.current = false;
      setBusy(false);
    };
    let result: unknown;
    try {
      result = onAction();
    } catch (error) {
      onError?.(error);
      finish();
      return;
    }
    void Promise.resolve(result)
      .catch((error: unknown) => onError?.(error))
      .finally(finish);
  };

  const blocked = busy || locked;
  return (
    <button
      {...rest}
      type={type}
      aria-disabled={blocked ? 'true' : undefined}
      aria-busy={busy ? 'true' : undefined}
      title={busy ? t('common.busy') : rest.title}
      onClick={handle}
    >
      {children}
    </button>
  );
}
