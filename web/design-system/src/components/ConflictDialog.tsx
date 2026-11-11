import { useEffect, useId, useRef } from 'react';
import type { KeyboardEvent } from 'react';
import { useI18n } from '../i18n';

export interface ConflictDialogProps {
  open: boolean;
  onReload: () => void;
  onReview: () => void;
  onCancel: () => void;
}

/**
 * Dialog bei 412 (Revision veraltet): kein stilles Überschreiben. Angeboten werden nur „aktuellen Stand laden“, „eigene Eingaben
 * prüfen“ (zeigt beide Stände nebeneinander, die Übernahme ist dann ein neuer, bewusster Speichervorgang mit frischer Revision)
 * und „Abbrechen“. Es gibt absichtlich keine Schaltfläche „Überschreiben“.
 */
export function ConflictDialog({ open, onReload, onReview, onCancel }: ConflictDialogProps) {
  const { t } = useI18n();
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const bodyId = useId();

  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    ref.current?.querySelector<HTMLElement>('button')?.focus();
    return () => previous?.focus?.();
  }, [open]);

  if (!open) return null;

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onCancel();
      return;
    }
    if (event.key !== 'Tab') return;
    const buttons = [...(ref.current?.querySelectorAll<HTMLElement>('button') ?? [])];
    const first = buttons[0];
    const last = buttons[buttons.length - 1];
    if (!first || !last) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return (
    <div className="tk-dialog-backdrop">
      <div
        ref={ref}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        className="tk-dialog"
        onKeyDown={onKeyDown}
      >
        <h2 id={titleId}>{t('conflict.title')}</h2>
        <p id={bodyId}>{t('conflict.body')}</p>
        <div className="tk-dialog-actions">
          <button type="button" onClick={onReload}>
            {t('conflict.reload')}
          </button>
          <button type="button" className="tk-button-secondary" onClick={onReview}>
            {t('conflict.review')}
          </button>
          <button type="button" className="tk-button-secondary" onClick={onCancel}>
            {t('common.cancel')}
          </button>
        </div>
      </div>
    </div>
  );
}
