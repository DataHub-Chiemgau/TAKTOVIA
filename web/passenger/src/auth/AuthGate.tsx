import { useAuth } from '@taktovia/api-client';
import { AuthStateView } from '@taktovia/design-system';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { Hilfe } from '../hilfe/Hilfe';
import { OfflinePage } from '../netz/Offline';
import { useOnline } from '../netz/useOnline';
import { isPublicPath } from '../routen';
import { tx } from '../texte';

/**
 * Lässt die Anwendung erst nach gültiger Anmeldung sehen. Vor der Anmeldung, nach Ablauf, nach einem serverseitigen Ende (beendet oder
 * gesperrt) und bei verlangtem zweiten Faktor erscheint ein eigener Zustand ohne Daten (WP-M1-44). Öffentliche Seiten (Hilfe) sind ohne
 * Anmeldung erreichbar; ohne Verbindung erscheint der Offlinezustand statt einer Weiterleitung zum Anmeldedienst (WP-M2-35).
 */
export function AuthGate({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const online = useOnline();
  const { pathname } = useLocation();
  const { state } = auth;
  if (state.status === 'authenticated') return <>{children}</>;
  if (isPublicPath(pathname))
    return (
      <main>
        <Hilfe />
        {state.status === 'signedOut' ? (
          <>
            <p>{tx('hilfe.signin')}</p>
            <button type="button" onClick={() => void auth.signIn()}>
              Anmelden
            </button>
          </>
        ) : null}
      </main>
    );
  if (state.status === 'signingIn') return <AuthStateView kind="signingIn" />;
  if (!online) return <OfflinePage />;
  if (state.status === 'signedOut')
    return <AuthStateView kind="signedOut" onSignIn={() => void auth.signIn()} />;
  return (
    <AuthStateView
      kind={state.reason}
      onSignIn={() => void auth.signIn(state.reason === 'mfaRequired' ? { mfa: true } : undefined)}
    />
  );
}
