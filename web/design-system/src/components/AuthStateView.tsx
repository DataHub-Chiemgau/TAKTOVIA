import type { ReactNode } from 'react';
import { useI18n } from '../i18n';
import type { MessageKey } from '../i18n';

export type AuthStateKind =
  'signedOut' | 'signingIn' | 'expired' | 'revoked' | 'error' | 'mfaRequired' | 'noMembership';

export interface AuthStateViewProps {
  kind: AuthStateKind;
  /** Aktion „Anmelden“; fehlt bei Zuständen ohne Anmeldung (laufende Anmeldung, keine Mitgliedschaft). */
  onSignIn?: () => void;
  /** Zusätzliche Aktion, z. B. „Abmelden“ bei fehlender Mitgliedschaft. */
  action?: ReactNode;
}

/**
 * Zustände vor und außerhalb einer gültigen Sitzung: nicht angemeldet, Anmeldung läuft, Sitzung abgelaufen, beendet oder gesperrt,
 * zweiter Faktor erforderlich, keine Mitgliedschaft. Die Seite zeigt nie Daten und nie Einzelheiten des Servers; Texte stammen aus dem
 * Katalog (Kap. 06 §6.9).
 */
export function AuthStateView({ kind, onSignIn, action }: AuthStateViewProps) {
  const { t } = useI18n();
  const title = t(`auth.${kind}.title` as MessageKey);
  const body = kind === 'signingIn' ? undefined : t(`auth.${kind}.body` as MessageKey);
  const label = kind === 'mfaRequired' ? t('auth.signInMfa') : t('auth.signIn');
  return (
    <main>
      <section
        className="tk-state"
        data-testid="auth-state"
        data-kind={kind}
        role={kind === 'signingIn' ? undefined : 'alert'}
        aria-busy={kind === 'signingIn' ? 'true' : undefined}
      >
        <h1>{title}</h1>
        {body ? <p>{body}</p> : null}
        {onSignIn && kind !== 'signingIn' && kind !== 'noMembership' ? (
          <button type="button" onClick={onSignIn}>
            {label}
          </button>
        ) : null}
        {action}
      </section>
    </main>
  );
}
