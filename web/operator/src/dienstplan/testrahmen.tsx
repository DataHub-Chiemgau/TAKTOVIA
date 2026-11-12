import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SessionProvider } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { App } from '../App';
import { clientFuer } from './testwelt';
import type { Testwelt } from './testwelt';

export const LESEN = ['me:read', 'rosters:read', 'drivers:read'];
export const SCHREIBEN = [...LESEN, 'rosters:write'];
export const VOLL = [...SCHREIBEN, 'rosters:publish'];

/** Rendert UI-12 in der Operator-Anwendung mit dem Server-Ersatz der Testwelt (nur für Tests). */
export function zeigeDienstplan(welt: Testwelt, scopes: string[] = LESEN) {
  const client = clientFuer(
    (id, args) => welt.handle(id, args),
    () => sampleMe({ roles: ['staff_planning'], scopes }),
  );
  return render(
    <MemoryRouter initialEntries={['/personal/dienstplan']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
}
