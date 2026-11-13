import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import axe from 'axe-core';
import type { AxeResults } from 'axe-core';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { SessionProvider } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { LiveRegionProvider } from '@taktovia/design-system';
import { NOW, OFFER_ID, buchungsWelt } from '../booking/buchungsWelt';
import { waehleOrte } from '../booking/buchungsAblauf';
import { BookingDetail } from '../booking/BookingDetail';
import { CancelBooking } from '../booking/CancelBooking';
import { CheckoutScreen } from '../booking/CheckoutScreen';
import { OfferScreen } from '../booking/OfferScreen';
import { StartView } from '../booking/StartView';
import { basisBuchung, reiseWelt, renderReise } from '../booking/reiseWelt';

/**
 * WP-M2-47: Regressionssuite der Kernreise Suche -> Angebot -> Checkout -> Detail -> Storno (UI-01 bis UI-05) im Komponentenumfeld (jsdom).
 * Geprueft wird, was ohne Layout und ohne Screenreader pruefbar ist: axe-core mit dem WCAG-2.2-AA-Regelsatz an jedem Schritt, Bedienbarkeit
 * ohne Karte und ohne Zeigergeraet (nur native, fokussierbare Bedienelemente), Statusansagen ueber die Live-Region ohne Fokussprung und
 * Quelltextregeln fuer Zoom, Reflow, Touchflaechen und reduzierte Bewegung. NICHT geprueft werden (jsdom): Farbkontrast, tatsaechlicher Reflow bei
 * 320 CSS-Pixeln, Textvergroesserung und die Ansage durch einen echten Screenreader; das leisten `npm run a11y:browser` (echter Browser) und das
 * manuelle Pruefprotokoll AT-19 (docs/umsetzungsplan/nachweise/WP-M2-47-pruefprotokoll.md).
 */

const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];
const now = () => NOW;

async function axeVerstoesse(container: Element): Promise<string> {
  const r: AxeResults = await axe.run(container, {
    runOnly: { type: 'tag', values: AXE_TAGS },
    // jsdom rendert kein Layout: Kontrast und Landmark-Rahmen werden im Browserlauf geprueft.
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
  });
  return r.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length})`).join('\n');
}

/** Alle per Tab erreichbaren Elemente in Dokumentreihenfolge (ohne tabindex=-1, disabled, hidden, aria-hidden-Teilbaeume). */
function tabbable(root: Element = document.body): HTMLElement[] {
  const sel =
    'a[href], button, input:not([type=hidden]), select, textarea, summary, [tabindex]:not([tabindex="-1"])';
  return [...root.querySelectorAll<HTMLElement>(sel)].filter(
    (el) =>
      !(el as HTMLButtonElement).disabled &&
      !el.closest('[hidden], [aria-hidden="true"], [inert]') &&
      el.getAttribute('tabindex') !== '-1',
  );
}

function pruefeBedienbarkeit(label: string) {
  const items = tabbable();
  expect(items.length, `${label}: keine Tab-Stopps`).toBeGreaterThan(0);
  for (const el of items) {
    // kein positiver tabindex (stoert die natuerliche Reihenfolge), jedes Ziel hat einen zugaenglichen Namen
    expect(
      Number(el.getAttribute('tabindex') ?? 0),
      `${label}: positiver tabindex`,
    ).toBeLessThanOrEqual(0);
    const name =
      el.getAttribute('aria-label') ??
      (el.id ? document.querySelector(`label[for="${el.id}"]`)?.textContent : null) ??
      el.closest('label')?.textContent ??
      el.getAttribute('aria-labelledby') ??
      el.textContent;
    expect(
      (name ?? '').trim().length,
      `${label}: Bedienelement ohne Namen ${el.outerHTML.slice(0, 80)}`,
    ).toBeGreaterThan(0);
    // nichts, was nur mit Zeigergeraet geht: kein SVG-/Karten-Element im Tab-Pfad
    expect(el.closest('svg'), `${label}: Karte im Tab-Pfad`).toBeNull();
  }
  expect(
    document.querySelector('[role="application"]'),
    `${label}: Karte als Anwendung`,
  ).toBeNull();
}

function renderKern(client: ApiClient) {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <SessionProvider client={client}>
        <LiveRegionProvider>
          <Routes>
            <Route path="/" element={<StartView now={now} />} />
            <Route path="/angebote" element={<OfferScreen now={now} />} />
            <Route path="/checkout/:offerId" element={<CheckoutScreen now={now} pollMs={5} />} />
            <Route path="/reisen" element={<p>Meine Reisen</p>} />
            <Route path="/reisen/:bookingId" element={<p>Reise</p>} />
          </Routes>
        </LiveRegionProvider>
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe('AT-19 Kernreise barrierefrei (WP-M2-47)', () => {
  afterEach(cleanup);

  it('kernreise_suche_angebot_checkout_ohne_karte_nur_mit_nativen_bedienelementen_und_axe_wcag22aa', async () => {
    const { client, world } = buchungsWelt();
    const { container } = renderKern(client);
    // UI-01 Suche
    await screen.findByRole('combobox', { name: 'Start' });
    await waehleOrte();
    await screen.findByTestId('orte-bereit');
    expect(await axeVerstoesse(container)).toBe('');
    pruefeBedienbarkeit('UI-01');
    // jede Eingabe ist per Fokus erreichbar
    for (const n of ['Reisende', 'Rollstuhlplatz benötigt', 'Jetzt losfahren']) {
      const el = screen.getByLabelText(n);
      el.focus();
      expect(document.activeElement).toBe(el);
    }
    fireEvent.click(screen.getByRole('button', { name: 'Verbindungen suchen' }));
    // UI-02 Angebot
    const auswahl = await screen.findByRole('button', { name: /Angebot auswählen/ });
    expect(await axeVerstoesse(container)).toBe('');
    pruefeBedienbarkeit('UI-02');
    auswahl.focus();
    fireEvent.click(auswahl);
    const weiter = await screen.findByRole('link', { name: /Weiter zur Buchung/ });
    expect(weiter.getAttribute('href')).toBe(`/checkout/${OFFER_ID}`);
    fireEvent.click(weiter);
    // UI-03 Checkout
    const radio = await screen.findByRole('radio', { name: /gelingt/ });
    expect(await axeVerstoesse(container)).toBe('');
    pruefeBedienbarkeit('UI-03');
    fireEvent.click(radio);
    fireEvent.click(screen.getByRole('checkbox', { name: /Beförderungs- und Stornobedingungen/ }));
    const buchen = screen.getByTestId('zahlungspflichtig-buchen');
    buchen.focus();
    fireEvent.click(buchen);
    // Statusansage ueber die Live-Region, ohne dass der Fokus auf ein anderes Element springt
    const status = screen.getAllByRole('status').find((el) => el.classList.contains('tk-sr-only'));
    await screen.findByTestId('buchungsnummer');
    await waitFor(() => expect(status?.textContent ?? '').not.toBe(''));
    expect([buchen, document.body]).toContain(document.activeElement);
    expect(await axeVerstoesse(container)).toBe('');
    // die Reise lief ohne einen einzigen Kartenaufruf: genau ein Versuch, ein Hold, eine Buchung
    expect(world.count('createRideOffer')).toBe(1);
    expect(world.bookings).toHaveLength(1);
  });

  it('detail_und_storno_per_tastatur_axe_wcag22aa_und_statusansage_ohne_fokussprung', async () => {
    const { client, world } = reiseWelt(basisBuchung());
    const { container } = renderReise(client, (id) => (
      <BookingDetail bookingId={id} zone="Europe/Berlin" pollMs={10}>
        {(b, reload) => <CancelBooking booking={b} zone="Europe/Berlin" onChanged={reload} />}
      </BookingDetail>
    ));
    // UI-04 Detail
    const starten = await screen.findByTestId('storno-starten');
    expect(screen.getByRole('heading', { level: 2, name: /.+/ })).toBeInTheDocument();
    expect(await axeVerstoesse(container)).toBe('');
    pruefeBedienbarkeit('UI-04 Detail');
    starten.focus();
    fireEvent.click(starten);
    // Storno: Gruppe mit Grundauswahl und ausdruecklicher Bestaetigung
    const gruppe = await screen.findByRole('group');
    expect(within(gruppe).getByRole('combobox', { name: 'Grund' })).toBeInTheDocument();
    expect(await axeVerstoesse(container)).toBe('');
    pruefeBedienbarkeit('UI-04 Storno');
    const bestaetigen = await screen.findByTestId('storno-bestaetigen');
    bestaetigen.focus();
    fireEvent.click(bestaetigen);
    await screen.findByTestId('storno-ergebnis');
    const status = screen.getAllByRole('status').find((el) => el.classList.contains('tk-sr-only'));
    await waitFor(() => expect(status?.textContent ?? '').toMatch(/storn/i));
    expect([bestaetigen, document.body]).toContain(document.activeElement);
    expect(await axeVerstoesse(container)).toBe('');
    expect(world.count('cancelBooking')).toBe(1);
  });

  it('live_region_ist_hoeflich_atomar_und_von_anfang_an_im_dom', async () => {
    const { client } = reiseWelt(basisBuchung());
    renderReise(client, () => <p>x</p>);
    const live = document.querySelector('[role="status"].tk-sr-only');
    expect(live).not.toBeNull();
    expect(live?.getAttribute('aria-live')).toBe('polite');
    expect(live?.getAttribute('aria-atomic')).toBe('true');
  });
});

/** Quelltextregeln fuer das, was jsdom nicht messen kann (Gegenprobe im Browserlauf `npm run a11y:browser`). */
function quellen(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) quellen(p, out);
    else if (/\.(tsx|css)$/.test(e) && !/\.test\./.test(e) && !/Welt\.tsx$/.test(e)) out.push(p);
  }
  return out;
}

describe('AT-19 Quelltextregeln der Passenger-PWA (Zoom, Reflow, Bewegung, Zeigerunabhaengigkeit)', () => {
  const src = resolve(__dirname, '..');
  const files = quellen(src).map((f) => ({ f, t: readFileSync(f, 'utf8') }));
  const styles = readFileSync(resolve(src, '../../design-system/src/styles.css'), 'utf8');

  it('keine_festen_pixelschriftgroessen_und_keine_feste_breite_ueber_320', () => {
    for (const { f, t } of files) {
      expect(t, `${f}: font-size in px`).not.toMatch(/fontSize:\s*['"]?\d+px|font-size:\s*\d+px/);
      expect(t, `${f}: feste Breite > 320px`).not.toMatch(
        /width:\s*['"]?(3[2-9]\d|[4-9]\d\d|\d{4,})px/,
      );
    }
    expect(styles).not.toMatch(/font-size:\s*\d+px/);
    expect(styles).toMatch(/overflow-wrap:\s*anywhere/);
  });

  it('kein_positiver_tabindex_kein_autofocus_kein_outline_none', () => {
    for (const { f, t } of files) {
      expect(t, `${f}: positiver tabindex`).not.toMatch(/tabIndex=\{?['"]?[1-9]/);
      expect(t, `${f}: autoFocus verschiebt den Fokus unangekuendigt`).not.toMatch(/\bautoFocus\b/);
      expect(t, `${f}: outline entfernt`).not.toMatch(/outline:\s*(none|0)\b/);
    }
  });

  it('keine_klickflaechen_auf_nicht_interaktiven_elementen', () => {
    for (const { f, t } of files) {
      expect(t, `${f}: onClick auf div/span (nicht per Tastatur bedienbar)`).not.toMatch(
        /<(div|span|li|p)\b[^>]*\sonClick=/,
      );
    }
  });

  it('touchflaechen_44px_reduzierte_bewegung_und_sichtbarer_fokus_im_designsystem', () => {
    expect(styles).toMatch(/--tk-touch-min:\s*44px/);
    expect(styles).toMatch(/@media \(prefers-reduced-motion:\s*reduce\)/);
    expect(styles).toMatch(/:focus-visible\s*\{[^}]*outline:/);
  });

  it('karte_ist_beschriftetes_bild_und_marker_sind_nicht_fokussierbar', () => {
    const karte = files.find((x) => x.f.endsWith('StopMap.tsx'))?.t ?? '';
    expect(karte).toMatch(/role="img"|role='img'/);
    expect(karte).toMatch(/aria-hidden/);
    expect(karte).not.toMatch(/tabIndex=\{?['"]?0/);
  });
});
