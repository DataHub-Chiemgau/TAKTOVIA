import '@taktovia/design-system/styles.css';
import { createApiClient, SessionProvider } from '@taktovia/api-client';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';

const root = document.getElementById('root');
if (!root) throw new Error('Root-Element fehlt');

// Das Zugriffstoken liegt nur im Arbeitsspeicher; die Anmeldung (OIDC/PKCE) folgt mit WP-M1-44.
const client = createApiClient({ baseUrl: import.meta.env.VITE_API_ORIGIN ?? '' });

createRoot(root).render(
  <StrictMode>
    <BrowserRouter>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </BrowserRouter>
  </StrictMode>,
);

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  void navigator.serviceWorker.register('/sw.js');
}
