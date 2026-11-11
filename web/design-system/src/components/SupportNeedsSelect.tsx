import { useId } from 'react';
import { useI18n } from '../i18n';
import type { MessageKey } from '../i18n';

/**
 * Katalog der transportrelevanten Unterstützung (WP-M1-31, OP-24 vorläufig). Entspricht `PassengerSupportNeeds.codes` in `api/passengers.yaml`.
 * Es gibt bewusst nur diese Auswahl: kein Freitext, keine Frage nach Diagnosen (Kap. 02 §3).
 */
export const SUPPORT_NEED_CODES = [
  'BOARDING_HELP',
  'AUDIO_GUIDANCE',
  'LOW_FLOOR',
  'WHEELCHAIR_SPACE',
  'ASSISTANCE_ANIMAL',
  'ACCOMPANYING_PERSON',
] as const;
export type SupportNeedCode = (typeof SUPPORT_NEED_CODES)[number];

export interface SupportNeedsSelectProps {
  value: readonly SupportNeedCode[];
  onChange: (next: readonly SupportNeedCode[]) => void;
  disabled?: boolean;
}

/** UI-05 Basis: Komponente „Unterstützungsbedarf“ als Auswahl ohne Diagnoseabfrage; wird von der Kontoseite (WP-M1-29) eingebettet. */
export function SupportNeedsSelect({ value, onChange, disabled }: SupportNeedsSelectProps) {
  const { t } = useI18n();
  const hintId = useId();
  const toggle = (code: SupportNeedCode) =>
    onChange(value.includes(code) ? value.filter((c) => c !== code) : [...value, code]);
  return (
    <fieldset aria-describedby={hintId} disabled={disabled}>
      <legend>{t('support.legend')}</legend>
      <p id={hintId}>{t('support.hint')}</p>
      {SUPPORT_NEED_CODES.map((code) => (
        <label key={code} style={{ display: 'block' }}>
          <input type="checkbox" checked={value.includes(code)} onChange={() => toggle(code)} />{' '}
          {t(`support.${code}` as MessageKey)}
        </label>
      ))}
    </fieldset>
  );
}
