import '@taktovia/design-system/styles.css';
import {
  AuthProvider,
  DraftProvider,
  keycloakConfig,
  SessionProvider,
  useAuth,
} from '@taktovia/api-client';
import type { AuthConfig } from '@taktovia/api-client';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { AuthGate } from './auth/AuthGate';

const root = document.getElementById('root');
if (!root) throw new Error('Root-Element fehlt');

// Anmeldung per OIDC Authorization Code mit PKCE (WP-M1-44): `VITE_OIDC_ISSUER` ist die Realm-URL des Anbieters (Keycloak), der Client
// `operator-web` fordert für Administratorrollen den zweiten Faktor (loa2). Tokens liegen nur im Arbeitsspeicher dieses Tabs.
const config: AuthConfig = {
  oidc: keycloakConfig(
    import.meta.env.VITE_OIDC_ISSUER ?? `${window.location.origin}/realms/taktovia`,
    import.meta.env.VITE_OIDC_CLIENT_ID ?? 'operator-web',
    window.location.origin,
  ),
  baseUrl: import.meta.env.VITE_API_ORIGIN ?? '',
  requestMfa: true,
};

function Authenticated() {
  const { client } = useAuth();
  return (
    <SessionProvider client={client}>
      <DraftProvider>
        <App />
      </DraftProvider>
    </SessionProvider>
  );
}

createRoot(root).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider config={config}>
        <AuthGate>
          <Authenticated />
        </AuthGate>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
);
