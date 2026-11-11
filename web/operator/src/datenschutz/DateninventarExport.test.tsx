import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { SessionProvider } from '@taktovia/api-client';
import { fakeApiClient, sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { DateninventarExport, inventarAlsCsv } from './DateninventarExport';
import type { Dateninventar } from './DateninventarExport';
import inventar from './dateninventar.json';

const doc = inventar as unknown as Dateninventar;

function setup(roles: string[]) {
  const client = fakeApiClient({ getMe: () => sampleMe({ roles }) });
  return render(
    <MemoryRouter>
      <SessionProvider client={client}>
        <DateninventarExport inventar={doc} />
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe('UI-20 Basis: Export des Dateninventars', () => {
  it('inventar_export_lesbar_nur_mit_leserecht', async () => {
    const { unmount } = setup(['PASSENGER']);
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(screen.queryByText('passenger_private')).toBeNull();
    expect(screen.queryByText('contact_ciphertext')).toBeNull();
    unmount();

    setup(['ADMIN']);
    const table = await screen.findByRole('table', { name: /passenger_private/ });
    const row = within(table).getByText('contact_ciphertext').closest('tr') as HTMLElement;
    expect(row).toHaveTextContent('ja');
    expect(row).toHaveTextContent('Vertragserfüllung');
    expect(row).toHaveTextContent('Kontakt/Unterstützung');
    expect(row).toHaveTextContent('Kontakt- und Unterstützungsdaten');
  });

  it('kennzeichnet den vorläufigen, nicht freigegebenen Katalog sichtbar mit Text', async () => {
    setup(['ADMIN']);
    expect(await screen.findByText('Katalog nicht freigegeben (OP-24)')).toBeInTheDocument();
    expect(screen.getByText(/NICHT freigegeben/)).toBeInTheDocument();
  });

  it('zeigt jede Tabelle des Inventars mit Löschklasse, die Anzahl entspricht dem Export', async () => {
    setup(['ADMIN']);
    await screen.findByRole('table', { name: /journey_leg/ });
    expect(screen.getAllByRole('table')).toHaveLength(doc.tabellen.length);
    expect(screen.getByRole('table', { name: /travel_attempt/ })).toBeInTheDocument();
  });

  it('der Filter „Nur personenbezogene Felder“ blendet Felder ohne Personenbezug aus', async () => {
    setup(['ADMIN']);
    const table = await screen.findByRole('table', { name: /travel_attempt/ });
    expect(within(table).getByText('primary_rejection')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Nur personenbezogene Felder' }));
    const filtered = screen.getByRole('table', { name: /travel_attempt/ });
    expect(within(filtered).queryByText('primary_rejection')).toBeNull();
    expect(within(filtered).getByText('coarse_origin')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: /currency/ })).toBeNull();
  });

  it('CSV-Export enthält Kopfzeile und eine Zeile je Spalte, Semikolon-getrennt und mit Anführungszeichen bei Bedarf', () => {
    const csv = inventarAlsCsv(doc);
    const lines = csv.trim().split('\n');
    expect(lines[0]).toBe(
      'tabelle;aggregat_loeschklasse;spalte;personenbezogen;zweck;rechtsgrundlage;schutzklasse;loeschklasse;bemerkung',
    );
    expect(lines).toHaveLength(1 + doc.tabellen.reduce((n, t) => n + t.spalten.length, 0));
    expect(csv).toContain(
      'passenger_private;LK_PII;contact_ciphertext;ja;KONTAKT_UND_UNTERSTUETZUNG',
    );
    const quoted = inventarAlsCsv({
      ...doc,
      tabellen: [
        {
          tabelle: 't',
          aggregat: '',
          loeschklasse: 'LK_STAMM',
          bemerkung: '',
          spalten: [
            {
              spalte: 'c',
              personenbezogen: 'nein',
              zweck: 'Z',
              rechtsgrundlage: '',
              schutzklasse: '',
              loeschklasse: '',
              bemerkung: 'a;b "c"',
            },
          ],
        },
      ],
    });
    expect(quoted).toContain('"a;b ""c"""');
  });

  it('hat keine axe-Verstöße', async () => {
    const { container } = setup(['ADMIN']);
    await screen.findByRole('table', { name: /passenger_private/ });
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
