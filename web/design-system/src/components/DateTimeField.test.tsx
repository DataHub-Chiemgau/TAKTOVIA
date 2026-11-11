import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { DateTimeField, ZonedTime } from '../index';
import type { DateTimeValue } from './DateTimeField';
import {
  formatLocal,
  isKnownZone,
  offsetMinutesAt,
  operatingDay,
  resolveLocal,
  toRfc3339,
} from '../time';
import { formatViolations, runAxe } from '../testing/axe';

function Harness({
  initial,
  zone = 'Europe/Berlin',
  onInstant,
}: {
  initial: DateTimeValue;
  zone?: string;
  onInstant?: (i: string | null) => void;
}) {
  const [v, setV] = useState(initial);
  return (
    <DateTimeField
      label="Abholzeit"
      zone={zone}
      value={v}
      onChange={(n) => {
        setV({ local: n.local, offset: n.offset });
        onInstant?.(n.instant);
      }}
    />
  );
}

describe('DateTimeField (LH-6.1-SYS-006)', () => {
  it('zeigt_zone_offset_und_verlangt_offsetwahl_bei_mehrdeutiger_ortszeit', () => {
    // eindeutig im Sommer: Zone, Offset, RFC 3339 und Betriebstag stehen da
    const { unmount } = render(
      <Harness initial={{ local: '2026-07-01T12:00:00', offset: null }} />,
    );
    expect(screen.getByTestId('zone')).toHaveTextContent('Europe/Berlin (UTC+02:00)');
    expect(screen.getByText('2026-07-01T12:00:00+02:00')).toBeInTheDocument();
    expect(screen.getByTestId('operating-day')).toHaveTextContent('2026-07-01');
    expect(screen.queryByRole('alert')).toBeNull();
    unmount();

    // mehrdeutig (25.10.2026 02:30): kein Zeitpunkt, bis der Offset gewählt ist
    const seen: (string | null)[] = [];
    render(
      <Harness
        initial={{ local: '2026-10-25T02:30:00', offset: null }}
        onInstant={(i) => seen.push(i)}
      />,
    );
    expect(screen.getByTestId('zone')).toHaveTextContent('Europe/Berlin (UTC+02:00 oder +01:00)');
    expect(screen.getByRole('group', { name: 'UTC-Offset wählen' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('zweimal');
    const radios = screen.getAllByRole('radio');
    expect(radios.map((r) => (r as HTMLInputElement).value)).toEqual(['+02:00', '+01:00']);
    expect(radios.every((r) => !(r as HTMLInputElement).checked)).toBe(true);
    expect(screen.queryByTestId('operating-day')).toBeNull();

    fireEvent.click(radios[1] as HTMLElement);
    expect(seen.at(-1)).toBe('2026-10-25T02:30:00+01:00');
    expect(screen.getByText('2026-10-25T02:30:00+01:00')).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('radio')[0] as HTMLElement);
    expect(seen.at(-1)).toBe('2026-10-25T02:30:00+02:00');

    // Eingabeänderung verwirft die Wahl; wieder eindeutig -> kein Offsetwahl-Feld mehr
    fireEvent.change(screen.getByLabelText('Abholzeit'), {
      target: { value: '2026-10-25T04:00:00' },
    });
    expect(seen.at(-1)).toBe('2026-10-25T04:00:00+01:00');
    expect(screen.queryByRole('group')).toBeNull();
  });

  it('weist eine nicht existente Ortszeit ab und verlangt eine neue Eingabe', () => {
    const seen: (string | null)[] = [];
    render(
      <Harness
        initial={{ local: '2026-03-29T02:30:00', offset: null }}
        onInstant={(i) => seen.push(i)}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('existiert wegen der Zeitumstellung nicht');
    expect(screen.getByLabelText('Abholzeit')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByTestId('operating-day')).toBeNull();
    fireEvent.change(screen.getByLabelText('Abholzeit'), {
      target: { value: '2026-03-29T03:30:00' },
    });
    expect(seen.at(-1)).toBe('2026-03-29T03:30:00+02:00');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('meldet eine unbekannte Zone', () => {
    render(
      <Harness zone="Mars/Olympus" initial={{ local: '2026-07-01T12:00:00', offset: null }} />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Unbekannte Zeitzone');
  });

  it('hat keine axe-Verstöße (eindeutig, mehrdeutig, Lücke)', async () => {
    for (const local of ['2026-07-01T12:00:00', '2026-10-25T02:30:00', '2026-03-29T02:30:00']) {
      const { container, unmount } = render(<Harness initial={{ local, offset: null }} />);
      expect(formatViolations(await runAxe(container))).toBe('');
      unmount();
    }
  });
});

describe('ZonedTime: Anzeige in der Zone des Dienstes mit Offset und Betriebstag', () => {
  it('zeigt Ortszeit, Offset und lokalen Betriebstag', () => {
    render(<ZonedTime instant="2026-10-24T23:30:00Z" zone="Europe/Berlin" />);
    expect(screen.getByText('25.10.2026, 01:30:00 (UTC+02:00)')).toBeInTheDocument();
    expect(screen.getByTestId('operating-day')).toHaveTextContent('2026-10-25');
  });

  it('der Betriebstag folgt der Zone des Dienstes, nicht UTC', () => {
    render(<ZonedTime instant="2026-10-24T23:30:00Z" zone="America/New_York" />);
    expect(screen.getByTestId('operating-day')).toHaveTextContent('2026-10-24');
    expect(screen.getByText(/UTC-04:00/)).toBeInTheDocument();
  });

  it('Betriebstagbeginn 03:00 ordnet die Nacht dem Vortag zu', () => {
    render(<ZonedTime instant="2026-10-24T23:30:00Z" zone="Europe/Berlin" dayStartMinutes={180} />);
    expect(screen.getByTestId('operating-day')).toHaveTextContent('2026-10-24');
  });

  it('ungültige Eingabe wird als Fehler gemeldet', () => {
    render(<ZonedTime instant="kein Zeitpunkt" zone="Europe/Berlin" />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});

describe('time.ts', () => {
  it('Offsets und RFC 3339 an der Umstellung', () => {
    const summer = Date.parse('2026-10-25T00:30:00Z');
    const winter = Date.parse('2026-10-25T01:30:00Z');
    expect(toRfc3339(summer, 'Europe/Berlin')).toBe('2026-10-25T02:30:00+02:00');
    expect(toRfc3339(winter, 'Europe/Berlin')).toBe('2026-10-25T02:30:00+01:00');
    expect(offsetMinutesAt(summer, 'Europe/Berlin')).toBe(120);
    expect(formatLocal(winter, 'Europe/Berlin')).toBe('25.10.2026, 02:30:00 (UTC+01:00)');
  });

  it('Auflösung: eindeutig, mehrdeutig, Lücke, ungültig, unbekannte Zone', () => {
    expect(resolveLocal('2026-07-01T12:00', 'Europe/Berlin').kind).toBe('unique');
    const amb = resolveLocal('2026-10-25T02:30', 'Europe/Berlin');
    expect(amb.kind === 'ambiguous' && amb.options.map((o) => o.offset)).toEqual([
      '+02:00',
      '+01:00',
    ]);
    expect(resolveLocal('2026-03-29T02:30', 'Europe/Berlin').kind).toBe('gap');
    expect(resolveLocal('2026-03-29T01:59:59', 'Europe/Berlin').kind).toBe('unique');
    expect(resolveLocal('2026-03-29T03:00', 'Europe/Berlin').kind).toBe('unique');
    expect(resolveLocal('2026-02-30T10:00', 'Europe/Berlin').kind).toBe('invalid');
    expect(resolveLocal('gestern', 'Europe/Berlin').kind).toBe('invalid');
    expect(resolveLocal('2026-07-01T12:00', 'CET').kind).toBe('unknown-zone');
    expect(resolveLocal('2026-07-01T12:00', 'UTC').kind).toBe('unique');
  });

  it('Dauer über die Zeitumstellung aus absoluten Zeitpunkten: 01:00 bis 04:00 am 25.10.2026 sind 4 Stunden', () => {
    const a = resolveLocal('2026-10-25T01:00', 'Europe/Berlin');
    const b = resolveLocal('2026-10-25T04:00', 'Europe/Berlin');
    expect(
      a.kind === 'unique' && b.kind === 'unique' && (b.value.epochMs - a.value.epochMs) / 3_600_000,
    ).toBe(4);
  });

  it('Zonenprüfung entspricht dem Server (nur Gebiet/Ort und UTC)', () => {
    for (const ok of ['Europe/Berlin', 'UTC', 'America/Argentina/Buenos_Aires'])
      expect(isKnownZone(ok)).toBe(true);
    for (const bad of ['CET', 'EST', '+01:00', 'Etc/GMT-1', 'Mars/Olympus', 'europe/berlin', ''])
      expect(isKnownZone(bad)).toBe(false);
  });

  it('Betriebstag', () => {
    expect(operatingDay(Date.parse('2026-10-24T23:30:00Z'), 'Europe/Berlin')).toBe('2026-10-25');
    expect(operatingDay(Date.parse('2026-10-24T23:30:00Z'), 'UTC')).toBe('2026-10-24');
    expect(operatingDay(Date.parse('2026-10-25T22:59:59Z'), 'Europe/Berlin')).toBe('2026-10-25');
    expect(operatingDay(Date.parse('2026-10-25T23:00:00Z'), 'Europe/Berlin')).toBe('2026-10-26');
  });
});
