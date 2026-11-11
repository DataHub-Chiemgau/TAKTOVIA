import { ApiError } from './runtime';
import type { ApiClient, CallArgs } from './runtime';
import type { OperationId, Operations } from './generated';

/**
 * Idempotenzschlüssel je Benutzerabsicht (Kap. 05 §5.1): Ein Befehl, dessen Ausgang unbekannt ist (Verbindungsabbruch, Zeitüberschreitung,
 * Serverfehler, „Vorgang läuft noch“), wird mit DEMSELBEN Schlüssel wiederholt, damit der Server ihn höchstens einmal ausführt. Nach einer
 * endgültigen Antwort (Erfolg oder Ablehnung 4xx) gilt der Schlüssel als verbraucht; die nächste Absicht erhält einen neuen.
 * Die Schlüssel liegen nur im Arbeitsspeicher, nie im Web-Speicher.
 */
export class CommandKeys {
  private readonly keys = new Map<string, string>();
  constructor(private readonly newKey: () => string = () => crypto.randomUUID()) {}

  /** Schlüssel der Absicht; wird bei der ersten Anforderung erzeugt und bis `settle` wiederverwendet. */
  keyFor(intent: string): string {
    let key = this.keys.get(intent);
    if (key === undefined) {
      key = this.newKey();
      this.keys.set(intent, key);
    }
    return key;
  }

  settle(intent: string): void {
    this.keys.delete(intent);
  }

  has(intent: string): boolean {
    return this.keys.has(intent);
  }
}

/** Ausgang unbekannt: Der Server könnte den Befehl ausgeführt haben, die Wiederholung muss denselben Schlüssel tragen. */
export function outcomeUnknown(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  if (error.status === 0 || error.status === 408 || error.status >= 500) return true;
  return error.status === 409 && error.problem?.code === 'OPERATION_IN_PROGRESS';
}

/** Führt einen Befehl mit Schlüsselwiederverwendung aus. `intent` benennt die Benutzerabsicht (z. B. `confirmBooking:<Angebots-ID>`). */
export async function runCommand<K extends OperationId>(
  client: ApiClient,
  keys: CommandKeys,
  intent: string,
  id: K,
  args: CallArgs<K>,
): Promise<Operations[K]['response']> {
  const headers = {
    ...(args.headers as Record<string, string> | undefined),
    'Idempotency-Key': keys.keyFor(intent),
  } as NonNullable<CallArgs<K>['headers']>;
  try {
    const result = await client.call(id, { ...args, headers });
    keys.settle(intent);
    return result;
  } catch (error) {
    if (!outcomeUnknown(error)) keys.settle(intent);
    throw error;
  }
}
