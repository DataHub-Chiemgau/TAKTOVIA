import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { Profil } from './Profil';
import { fakeServer, field, show, STOP_A } from './testServer';

const save = () => fireEvent.click(screen.getByRole('button', { name: 'Profil speichern' }));

describe('UI-05 Favoriten, Sprache, Kontakt (WP-M2-36)', () => {
  afterEach(cleanup);

  it('favorit_anlegen_umbenennen_loeschen', async () => {
    const server = fakeServer();
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    // leerer Zustand
    expect(screen.getByText('Noch keine Favoriten.')).toBeInTheDocument();
    // anlegen mit bestätigter Koordinate
    fireEvent.click(screen.getByRole('button', { name: 'Favorit hinzufügen' }));
    fireEvent.change(field('Bezeichnung Favorit 1'), { target: { value: 'Zuhause' } });
    fireEvent.change(field('Breitengrad Favorit 1'), { target: { value: '51,0412' } });
    fireEvent.change(field('Längengrad Favorit 1'), { target: { value: '13.7512' } });
    save();
    await screen.findByText('Gespeichert.');
    expect(server.state.profile.favorites).toEqual([
      { label: 'Zuhause', lat: 51.0412, lon: 13.7512, stop_id: null, stop_bookable: false },
    ]);
    // umbenennen
    fireEvent.change(field('Bezeichnung Favorit 1'), { target: { value: 'Daheim' } });
    save();
    await waitFor(() => expect(server.state.profile.favorites[0]?.label).toBe('Daheim'));
    expect(server.state.profile.revision).toBe(5);
    // löschen
    fireEvent.click(screen.getByRole('button', { name: 'Favorit 1 entfernen' }));
    save();
    await waitFor(() => expect(server.state.profile.favorites).toEqual([]));
    expect(screen.getByText('Noch keine Favoriten.')).toBeInTheDocument();
  });

  it('favorit_mit_haltestelle_uebernimmt_koordinate_und_name', async () => {
    const server = fakeServer();
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    fireEvent.click(screen.getByRole('button', { name: 'Favorit hinzufügen' }));
    await screen.findByRole('option', { name: 'Altmarkt' });
    fireEvent.change(screen.getByLabelText('Haltestelle Favorit 1'), { target: { value: STOP_A } });
    expect(field('Bezeichnung Favorit 1').value).toBe('Altmarkt');
    expect(field('Breitengrad Favorit 1').value).toBe('51.05');
    save();
    await screen.findByText('Gespeichert.');
    expect(server.state.profile.favorites[0]).toMatchObject({
      stop_id: STOP_A,
      lat: 51.05,
      lon: 13.74,
      stop_bookable: true,
    });
  });

  it('sprachwechsel_wird_gespeichert_und_gilt_fuer_die_oberflaeche', async () => {
    const server = fakeServer();
    const first = show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    expect(document.documentElement.lang).toBe('de-DE');
    fireEvent.change(screen.getByLabelText('Sprache'), { target: { value: 'en-GB' } });
    save();
    await screen.findByText('Gespeichert.');
    expect(server.state.profile.locale).toBe('en-GB');
    expect(document.documentElement.lang).toBe('en-GB');
    // nach erneutem Laden (anderes Gerät) dieselbe Sprache
    first.unmount();
    document.documentElement.lang = 'de-DE';
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    await waitFor(() => expect(document.documentElement.lang).toBe('en-GB'));
  });

  it('ungueltige_koordinate_wird_am_feld_gemeldet_ohne_zu_senden', async () => {
    const server = fakeServer();
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    const cases: [string, string][] = [
      ['91', '13'],
      ['51', '181'],
      ['abc', '13'],
      ['0', '0'],
      ['', ''],
    ];
    for (const [lat, lon] of cases) {
      cleanup();
      show(server.client);
      await screen.findByRole('heading', { name: 'Profil' });
      fireEvent.click(screen.getByRole('button', { name: 'Favorit hinzufügen' }));
      fireEvent.change(field('Bezeichnung Favorit 1'), { target: { value: 'X' } });
      fireEvent.change(field('Breitengrad Favorit 1'), { target: { value: lat } });
      fireEvent.change(field('Längengrad Favorit 1'), { target: { value: lon } });
      save();
      const alert = await screen.findByRole('alert');
      expect(
        within(alert).getAllByText(/(Breitengrad|Längengrad) Favorit 1|Bezeichnung Favorit 1/)
          .length,
      ).toBeGreaterThan(0);
    }
    expect(server.state.patches).toHaveLength(0);
  });

  it('nicht_existierende_haltepunktreferenz_wird_vom_server_abgewiesen_und_am_feld_gezeigt', async () => {
    const server = fakeServer();
    // in der Liste, dem Server aber (inzwischen) unbekannt
    server.state.stops.push({
      stop_id: '33333333-3333-4333-8333-333333333333',
      name: 'Geisterhalt',
      lat: 51.1,
      lon: 13.8,
      active: true,
    });
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    fireEvent.click(screen.getByRole('button', { name: 'Favorit hinzufügen' }));
    await screen.findByRole('option', { name: 'Geisterhalt' });
    fireEvent.change(screen.getByLabelText('Haltestelle Favorit 1'), {
      target: { value: '33333333-3333-4333-8333-333333333333' },
    });
    save();
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(/Breitengrad Favorit 1/)).toBeInTheDocument();
    expect(within(alert).getByText(/Dieser Wert ist ungültig/)).toBeInTheDocument();
    expect(server.state.profile.favorites).toEqual([]);
  });

  it('veralteter_stand_ergibt_412_ohne_zu_ueberschreiben', async () => {
    const server = fakeServer();
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    server.state.profile.revision += 1;
    fireEvent.click(screen.getByRole('button', { name: 'Favorit hinzufügen' }));
    fireEvent.change(field('Bezeichnung Favorit 1'), { target: { value: 'Zuhause' } });
    fireEvent.change(field('Breitengrad Favorit 1'), { target: { value: '51' } });
    fireEvent.change(field('Längengrad Favorit 1'), { target: { value: '13' } });
    save();
    await screen.findByText(/zwischenzeitlich geändert/);
    expect(server.state.profile.favorites).toEqual([]);
  });

  it('favorit_mit_inaktivem_haltepunkt_bleibt_ort_wird_aber_nicht_als_haltestelle_vorgeschlagen', async () => {
    const server = fakeServer({
      favorites: [
        {
          label: 'Alte Haltestelle',
          lat: 51.05,
          lon: 13.74,
          stop_id: STOP_A,
          stop_bookable: false,
        },
      ],
    });
    const first = server.state.stops[0];
    if (first) first.active = false;
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    expect(screen.getByText(/nicht mehr aktiv\. Der Ort bleibt nutzbar/)).toBeInTheDocument();
    expect(field('Breitengrad Favorit 1').value).toBe('51.05');
    // in der Auswahl wird die inaktive Haltestelle nicht als Vorschlag angeboten
    expect(screen.queryByRole('option', { name: 'Altmarkt' })).toBeNull();
    expect(
      screen.getByRole('option', { name: 'Haltestelle (nicht mehr aktiv)' }),
    ).toBeInTheDocument();
    // der Ort bleibt speicherbar
    fireEvent.change(field('Bezeichnung Favorit 1'), { target: { value: 'Alter Ort' } });
    save();
    await screen.findByText('Gespeichert.');
    expect(server.state.profile.favorites[0]).toMatchObject({ label: 'Alter Ort', lat: 51.05 });
  });

  it('nicht_verifizierte_kontaktkanaele_sind_gekennzeichnet', async () => {
    const server = fakeServer({
      contact: {
        email: { value: 'a@example.org', status: 'VERIFIED', verified_at: '2026-10-25T08:05:00Z' },
        phone: { value: '+491512345678', status: 'UNVERIFIED', verified_at: null },
      },
    });
    show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    expect(screen.getAllByText('Kontakt verifiziert.')).toHaveLength(1);
    expect(screen.getAllByText(/Verifikation fehlt: Dieser Kontakt/)).toHaveLength(1);
  });

  it('fremde_oder_fehlende_kennung_zeigt_nicht_gefunden_ohne_daten', async () => {
    const client = {
      call: () => Promise.reject(new ApiError(404)),
    } as unknown as ApiClient;
    render(
      <MemoryRouter>
        <SessionProvider client={client} now={() => 0}>
          <Profil />
        </SessionProvider>
      </MemoryRouter>,
    );
    await screen.findByRole('heading', { name: /nicht gefunden/i });
    expect(screen.queryByLabelText(/Bezeichnung Favorit/)).toBeNull();
    void sampleMe;
  });

  it('favoriten_bereich_ist_barrierefrei', async () => {
    const server = fakeServer({
      favorites: [
        { label: 'Zuhause', lat: 51.04, lon: 13.75, stop_id: null, stop_bookable: false },
      ],
    });
    const { container } = show(server.client);
    await screen.findByRole('heading', { name: 'Profil' });
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
