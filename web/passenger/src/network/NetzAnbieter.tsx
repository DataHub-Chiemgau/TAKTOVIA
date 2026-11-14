import { useApi } from '@taktovia/api-client';
import {
  ConnectionBanner,
  ConnectionProvider,
  standHhmm,
  useConnection,
} from '@taktovia/design-system';
import { useCallback } from 'react';
import type { ReactNode } from 'react';

const ZONE = 'Europe/Berlin';

/**
 * Verbindungszustand der Passenger-PWA (WP-M5-09): nach einer Wiederverbindung lädt der Anbieter den Serverstand (`getMe`), erst danach endet der Offlinezustand. Die Buchungsansichten melden
 * ihren geladenen Stand zusätzlich über `useConnection().serverStateLoaded`.
 */
export function NetzAnbieter({ children }: { children: ReactNode }) {
  const api = useApi();
  const load = useCallback(() => api.call('getMe', {}).then(() => undefined), [api]);
  return <ConnectionProvider loadServerState={load}>{children}</ConnectionProvider>;
}

/** Sichtbarer Netzwerkzustand im Seitenrahmen mit „Stand hh:mm“ des zuletzt geladenen Serverstands; als Statusbereich für Screenreader. */
export function NetzBanner() {
  const { model } = useConnection();
  return (
    <ConnectionBanner
      standText={model.lastServerStateAt ? standHhmm(model.lastServerStateAt, ZONE) : undefined}
    />
  );
}
