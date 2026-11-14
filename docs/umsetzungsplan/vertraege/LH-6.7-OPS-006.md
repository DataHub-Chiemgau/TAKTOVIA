# LH-6.7-OPS-006 – Überfälligkeit und ausstehende Informationen

- **Verantwortlicher:** Rolle Leitstelle/Dispatch (Modul `booking-dispatch`/`fleet-safety`, periodischer Job mit Lease)
- **Arbeitspaket:** WP-M3-42
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Auftrags- und Fahrzeugansichten kennzeichnen Überfälligkeit und ausstehende Informationen.

**Originalabnahme (unverändert):** Definierte Schwelle löst sichtbaren Hinweis mit Betroffenenzahl aus.

## Vertrag

- **Tabellen / DDL:** Hinweise über `business_alert` (Dedupe `(policy,event,recipient)`, Kap. 04 §4.4; Hinweiserzeugung über WP-M3-52, Tabelle noch nicht in V1/V2) mit Betroffenenzahl; Quellen: `position_sample` (Standortalter, WP-M3-34), Fahrerereignisse/Planrevisionsbestätigung (WP-M3-27/WP-M3-29), `booking`/`route_stop` (Abholzeit). Auswertung durch periodischen Job mit Lease (WP-M1-15). RLS (V1) plus Dienstscope.
- **API:** Kennzeichen in `/v1/operator/vehicles` und der Auftragsliste (`GET /v1/bookings`, WP-M3-37) (Felder Arbeitsnamen `flags[]` mit `kind` `OVERDUE` oder `INFO_PENDING`, `affected_count`); Arbeitsliste offener Hinweise (Pfad im OpenAPI-Entwurf). Betroffenenzahl zählt nur Fahrgäste/Buchungen des berechtigten Dienstes; Hinweis enthält keine Namen.
- **Zustandsautomat:** Hinweis: offen → erledigt (Ursache entfallen); derselbe Sachverhalt erzeugt genau einen offenen Hinweis (Dedupe). Überfälligkeit (Verspätung) und ausstehende Information (z. B. fehlende Fahrerbestätigung einer Planrevision, offener `REVIEW_REQUIRED`-Fall) sind getrennte Zustände.
- **JSON-Schema / Ereignisse:** Hinweisereignis über WP-M3-52; Schwellenwert wird bei jeder Auswertung aus der wirksamen Konfiguration des Dienstes gelesen; Änderung wirkt nicht rückwirkend auf ausgelöste Hinweise.
- **Parameter und Kennzahlen:** Kandidaten aus `kataloge.csv`: `vehicle_unavailability_alert_min` (Dienst), `alert_escalation_delay_min` (Alerttyp), `on_time_pickup_tolerance_min` (Dienst); weitere Schwellen laut OP-06. Die Zuordnung je Überfälligkeitsart (Abholung überfällig, ausbleibende Fahrerbestätigung, veralteter Standort, offener `REVIEW_REQUIRED`-Fall) ist im Pflichtenheft nicht festgelegt.
- **Oberfläche:** UI-07 – Kennzeichnung in Liste und Detail (Verspätung und „Information ausstehend" visuell getrennt), Arbeitsliste mit Betroffenenzahl, Leerzustand ohne Hinweise.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-06 (welche Überfälligkeitsarten in P1 gelten, Zuordnung zu Parametern, Zählweise „Betroffenenzahl": Fahrgäste oder Buchungen). Bis zur Entscheidung gilt als Arbeitsannahme: Zählung nach Buchungen des berechtigten Dienstes, mindestens die Arten „überfällige Abholung" (`on_time_pickup_tolerance_min`), „veralteter Standort" und „Information ausstehend". Das Pflichtenheft legt dies nicht fest; Test ordnet die Schwelle über Fixture zu. Die Auswertungsschwellen sind vor Abnahme (nicht vor Bau) vom Auftraggeber zu bestätigen.

## Tests

- **Positiv:** Wird die konfigurierte Schwelle überschritten, erscheint ein Hinweis mit korrekter Betroffenenzahl in Auftrags- und Fahrzeugansicht; ausstehende Information (fehlende Fahrerbestätigung) erscheint als eigener Zustand, nicht als Verspätung.
- **Negativ:** Schwelle nicht erreicht → kein Hinweis; Ursache behoben → Kennzeichnung verschwindet; Änderung der Schwelle wirkt nicht rückwirkend auf bereits ausgelöste Hinweise; Betroffenenzahl zählt nur Buchungen des berechtigten Dienstes (fremder Dienst/Mandant nicht enthalten); Hinweis enthält keine Namen.
- **Konkurrierend:** Zwei Auswertungsläufe gleichzeitig (Lease) erzeugen für denselben Sachverhalt genau einen offenen Hinweis.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/OverdueAlertTest.kt::schwelle_ueberschritten_loest_hinweis_mit_betroffenenzahl_aus; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/OverdueAlertTest.kt::ausstehende_information_ist_eigener_zustand; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/OverdueAlertTest.kt::zwei_auswertungslaeufe_gleichzeitig_ein_hinweis; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/OverdueAlertTest.kt::unterhalb_schwelle_kein_hinweis_und_nach_ursachenwegfall_erledigt; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/OverdueAlertTest.kt::schwellenaenderung_wirkt_nicht_rueckwirkend; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/OverdueAlertTest.kt::betroffenenzahl_nur_berechtigter_dienst_und_hinweis_ohne_namen; web/operator/src/alerts/OverdueFlags.test.tsx::verspaetung_und_information_ausstehend_getrennt_in_liste_und_detail_leerzustand; backend/app/src/test/kotlin/de/taktovia/app/operator/OverdueFlagsApiTest.kt::hinweise_in_auftragsliste_arbeitsliste_und_scope_getrennt_nach_art; backend/app/src/test/kotlin/de/taktovia/app/operator/OverdueFlagsApiTest.kt::auswertungslauf_der_anwendung_meldet_veralteten_standort_in_fahrzeugansicht_und_alarm; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/OperationalSignalsTest.kt::veralteter_standort_erst_ueber_der_schwelle_und_nur_mit_angetretener_schicht; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/OperationalSignalsTest.kt::offener_klaerfall_bis_ein_spaeteres_ereignis_regulaer_angewendet_wurde

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Dienstscope der Betroffenenzahl, keine Namen im Hinweis).
