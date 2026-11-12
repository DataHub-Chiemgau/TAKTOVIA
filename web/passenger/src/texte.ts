/**
 * Textkatalog der Passenger-PWA (Deutsch, erste Sprache; Kap. 06 §6.1). Oberflächentexte stehen nie im Komponentencode, sondern unter
 * einem Schlüssel `<bereich>.<name>`, damit weitere Sprachen nur abweichende Werte liefern (WP-M2-36). Zustandstexte der Fehlerklassen
 * liegen im Katalog des Designsystems (`state.*`).
 */
export const texte = {
  'nav.reisen': 'Meine Reisen',
  'nav.hilfe': 'Hilfe',
  'nav.konto': 'Konto',
  'nav.start': 'Neue Fahrt',
  'ui01.title': 'Fahrt suchen',
  'ui01.empty.title': 'Fahrtsuche noch nicht verfügbar',
  'ui01.empty.hint': 'Die Fahrtsuche (UI-01) wird mit der Buchung eingerichtet.',
  'ui02.title': 'Angebote',
  'ui02.empty.title': 'Keine Angebote',
  'ui02.empty.hint': 'Suchen Sie zuerst eine Fahrt, dann erscheinen hier die Angebote (UI-02).',
  'ui03.title': 'Buchung abschließen',
  'ui03.empty.title': 'Kein Angebot gewählt',
  'ui03.empty.hint': 'Wählen Sie zuerst ein Angebot (UI-03).',
  'ui04.title': 'Meine Reisen',
  'ui04.empty.title': 'Keine Reisen',
  'ui04.empty.hint': 'Aktive und vergangene Reisen (UI-04) erscheinen hier.',
  'ui04.detail.title': 'Reise',
  'ui05.title': 'Konto',
  'hilfe.title': 'Hilfe',
  'hilfe.intro':
    'So buchen Sie eine Fahrt: Start und Ziel nennen, Zeit wählen, Angebot prüfen und bestätigen. Die App arbeitet nur mit Verbindung; ohne Netz ist keine Buchung möglich.',
  'hilfe.access':
    'Alle Funktionen sind per Tastatur und Screenreader bedienbar. Meldungen zu Ihrer Buchung werden angesagt, ohne dass die Seite springt.',
  'hilfe.signin': 'Zum Buchen und für Ihre Reisen melden Sie sich an.',
  'shell.brand': 'TAKTOVIA Fahrgast',
  'shell.status.shell': 'Grundgerüst, noch keine Inhalte',
  'offline.banner': 'Keine Verbindung. Buchen und Ändern sind nur online möglich.',
  'offline.title': 'Sie sind offline',
  'offline.hint':
    'Es wird nichts gespeichert oder gebucht, solange die Verbindung fehlt. Sobald Sie wieder online sind, geht es hier weiter.',
  'offline.back': 'Wieder online.',
  'ort.suche.hint': 'Adresse, Ort, Haltestelle oder Favorit eingeben (mindestens 2 Zeichen).',
  'ort.zuKurz': 'Bitte geben Sie mindestens 2 Zeichen ein.',
  'ort.keinTreffer':
    'Kein Ort gefunden. Prüfen Sie die Schreibweise oder wählen Sie eine Haltestelle, einen Favoriten oder einen Punkt auf der Karte.',
  'ort.geocoderAus':
    'Die Adress- und Ortssuche ist gerade nicht verfügbar. Haltestellen, Favoriten und der Kartenpunkt funktionieren weiter.',
  'ort.zuVieleAnfragen':
    'Zu viele Suchanfragen. Bitte warten Sie einen Moment und versuchen Sie es erneut.',
  'ort.fehler':
    'Die Suche ist fehlgeschlagen. Bitte versuchen Sie es erneut oder wählen Sie einen Favoriten.',
  'ort.typ.adresse': 'Adresse',
  'ort.typ.poi': 'Ort',
  'ort.typ.haltestelle': 'Haltestelle',
  'ort.typ.favorit': 'Favorit',
  'ort.typ.kartenpunkt': 'Kartenpunkt',
  'ort.ausserhalb': 'außerhalb Bediengebiet',
  'ort.ausserhalbHinweis':
    'Dieser Ort liegt außerhalb des Bediengebiets und kann nicht gebucht werden. Bitte wählen Sie einen anderen Ort.',
  'ort.bestaetigt': 'Treffpunkt bestätigt',
  'ort.aendern': 'Ändern',
  'ort.favoriten': 'Meine Favoriten',
  'ort.karte': 'Punkt auf Karte wählen',
  'ort.karteHinweis':
    'Geben Sie die Koordinaten des Punkts ein (die Kartenansicht folgt mit der Karte, WP-M2-40).',
  'ort.karteUebernehmen': 'Kartenpunkt prüfen',
  'ort.karteUngueltig':
    'Breite (-90 bis 90) und Länge (-180 bis 180) müssen gültige Zahlen sein, nicht beide 0.',
  'ort.nachsteHaltestelle': 'Nächste Haltestelle',
  'sw.updated': 'Die App wurde aktualisiert.',
} as const;

export type TextKey = keyof typeof texte;

export function tx(key: TextKey): string {
  return texte[key];
}
