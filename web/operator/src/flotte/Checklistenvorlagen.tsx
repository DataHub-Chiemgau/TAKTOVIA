import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type {
  ChecklistStepWrite,
  ChecklistTemplateRevision,
  ChecklistTemplateState,
} from '@taktovia/api-client';
import { DataTable, Field, Form, ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { useScreenContext } from '../ScreenContext';

const STATE_LABELS: Readonly<Record<ChecklistTemplateState, string>> = {
  DRAFT: 'Entwurf',
  PUBLISHED: 'freigegeben',
  RETIRED: 'abgelöst',
};
const KIND_LABELS: Readonly<Record<ChecklistStepWrite['input_kind'], string>> = {
  OK_DEFECT_NA: 'OK / Mangel / Nicht anwendbar',
  NUMBER: 'Zahl',
  TEXT_SHORT: 'Kurztext',
  ODOMETER_KM: 'Kilometerstand (Schichtstart)',
  ENERGY_PCT: 'Energie / Tank in % (Schichtstart)',
  VEHICLE_IDENTITY: 'Fahrzeugidentität (Schichtstart)',
  READINESS_YES_NO: 'Einsatzbereitschaft (Ja / Nein)',
};

type StepDraft = {
  step_code: string;
  title: string;
  help_text: string;
  input_kind: ChecklistStepWrite['input_kind'];
  mandatory: boolean;
  safety_critical: boolean;
  na_requires_reason: boolean;
  /** Sperrart bei negativer Antwort auf ein kritisches Element (WP-M3-16); leer = Fahrzeugsperre. */
  block_kind: '' | 'VEHICLE' | 'SERVICE';
  /** Nur für Fahrzeuge mit Rampe oder Lift (WP-M3-12). */
  equipment_condition: '' | 'RAMP_OR_LIFT';
  /** Entfallende Ressourcendimension bei Mangel oder „Nicht prüfbar“ (WP-M3-12). */
  restricts_dimension: '' | 'wheelchair';
};
const EMPTY_STEP: StepDraft = {
  step_code: '',
  title: '',
  help_text: '',
  input_kind: 'OK_DEFECT_NA',
  mandatory: true,
  safety_critical: false,
  na_requires_reason: false,
  block_kind: '',
  equipment_condition: '',
  restricts_dimension: '',
};

/**
 * UI-11 Flotte/Werkstatt: Vorlageneditor (Grundform, WP-M3-10). Vorlagen sind unveränderliche Revisionen (Entwurf, freigegeben, abgelöst);
 * eine freigegebene Revision wird nie geändert, Änderung heißt „Neue Revision“. Die Freigabe verlangt eine zweite Person (Vier-Augen-Prinzip),
 * das prüft der Server. Die Anzeige ist keine Rechtequelle.
 */
export function Checklistenvorlagen() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Checklistenvorlagen' });
  const canWrite = session.status === 'ready' && hasRight(session.me, 'checklists.write');
  const [rows, setRows] = useState<ResourceState<ChecklistTemplateRevision[]>>({
    status: 'loading',
  });
  const [name, setName] = useState('');
  const [vehicleClass, setVehicleClass] = useState('');
  const [serviceMode, setServiceMode] = useState('');
  const [step, setStep] = useState<StepDraft>(EMPTY_STEP);
  const [steps, setSteps] = useState<ChecklistStepWrite[]>([]);
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);

  const load = useCallback(() => {
    setRows({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listChecklistTemplateRevisions', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setRows({ status: 'ready', data: page.items });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const explain = (e: unknown, fallback: string): string => {
    if (!(e instanceof ApiError)) return fallback;
    if (e.status === 403)
      return 'Ihnen fehlt das Recht oder Sie dürfen nicht selbst freigeben (Vier-Augen-Prinzip).';
    if (e.status === 404) return 'Die Vorlage wurde nicht gefunden.';
    if (e.status === 409)
      return 'Konflikt: Es gibt bereits einen Entwurf oder die Revision ist nicht mehr im erwarteten Zustand.';
    if (e.status === 412) return 'Die Vorlage wurde zwischenzeitlich geändert. Bitte neu laden.';
    return fallback;
  };

  const addStep = () => {
    setSteps([
      ...steps,
      {
        step_code: step.step_code.trim(),
        title: step.title.trim(),
        help_text: step.help_text.trim() === '' ? null : step.help_text.trim(),
        input_kind: step.input_kind,
        mandatory: step.mandatory,
        safety_critical: step.safety_critical,
        na_requires_reason: step.na_requires_reason,
        ...(step.safety_critical && step.block_kind ? { block_kind: step.block_kind } : {}),
        ...(step.equipment_condition ? { equipment_condition: step.equipment_condition } : {}),
        ...(step.restricts_dimension ? { restricts_dimension: step.restricts_dimension } : {}),
      },
    ]);
    setStep(EMPTY_STEP);
  };

  const create = async () => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      await api.call('createChecklistTemplate', {
        body: {
          name: name.trim(),
          vehicle_class: vehicleClass.trim(),
          service_mode: serviceMode.trim(),
          steps,
        },
      });
      setName('');
      setVehicleClass('');
      setServiceMode('');
      setSteps([]);
      setNotice('Der Entwurf wurde angelegt.');
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.problem) setProblem(e.problem);
      else setNotice(explain(e, 'Die Vorlage konnte nicht angelegt werden.'));
    }
  };

  const publish = async (r: ChecklistTemplateRevision) => {
    setNotice(undefined);
    try {
      await api.call('publishChecklistTemplateRevision', {
        path: { checklist_revision_id: r.id },
        revision: r.revision,
      });
      setNotice('Die Revision wurde freigegeben und ist ab jetzt unveränderlich.');
      load();
    } catch (e) {
      setNotice(explain(e, 'Die Revision konnte nicht freigegeben werden.'));
    }
  };

  const newRevision = async (r: ChecklistTemplateRevision) => {
    setNotice(undefined);
    try {
      await api.call('createChecklistTemplateRevision', {
        path: { checklist_revision_id: r.id },
        revision: r.revision,
      });
      setNotice('Ein neuer Entwurf auf Basis der freigegebenen Revision wurde angelegt.');
      load();
    } catch (e) {
      setNotice(explain(e, 'Die neue Revision konnte nicht angelegt werden.'));
    }
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Flotte und Werkstatt: Checklistenvorlagen" context={context}>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Noch keine Checklistenvorlagen in diesem Mandanten',
          hint: canWrite
            ? 'Legen Sie unten die erste Vorlage an.'
            : 'Es wurden noch keine Vorlagen angelegt.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Revisionen der Checklistenvorlagen"
            rows={data}
            rowKey={(r) => r.id}
            columns={[
              { key: 'name', header: 'Name', render: (r) => r.name ?? '(ohne Name)' },
              { key: 'ver', header: 'Version', render: (r) => String(r.version) },
              { key: 'state', header: 'Zustand', render: (r) => STATE_LABELS[r.state] },
              { key: 'steps', header: 'Prüfelemente', render: (r) => String(r.steps.length) },
              {
                key: 'act',
                header: 'Aktion',
                render: (r) =>
                  !canWrite ? null : r.state === 'DRAFT' ? (
                    <button type="button" onClick={() => void publish(r)}>
                      Freigeben {r.name ?? r.id}
                    </button>
                  ) : r.state === 'PUBLISHED' ? (
                    <button type="button" onClick={() => void newRevision(r)}>
                      Neue Revision {r.name ?? r.id}
                    </button>
                  ) : null,
              },
            ]}
          />
        )}
      </ResourceView>

      {canWrite ? (
        <section aria-labelledby="neu-titel">
          <h2 id="neu-titel">Neue Vorlage</h2>
          <Form onSubmit={create} submitLabel="Entwurf anlegen" problem={problem}>
            <Field
              pointer="/name"
              label="Name der Vorlage"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            <Field
              pointer="/vehicle_class"
              label="Fahrzeugklasse"
              value={vehicleClass}
              onChange={(e) => setVehicleClass(e.target.value)}
            />
            <Field
              pointer="/service_mode"
              label="Betriebsart"
              value={serviceMode}
              onChange={(e) => setServiceMode(e.target.value)}
            />
            <p>Prüfelemente im Entwurf: {steps.length}</p>
            <fieldset>
              <legend>Prüfelement hinzufügen</legend>
              <Field
                pointer="/steps/0/step_code"
                label="Schlüssel des Prüfelements"
                value={step.step_code}
                onChange={(e) => setStep({ ...step, step_code: e.target.value })}
              />
              <Field
                pointer="/steps/0/title"
                label="Titel"
                value={step.title}
                onChange={(e) => setStep({ ...step, title: e.target.value })}
              />
              <Field
                pointer="/steps/0/help_text"
                label="Hilfetext"
                value={step.help_text}
                onChange={(e) => setStep({ ...step, help_text: e.target.value })}
              />
              <div className="tk-field">
                <label htmlFor="input-kind">Eingabetyp</label>
                <select
                  id="input-kind"
                  value={step.input_kind}
                  onChange={(e) =>
                    setStep({ ...step, input_kind: e.target.value as StepDraft['input_kind'] })
                  }
                >
                  {(Object.keys(KIND_LABELS) as StepDraft['input_kind'][]).map((k) => (
                    <option key={k} value={k}>
                      {KIND_LABELS[k]}
                    </option>
                  ))}
                </select>
              </div>
              <label>
                <input
                  type="checkbox"
                  checked={step.mandatory}
                  onChange={(e) => setStep({ ...step, mandatory: e.target.checked })}
                />{' '}
                Pflichtelement
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={step.safety_critical}
                  onChange={(e) => setStep({ ...step, safety_critical: e.target.checked })}
                />{' '}
                Sicherheitskritisch
              </label>
              {step.safety_critical ? (
                <label>
                  Sperrart bei Mangel
                  <select
                    value={step.block_kind}
                    onChange={(e) =>
                      setStep({ ...step, block_kind: e.target.value as StepDraft['block_kind'] })
                    }
                  >
                    <option value="">Fahrzeugsperre (Standard)</option>
                    <option value="VEHICLE">Fahrzeugsperre</option>
                    <option value="SERVICE">Dienstsperre (nur diese Schicht)</option>
                  </select>
                </label>
              ) : null}
              <label>
                Ausstattungsbedingung
                <select
                  value={step.equipment_condition}
                  onChange={(e) =>
                    setStep({
                      ...step,
                      equipment_condition: e.target.value as StepDraft['equipment_condition'],
                      restricts_dimension: e.target.value === '' ? '' : step.restricts_dimension,
                    })
                  }
                >
                  <option value="">Für alle Fahrzeuge</option>
                  <option value="RAMP_OR_LIFT">Nur Fahrzeuge mit Rampe oder Lift</option>
                </select>
              </label>
              {step.equipment_condition ? (
                <label>
                  Entfallende Dimension bei Mangel oder „Nicht prüfbar“
                  <select
                    value={step.restricts_dimension}
                    onChange={(e) =>
                      setStep({
                        ...step,
                        restricts_dimension: e.target.value as StepDraft['restricts_dimension'],
                      })
                    }
                  >
                    <option value="">Keine (nur Mangel)</option>
                    <option value="wheelchair">Rollstuhlplätze</option>
                  </select>
                </label>
              ) : null}
              <label>
                <input
                  type="checkbox"
                  checked={step.na_requires_reason}
                  onChange={(e) => setStep({ ...step, na_requires_reason: e.target.checked })}
                />{' '}
                Begründung bei „Nicht anwendbar“ erforderlich
              </label>
              <button type="button" onClick={addStep}>
                Prüfelement übernehmen
              </button>
            </fieldset>
          </Form>
        </section>
      ) : null}
    </ScreenFrame>
  );
}
