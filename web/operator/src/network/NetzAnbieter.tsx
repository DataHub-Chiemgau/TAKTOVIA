import { useApi } from '@taktovia/api-client';
import {
  ConnectionBanner,
  ConnectionProvider,
  standHhmm,
  useConnection,
} from '@taktovia/design-system';
import { useCallback, useEffect } from 'react';
import type { ReactNode } from 'react';
import type { LiveChannelStatus } from '../live/LiveChannel';

/** Verbindungszustand des Operator-Webs (WP-M5-09, UI-07): nach einer Wiederverbindung lädt der Anbieter den Serverstand, erst danach endet der Offlinezustand. */
export function NetzAnbieter({ children }: { children: ReactNode }) {
  const api = useApi();
  const load = useCallback(() => api.call('getMe', {}).then(() => undefined), [api]);
  return <ConnectionProvider loadServerState={load}>{children}</ConnectionProvider>;
}

/** Netzwerkzustand im Seitenrahmen (Text und Symbol, als Statusbereich) mit dem Datenstand des Livekanals. */
export function NetzBanner({ zone = 'Europe/Berlin' }: { zone?: string }) {
  const { model } = useConnection();
  return (
    <ConnectionBanner
      standText={model.lastServerStateAt ? standHhmm(model.lastServerStateAt, zone) : undefined}
    />
  );
}

/**
 * Übersetzt den Zustand des Livekanals in den Verbindungszustand (UI-07): steht der Strom (`live`), gilt der Serverstand als geladen; Wiederaufbau, Abruf, „veraltet“ und Ende
 * (kein Lebenszeichen) sind `eingeschränkt` mit dem Datenstand des letzten Lebenszeichens, nie ein vorgetäuschtes „live“.
 */
export function useLiveConnection(status: LiveChannelStatus): void {
  const { serverStateLoaded, reportRequest } = useConnection();
  const { state, lastSeen } = status;
  useEffect(() => {
    if (state === 'live') {
      serverStateLoaded(lastSeen);
      reportRequest(true);
    } else if (state !== 'connecting') {
      reportRequest(false);
    }
  }, [state, lastSeen, serverStateLoaded, reportRequest]);
}
