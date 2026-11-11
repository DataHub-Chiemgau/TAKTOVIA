import { useAuth } from '@taktovia/api-client';
import { AuthStateView } from '@taktovia/design-system';
import type { ReactNode } from 'react';

/**
 * Lässt die Anwendung erst nach gültiger Anmeldung sehen. Vor der Anmeldung, nach Ablauf, nach einem serverseitigen Ende (beendet oder
 * gesperrt) und bei verlangtem zweiten Faktor erscheint ein eigener Zustand ohne Daten (WP-M1-44).
 */
export function AuthGate({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const { state } = auth;
  if (state.status === 'authenticated') return <>{children}</>;
  if (state.status === 'signingIn') return <AuthStateView kind="signingIn" />;
  if (state.status === 'signedOut')
    return <AuthStateView kind="signedOut" onSignIn={() => void auth.signIn()} />;
  return (
    <AuthStateView
      kind={state.reason}
      onSignIn={() => void auth.signIn(state.reason === 'mfaRequired' ? { mfa: true } : undefined)}
    />
  );
}
