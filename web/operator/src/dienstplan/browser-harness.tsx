import '@taktovia/design-system/styles.css';
import { SessionProvider, createApiClient } from '@taktovia/api-client';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from '../App';

/**
 * Prüfstand für den Browserlauf von UI-12 (`web/tools/dienstplan-browser.mjs`): dieselbe Anwendung wie `main.tsx`, aber ohne Anmeldung; die API-Aufrufe gehen
 * unverändert über `fetch` und werden im Browserlauf vom Server-Ersatz beantwortet. Nur für diesen Lauf, nicht Teil des Produktions-Builds.
 */
const root = document.getElementById('root');
if (!root) throw new Error('Root-Element fehlt');
const client = createApiClient({ baseUrl: window.location.origin });
createRoot(root).render(
  <StrictMode>
    <BrowserRouter>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </BrowserRouter>
  </StrictMode>,
);
