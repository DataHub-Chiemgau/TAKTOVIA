import { ApiError, useApi } from '@taktovia/api-client';
import type { DriverRelease } from '@taktovia/api-client';
import { Field, Form, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike } from '@taktovia/design-system';
import { useState } from 'react';
import { useScreenContext } from '../ScreenContext';

type Draft = {
  driver_id: string;
  recipient_tenant_id: string;
  service_ids: string;
  valid_from: string;
  valid_to: string;
};

const EMPTY: Draft = {
  driver_id: '',
  recipient_tenant_id: '',
  service_ids: '',
  valid_from: '',
  valid_to: '',
};

/** Sichtbare Feldliste der Projektion (Kap. 02 §2, MUL-005); alles andere bleibt beim Eigentümer. */
export const SICHTBARE_FELDER = ['Qualifikationen', 'Einsatzzeitraum', 'Kontaktkanal'] as const;
export const NICHT_SICHTBAR = ['Personalakte', 'Arbeitszeiten', 'Abwesenheiten', 'Gehalt'] as const;

const toIso = (local: string): string => {
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? local : d.toISOString();
};

/**
 * UI-18 Basis: Fahrerfreigabe (WP-M1-35, LH-I.4-MUL-005). Der Eigentümer gibt einen Fahrer befristet für Dienste eines anderen Mandanten frei und sieht vorab, welche
 * Felder der Empfänger erhält. Zustände: fehlendes Recht (403), Fahrer oder Empfänger unbekannt (404, gleichartig), Konflikt wie „Fahrer nicht aktiv“ (409, Scopekonflikt),
 * Feldfehler (422, z. B. Frist oder leere Dienstliste), Widerruf (412 bei veralteter Revision). Die Anzeige ist keine Rechtequelle; der Server prüft jede Anfrage.
 */
export function Fahrerfreigabe() {
  const api = useApi();
  const context = useScreenContext({ tone: 'info', label: 'Basis: Fahrerfreigabe' });
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [release, setRelease] = useState<DriverRelease | undefined>(undefined);

  const explain = (e: unknown, fallback: string): string => {
    if (!(e instanceof ApiError)) return fallback;
    if (e.status === 403) return 'Ihnen fehlt das Recht, Fahrerfreigaben zu verwalten.';
    if (e.status === 404) return 'Fahrer oder Empfängermandant wurde nicht gefunden.';
    if (e.status === 409)
      return 'Konflikt: Die Freigabe ist nicht möglich (Fahrer nicht aktiv oder Freigabe nicht mehr widerrufbar).';
    if (e.status === 412)
      return 'Die Freigabe wurde zwischenzeitlich geändert. Bitte neu laden und prüfen.';
    return fallback;
  };

  const create = async () => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      const created = await api.call('createDriverRelease', {
        body: {
          driver_id: draft.driver_id.trim(),
          recipient_tenant_id: draft.recipient_tenant_id.trim(),
          service_ids: draft.service_ids
            .split(/[\s,;]+/)
            .map((s) => s.trim())
            .filter(Boolean),
          valid_from: toIso(draft.valid_from),
          valid_to: toIso(draft.valid_to),
        },
      });
      setRelease(created);
      setNotice('Die Fahrerfreigabe wurde erteilt.');
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.problem) setProblem(e.problem);
      else setNotice(explain(e, 'Die Fahrerfreigabe konnte nicht erteilt werden.'));
    }
  };

  const revoke = async () => {
    if (!release) return;
    setNotice(undefined);
    try {
      await api.call('revokeDriverRelease', {
        path: { release_id: release.id },
        revision: release.revision,
      });
      setRelease(undefined);
      setNotice('Die Fahrerfreigabe wurde widerrufen; der Empfänger hat ab sofort keinen Zugriff.');
    } catch (e) {
      setNotice(explain(e, 'Die Fahrerfreigabe konnte nicht widerrufen werden.'));
    }
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Fahrerfreigabe" context={context}>
      {notice ? <p role="status">{notice}</p> : null}
      <section aria-labelledby="felder-titel">
        <h2 id="felder-titel">Was der Empfänger sieht</h2>
        <p>Sichtbar für den Empfängermandanten: {SICHTBARE_FELDER.join(', ')}.</p>
        <p>Nie sichtbar: {NICHT_SICHTBAR.join(', ')}.</p>
        <p>
          Die Freigabe gilt nur für die genannten Dienste und endet mit der Frist (Vertragsablauf);
          bis dahin ist sie jederzeit widerrufbar.
        </p>
      </section>
      <Form onSubmit={create} submitLabel="Fahrer freigeben" problem={problem}>
        <Field
          pointer="/driver_id"
          label="Fahrer (Kennung)"
          value={draft.driver_id}
          onChange={(e) => setDraft({ ...draft, driver_id: e.target.value })}
        />
        <Field
          pointer="/recipient_tenant_id"
          label="Empfängermandant (Kennung)"
          value={draft.recipient_tenant_id}
          onChange={(e) => setDraft({ ...draft, recipient_tenant_id: e.target.value })}
        />
        <Field
          pointer="/service_ids"
          label="Dienste des Empfängers (Kennungen, getrennt durch Komma)"
          value={draft.service_ids}
          onChange={(e) => setDraft({ ...draft, service_ids: e.target.value })}
        />
        <Field
          pointer="/valid_from"
          label="Beginn (lokale Zeit)"
          type="datetime-local"
          value={draft.valid_from}
          onChange={(e) => setDraft({ ...draft, valid_from: e.target.value })}
        />
        <Field
          pointer="/valid_to"
          label="Ende (lokale Zeit)"
          type="datetime-local"
          value={draft.valid_to}
          onChange={(e) => setDraft({ ...draft, valid_to: e.target.value })}
        />
      </Form>
      {release ? (
        <section aria-labelledby="erteilt-titel">
          <h2 id="erteilt-titel">Erteilte Freigabe</h2>
          <p>
            {release.id}: {release.valid_from} bis {release.valid_to}, {release.service_ids.length}{' '}
            Dienst(e).
          </p>
          <button type="button" onClick={revoke}>
            Freigabe widerrufen
          </button>
        </section>
      ) : null}
    </ScreenFrame>
  );
}
