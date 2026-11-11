import { StateView } from '@taktovia/design-system';
import { tx } from '../texte';
import { useOnline } from './useOnline';

/** Dauerhafter, nicht nur farbiger Hinweis im Seitenrahmen; als Status angesagt. Bietet bewusst keine Buchungsaktion an. */
export function OfflineBanner() {
  const online = useOnline();
  if (online) return null;
  return (
    <p role="status" className="tk-offline-banner" data-testid="offline-banner">
      <strong>Offline.</strong> {tx('offline.banner')}
    </p>
  );
}

/** Ganzseitiger Offlinezustand (z. B. vor der Anmeldung). Keine Anmelde- und keine Buchungsschaltfläche. */
export function OfflinePage() {
  return (
    <main>
      <StateView kind="offline" level={1} title={tx('offline.title')} hint={tx('offline.hint')} />
    </main>
  );
}
