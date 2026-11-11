import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppFrame, ScreenFrame, visibleNav } from '../index';
import { formatViolations, runAxe } from '../testing/axe';

const nav = [
  { id: 'a', label: 'Callcenter', href: '/callcenter', requires: 'callcenter' },
  { id: 'b', label: 'Finanzen', href: '/finanzen', requires: 'finance' },
  { id: 'c', label: 'Start', href: '/' },
] as const;

const context = {
  tenant: 'Verkehrsbetrieb Nord',
  service: 'Rufbus Nord',
  status: { tone: 'ok', label: 'Sitzung aktiv' },
  dataAsOf: '2026-10-25T09:00:00+01:00',
  zone: 'Europe/Berlin',
} as const;

describe('Navigation je Rolle', () => {
  it('zeigt nur Einträge mit vorhandenem Recht, Einträge ohne Anforderung immer', () => {
    expect(visibleNav([...nav], new Set(['callcenter'])).map((n) => n.id)).toEqual(['a', 'c']);
    expect(visibleNav([...nav], new Set()).map((n) => n.id)).toEqual(['c']);
  });
});

describe('Anwendungsrahmen', () => {
  function renderFrame() {
    return render(
      <AppFrame brand="TAKTOVIA Operator" nav={[...nav]} currentHref="/callcenter">
        <ScreenFrame title="Callcenter" context={context} detail={<p>Detail</p>}>
          <p>Inhalt</p>
        </ScreenFrame>
      </AppFrame>,
    );
  }

  it('hat Landmarken: Kopfbereich, Navigation, genau ein Hauptbereich, Detailpanel; erste Ebene ist Sprunglink', async () => {
    const { container } = renderFrame();
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Hauptnavigation' })).toBeInTheDocument();
    expect(screen.getAllByRole('main')).toHaveLength(1);
    expect(screen.getByRole('complementary', { name: 'Details' })).toHaveTextContent('Detail');
    expect(screen.getByRole('link', { name: 'Zum Hauptinhalt springen' })).toHaveAttribute(
      'href',
      '#main',
    );
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('markiert die aktuelle Seite mit aria-current', () => {
    renderFrame();
    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    expect(within(nav).getByRole('link', { name: 'Callcenter' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: 'Finanzen' })).not.toHaveAttribute('aria-current');
  });

  it('der Menüschalter für schmale Anzeigen steuert die Navigation per aria-expanded', () => {
    renderFrame();
    const toggle = screen.getByRole('button', { name: 'Menü' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    fireEvent.keyDown(toggle, { key: 'Escape' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('jeder Bildschirmrahmen benennt Mandant, Dienst, fachlichen Status und Datenstand mit Zone', () => {
    renderFrame();
    const ctx = screen.getByRole('region', { name: 'Arbeitskontext' });
    expect(ctx).toHaveTextContent('Verkehrsbetrieb Nord');
    expect(ctx).toHaveTextContent('Rufbus Nord');
    expect(ctx).toHaveTextContent('Sitzung aktiv');
    expect(ctx).toHaveTextContent('Datenstand');
    expect(ctx).toHaveTextContent('Europe/Berlin');
  });

  it('ohne Detailpanel wird kein leeres Panel gerendert', () => {
    render(
      <AppFrame brand="X" nav={[]} currentHref="/">
        <ScreenFrame title="T" context={context}>
          <p>Inhalt</p>
        </ScreenFrame>
      </AppFrame>,
    );
    expect(screen.queryByRole('complementary')).toBeNull();
  });
});
