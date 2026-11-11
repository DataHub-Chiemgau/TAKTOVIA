import { createContext, useContext, useMemo } from 'react';
import type { ReactNode } from 'react';

/** Deutscher Textkatalog; Deutsch ist die erste Sprache (Kap. 06 §6.1). Weitere Sprachen liefern nur abweichende Texte. */
export const de = {
  'common.menu': 'Menü',
  'common.skipToMain': 'Zum Hauptinhalt springen',
  'common.mainNav': 'Hauptnavigation',
  'common.details': 'Details',
  'common.context': 'Arbeitskontext',
  'common.tenant': 'Mandant',
  'common.service': 'Dienst',
  'common.status': 'Status',
  'common.dataAsOf': 'Datenstand',
  'common.loadMore': 'Weitere laden',
  'common.loading': 'Wird geladen …',
  'common.noEntries': 'Keine Einträge vorhanden.',
  'common.submit': 'Speichern',
  'common.cancel': 'Abbrechen',
  'common.retry': 'Erneut versuchen',
  'common.busy': 'In Arbeit',
  'common.correlation': 'Support-Referenz: {id}',
  'common.problemsInForm': 'Bitte prüfen Sie Ihre Eingaben:',
  'common.otherViolation': 'Eingabe {pointer}',
  'conflict.title': 'Die Daten wurden zwischenzeitlich geändert',
  'conflict.body':
    'Jemand anderes hat diesen Eintrag geändert, seit Sie ihn geöffnet haben. Ihre Änderung wurde nicht gespeichert und überschreibt nichts.',
  'conflict.reload': 'Aktuellen Stand laden',
  'conflict.review': 'Meine Eingaben prüfen',
  'state.loading.title': 'Wird geladen',
  'state.empty.title': 'Keine Einträge',
  'state.error.title': 'Etwas ist schiefgelaufen',
  'state.error.body': 'Die Anfrage konnte nicht verarbeitet werden. Bitte versuchen Sie es erneut.',
  'state.unauthenticated.title': 'Anmeldung erforderlich',
  'state.unauthenticated.body': 'Ihre Sitzung ist nicht mehr gültig. Melden Sie sich erneut an.',
  'state.forbidden.title': 'Keine Berechtigung',
  'state.forbidden.body':
    'Für diesen Bereich fehlt Ihnen die Berechtigung. Es werden keine Daten angezeigt.',
  'state.notFound.title': 'Nicht gefunden',
  'state.notFound.body': 'Die gesuchte Seite oder der Eintrag wurde nicht gefunden.',
  'state.conflict.title': 'Die Daten wurden geändert',
  'state.conflict.body':
    'Der Eintrag hat einen neueren Stand. Laden Sie ihn neu, bevor Sie weiterarbeiten.',
  'state.preconditionRequired.title': 'Aktueller Stand erforderlich',
  'state.preconditionRequired.body':
    'Die Änderung kann nur auf Grundlage des aktuellen Stands gespeichert werden. Laden Sie den Eintrag neu.',
  'state.rateLimited.title': 'Zu viele Anfragen',
  'state.rateLimited.body': 'Bitte warten Sie einen Moment, bevor Sie es erneut versuchen.',
  'state.rateLimited.retry': 'Erneut möglich in {seconds} Sekunden.',
  'state.unavailable.title': 'Dienst vorübergehend nicht erreichbar',
  'state.unavailable.body':
    'Bitte versuchen Sie es in Kürze erneut. Ihre Eingaben bleiben erhalten.',
  'state.offline.title': 'Keine Verbindung',
  'state.offline.body': 'Der Server ist nicht erreichbar. Prüfen Sie Ihre Verbindung.',
  'status.ok': 'in Ordnung',
  'auth.signedOut.title': 'Anmeldung erforderlich',
  'auth.signedOut.body':
    'Melden Sie sich an, um fortzufahren. Bis dahin werden keine Daten angezeigt.',
  'auth.signIn': 'Anmelden',
  'auth.signingIn.title': 'Anmeldung läuft',
  'auth.expired.title': 'Sitzung abgelaufen',
  'auth.expired.body':
    'Ihre Sitzung ist abgelaufen. Melden Sie sich erneut an; nicht gespeicherte Eingaben sind verloren.',
  'auth.revoked.title': 'Sitzung beendet oder gesperrt',
  'auth.revoked.body':
    'Ihre Sitzung wurde beendet oder gesperrt, zum Beispiel durch Abmeldung auf allen Geräten oder eine Kontosperre. Es werden keine Daten angezeigt.',
  'auth.error.title': 'Anmeldung fehlgeschlagen',
  'auth.error.body':
    'Die Anmeldung konnte nicht abgeschlossen werden. Bitte versuchen Sie es erneut.',
  'auth.mfaRequired.title': 'Zweiter Faktor erforderlich',
  'auth.mfaRequired.body':
    'Für Ihre Rolle ist ein zweiter Faktor Pflicht. Sie werden zur Bestätigung zum Anmeldedienst geführt.',
  'auth.signInMfa': 'Mit zweitem Faktor anmelden',
  'auth.noMembership.title': 'Keine Mitgliedschaft',
  'auth.noMembership.body':
    'Ihr Konto ist keinem Mandanten zugeordnet. Es werden keine Daten angezeigt. Wenden Sie sich an Ihre Administration.',
  'auth.signOut': 'Abmelden',
  'tenant.label': 'Mandant wechseln',
  'tenant.switch': 'Wechseln',
  'tenant.switching': 'Mandant wird gewechselt …',
  'tenant.error': 'Der Mandant konnte nicht gewechselt werden.',
  'tenant.dirty.title': 'Ungespeicherte Eingaben',
  'tenant.dirty.body':
    'In „{tenant}“ gibt es ungespeicherte Eingaben ({forms}). Sie werden nicht in den neuen Mandanten übernommen, aber für die Rückkehr zu „{tenant}“ aufbewahrt.',
  'tenant.dirty.confirm': 'Wechseln und Eingaben aufbewahren',
  'tenant.dirty.stay': 'Hier bleiben',
  'sessions.caption': 'Meine Sitzungen im aktiven Mandanten',
  'sessions.created': 'Angemeldet seit',
  'sessions.lastSeen': 'Zuletzt aktiv',
  'sessions.state': 'Status',
  'sessions.active': 'aktiv',
  'sessions.revokedState': 'beendet',
  'sessions.thisDevice': 'dieses Gerät',
  'sessions.revoke': 'Abmelden',
  'sessions.revokeAll': 'Alle Geräte abmelden',
  'sessions.empty': 'Keine Sitzungen',
  'sessions.action': 'Aktion',
  'violation.REQUIRED': 'Dieses Feld ist erforderlich.',
  'violation.UNKNOWN_FIELD': 'Dieses Feld ist nicht vorgesehen.',
  'violation.TYPE_MISMATCH': 'Der Wert hat den falschen Typ.',
  'violation.TOO_SHORT': 'Der Wert ist zu kurz.',
  'violation.TOO_LONG': 'Der Wert ist zu lang.',
  'violation.BELOW_MINIMUM': 'Der Wert ist zu klein.',
  'violation.ABOVE_MAXIMUM': 'Der Wert ist zu groß.',
  'violation.PATTERN_MISMATCH': 'Das Format ist ungültig.',
  'violation.INVALID_FORMAT': 'Das Format ist ungültig.',
  'violation.NOT_IN_ENUM': 'Dieser Wert ist nicht zulässig.',
  'violation.TOO_FEW_ITEMS': 'Es sind zu wenige Einträge angegeben.',
  'violation.TOO_MANY_ITEMS': 'Es sind zu viele Einträge angegeben.',
  'violation.NOT_UNIQUE': 'Die Einträge müssen eindeutig sein.',
  'violation.INVALID_VALUE': 'Dieser Wert ist ungültig.',
  'violation.NESTING_TOO_DEEP': 'Die Verschachtelung ist zu tief.',
  'violation.MONEY_NOT_INTEGER':
    'Der Betrag muss in kleinster Einheit ganzzahlig angegeben werden.',
  'violation.MONEY_OUT_OF_RANGE': 'Der Betrag liegt außerhalb des zulässigen Bereichs.',
  'violation.MONEY_NEGATIVE': 'Der Betrag darf nicht negativ sein.',
  'violation.CURRENCY_UNKNOWN': 'Die Währung ist unbekannt.',
  'violation.EXPONENT_MISMATCH': 'Die Nachkommastellen passen nicht zur Währung.',
  'violation.OFFSET_REQUIRED': 'Es fehlt der UTC-Offset der Zeitangabe.',
  'violation.LOCAL_TIME_NONEXISTENT':
    'Diese Ortszeit existiert wegen der Zeitumstellung nicht. Bitte korrigieren.',
  'violation.LOCAL_TIME_AMBIGUOUS':
    'Diese Ortszeit ist wegen der Zeitumstellung mehrdeutig. Bitte wählen Sie den Offset.',
  'violation.ZONE_UNKNOWN': 'Die Zeitzone ist unbekannt.',
  'violation.ZONE_OFFSET_MISMATCH': 'Offset und Zeitzone passen nicht zusammen.',
  'violation.INTERVAL_INVALID': 'Das Zeitintervall ist ungültig.',
  'violation.TARGET_NOT_ALLOWED': 'Dieses Ziel ist nicht zulässig.',
  'violation.UNKNOWN': 'Die Eingabe wurde abgelehnt.',
  // Feldschutz (WP-M1-31): UI-05 Unterstützungsbedarf (nur Auswahl, keine Diagnoseabfrage), UI-20 Zugriff mit Zweckangabe
  'support.legend': 'Unterstützung bei der Fahrt',
  'support.hint':
    'Wählen Sie nur, was für die Beförderung nötig ist. Angaben zu Krankheiten oder Diagnosen sind nicht nötig und werden nicht abgefragt.',
  'support.BOARDING_HELP': 'Hilfe beim Ein- und Aussteigen',
  'support.AUDIO_GUIDANCE': 'Akustische Ansagen und Begleitung',
  'support.LOW_FLOOR': 'Niederflureinstieg',
  'support.WHEELCHAIR_SPACE': 'Platz für einen Rollstuhl',
  'support.ASSISTANCE_ANIMAL': 'Assistenzhund oder -tier',
  'support.ACCOMPANYING_PERSON': 'Begleitperson',
  'purpose.label': 'Zweck des Zugriffs',
  'purpose.placeholder': 'Bitte Zweck wählen',
  'purpose.required': 'Ohne Zweckangabe ist kein Zugriff möglich.',
  'purpose.BOOKING_ASSISTANCE': 'Buchung oder Fahrt für den Fahrgast unterstützen',
  'purpose.SUPPORT_CASE': 'Supportfall bearbeiten',
  'purpose.TRANSPORT_PLANNING': 'Beförderung disponieren',
  'purpose.hint': 'Der Zugriff wird mit Ihrem Namen und dem Zweck protokolliert.',
} as const;

export type MessageKey = keyof typeof de;
export type Messages = Readonly<Partial<Record<MessageKey, string>>>;

export function translate(
  messages: Messages,
  key: MessageKey,
  params?: Record<string, string | number>,
): string {
  const template = messages[key] ?? de[key];
  return template.replace(/\{(\w+)\}/g, (_, name: string) => String(params?.[name] ?? `{${name}}`));
}

export interface I18n {
  locale: string;
  t: (key: MessageKey, params?: Record<string, string | number>) => string;
}

const defaultI18n: I18n = { locale: 'de', t: (key, params) => translate(de, key, params) };
const Context = createContext<I18n>(defaultI18n);

export function I18nProvider({
  locale = 'de',
  messages = {},
  children,
}: {
  locale?: string;
  messages?: Messages;
  children: ReactNode;
}) {
  const value = useMemo<I18n>(
    () => ({ locale, t: (key, params) => translate(messages, key, params) }),
    [locale, messages],
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useI18n(): I18n {
  return useContext(Context);
}
