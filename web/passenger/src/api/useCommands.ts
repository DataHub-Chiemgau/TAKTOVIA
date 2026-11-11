import { useMemo } from 'react';
import { CommandKeys, runCommand, useApi } from '@taktovia/api-client';
import type { CallArgs, OperationId, Operations } from '@taktovia/api-client';

/**
 * Befehle der Passenger-PWA mit Idempotenzschlüssel je Absicht: Wiederholt der Fahrgast nach einem Verbindungsabbruch denselben Befehl
 * (gleiche `intent`), geht derselbe `Idempotency-Key` an den Server (WP-M2-35 AK 4). Fehler bleiben `ApiError`; die Darstellung übernimmt
 * `ErrorState` (401/403/404/409/412/428/429, Netz = Status 0).
 */
export function useCommands() {
  const client = useApi();
  const keys = useMemo(() => new CommandKeys(), []);
  return useMemo(
    () => ({
      run: <K extends OperationId>(
        intent: string,
        id: K,
        args: CallArgs<K>,
      ): Promise<Operations[K]['response']> => runCommand(client, keys, intent, id, args),
      pending: (intent: string) => keys.has(intent),
    }),
    [client, keys],
  );
}
