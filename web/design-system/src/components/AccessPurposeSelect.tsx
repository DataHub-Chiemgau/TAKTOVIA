import { useId } from 'react';
import { useI18n } from '../i18n';
import type { MessageKey } from '../i18n';

/** Zwecke des Feldzugriffs (WP-M1-31); entspricht dem Header `X-Access-Purpose` von `getPassengerSupportNeeds`. */
export const ACCESS_PURPOSES = [
  'BOOKING_ASSISTANCE',
  'SUPPORT_CASE',
  'TRANSPORT_PLANNING',
] as const;
export type AccessPurpose = (typeof ACCESS_PURPOSES)[number];

export interface AccessPurposeSelectProps {
  value: AccessPurpose | '';
  onChange: (next: AccessPurpose | '') => void;
  /** Zeigt die Pflichtmeldung, wenn ein Zugriff ohne Zweck versucht wurde. */
  missing?: boolean;
}

/**
 * UI-20 Basis: Zugriff auf ein geschütztes Merkmal nur mit Zweckangabe. Die Komponente gibt den Zweck für den Header `X-Access-Purpose`
 * vor; ohne Auswahl bleibt `value` leer und die aufrufende Ansicht darf die Anfrage nicht senden. Die Berechtigung prüft allein der Server.
 */
export function AccessPurposeSelect({ value, onChange, missing }: AccessPurposeSelectProps) {
  const { t } = useI18n();
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  return (
    <div>
      <label htmlFor={id}>{t('purpose.label')}</label>
      <select
        id={id}
        value={value}
        required
        aria-describedby={missing ? `${hintId} ${errorId}` : hintId}
        aria-invalid={missing || undefined}
        onChange={(e) => onChange(e.target.value as AccessPurpose | '')}
      >
        <option value="">{t('purpose.placeholder')}</option>
        {ACCESS_PURPOSES.map((p) => (
          <option key={p} value={p}>
            {t(`purpose.${p}` as MessageKey)}
          </option>
        ))}
      </select>
      <p id={hintId}>{t('purpose.hint')}</p>
      {missing ? (
        <p id={errorId} role="alert">
          {t('purpose.required')}
        </p>
      ) : null}
    </div>
  );
}
