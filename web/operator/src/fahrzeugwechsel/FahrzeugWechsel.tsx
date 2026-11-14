import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type { ShiftVehicleSwitch, ShiftVehicleSwitchReason } from '@taktovia/api-client';
import { DataTable, Field, Form, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike } from '@taktovia/design-system';
import { useState } from 'react';
import { useScreenContext } from '../ScreenContext';

const REASONS: Readonly<Record<ShiftVehicleSwitchReason, string>> = {
  DEFECT: 'Mangel am Fahrzeug',
  BREAKDOWN: 'Panne',
  SERVICE_NEED: 'Wartungsbedarf',
  CAPACITY: 'Kapazitätsbedarf',
  OTHER: 'Sonstiges (Hinweis Pflicht)',
};

const when = (iso: string): string => new Date(iso).toLocaleString('de-DE');

/** Meldung zum Fehlerfall des Wechsels (stabile Statuscodes des Servers, keine Personendaten). */
export const switchErrorText = (status: number, detail?: string): string => {
  if (status === 403) return 'Ihnen fehlt das Recht, das Fahrzeug einer Schicht zu wechseln.';
  if (status === 404) return 'Schicht oder Ersatzfahrzeug wurden nicht gefunden.';
  if (status === 409 && detail?.startsWith('VEHICLE_BLOCKED'))
    return 'Das Ersatzfahrzeug ist gesperrt und kann nicht übernommen werden.';
  if (status === 409)
    return 'Der Wechsel ist nicht möglich: das Fahrzeug der Schicht hat sich geändert, die Schicht ist beendet oder das Ersatzfahrzeug ist anderweitig belegt.';
  if (status === 422)
    return 'Die Eingaben sind unvollständig oder das Ersatzfahrzeug nicht zulässig.';
  return 'Der Wechsel konnte nicht ausgeführt werden.';
};

/**
 * UI-07 Leitstelle: Fahrzeugwechsel in laufender Schicht (WP-M3-22, LH-6.21-CHK-015). Der Dialog verlangt Schicht, das aktuelle Fahrzeug (Bedingung gegen parallele Wechsel), das
 * Ersatzfahrzeug und eine Begründung. Nach dem Wechsel steht ausdrücklich da, dass das Ersatzfahrzeug **nicht disponierbar** ist, bis seine eigene Prüfung bestätigt ist; die
 * Freigabe des alten Fahrzeugs ist widerrufen. Die Anzeige ist keine Rechtequelle, der Server prüft jede Anfrage erneut
 * (`fleet:switch`; der Dialog öffnet für die Disposition, Fahrer wechseln in der Fahrer-App).
 */
export function FahrzeugWechsel() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Fahrzeugwechsel' });
  const allowed = session.status === 'ready' && hasRight(session.me, 'shifts.write');
  const [form, setForm] = useState({ shift: '', from: '', to: '', reason: 'BREAKDOWN', note: '' });
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [done, setDone] = useState<ShiftVehicleSwitch | undefined>(undefined);
  const [history, setHistory] = useState<ShiftVehicleSwitch[]>([]);

  const loadHistory = async (shiftId: string) => {
    try {
      const list = await api.call('listShiftVehicleSwitches', { path: { shift_id: shiftId } });
      setHistory(list.items);
    } catch {
      setHistory([]);
    }
  };

  const submit = async () => {
    setProblem(undefined);
    setNotice(undefined);
    setDone(undefined);
    try {
      const result = await api.call('createShiftVehicleSwitch', {
        path: { shift_id: form.shift.trim() },
        body: {
          from_vehicle_id: form.from.trim(),
          new_vehicle_id: form.to.trim(),
          reason_code: form.reason as ShiftVehicleSwitchReason,
          ...(form.note.trim() === '' ? {} : { note: form.note.trim() }),
        },
      });
      setDone(result);
      await loadHistory(form.shift.trim());
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.problem) setProblem(e.problem);
      if (e instanceof ApiError) setNotice(switchErrorText(e.status, e.problem?.detail));
      else setNotice(switchErrorText(0));
    }
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Leitstelle: Fahrzeugwechsel" context={context}>
      {!allowed ? (
        <p role="status">Ihnen fehlt das Recht, das Fahrzeug einer Schicht zu wechseln.</p>
      ) : (
        <Form onSubmit={submit} submitLabel="Fahrzeug wechseln" problem={problem}>
          <Field
            pointer="/shift_id"
            label="Schicht-ID"
            value={form.shift}
            onChange={(e) => setForm({ ...form, shift: e.target.value })}
          />
          <Field
            pointer="/from_vehicle_id"
            label="Aktuelles Fahrzeug (ID)"
            value={form.from}
            onChange={(e) => setForm({ ...form, from: e.target.value })}
          />
          <Field
            pointer="/new_vehicle_id"
            label="Ersatzfahrzeug (ID)"
            value={form.to}
            onChange={(e) => setForm({ ...form, to: e.target.value })}
          />
          <Field
            pointer="/reason_code"
            label={`Begründung (${Object.keys(REASONS).join(', ')})`}
            value={form.reason}
            onChange={(e) => setForm({ ...form, reason: e.target.value })}
          />
          <Field
            pointer="/note"
            label="Hinweis (bei Sonstiges Pflicht, keine Personenangaben)"
            value={form.note}
            onChange={(e) => setForm({ ...form, note: e.target.value })}
          />
        </Form>
      )}
      {notice ? <p role="alert">{notice}</p> : null}
      {done ? (
        <section aria-label="Ergebnis des Wechsels">
          <p role="status">
            Das Ersatzfahrzeug {done.to_vehicle_id} ist nicht disponierbar, bis seine Prüfung
            bestätigt ist. {done.revoked_releases} Einsatzfreigabe(n) des bisherigen Fahrzeugs
            wurden widerrufen.
          </p>
        </section>
      ) : null}
      {history.length > 0 ? (
        <DataTable
          caption="Wechselhistorie der Schicht"
          rows={history}
          rowKey={(r) => r.id}
          columns={[
            { key: 'at', header: 'Zeit', render: (r) => when(r.created_at) },
            { key: 'from', header: 'Von', render: (r) => r.from_vehicle_id },
            { key: 'to', header: 'Auf', render: (r) => r.to_vehicle_id },
            { key: 'reason', header: 'Begründung', render: (r) => REASONS[r.reason_code] },
            {
              key: 'role',
              header: 'Ausgelöst von',
              render: (r) => (r.actor_role === 'DRIVER' ? 'Fahrer' : 'Leitstelle'),
            },
          ]}
        />
      ) : null}
    </ScreenFrame>
  );
}
