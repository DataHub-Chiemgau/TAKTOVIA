import '@taktovia/design-system/styles.css';
import {
  AuthProvider,
  DraftProvider,
  keycloakConfig,
  SessionProvider,
  useAuth,
} from '@taktovia/api-client';
import type { AuthConfig } from '@taktovia/api-client';
import { LiveRegionProvider } from '@taktovia/design-system';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { AuthGate } from './auth/AuthGate';

const root = document.getElementById('root');
if (!root) throw new Error('Root-Element fehlt');

// Anmeldung per OIDC Authorization Code mit PKCE (WP-M1-44): `VITE_OIDC_ISSUER` ist die Realm-URL des Anbieters (Keycloak), Client `passenger-web`.
// Tokens liegen nur im Arbeitsspeicher dieses Tabs; den zweiten Faktor fordert der Server bei Bedarf an (Step-up).
const config: AuthConfig = {
  oidc: keycloakConfig(
    import.meta.env.VITE_OIDC_ISSUER ?? `${window.location.origin}/realms/taktovia`,
    import.meta.env.VITE_OIDC_CLIENT_ID ?? 'passenger-web',
    window.location.origin,
  ),
  baseUrl: import.meta.env.VITE_API_ORIGIN ?? '',
};

function Authenticated() {
  const { client } = useAuth();
  return (
    <SessionProvider client={client}>
      <DraftProvider>
        <LiveRegionProvider>
          <App />
        </LiveRegionProvider>
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

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  void navigator.serviceWorker.register('/sw.js');
}
