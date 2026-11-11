# LH-6.19-NFR-009 – Netzwerkzustand und degradierte Funktion in den Apps

- **Verantwortlicher:** Rolle Frontend (Driver-App `mobile/driver`, Passenger-PWA `web/passenger`, Operator-Web `web/operator`)
- **Arbeitspaket:** WP-M5-09
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> App zeigt Netzwerkzustand und degradierte Funktion verständlich an.

**Originalabnahme (unverändert):** Offline-Test zeigt relevante letzte Aufträge ohne falsches Echtzeitversprechen.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle. Datenstand/Revision stammt aus bestehenden Antworten (`vehicle_run`-Revision).
- **API:** Antworten von `/v1/driver/shifts/{id}/run` und `/v1/bookings` tragen Datenstand-/Revisionsangabe, die die Clients für die Anzeige benötigen (api/*.yaml ggf. ergänzen).
- **Zustandsautomat:** Verbindungszustand (online, eingeschränkt, offline, Synchronisation läuft, Synchronisationskonflikt) mit Texten und je Zustand erlaubten/gesperrten Aktionen nach Kap. 01 §1.5; Zustände der Offline-Ereignisse „lokal gespeichert“, „synchronisiert“, „freigegeben“; Ablage der Zustandsübersicht (Zustände, Texte, erlaubte/gesperrte Aktionen) wird im Bau festgelegt; Vorbedingung der Abnahme ist sie nicht zu erfinden, sondern aus WP-M5-01 zu übernehmen.
- **JSON-Schema / Ereignisse:** keine neuen; Offline-Queue/Deduplikation liegt in M3.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-09 und UI-10 (Driver): Offlinebanner mit Zeit des letzten Plans (Kap. 06 §6.7), zuletzt bestätigter Dienst und Stoppliste mit Datenstand, keine laufende ETA, keine „live“-Kennzeichnung; gesperrte Aktionen (Einsatzfreigabe, Aufhebung einer Sperre, neue verbindliche Buchung) mit Begründung und nächstem Schritt statt stiller Deaktivierung. UI-04 (Passenger): letzter bekannter Buchungsstatus mit Datenstand, ETA/Fahrzeugposition als „Stand hh:mm“. UI-03: Checkout ohne Verbindung nicht abschließbar, kein vorgetäuschter Erfolg; Abbruch mitten in der Zahlung zeigt „Ergebnis wird geprüft“. UI-07 (Operator): Verbindungsverlust des Livekanals als sichtbarer Zustand mit Datenstand. Netzwerkzustand jederzeit erkennbar, per Screenreader über Live-Region gemeldet, nicht nur über Farbe. Nach Wiederverbindung verschwindet der Offlinezustand erst, wenn der Serverstand geladen ist; ausstehende Ereignisse als „wird übertragen“.
- **Entscheidungen:** D-11 (Notbetrieb, Offline-Umfang) blockiert nur die Produktivschaltung.
- **Offene Punkte:** OP-12 (Geltungsbereich „App“; „relevante letzte Aufträge“ nicht definiert: Zeitraum, Umfang; Arbeitsannahme: Driver-App und Passenger-PWA Pflicht, Operator-Livekanal Ergänzung; Definition im Detailvertrag aus WP-M5-01 festzulegen), OP-27 (Zielgeräte, Screenreader, Testinstrumentierung).

## Tests

- **Positiv:** Netzwerkzustand in beiden Apps jederzeit erkennbar, Zustandswechsel über Live-Region für Screenreader gemeldet und nicht nur über Farbe; Offlinezustand verschwindet erst nach geladenem Serverstand, ausstehende Ereignisse als „wird übertragen“; UI-07 zeigt Verlust des Livekanals mit Datenstand; Verbindungsverlust und Rückkehr auf Zielgerät und im Browser: Offline-Test der Driver-App zeigt zuletzt bestätigten Dienst und Stoppliste mit Zeit des Datenstands ohne Echtzeitbehauptung; Passenger-PWA zeigt letzten Buchungsstatus mit „Stand hh:mm“.
- **Negativ:** keine laufende ETA und keine „live“-Kennzeichnung im Offlinezustand; Offlinezustand verschwindet nicht vor geladenem Serverstand; neue verbindliche Buchung, Einsatzfreigabe und Aufhebung einer Sperre offline nicht auslösbar; instabile Verbindung (Paketverlust, hohe Latenz) führt nicht zu flackernder Anzeige oder doppelten Aktionen; Checkout offline nicht abschließbar, kein Erfolg vorgetäuscht; Checkout-Abbruch mitten in der Zahlung zeigt „Ergebnis wird geprüft“; offline gesperrte Aktionen (Einsatzfreigabe, Sperrenaufhebung, neue Buchung) nennen Grund und nächsten Schritt; Zustandswechsel wird per Live-Region gemeldet.
- **Konkurrierend:** offline erfasster Check-in trifft auf serverseitiges Storno: Klärfall statt stillem Überschreiben (Verhalten aus M3, hier Anzeige geprüft).
- **Referenztests:** AT-11 (als Anzeigeprüfung wiederholt).
- Testfälle: AT-11; geplant:mobile/driver/test/verbindungszustand_test.dart::offline_zeigt_letzten_dienst_mit_datenstand_ohne_echtzeit; geplant:mobile/driver/test/verbindungszustand_test.dart::offline_gesperrte_aktionen_nennen_grund_und_naechsten_schritt; geplant:mobile/driver/test/verbindungszustand_test.dart::zustandswechsel_ueber_live_region_nicht_nur_farbe; geplant:mobile/driver/test/verbindungszustand_test.dart::wiederverbindung_offline_erst_nach_serverstand_ausstehende_wird_uebertragen; geplant:mobile/driver/test/verbindungszustand_test.dart::instabile_verbindung_kein_flackern_keine_doppelten_aktionen; geplant:mobile/driver/test/verbindungszustand_test.dart::offline_checkin_trifft_storno_zeigt_klaerfall; geplant:web/passenger/src/network/ConnectionState.test.tsx::offline_zeigt_letzten_buchungsstatus_mit_stand_hhmm; geplant:web/passenger/src/network/ConnectionState.test.tsx::checkout_offline_nicht_abschliessbar; geplant:web/passenger/src/network/ConnectionState.test.tsx::checkout_abbruch_zeigt_ergebnis_wird_geprueft; geplant:web/operator/src/network/ConnectionState.test.tsx::livekanal_verlust_sichtbar_mit_datenstand

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
