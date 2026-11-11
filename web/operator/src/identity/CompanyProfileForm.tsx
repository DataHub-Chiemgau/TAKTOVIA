import { ApiError, useApi } from '@taktovia/api-client';
import type { CompanyProfile } from '@taktovia/api-client';
import { Field, Form, ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { useScreenContext } from '../ScreenContext';

type Draft = {
  legal_name: string;
  contact_email: string;
  default_currency: string;
  address: string;
};

const toDraft = (p: CompanyProfile): Draft => ({
  legal_name: p.legal_name,
  contact_email: p.contact_email,
  default_currency: p.default_currency,
  address: p.address ?? '',
});

/**
 * UI-18 Basis: Formular Unternehmensprofil (Kontakt, Standardwährung, Anschrift) des **aktiven Mandanten**. Der Mandantenadministrator ändert es selbst;
 * es wirkt ohne Mitwirkung einer Plattformbetreiber-Rolle. Zustände: fehlendes Recht (403, ohne Daten), 412-Konflikt (zwischenzeitlich geändert:
 * neu laden statt überschreiben) und Feldfehler (422, z. B. Währung außerhalb des Katalogs).
 */
export function CompanyProfileForm() {
  const api = useApi();
  const context = useScreenContext({ tone: 'info', label: 'Basis: Unternehmensprofil' });
  const [state, setState] = useState<ResourceState<CompanyProfile>>({ status: 'loading' });
  const [draft, setDraft] = useState<Draft>({
    legal_name: '',
    contact_email: '',
    default_currency: '',
    address: '',
  });
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);

  const load = useCallback(() => {
    setState({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('getCompanyProfile', {})
      .then((profile) => {
        if (cancelled) return;
        setState({ status: 'ready', data: profile });
        setDraft(toDraft(profile));
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setState({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const save = async () => {
    if (state.status !== 'ready') return;
    setProblem(undefined);
    setNotice(undefined);
    try {
      await api.call('updateCompanyProfile', {
        revision: state.data.revision,
        body: {
          legal_name: draft.legal_name.trim(),
          contact_email: draft.contact_email.trim(),
          default_currency: draft.default_currency.trim(),
          ...(draft.address.trim() ? { address: draft.address.trim() } : {}),
        },
      });
      setNotice('Das Unternehmensprofil wurde gespeichert.');
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 412) {
        setNotice(
          'Konflikt: Das Profil wurde zwischenzeitlich geändert. Es wurde neu geladen, bitte Änderung prüfen.',
        );
        load();
      } else if (e instanceof ApiError && e.problem) setProblem(e.problem);
      else setNotice('Das Unternehmensprofil konnte nicht gespeichert werden.');
    }
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Unternehmensprofil" context={context}>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={state}
        isEmpty={() => false}
        empty={{ title: 'Noch kein Unternehmensprofil' }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {() => (
          <Form onSubmit={save} submitLabel="Speichern" problem={problem}>
            <Field
              pointer="/legal_name"
              label="Name des Unternehmens"
              value={draft.legal_name}
              onChange={(e) => setDraft({ ...draft, legal_name: e.target.value })}
            />
            <Field
              pointer="/contact_email"
              label="Kontakt-E-Mail"
              type="email"
              value={draft.contact_email}
              onChange={(e) => setDraft({ ...draft, contact_email: e.target.value })}
            />
            <Field
              pointer="/default_currency"
              label="Standardwährung"
              hint="Dreibuchstabiger Währungscode aus dem Währungskatalog, z. B. EUR"
              value={draft.default_currency}
              onChange={(e) => setDraft({ ...draft, default_currency: e.target.value })}
            />
            <Field
              pointer="/address"
              label="Anschrift"
              value={draft.address}
              onChange={(e) => setDraft({ ...draft, address: e.target.value })}
            />
          </Form>
        )}
      </ResourceView>
    </ScreenFrame>
  );
}
