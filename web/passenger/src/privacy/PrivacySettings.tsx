import { useCallback, useEffect, useState } from 'react';
import { ApiError, useApi } from '@taktovia/api-client';
import type { PrivacySettings as Settings } from '@taktovia/api-client';
import { ErrorState, StateView } from '@taktovia/design-system';

type Load =
  | { status: 'loading' }
  | { status: 'error'; error: ApiError }
  | { status: 'ready'; settings: Settings };

/**
 * UI-05 Basis: Datenschutzeinstellungen (WP-M1-30). Betriebliche und sicherheitsrelevante Mitteilungen stehen als eigener, nicht abwählbarer Eintrag
 * (Pflichtzustimmung); die freiwillige Marketingeinwilligung ist ein getrennter Schalter, standardmäßig aus, und wird nie mit etwas anderem kombiniert
 * (Kap. 06 §6.3). Jede Änderung ist eine neue Revision auf dem Server; bei einem Fehler springt der Schalter zurück.
 */
export function PrivacySettings() {
  const api = useApi();
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const [message, setMessage] = useState<string | undefined>(undefined);

  const reload = useCallback(() => {
    api.call('getMyPrivacySettings', {}).then(
      (settings) => setLoad({ status: 'ready', settings }),
      (e: unknown) =>
        setLoad({ status: 'error', error: e instanceof ApiError ? e : new ApiError(0) }),
    );
  }, [api]);
  useEffect(() => reload(), [reload]);

  if (load.status === 'loading') return <StateView kind="loading" />;
  if (load.status === 'error') return <ErrorState error={load.error} />;
  const { settings } = load;
  const marketing = settings.consents.find((c) => c.purpose === 'MARKETING');
  const marketingOn = marketing?.granted ?? false;

  const toggle = async () => {
    setMessage(undefined);
    try {
      await api.call('recordMyConsent', {
        body: {
          purpose: 'MARKETING',
          granted: !marketingOn,
          text_version: settings.current_text_version,
        },
      });
      reload();
      setMessage(
        marketingOn
          ? 'Die Einwilligung wurde widerrufen.'
          : 'Danke, Ihre Einwilligung wurde gespeichert.',
      );
    } catch {
      setMessage('Die Einstellung konnte nicht gespeichert werden und bleibt unverändert.');
      reload();
    }
  };

  return (
    <section aria-labelledby="datenschutz-h">
      <h2 id="datenschutz-h">Datenschutz</h2>
      <div role="group" aria-label="Betriebliche Mitteilungen">
        <p>
          Betriebliche und sicherheitsrelevante Mitteilungen zu Ihren Fahrten (z. B. Änderungen oder
          Ausfälle) erhalten Sie immer. Sie sind unabhängig von Ihrer Entscheidung zu Werbung und
          nicht abwählbar.
        </p>
      </div>
      <div role="group" aria-label="Marketing">
        <label>
          <input
            type="checkbox"
            role="switch"
            checked={marketingOn}
            onChange={() => void toggle()}
          />{' '}
          Marketing-Mitteilungen (freiwillig)
        </label>
        <p>
          {marketingOn ? 'Sie haben eingewilligt.' : 'Sie haben nicht eingewilligt.'} Der Widerruf
          ist jederzeit möglich und betrifft keine betrieblichen Mitteilungen.
        </p>
      </div>
      {message ? <p role="status">{message}</p> : null}
      <h3>Verlauf</h3>
      {settings.history.length === 0 ? (
        <p>Noch keine Einwilligung erteilt.</p>
      ) : (
        <ul>
          {settings.history.map((r) => (
            <li
              key={r.id}
            >{`${r.recorded_at}: ${r.granted ? 'eingewilligt' : 'widerrufen'} (Textversion ${r.text_version})`}</li>
          ))}
        </ul>
      )}
    </section>
  );
}
