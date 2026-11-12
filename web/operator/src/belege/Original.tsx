import { useApi } from '@taktovia/api-client';
import { StateView } from '@taktovia/design-system';
import { useEffect, useState } from 'react';

type State =
  | { status: 'loading' }
  | { status: 'ready'; url: string; mime: string }
  | { status: 'unavailable' }
  | { status: 'error' };
/** Ergebnis gehört zu genau einem Beleg: für einen anderen Beleg gilt wieder „lädt“, ohne in der Wirkung einen Zustand zu setzen. */
type Result = { id: string; state: State };

/**
 * UI-14 Original (WP-M4-38, DOC-003/-007): zeigt das **unveränderte** Original links neben den Feldern. Die Bytes werden mit dem Zugriffstoken der Sitzung geladen und nur als lokale Objektadresse des Tabs
 * eingebunden (nie eine Adresse mit Token); das Original wird nirgends verändert oder zwischengespeichert. Bilder erscheinen als Bild, PDF in einem eingebetteten Betrachter; andere Typen mit
 * Hinweis. Ohne `callBlob` im Client (Testumgebung) erscheint der Zustand „Original nicht ladbar“.
 */
export function Original({ expenseId, mime }: { expenseId: string; mime: string }) {
  const api = useApi();
  const [result, setResult] = useState<Result | undefined>();
  const unavailable = !api.callBlob || typeof URL.createObjectURL !== 'function';
  const state: State = unavailable
    ? { status: 'unavailable' }
    : result?.id === expenseId
      ? result.state
      : { status: 'loading' };

  useEffect(() => {
    if (!api.callBlob || typeof URL.createObjectURL !== 'function') return;
    let cancelled = false;
    let objectUrl: string | undefined;
    api
      .callBlob('getExpenseOriginal', { path: { expense_id: expenseId } })
      .then((r) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(r.blob);
        setResult({
          id: expenseId,
          state: { status: 'ready', url: objectUrl, mime: r.mime || mime },
        });
      })
      .catch(() => {
        if (!cancelled) setResult({ id: expenseId, state: { status: 'error' } });
      });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [api, expenseId, mime]);

  return (
    <section aria-label="Originalbeleg">
      <h3>Original</h3>
      {state.status === 'loading' && <StateView kind="loading" level={2} />}
      {state.status === 'unavailable' && (
        <p role="status">Das Original ist in dieser Umgebung nicht ladbar.</p>
      )}
      {state.status === 'error' && (
        <p role="alert">
          Das Original konnte nicht geladen werden. Der Beleg selbst bleibt unverändert.
        </p>
      )}
      {state.status === 'ready' &&
        (state.mime.startsWith('image/') ? (
          <img src={state.url} alt="Originalbeleg (unverändert)" style={{ maxWidth: '100%' }} />
        ) : state.mime === 'application/pdf' ? (
          <iframe
            title="Originalbeleg (unverändert)"
            src={state.url}
            style={{ width: '100%', minHeight: '24rem' }}
          />
        ) : (
          <p>Der Typ {state.mime} wird nicht angezeigt.</p>
        ))}
    </section>
  );
}
