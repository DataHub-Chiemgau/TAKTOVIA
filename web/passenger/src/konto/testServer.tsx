import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, Profile, ProfileUpdate } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { Profil } from './Profil';

export const STOP_A = '11111111-1111-4111-8111-111111111111';
export const STOP_B = '22222222-2222-4222-8222-222222222222';

/**
 * Ersatz des Servers (kein Ersatz für die echten Komponenten): hält das Profil über Neuanmeldungen hinweg, prüft `If-Match` wie der Server (412) und lehnt
 * unbekannte Haltestellen mit 422 ab. Das echte Serververhalten belegen `PassengerProfileTest` und `PassengerApiTest` im Backend.
 */
export function fakeServer(initial?: Partial<Profile>) {
  /** Haltepunkte; `active: false` = inzwischen deaktiviert (nur noch als Favoritenbezug bekannt, nicht in der Liste). */
  const stops = [
    { stop_id: STOP_A, name: 'Altmarkt', lat: 51.05, lon: 13.74, active: true },
    { stop_id: STOP_B, name: 'Hauptbahnhof', lat: 51.04, lon: 13.73, active: true },
  ];
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
    stops,
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
          const problem = (pointer: string) =>
            Promise.reject(
              new ApiError(422, {
                type: 't',
                title: 'x',
                status: 422,
                code: 'VALIDATION_FAILED',
                correlation_id: 'c',
                violations: [{ pointer, code: 'INVALID_VALUE', message: 'm' }],
              }),
            );
          const bad = (b.preferred_stops ?? []).findIndex((s) => !known.has(s));
          if (bad >= 0) return problem(`/preferred_stops/${bad}`);
          // Favoriten wie der Server: Koordinate im Wertebereich und nie 0/0, Haltepunkt muss bekannt sein
          for (const [i, f] of (b.favorites ?? []).entries()) {
            if (f.stop_id !== undefined && !known.has(f.stop_id))
              return problem(`/favorites/${i}/stop_id`);
            if (f.lat !== undefined && (f.lat < -90 || f.lat > 90 || (f.lat === 0 && f.lon === 0)))
              return problem(`/favorites/${i}/lat`);
          }
          state.patches.push(b);
          const p = state.profile;
          if (b.locale) p.locale = b.locale as Profile['locale'];
          if (b.contact?.email)
            p.contact.email = { value: b.contact.email, status: 'UNVERIFIED', verified_at: null };
          if (b.contact?.phone)
            p.contact.phone = { value: b.contact.phone, status: 'UNVERIFIED', verified_at: null };
          if (b.favorites)
            p.favorites = b.favorites.map((f) => {
              const st = stops.find((x) => x.stop_id === f.stop_id);
              return {
                label: f.label,
                lat: f.lat ?? st?.lat ?? 0,
                lon: f.lon ?? st?.lon ?? 0,
                stop_id: f.stop_id ?? null,
                stop_bookable: st?.active ?? false,
              };
            });
          if (b.preferred_stops) p.preferred_stops = b.preferred_stops;
          if (b.notification_channels) p.notification_channels = b.notification_channels;
          p.revision += 1;
          return reply(p);
        }
        case 'listStops':
          return reply({
            items: stops
              .filter((x) => x.active)
              .map((x) => ({
                id: x.stop_id,
                stop_id: x.stop_id,
                revision: 1,
                name: x.name,
                point: { latitude: x.lat, longitude: x.lon },
                active: true,
              })),
            next_cursor: null,
            as_of: '2026-10-25T08:00:00Z',
          });
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

export function show(client: ApiClient) {
  return render(
    <MemoryRouter>
      <SessionProvider client={client} now={() => Date.parse('2026-10-25T09:00:00+01:00')}>
        <Profil />
      </SessionProvider>
    </MemoryRouter>,
  );
}

export const field = (name: string) => screen.getByLabelText(name) as HTMLInputElement;
