import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  OdometerConflict,
  OdometerReading,
  OperationId,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { runAxe, formatViolations } from '@taktovia/design-system/testing';
import { Kilometerstand } from './Kilometerstand';

const VEHICLE = '00000000-0000-4000-8000-0000000000a1';

const reading = (
  n: number,
  km: number,
  source: OdometerReading['source'],
  ref: string,
  measured: string,
): OdometerReading => ({
  id: `00000000-0000-4000-8000-00000000010${n}`,
  vehicle_id: VEHICLE,
  reading_km: km,
  source,
  source_ref: ref,
  measured_at: measured,
  recorded_at: '2026-06-01T12:00:00Z',
  recorded_by: 'service:odometer-register',
});

const EARLIER = reading(1, 5000, 'DRIVER_CHECK', 'pruefung-1', '2026-06-01T08:00:00Z');
const LATER = reading(2, 4000, 'FUEL_RECEIPT', 'beleg-1:4000', '2026-06-01T10:00:00Z');

const OPEN_CONFLICT: OdometerConflict = {
  id: '00000000-0000-4000-8000-0000000002c1',
  vehicle_id: VEHICLE,
  kind: 'DECREASING',
  status: 'OPEN',
  tolerance_km: 5,
  earlier: EARLIER,
  later: LATER,
  detected_at: '2026-06-01T12:00:00Z',
  resolution: null,
};

type Handlers = Partial<Record<OperationId, (args: Record<string, unknown>) => unknown>>;
type Call = { id: OperationId; args: Record<string, unknown> };

function setup(scopes: string[], handlers: Handlers) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe')
        return Promise.resolve(sampleMe({ subject: 'finanz-1', roles: ['finance'], scopes }));
      const h = handlers[id];
      if (!h) return Promise.reject(new ApiError(404));
      const r = h(args);
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    }),
  } as unknown as ApiClient;
  const view = render(
    <SessionProvider client={client}>
      <Kilometerstand vehicleId={VEHICLE} />
    </SessionProvider>,
  );
  return { ...view, calls };
}

const page = <T,>(items: T[]) => ({ items, next_cursor: null, as_of: '2026-06-01T12:00:00Z' });
const problem = (status: number, code: string) =>
  new ApiError(status, {
    type: 'about:blank',
    title: code,
    status,
    code,
    correlation_id: '00000000-0000-4000-8000-0000000000ff',
  });

describe('Fahrzeugdetail: Kilometerstände (WP-M4-41)', () => {
  it('konfliktliste_zeigt_quellen_gegenueber_und_aufloesungsdialog_verlangt_grund', async () => {
    const resolved: OdometerConflict = {
      ...OPEN_CONFLICT,
      status: 'RESOLVED',
      resolution: {
        decision: 'CHOSEN',
        chosen_reading_id: EARLIER.id,
        resolved_by: 'user:finanz-1',
        reason: 'Fahrercheck durch Foto belegt',
        resolved_at: '2026-06-01T13:00:00Z',
      },
    };
    let conflicts = [OPEN_CONFLICT];
    const { calls, container } = setup(['odometer:read', 'odometer:resolve'], {
      listOdometerReadings: () => page([EARLIER, LATER]),
      listOdometerConflicts: () => page(conflicts),
      resolveOdometerConflict: () => {
        conflicts = [resolved];
        return resolved;
      },
    });

    // Gegenüberstellung: beide Stände mit Quelle, Bezug, Messzeit und Erfassungszeit
    const list = await screen.findByRole('list', { name: 'Kilometerstandskonflikte' });
    expect(list).toHaveTextContent('Rückläufiger Stand');
    expect(list).toHaveTextContent('offen');
    expect(list).toHaveTextContent(/5\.000 km: Fahrercheck \(Bezug pruefung-1\)/);
    expect(list).toHaveTextContent(/4\.000 km: Tankbeleg \(Bezug beleg-1:4000\)/);
    expect(screen.getByRole('table', { name: 'Kilometerstandsverlauf' })).toBeInTheDocument();
    expect(formatViolations(await runAxe(container))).toBe('');

    // Auflösungsdialog: ohne Grund ist das Speichern gesperrt, eine automatische Auswahl gibt es nicht (nichts wird ohne Klick gesendet)
    expect(calls.some((c) => c.id === 'resolveOdometerConflict')).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Konflikt auflösen' }));
    const save = screen.getByRole('button', { name: 'Auflösung speichern' });
    expect(save).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Grund (Pflicht)'), { target: { value: 'ab' } });
    expect(save).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Grund (Pflicht)'), {
      target: { value: 'Fahrercheck durch Foto belegt' },
    });
    expect(save).toBeEnabled();
    fireEvent.click(save);

    expect(
      await screen.findByText('Der Konflikt ist aufgelöst und protokolliert.'),
    ).toBeInTheDocument();
    expect(calls.find((c) => c.id === 'resolveOdometerConflict')?.args).toMatchObject({
      path: { vehicle_id: VEHICLE, conflict_id: OPEN_CONFLICT.id },
      body: {
        decision: 'CHOSEN',
        chosen_reading_id: EARLIER.id,
        reason: 'Fahrercheck durch Foto belegt',
      },
    });
    // aufgelöster Konflikt nennt Akteur und Grund, die Rohstände bleiben sichtbar
    expect(await screen.findByText(/Aufgelöst von user:finanz-1/)).toHaveTextContent(
      'Grund: Fahrercheck durch Foto belegt',
    );
    expect(screen.queryByRole('button', { name: 'Konflikt auflösen' })).toBeNull();
    expect(screen.getByRole('table', { name: 'Kilometerstandsverlauf' })).toHaveTextContent(
      '4.000 km',
    );
  });

  it('ohne_recht_nur_lesen_leerzustand_und_fehler_mit_wiederholung', async () => {
    // ohne Auflösungsrecht: Anzeige, aber keine Schaltfläche
    const ro = setup(['odometer:read'], {
      listOdometerReadings: () => page([EARLIER, LATER]),
      listOdometerConflicts: () => page([OPEN_CONFLICT]),
    });
    expect(await screen.findByText(/nur lesbar/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Konflikt auflösen' })).toBeNull();
    ro.unmount();

    // Leerzustand ohne Stände
    const empty = setup(['odometer:read'], {
      listOdometerReadings: () => page([]),
      listOdometerConflicts: () => page([]),
    });
    expect(
      await screen.findByText('Für dieses Fahrzeug liegen noch keine Kilometerstände vor'),
    ).toBeInTheDocument();
    empty.unmount();

    // Fehlerzustand mit Wiederholung
    let fail = true;
    setup(['odometer:read'], {
      listOdometerReadings: () => (fail ? problem(503, 'SERVICE_UNAVAILABLE') : page([EARLIER])),
      listOdometerConflicts: () => page([]),
    });
    const retry = await screen.findByRole('button', { name: 'Erneut versuchen' });
    fail = false;
    fireEvent.click(retry);
    expect(
      await screen.findByRole('table', { name: 'Kilometerstandsverlauf' }),
    ).toBeInTheDocument();
  });

  it('zweite_aufloesung_zeigt_409_ohne_datenverlust', async () => {
    setup(['odometer:read', 'odometer:resolve'], {
      listOdometerReadings: () => page([EARLIER, LATER]),
      listOdometerConflicts: () => page([OPEN_CONFLICT]),
      resolveOdometerConflict: () => problem(409, 'CONFLICT'),
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Konflikt auflösen' }));
    fireEvent.click(screen.getByLabelText('Beide Stände verwerfen'));
    fireEvent.change(screen.getByLabelText('Grund (Pflicht)'), {
      target: { value: 'nicht belegbar' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Auflösung speichern' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('bereits aufgelöst (409)');
    expect(screen.getByRole('list', { name: 'Kilometerstandskonflikte' })).toHaveTextContent(
      '4.000 km',
    );
  });
});
