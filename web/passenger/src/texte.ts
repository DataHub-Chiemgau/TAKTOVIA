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
  'standort.button': 'Meinen Standort verwenden',
  'standort.dialog.titel': 'Standort verwenden?',
  'standort.dialog.text':
    'Ihr Gerätestandort wird nur für diese eine Suche verwendet, nicht gespeichert und nur zur Adresssuche an unseren Geocoding-Dienst übermittelt. Sie können Start und Ziel jederzeit von Hand wählen.',
  'standort.dialog.ja': 'Standort abfragen',
  'standort.dialog.nein': 'Abbrechen',
  'standort.laeuft': 'Standort wird ermittelt …',
  'standort.vorschlag': 'Ihr Standort ist als Start verfügbar.',
  'standort.uebernehmen': 'Als Start übernehmen',
  'standort.verwerfen': 'Verwerfen',
  'standort.abgelehnt': 'Die Standortfreigabe wurde abgelehnt. Wählen Sie Start und Ziel von Hand.',
  'standort.blockiert':
    'Der Standort ist in Ihrem Browser für diese Seite blockiert. Sie können das in den Browsereinstellungen ändern oder Start und Ziel von Hand wählen.',
  'standort.nichtVerfuegbar':
    'Der Standort ist gerade nicht verfügbar. Wählen Sie Start und Ziel von Hand.',
  'standort.zeit':
    'Die Standortabfrage hat zu lange gedauert. Versuchen Sie es erneut oder wählen Sie Start und Ziel von Hand.',
  'standort.ungenau':
    'Der Standort ist zu ungenau für einen Startvorschlag. Wählen Sie den Start von Hand oder versuchen Sie es erneut.',
  'standort.keineUnterstuetzung':
    'Dieses Gerät unterstützt keine Standortabfrage. Wählen Sie Start und Ziel von Hand.',
  'standort.manuell': 'Start von Hand wählen',
  'standort.meinStandort': 'Mein Standort',
  'sw.updated': 'Die App wurde aktualisiert.',
  // Textbausteine der standardisierten Ablehnungsgründe mit nächster Handlung (WP-M2-13, Kap. 06 §6.2); Schlüssel = Code aus `RideRejectionCode` in camelCase
  'rej.next': 'Nächste Handlung:',
  'rej.leadTime.text': 'Die gewünschte Abfahrt liegt zu kurzfristig.',
  'rej.leadTime.next': 'Wählen Sie eine spätere Abfahrtszeit.',
  'rej.horizon.text': 'Die gewünschte Zeit liegt zu weit in der Zukunft.',
  'rej.horizon.next': 'Wählen Sie einen näheren Termin.',
  'rej.windowNotFeasible.text':
    'Für die gewünschte Ankunft lässt sich kein erreichbares Abholfenster bilden.',
  'rej.windowNotFeasible.next': 'Wählen Sie eine spätere Ankunftszeit.',
  'rej.outsideHours.text': 'Der Dienst ist zur gewünschten Zeit nicht in Betrieb.',
  'rej.outsideHours.next': 'Wählen Sie eine Zeit innerhalb der Betriebszeiten.',
  'rej.outsideArea.text': 'Start oder Ziel liegt außerhalb des Bediengebiets.',
  'rej.outsideArea.next': 'Wählen Sie Start und Ziel innerhalb des Bediengebiets.',
  'rej.ruleForbidden.text': 'Diese Verbindung ist für den Dienst nicht zugelassen.',
  'rej.ruleForbidden.next': 'Wählen Sie ein anderes Ziel oder einen anderen Start.',
  'rej.noStopInRange.text':
    'In Reichweite von Start oder Ziel gibt es keinen buchbaren Haltepunkt.',
  'rej.noStopInRange.next': 'Wählen Sie einen Start oder ein Ziel näher an einem Haltepunkt.',
  'rej.noAccessibleStop.text':
    'In Reichweite gibt es keinen für Rollstuhlnutzung geeigneten Haltepunkt.',
  'rej.noAccessibleStop.next':
    'Wählen Sie einen geeigneten Haltepunkt oder kontaktieren Sie den Betreiber.',
  'rej.noRoute.text': 'Zwischen Start und Ziel besteht keine befahrbare Verbindung.',
  'rej.noRoute.next': 'Wählen Sie ein anderes Ziel.',
  'rej.equipmentMissing.text': 'Kein Fahrzeug mit der benötigten Ausstattung verfügbar.',
  'rej.equipmentMissing.next':
    'Wählen Sie eine andere Zeit oder verzichten Sie auf die Zusatzausstattung.',
  'rej.noStaff.text': 'Zur gewünschten Zeit ist kein Fahrpersonal eingeplant.',
  'rej.noStaff.next': 'Wählen Sie eine andere Zeit.',
  'rej.noCapacity.text': 'Zur gewünschten Zeit ist kein Platz mehr frei.',
  'rej.noCapacity.next': 'Wählen Sie eine andere Zeit oder verringern Sie die Personenzahl.',
} as const;

export type TextKey = keyof typeof texte;

export function tx(key: TextKey): string {
  return texte[key];
}
