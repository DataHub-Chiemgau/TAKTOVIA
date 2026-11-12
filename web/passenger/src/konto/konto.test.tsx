import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { fakeServer, field, show, STOP_B } from './testServer';

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
    fireEvent.change(field('Breitengrad Favorit 1'), { target: { value: '51.04' } });
    fireEvent.change(field('Längengrad Favorit 1'), { target: { value: '13.75' } });
    fireEvent.click(screen.getByRole('button', { name: 'Haltestelle hinzufügen' }));
    fireEvent.change(field('Bevorzugter Haltepunkt 1'), { target: { value: STOP_B } });
    fireEvent.click(screen.getByRole('button', { name: 'Profil speichern' }));
    await screen.findByText('Gespeichert.');

    expect(server.state.profile).toMatchObject({
      locale: 'en-GB',
      revision: 4,
      favorites: [{ label: 'Zuhause', lat: 51.04, lon: 13.75, stop_id: null }],
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
    expect(field('Breitengrad Favorit 1').value).toBe('51.04');
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
