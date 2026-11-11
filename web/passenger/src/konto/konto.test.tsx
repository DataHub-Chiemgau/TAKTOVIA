import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, Profile, ProfileUpdate } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { Profil } from './Profil';

const STOP_A = '11111111-1111-4111-8111-111111111111';
const STOP_B = '22222222-2222-4222-8222-222222222222';

/**
 * Ersatz des Servers (kein Ersatz für die echten Komponenten): hält das Profil über Neuanmeldungen hinweg, prüft `If-Match` wie der Server (412) und lehnt
 * unbekannte Haltestellen mit 422 ab. Das echte Serververhalten belegen `PassengerProfileTest` und `PassengerApiTest` im Backend.
 */
function fakeServer(initial?: Partial<Profile>) {
  const state = {
    profile: {
      id: '00000000-0000-4000-8000-0000000000aa',
      status: 'ACTIVE',
      locale: 'de-DE',
      created_at: '2026-10-25T08:00:00Z',
      revision: 3,
      contact: {
        email: {
          value: 'anna@example.org',
          status: 'VERIFIED',
          verified_at: '2026-10-25T08:05:00Z',
        },
        phone: null,
      },
      favorites: [],
      preferred_stops: [],
      notification_channels: ['PUSH'],
      ...initial,
    } as Profile,
    support: [] as string[],
    verified: [] as string[],
    patches: [] as ProfileUpdate[],
  };
  const known = new Set([STOP_A, STOP_B]);
  const client = {
    call(id: string, args: { body?: unknown; revision?: number | string }) {
      const reply = (v: unknown) => Promise.resolve(structuredClone(v));
      switch (id) {
        case 'getMe':
          return reply(sampleMe({ roles: ['PASSENGER'] }));
        case 'getMyProfile':
          return reply(state.profile);
        case 'updateMyProfile': {
          if (String(args.revision) !== String(state.profile.revision))
            return Promise.reject(new ApiError(412));
          const b = args.body as ProfileUpdate;
          const bad = [
            ...(b.preferred_stops ?? []),
            ...(b.favorites ?? []).map((f) => f.stop_id),
          ].findIndex((s) => !known.has(s));
          if (bad >= 0)
            return Promise.reject(
              new ApiError(422, {
                type: 't',
                title: 'x',
                status: 422,
                code: 'VALIDATION_FAILED',
                correlation_id: 'c',
                violations: [
                  { pointer: `/preferred_stops/${bad}`, code: 'INVALID_VALUE', message: 'm' },
                ],
              }),
            );
          state.patches.push(b);
          const p = state.profile;
          if (b.locale) p.locale = b.locale as Profile['locale'];
          if (b.contact?.email)
            p.contact.email = { value: b.contact.email, status: 'UNVERIFIED', verified_at: null };
          if (b.contact?.phone)
            p.contact.phone = { value: b.contact.phone, status: 'UNVERIFIED', verified_at: null };
          if (b.favorites) p.favorites = b.favorites;
          if (b.preferred_stops) p.preferred_stops = b.preferred_stops;
          if (b.notification_channels) p.notification_channels = b.notification_channels;
          p.revision += 1;
          return reply(p);
        }
        case 'getMyTransportSupport':
          return reply({ codes: state.support });
        case 'replaceMyTransportSupport':
          state.support = (args.body as { codes: string[] }).codes;
          return Promise.resolve(undefined);
        case 'startContactVerification':
          return reply({ channel: 'PHONE', expires_at: '2026-10-25T09:10:00Z' });
        case 'confirmContactVerification': {
          const b = args.body as { channel: 'EMAIL' | 'PHONE'; code: string };
          if (b.code !== '123456') return Promise.reject(new ApiError(422));
          const key = b.channel === 'EMAIL' ? 'email' : 'phone';
          const c = state.profile.contact[key];
          if (c)
            state.profile.contact[key] = {
              ...c,
              status: 'VERIFIED',
              verified_at: '2026-10-25T08:30:00Z',
            };
          state.verified.push(b.channel);
          return reply({
            channel: b.channel,
            status: 'VERIFIED',
            verified_at: '2026-10-25T08:30:00Z',
          });
        }
        default:
          return Promise.reject(new ApiError(404));
      }
    },
  } as unknown as ApiClient;
  return { state, client };
}

function show(client: ApiClient) {
  return render(
    <MemoryRouter>
      <SessionProvider client={client} now={() => Date.parse('2026-10-25T09:00:00+01:00')}>
        <Profil />
      </SessionProvider>
    </MemoryRouter>,
  );
}

const field = (name: string) => screen.getByLabelText(name) as HTMLInputElement;

describe('Fahrgastprofil (UI-05 Basis, WP-M1-29)', () => {
  afterEach(cleanup);

  it('fuenf_profilbereiche_aenderbar_und_neu_geladen', async () => {
    const server = fakeServer();
    const first = show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    // Sprache, Kontakt, Benachrichtigungskanäle, Favoriten, bevorzugte Haltestellen
    fireEvent.change(screen.getByLabelText('Sprache'), { target: { value: 'en-GB' } });
    fireEvent.change(field('E-Mail-Adresse'), { target: { value: 'neu@example.org' } });
    fireEvent.change(field('Telefonnummer'), { target: { value: '+49 151 2345678' } });
    fireEvent.click(screen.getByRole('checkbox', { name: 'SMS' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Push-Mitteilung' }));
    fireEvent.click(screen.getByRole('button', { name: 'Favorit hinzufügen' }));
    fireEvent.change(field('Bezeichnung Favorit 1'), { target: { value: 'Zuhause' } });
    fireEvent.change(field('Haltepunkt Favorit 1'), { target: { value: STOP_A } });
    fireEvent.click(screen.getByRole('button', { name: 'Haltestelle hinzufügen' }));
    fireEvent.change(field('Bevorzugter Haltepunkt 1'), { target: { value: STOP_B } });
    fireEvent.click(screen.getByRole('button', { name: 'Profil speichern' }));
    await screen.findByText('Gespeichert.');

    expect(server.state.profile).toMatchObject({
      locale: 'en-GB',
      revision: 4,
      favorites: [{ label: 'Zuhause', stop_id: STOP_A }],
      preferred_stops: [STOP_B],
      notification_channels: ['SMS'],
    });
    expect(server.state.profile.contact.email?.value).toBe('neu@example.org');
    expect(server.state.profile.contact.phone?.value).toBe('+49 151 2345678');
    // ein geänderter Kontakt ist bis zur Verifikation unverifiziert
    expect(screen.getAllByText(/Verifikation fehlt/).length).toBeGreaterThan(0);

    // Abmelden und erneut anmelden (neuer Bildschirmbaum, gleicher Server): alles unverändert abrufbar
    first.unmount();
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    expect((screen.getByLabelText('Sprache') as HTMLSelectElement).value).toBe('en-GB');
    expect(field('E-Mail-Adresse').value).toBe('neu@example.org');
    expect(field('Telefonnummer').value).toBe('+49 151 2345678');
    expect(field('Bezeichnung Favorit 1').value).toBe('Zuhause');
    expect(field('Haltepunkt Favorit 1').value).toBe(STOP_A);
    expect(field('Bevorzugter Haltepunkt 1').value).toBe(STOP_B);
    expect((screen.getByRole('checkbox', { name: 'SMS' }) as HTMLInputElement).checked).toBe(true);
    expect(
      (screen.getByRole('checkbox', { name: 'Push-Mitteilung' }) as HTMLInputElement).checked,
    ).toBe(false);
  });

  it('veraltete Revision (anderes Gerät) überschreibt nichts und führt zum aktuellen Stand', async () => {
    const server = fakeServer();
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    server.state.profile.revision += 1; // zweites Gerät hat inzwischen gespeichert
    fireEvent.change(screen.getByLabelText('Sprache'), { target: { value: 'en-GB' } });
    fireEvent.click(screen.getByRole('button', { name: 'Profil speichern' }));
    await screen.findByText(/zwischenzeitlich geändert/);
    expect(server.state.profile.locale).toBe('de-DE');
    expect(server.state.patches).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Aktuellen Stand laden' }));
    await waitFor(() =>
      expect((screen.getByLabelText('Sprache') as HTMLSelectElement).value).toBe('de-DE'),
    );
  });

  it('unbekannte Haltestelle wird am Feld gemeldet, ohne zu speichern', async () => {
    const server = fakeServer();
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    fireEvent.click(screen.getByRole('button', { name: 'Haltestelle hinzufügen' }));
    fireEvent.change(field('Bevorzugter Haltepunkt 1'), {
      target: { value: '33333333-3333-4333-8333-333333333333' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Profil speichern' }));
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(/Bevorzugter Haltepunkt 1/)).toBeInTheDocument();
    expect(server.state.profile.preferred_stops).toEqual([]);
  });

  it('„Verifikation fehlt“ führt zur Verifikation; falscher Code wird abgewiesen, richtiger verifiziert', async () => {
    const server = fakeServer({
      status: 'PENDING_VERIFICATION',
      contact: {
        email: null,
        phone: { value: '+491512345678', status: 'UNVERIFIED', verified_at: null },
      },
    });
    const { container } = show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    expect(screen.getAllByText(/Verifikation fehlt/).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: 'Code für Telefon anfordern' }));
    await screen.findByText('Der Code wurde gesendet.');
    fireEvent.change(screen.getByLabelText('Code für Telefon'), { target: { value: '000000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Telefon bestätigen' }));
    await screen.findByText('Der Code ist falsch oder abgelaufen.');
    fireEvent.change(screen.getByLabelText('Code für Telefon'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Telefon bestätigen' }));
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Telefon bestätigen' })).toBeNull(),
    );
    expect(server.state.verified).toEqual(['PHONE']);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('Unterstützungsbedarf: nur Auswahl aus dem Katalog, wird gespeichert', async () => {
    const server = fakeServer();
    show(server.client);
    await screen.findByRole('heading', { name: 'Unterstützung' });
    fireEvent.click(screen.getByRole('checkbox', { name: 'Niederflureinstieg' }));
    fireEvent.click(screen.getByRole('button', { name: 'Auswahl speichern' }));
    await screen.findByText('Gespeichert.');
    expect(server.state.support).toEqual(['LOW_FLOOR']);
    expect(screen.queryByRole('textbox', { name: /Diagnose/i })).toBeNull();
  });
});
