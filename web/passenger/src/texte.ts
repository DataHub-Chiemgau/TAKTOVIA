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
  // UI-01 Startansicht (WP-M2-37)
  'start.verfuegbarkeit': 'Verfügbarkeit',
  'start.inBetrieb': 'Der Dienst ist in Betrieb.',
  'start.ausserBetrieb': 'Der Dienst ist derzeit außer Betrieb.',
  'start.naechsteBetriebszeit': 'Nächste Betriebszeit:',
  'start.keineBetriebszeit': 'Eine nächste Betriebszeit ist nicht bekannt.',
  'start.verfuegbarkeitFehler': 'Die Verfügbarkeit des Dienstes konnte nicht gelesen werden.',
  'start.wann': 'Wann möchten Sie fahren?',
  'start.jetzt': 'Jetzt losfahren',
  'start.zeitWaehlen': 'Zeit wählen',
  'start.abfahrt': 'Abfahrt',
  'start.ankunft': 'Ankunft',
  'start.zeitbezug': 'Zeitbezug',
  'start.datumUhrzeit': 'Datum und Uhrzeit',
  'start.zeitzone': 'Zeitzone',
  'start.reisende': 'Reisende',
  'start.rollstuhl': 'Rollstuhlplatz benötigt',
  'start.suchen': 'Verbindungen suchen',
  'start.suchtLaeuft': 'Verbindungen werden gesucht …',
  'start.abbrechen': 'Suche abbrechen',
  'start.abgebrochen': 'Die Suche wurde abgebrochen. Es wurde nichts gebucht.',
  'start.zeitFehlt': 'Bitte geben Sie eine gültige Zeit an, bevor Sie suchen.',
  'start.orteFehlen': 'Wählen Sie zuerst Start und Ziel.',
  'start.keineAbdeckung': 'Keine Abdeckung',
  'start.keineFahrt': 'Keine Fahrt gefunden',
  'start.keinDienst':
    'Für Ihr Konto ist kein Dienst hinterlegt. Bitte wenden Sie sich an den Betreiber.',
  'start.dienst': 'Dienst',
  'start.bereit':
    'Start und Ziel sind bestätigt. Geben Sie jetzt die Zeit an und suchen Sie eine Verbindung.',
  'start.anstehend': 'Meine anstehenden Buchungen',
  'start.anstehendLeer': 'Sie haben keine anstehenden Buchungen.',
  'start.anstehendFehler': 'Ihre Buchungen konnten nicht geladen werden.',
  'start.aktualisieren': 'Aktualisieren',
  'start.abholung': 'Abholung',
  'start.ankunftFenster': 'Ankunft',
  // UI-02 Angebotsliste (WP-M2-41)
  'angebot.laedt': 'Angebote werden geladen …',
  'angebot.fehler': 'Das Angebot konnte nicht geladen werden.',
  'angebot.keine': 'Es liegt kein Angebot vor.',
  'angebot.neueSuche': 'Neue Suche',
  'angebot.auswaehlen': 'Angebot auswählen',
  'angebot.abholpunkt': 'Abholpunkt',
  'angebot.zielpunkt': 'Zielhaltepunkt',
  'angebot.fussweg': 'Fußweg',
  'angebot.fussnote': 'Luftlinie, ca.',
  'angebot.fussUnbekannt': 'nicht verfügbar',
  'angebot.abholfenster': 'Abholfenster',
  'angebot.ankunftsfenster': 'Ankunftsfenster',
  'angebot.fahrtdauer': 'Fahrtdauer',
  'angebot.preis': 'Preis',
  'angebot.gesamtpreis': 'Gesamtpreis',
  'angebot.preisAufschluesselung': 'Preisaufschlüsselung',
  'angebot.gueltigBis': 'Angebot gültig bis',
  'angebot.bedingungen': 'Bedingungen',
  'angebot.stornoregel': 'Stornoregel',
  'angebot.barrierefreiheitUnbestaetigt': 'Barrierefreiheit unbestätigt',
  'angebot.stufenfrei': 'Stufenfreier Zugang',
  'angebot.rollstuhl': 'Einstieg mit Rollstuhl',
  'angebot.ja': 'ja',
  'angebot.nein': 'nein',
  'angebot.unbestaetigt': 'unbestätigt',
  'angebot.abgelaufen': 'Dieses Angebot ist abgelaufen. Bitte suchen Sie erneut.',
  'angebot.abgelaufenTitel': 'Angebot abgelaufen',
  'angebot.geaendert':
    'Das Angebot hat sich geändert. Bitte prüfen Sie den neuen Stand und wählen Sie das Angebot erneut aus. Es wurde nichts gebucht.',
  'angebot.geaendertPreis': 'Der Preis hat sich geändert. Neuer Preis:',
  'angebot.nichtMehrVerfuegbar':
    'Der Platz ist nicht mehr verfügbar. Bitte suchen Sie erneut. Es wurde nichts gebucht.',
  'angebot.buchen': 'Verbindlich buchen',
  'angebot.bucht': 'Buchung wird bestätigt …',
  'angebot.unklar':
    'Das Ergebnis der Buchung wird geprüft. Es wird weder Erfolg noch Ablehnung behauptet. Bitte prüfen Sie den Stand erneut.',
  'angebot.pruefen': 'Stand prüfen',
  'angebot.bestaetigtTitel': 'Buchung bestätigt',
  'angebot.buchungsnummer': 'Buchungsnummer',
  'angebot.status': 'Status',
  'angebot.statusConfirming': 'Wird bestätigt (Zahlung ausstehend)',
  'angebot.statusConfirmed': 'Bestätigt',
  'angebot.zuReisen': 'Zu meinen Reisen',
  'angebot.vorhaltung': 'Das Angebot ist unverbindlich, bis Sie verbindlich buchen.',
  // UI-03 Checkout (WP-M2-42)
  'angebot.zumCheckout': 'Weiter zur Buchung',
  'checkout.pruefen': 'Ihre Reise prüfen',
  'checkout.zusammenfassung': 'Zusammenfassung',
  'checkout.zahlung': 'Zahlung',
  'checkout.pspHinweis':
    'Testmodus (Zahlungssimulator): Es wird nichts belastet. Zahlungsdaten geben Sie nie in dieser Anwendung ein, die Eingabe erfolgt in der Komponente des Zahlungsdienstleisters.',
  'checkout.zustimmung': 'Ich akzeptiere die Beförderungs- und Stornobedingungen.',
  'checkout.preisZustimmung': 'Ich stimme dem geänderten Preis ausdrücklich zu:',
  'checkout.buchen': 'Zahlungspflichtig buchen',
  'checkout.voraussetzung':
    'Zum Buchen wählen Sie ein Zahlungsmittel und akzeptieren die Bedingungen.',
  'checkout.reserviert': 'Plätze werden reserviert …',
  'checkout.restfrist': 'Reservierung gültig noch',
  'checkout.holdAblaufWaehrend':
    'Die Reservierungsfrist ist abgelaufen. Wir prüfen Zahlung und Reservierung; ein Erfolg wird nur gezeigt, wenn die Buchung bestätigt ist. Sie können ein neues Angebot suchen.',
  'checkout.zahlungsstatus': 'Zahlungsstatus',
  'checkout.zahl.keine': 'Keine Zahlung begonnen',
  'checkout.zahl.created': 'Zahlung wird vorbereitet',
  'checkout.zahl.sca': 'Zusätzliche Bestätigung bei Ihrer Bank erforderlich',
  'checkout.zahl.authorized': 'Betrag reserviert (noch nicht eingezogen)',
  'checkout.zahl.failed': 'Zahlung abgelehnt',
  'checkout.zahl.capturePending': 'Einzug läuft',
  'checkout.zahl.captured': 'Bezahlt',
  'checkout.zahl.voidPending': 'Reservierung des Betrags wird aufgehoben',
  'checkout.zahl.voided': 'Reservierung des Betrags aufgehoben',
  'checkout.zahl.unknown': 'Zahlungsstand wird geprüft',
  'checkout.erfolg.ansage': 'Buchung bestätigt.',
  'checkout.zurReise': 'Zur Reise',
  'checkout.kontakt': 'Bei Fragen erreichen Sie den Betreiber über die',
  'checkout.abgelehnt.titel': 'Zahlung abgelehnt',
  'checkout.abgelehnt.text':
    'Die Zahlung wurde abgelehnt. Es wurde keine Buchung bestätigt und nichts berechnet. Sie können mit einem neuen Angebot und einem anderen Zahlungsmittel erneut buchen.',
  'checkout.holdverlust.titel': 'Reservierung abgelaufen',
  'checkout.holdverlust.text':
    'Die Reservierung ist nicht mehr gültig, die Buchung wurde nicht bestätigt. Ein bereits reservierter Betrag wird aufgehoben. Bitte suchen Sie ein neues Angebot.',
  'checkout.kapazitaet.titel': 'Platz nicht mehr verfügbar',
  'checkout.unklar.titel': 'Bestätigung wird geprüft',
  'checkout.unklar.text':
    'Das Ergebnis Ihrer Buchung wird geprüft. Es wird weder Erfolg noch Ablehnung behauptet; bitte lösen Sie keine zweite Buchung aus.',
  'checkout.sca.titel': 'Zusätzliche Bestätigung erforderlich',
  'checkout.sca.text':
    'Bitte schließen Sie die Bestätigung bei Ihrer Bank ab und kehren Sie dann hierher zurück. Erst die bestätigte Zahlung schließt die Buchung ab.',
  'checkout.sca.zurueck': 'Ich bin von der Bestätigung zurückgekehrt',
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
