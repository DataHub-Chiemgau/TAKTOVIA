import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type { Inspection, InspectionAmendment, InspectionAnswer } from '@taktovia/api-client';
import { DataTable, Field, Form, ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useState } from 'react';
import { useScreenContext } from '../ScreenContext';

const ANSWER_LABELS: Readonly<Record<InspectionAnswer['answer'], string>> = {
  OK: 'OK',
  DEFECT: 'Mangel',
  NOT_APPLICABLE: 'Nicht anwendbar',
  NOT_CHECKABLE: 'Nicht prüfbar',
  JA: 'Ja',
  NEIN: 'Nein',
};
const OUTCOME_LABELS: Readonly<Record<NonNullable<Inspection['outcome']>, string>> = {
  PASSED: 'bestanden',
  PASSED_RESTRICTED: 'eingeschränkt',
  BLOCKED: 'gesperrt',
  CLARIFICATION_REQUIRED: 'technisch ungeklärt',
};
const PLAUSIBILITY_LABELS = {
  PLAUSIBEL: 'plausibel',
  UEBERPRUEFUNG_ERFORDERLICH: 'Überprüfung erforderlich',
  BEGRUENDET_FREIGEGEBEN: 'begründet freigegeben',
} as const;

const when = (iso: string | null | undefined): string =>
  iso ? new Date(iso).toLocaleString('de-DE') : '–';

/** Anzeigewert einer Antwort: Status, Zahl, Text; ein Mangel nennt seine Beschreibung. */
export const answerText = (
  a: Pick<InspectionAnswer, 'answer' | 'number_value' | 'text_value' | 'note'>,
): string =>
  [
    ANSWER_LABELS[a.answer],
    a.number_value != null ? String(a.number_value) : null,
    a.text_value ?? null,
    a.note ? `„${a.note}“` : null,
  ]
    .filter((x): x is string => x !== null)
    .join(' · ');

type Row = {
  code: string;
  question: string;
  original: string;
  plausibility: string;
  amendments: string;
};

/** Zeilen der Prüfhistorie: die Fragen der damaligen Vorlagenrevision mit Originalantwort und Nachträgen daneben. */
export function historyRows(inspection: Inspection): Row[] {
  return (inspection.template?.steps ?? []).map((step) => {
    const original = inspection.answers.find((a) => a.step_code === step.step_code);
    const later = (inspection.amendments ?? []).filter((m) => m.step_code === step.step_code);
    return {
      code: step.step_code,
      question: `${step.position}. ${step.title}${step.safety_critical ? ' (sicherheitskritisch)' : ''}`,
      original: original ? answerText(original) : '–',
      plausibility: original?.plausibility
        ? `${PLAUSIBILITY_LABELS[original.plausibility]}${
            original.justification
              ? `: ${original.justification.reason} (${original.justification.justified_by}, ${when(original.justification.justified_at)})`
              : ''
          }`
        : '–',
      amendments:
        later.length === 0
          ? '–'
          : later
              .map(
                (m) =>
                  `v${m.version} ${ANSWER_LABELS[m.new_answer]}${m.new_note ? ` „${m.new_note}“` : ''} – ${m.reason} (${m.amended_by}, ${when(m.created_at)})`,
              )
              .join(' | '),
    };
  });
}

/**
 * UI-11 Flotte/Werkstatt: Prüfnachweis (WP-M3-14, WP-M3-15, WP-M3-17). Zeigt eine Prüfung so, wie sie erfasst wurde: die Fragen der damaligen, festen Vorlagenrevision (spätere
 * Vorlagenänderungen verändern die Historie nie) mit den Originalantworten; Nachträge stehen daneben mit Version, Autor, Zeit und Grund, nie anstelle der Originalantwort. Plausibilitätsstand
 * und begründete Freigabe der Startwerte sind sichtbar. Der Nachtrag verlangt einen Grund; ohne Recht (`inspections.amend`) ist der Bildschirm nur Anzeige. Die Anzeige ist keine
 * Rechtequelle, der Server prüft jede Anfrage erneut.
 */
export function InspectionHistory() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Prüfnachweis' });
  const canAmend = session.status === 'ready' && hasRight(session.me, 'inspections.amend');
  const [id, setId] = useState('');
  const [view, setView] = useState<ResourceState<Inspection> | undefined>(undefined);
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [amend, setAmend] = useState({
    step_code: '',
    new_answer: 'DEFECT',
    new_note: '',
    reason: '',
  });

  const load = (inspectionId: string) => {
    setView({ status: 'loading' });
    api
      .call('getInspection', { path: { inspection_id: inspectionId } })
      .then((data) => setView({ status: 'ready', data }))
      .catch((e: unknown) =>
        setView({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      );
  };

  const submitAmendment = async (inspection: Inspection) => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      await api.call('createInspectionAmendment', {
        path: { inspection_id: inspection.id },
        body: {
          step_code: amend.step_code.trim(),
          kind: 'CORRECTION',
          new_answer: amend.new_answer.trim() as InspectionAmendment['new_answer'],
          new_note: amend.new_note.trim() === '' ? null : amend.new_note.trim(),
          reason: amend.reason.trim(),
        },
      });
      setNotice('Der Nachtrag wurde gespeichert; die Originalantwort bleibt unverändert.');
      setAmend({ step_code: '', new_answer: 'DEFECT', new_note: '', reason: '' });
      load(inspection.id);
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.problem) setProblem(e.problem);
      else if (e instanceof ApiError && e.status === 403)
        setNotice('Ihnen fehlt das Recht, Nachträge zu erfassen.');
      else if (e instanceof ApiError && e.status === 409)
        setNotice('Ein Nachtrag ist erst nach dem Abschluss der Prüfung möglich.');
      else setNotice('Der Nachtrag konnte nicht gespeichert werden.');
    }
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Flotte und Werkstatt: Prüfnachweis" context={context}>
      <Form
        onSubmit={() => {
          if (id.trim() !== '') load(id.trim());
        }}
        submitLabel="Prüfung anzeigen"
      >
        <Field
          pointer="/inspection_id"
          label="Prüf-ID"
          value={id}
          onChange={(e) => setId(e.target.value)}
        />
      </Form>
      {notice ? <p role="status">{notice}</p> : null}
      {view ? (
        <ResourceView
          state={view}
          isEmpty={() => false}
          empty={{ title: 'Keine Prüfung gefunden' }}
          errorAction={
            <button type="button" onClick={() => load(id.trim())}>
              Erneut versuchen
            </button>
          }
        >
          {(inspection) => (
            <section aria-label="Prüfnachweis">
              <dl>
                <dt>Status</dt>
                <dd>{inspection.status === 'COMPLETED' ? 'abgeschlossen' : 'in Bearbeitung'}</dd>
                <dt>Ergebnis</dt>
                <dd>{inspection.outcome ? OUTCOME_LABELS[inspection.outcome] : '–'}</dd>
                <dt>Prüfer</dt>
                <dd>{inspection.completed_by ?? '–'}</dd>
                <dt>Fahrzeug</dt>
                <dd>{inspection.vehicle_id}</dd>
                <dt>Schicht</dt>
                <dd>{inspection.shift_id}</dd>
                <dt>Vorlage</dt>
                <dd>Version {inspection.template?.version ?? '–'}</dd>
                <dt>Gerätezeit</dt>
                <dd>{when(inspection.completed_at)}</dd>
                <dt>Serverzeit</dt>
                <dd>{when(inspection.received_at)}</dd>
              </dl>
              <DataTable
                caption="Fragen und Originalantworten der damaligen Vorlage"
                rows={historyRows(inspection)}
                rowKey={(r) => r.code}
                columns={[
                  { key: 'question', header: 'Frage', render: (r) => r.question },
                  { key: 'original', header: 'Originalantwort', render: (r) => r.original },
                  { key: 'plausibility', header: 'Plausibilität', render: (r) => r.plausibility },
                  { key: 'amendments', header: 'Nachträge', render: (r) => r.amendments },
                ]}
              />
              {canAmend && inspection.status === 'COMPLETED' ? (
                <Form
                  onSubmit={() => submitAmendment(inspection)}
                  submitLabel="Nachtrag speichern"
                  problem={problem}
                >
                  <Field
                    pointer="/step_code"
                    label="Prüfelement (Schlüssel)"
                    value={amend.step_code}
                    onChange={(e) => setAmend({ ...amend, step_code: e.target.value })}
                  />
                  <Field
                    pointer="/new_answer"
                    label="Neue Antwort (OK, DEFECT, NOT_APPLICABLE)"
                    value={amend.new_answer}
                    onChange={(e) => setAmend({ ...amend, new_answer: e.target.value })}
                  />
                  <Field
                    pointer="/new_note"
                    label="Beschreibung"
                    value={amend.new_note}
                    onChange={(e) => setAmend({ ...amend, new_note: e.target.value })}
                  />
                  <Field
                    pointer="/reason"
                    label="Grund (Pflicht)"
                    value={amend.reason}
                    onChange={(e) => setAmend({ ...amend, reason: e.target.value })}
                  />
                </Form>
              ) : null}
            </section>
          )}
        </ResourceView>
      ) : null}
    </ScreenFrame>
  );
}
