import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, ConsentRevision, PrivacySettings as Settings } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { PrivacySettings } from './PrivacySettings';

/** Ersatz des Servers: Marketing ohne Revision ist „nicht eingewilligt“, jede Änderung hängt eine Revision an. Echtes Verhalten: ConsentApiTest, PassengerApiTest. */
function fakeServer(failWrites = false) {
  const state = { history: [] as ConsentRevision[], writes: [] as unknown[] };
  const current = (): Settings => {
    const last = state.history[0];
    return {
      current_text_version: 'marketing-2026-1',
      consents: [
        {
          purpose: 'OPERATIONAL_SAFETY',
          granted: true,
          changeable: false,
          recorded_at: null,
          text_version: null,
        },
        {
          purpose: 'MARKETING',
          granted: last?.granted ?? false,
          changeable: true,
          recorded_at: last?.recorded_at ?? null,
          text_version: last?.text_version ?? null,
        },
      ],
      history: state.history,
    };
  };
  const client = {
    call(id: string, args: { body?: unknown }) {
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['PASSENGER'] }));
      if (id === 'getMyPrivacySettings') return Promise.resolve(structuredClone(current()));
      if (id === 'recordMyConsent') {
        if (failWrites) return Promise.reject(new ApiError(503));
        const b = args.body as { granted: boolean; text_version: string };
        state.writes.push(b);
        const rev: ConsentRevision = {
          id: `00000000-0000-4000-8000-00000000000${state.history.length + 1}`,
          purpose: 'MARKETING',
          sequence: state.history.length + 1,
          granted: b.granted,
          recorded_at: '2026-10-25T08:00:00Z',
          text_version: b.text_version,
        };
        state.history.unshift(rev);
        return Promise.resolve(rev);
      }
      return Promise.reject(new ApiError(404));
    },
  } as unknown as ApiClient;
  return { state, client };
}

function show(client: ApiClient) {
  return render(
    <SessionProvider client={client}>
      <PrivacySettings />
    </SessionProvider>,
  );
}

describe('Datenschutzeinstellungen (UI-05 Basis, WP-M1-30)', () => {
  afterEach(cleanup);

  it('marketing_schalter_getrennt_standard_aus_nicht_mit_pflichtzustimmung_kombiniert', async () => {
    const server = fakeServer();
    const { container } = show(server.client);
    await screen.findByRole('heading', { name: 'Datenschutz' });
    // Marketing: eigener Schalter, standardmäßig aus
    const marketing = screen.getByRole('switch', {
      name: /Marketing-Mitteilungen/,
    }) as HTMLInputElement;
    expect(marketing.checked).toBe(false);
    // Betriebliche/sicherheitsrelevante Mitteilungen: eigener Hinweis ohne Schalter, nicht abwählbar, nicht mit Marketing kombiniert
    const operational = screen.getByRole('group', { name: 'Betriebliche Mitteilungen' });
    expect(operational.querySelector('input')).toBeNull();
    expect(operational).toHaveTextContent('nicht abwählbar');
    expect(screen.getAllByRole('switch')).toHaveLength(1);
    expect(screen.queryByRole('checkbox', { name: /Buchung|Pflicht|Bedingungen/i })).toBeNull();
    expect(server.state.writes).toHaveLength(0);
    // Einwilligen und Widerrufen: je eine eigene Revision mit der gezeigten Textversion
    fireEvent.click(marketing);
    await waitFor(() =>
      expect(
        (screen.getByRole('switch', { name: /Marketing-Mitteilungen/ }) as HTMLInputElement)
          .checked,
      ).toBe(true),
    );
    fireEvent.click(screen.getByRole('switch', { name: /Marketing-Mitteilungen/ }));
    await waitFor(() =>
      expect(
        (screen.getByRole('switch', { name: /Marketing-Mitteilungen/ }) as HTMLInputElement)
          .checked,
      ).toBe(false),
    );
    expect(server.state.writes).toEqual([
      { purpose: 'MARKETING', granted: true, text_version: 'marketing-2026-1' },
      { purpose: 'MARKETING', granted: false, text_version: 'marketing-2026-1' },
    ]);
    expect(await screen.findAllByText(/Textversion marketing-2026-1/)).toHaveLength(2);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('Fehler beim Speichern: der Schalter springt zurück und es entsteht keine Revision', async () => {
    const server = fakeServer(true);
    show(server.client);
    await screen.findByRole('heading', { name: 'Datenschutz' });
    fireEvent.click(screen.getByRole('switch', { name: /Marketing-Mitteilungen/ }));
    await screen.findByText(/bleibt unverändert/);
    expect(
      (screen.getByRole('switch', { name: /Marketing-Mitteilungen/ }) as HTMLInputElement).checked,
    ).toBe(false);
    expect(server.state.history).toHaveLength(0);
  });
});
