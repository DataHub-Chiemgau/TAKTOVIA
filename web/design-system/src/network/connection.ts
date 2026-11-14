/**
 * Verbindungszustand der Anwendungen (WP-M5-09, LH-6.19-NFR-009): reiner Zustandsautomat ohne Oberfläche.
 *
 * Zustände: `online`, `limited` (eingeschränkt: Verbindung steht, Anfragen scheitern teilweise oder der Livekanal ist zurückgefallen), `offline`, `syncing` (Rückkehr der Verbindung:
 * Serverstand wird geladen und/oder Ereignisse werden übertragen) und `conflict` (Synchronisationskonflikt: mindestens ein Ereignis wurde vom Server nicht übernommen).
 *
 * Regeln:
 * - Nach einer Wiederverbindung gelten die Einschränkungen des Offlinezustands weiter, bis der Serverstand geladen ist (`awaitingServerState`); der Zustand ist bis dahin `syncing`, nie `online`.
 * - Ausstehende Ereignisse werden als „wird übertragen“ gezeigt (`syncing`), Klärfälle als `conflict`; ein Konflikt wird nie still überschrieben.
 * - Verbindliche Buchung, Einsatzfreigabe und Aufhebung einer Sperre gehören dem Server (Kap. 01 §1.5): offline und bis zum geladenen Serverstand nicht auslösbar, mit Grund und nächstem Schritt.
 */
export type ConnectionState = 'online' | 'limited' | 'offline' | 'syncing' | 'conflict';

export interface ConnectionModel {
  /** Die Verbindung des Geräts steht (nach der Entprellung durch den Anbieter). */
  linkUp: boolean;
  /** Anfragen scheitern oder der Livekanal liefert nur noch Abrufe (eingeschränkt). */
  limited: boolean;
  /** Verbindung war weg: bis der Serverstand wieder geladen ist, bleibt der Offlinezustand wirksam. */
  awaitingServerState: boolean;
  /** Anzahl noch nicht bestätigter Ereignisse („wird übertragen“). */
  pending: number;
  /** Anzahl Klärfälle (Server hat nicht übernommen). */
  conflicts: number;
  /** Zeitpunkt (RFC 3339) des zuletzt geladenen Serverstands. */
  lastServerStateAt: string | null;
}

export type ConnectionEvent =
  | { type: 'link'; up: boolean }
  | { type: 'limited'; value: boolean }
  | { type: 'server-state-loaded'; at: string }
  | { type: 'pending'; count: number }
  | { type: 'conflicts'; count: number };

export const initialConnection: ConnectionModel = {
  linkUp: true,
  limited: false,
  awaitingServerState: false,
  pending: 0,
  conflicts: 0,
  lastServerStateAt: null,
};

export function reduceConnection(m: ConnectionModel, e: ConnectionEvent): ConnectionModel {
  switch (e.type) {
    case 'link':
      if (e.up === m.linkUp) return m;
      // Verbindungsverlust: sofort offline; Rückkehr: erst „Serverstand laden“
      return { ...m, linkUp: e.up, awaitingServerState: true };
    case 'limited':
      return m.limited === e.value ? m : { ...m, limited: e.value };
    case 'server-state-loaded':
      return { ...m, awaitingServerState: false, limited: false, lastServerStateAt: e.at };
    case 'pending':
      return m.pending === Math.max(0, e.count) ? m : { ...m, pending: Math.max(0, e.count) };
    case 'conflicts':
      return m.conflicts === Math.max(0, e.count) ? m : { ...m, conflicts: Math.max(0, e.count) };
  }
}

export function deriveConnectionState(m: ConnectionModel): ConnectionState {
  if (!m.linkUp) return 'offline';
  if (m.awaitingServerState) return 'syncing';
  if (m.conflicts > 0) return 'conflict';
  if (m.pending > 0) return 'syncing';
  if (m.limited) return 'limited';
  return 'online';
}

/** Server-Einschränkungen gelten, solange der Offlinezustand nicht durch einen geladenen Serverstand beendet ist. */
export function isServerRestricted(m: ConnectionModel): boolean {
  return !m.linkUp || m.awaitingServerState;
}

export interface StateText {
  label: string;
  detail: string;
  /** Symbol für die Anzeige neben dem Text; Status nie nur über Farbe. */
  icon: string;
}

export const stateTexts: Record<ConnectionState, StateText> = {
  online: { label: 'Online', detail: 'Die Verbindung steht, die Daten sind aktuell.', icon: '✓' },
  limited: {
    label: 'Eingeschränkt',
    detail: 'Die Verbindung ist instabil. Angezeigte Daten können veraltet sein.',
    icon: '▲',
  },
  offline: {
    label: 'Offline',
    detail: 'Keine Verbindung. Angezeigt wird der zuletzt geladene Stand, keine Echtzeitdaten.',
    icon: '✕',
  },
  syncing: {
    label: 'Synchronisation läuft',
    detail:
      'Die Verbindung ist zurück. Der aktuelle Stand wird geladen, ausstehende Ereignisse werden übertragen.',
    icon: '↻',
  },
  conflict: {
    label: 'Synchronisationskonflikt',
    detail:
      'Mindestens ein Ereignis wurde vom Server nicht übernommen und wird geklärt. Es wird nichts still überschrieben.',
    icon: '!',
  },
};

/** Aktionen, für die der Verbindungszustand die Verfügbarkeit bestimmt (Kap. 01 §1.5). */
export type NetworkAction =
  | 'bind-booking'
  | 'checkout'
  | 'cancel-booking'
  | 'release-vehicle'
  | 'lift-block'
  | 'record-offline-draft'
  | 'view-last-state';

interface ActionRule {
  serverOnly: boolean;
  reason: string;
  nextStep: string;
}

const RULES: Record<NetworkAction, ActionRule> = {
  'bind-booking': {
    serverOnly: true,
    reason: 'Eine neue verbindliche Buchung braucht die Bestätigung des Servers.',
    nextStep: 'Sobald die Verbindung zurück und der aktuelle Stand geladen ist, können Sie buchen.',
  },
  checkout: {
    serverOnly: true,
    reason:
      'Die Buchung kann ohne Verbindung nicht abgeschlossen werden; es wird nichts bezahlt und nichts gebucht.',
    nextStep: 'Sobald die Verbindung zurück ist, geht es im Checkout weiter.',
  },
  'cancel-booking': {
    serverOnly: true,
    reason: 'Eine Stornierung wird erst mit der Bestätigung des Servers wirksam.',
    nextStep: 'Bitte stornieren Sie, sobald die Verbindung zurück ist.',
  },
  'release-vehicle': {
    serverOnly: true,
    reason: 'Die Einsatzfreigabe erteilt nur der Server, nie die App.',
    nextStep:
      'Prüfung lokal abschließen; die Freigabe erscheint nach der Übertragung. Bei Dringlichkeit die Leitstelle kontaktieren.',
  },
  'lift-block': {
    serverOnly: true,
    reason: 'Eine Sperre hebt nur die berechtigte Stelle auf, nie die App.',
    nextStep: 'Bitte die Leitstelle kontaktieren.',
  },
  'record-offline-draft': { serverOnly: false, reason: '', nextStep: '' },
  'view-last-state': { serverOnly: false, reason: '', nextStep: '' },
};

export interface ActionAvailability {
  allowed: boolean;
  /** Grund der Sperre (nie stille Deaktivierung); leer, wenn erlaubt. */
  reason: string;
  /** Nächster möglicher Schritt; leer, wenn erlaubt. */
  nextStep: string;
}

export function actionAvailability(m: ConnectionModel, action: NetworkAction): ActionAvailability {
  const rule = RULES[action];
  if (!rule.serverOnly || !isServerRestricted(m))
    return { allowed: true, reason: '', nextStep: '' };
  return {
    allowed: false,
    reason: m.linkUp
      ? 'Der aktuelle Stand vom Server wird noch geladen.' + ' ' + rule.reason
      : rule.reason,
    nextStep: rule.nextStep,
  };
}

/** Hinweis „Stand hh:mm“ (Ortszeit der Zone) für Angaben, die nicht live sind. */
export function standHhmm(iso: string, zone: string): string {
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return iso;
  const parts = new Intl.DateTimeFormat('de-DE', {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone: zone,
  }).format(ms);
  return `Stand ${parts}`;
}
