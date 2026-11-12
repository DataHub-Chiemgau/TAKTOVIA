# LH-6.3-PAX-003 – Standortfreigabe nur nach Zustimmung

- **Verantwortlicher:** Rolle Passenger-Web (UI-01)
- **Arbeitspaket:** WP-M2-39
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Standortfreigabe erfolgt nur nach aktiver Zustimmung; manuelle Ortswahl bleibt stets möglich.

**Originalabnahme (unverändert):** Abgelehnte Standortfreigabe verhindert keine manuelle Buchung.

## Vertrag

- **Tabellen / DDL:** keine. Der Standort wird nur als Suchkoordinate der aktuellen Anfrage verarbeitet und nicht persistiert.
- **API:** keine eigene; Standort geht nur als Koordinate in die Ortssuche (WP-M2-38) und nur dort an den Geocoding-Adapter in dokumentierter Form, nicht an Dritte.
- **Zustandsautomat:** UI-Zustände je Browserstatus: erlaubt, abgelehnt, dauerhaft blockiert, nicht verfügbar; zusätzlich Zeitüberschreitung und ungenaue Position. Jeder Zustand mit verständlichem Text; manuelle Eingabe bleibt sichtbar und fokussierbar.
- **JSON-Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-01 Schaltfläche „Meinen Standort verwenden“; die Browserabfrage (Geolocation) wird ausschließlich durch diese Nutzeraktion ausgelöst, nie beim Seitenaufruf; erteilte Freigabe liefert einen Startvorschlag, der bestätigt werden muss; Dialog per Tastatur und Screenreader bedienbar.
- **Entscheidungen:** D-11 (Datenrechte, nur falls die Zustimmung persistiert werden soll).
- **Offene Punkte:** OP-34 (ob die Zustimmung über die Browserberechtigung hinaus als `consent_revision` zu protokollieren ist). Arbeitsannahme: keine Persistenz; blockiert den Bau nicht. Zustimmungstext vom Bau mit Fachanalyse abzustimmen.

## Tests

- **Positiv:** Freigabe erteilt: Standort wird als Startvorschlag angeboten und muss bestätigt werden; manuelle Ortswahl ist in jedem Zustand sichtbar und fokussierbar (manuelle Ortswahl bleibt stets möglich).
- **Negativ:** automatisierter Browsertest: ohne Nutzeraktion keine Standortabfrage; abgelehnt, dauerhaft blockiert, nicht verfügbar, Timeout, ungenaue Position: jeweils vollständige Buchung per manueller Eingabe von Start und Ziel; Standort wird nicht persistiert und nicht an Dritte gesendet (Netzwerk-/Datenbankprüfung); Tastatur-/Screenreaderprüfung des Dialogs.
- **Konkurrierend:** Freigabe trifft ein, während der Fahrgast bereits manuell einen Start gewählt hat: manuelle Wahl wird nicht überschrieben; Freigabe trifft nach Seitenwechsel oder Abbruch ein: kein Startvorschlag ohne erneute Bestätigung.
- **Referenztests:** AT-19 (ohne Kartenpflicht, Teil, abgeleitet aus WP-M2-39 Tests; im WP nicht ausdrücklich genannt).
- Testfälle: AT-19; web/passenger/src/booking/LocationConsent.test.tsx::keine_standortabfrage_ohne_nutzeraktion; web/passenger/src/booking/LocationConsent.test.tsx::abgelehnte_freigabe_erlaubt_manuelle_buchung; web/passenger/src/booking/LocationConsent.test.tsx::standort_wird_nicht_persistiert; web/passenger/src/booking/LocationConsent.test.tsx::erteilte_freigabe_bietet_startvorschlag_zur_bestaetigung; web/passenger/src/booking/LocationConsent.test.tsx::blockiert_nicht_verfuegbar_timeout_ungenau_erlauben_manuelle_buchung; web/passenger/src/booking/LocationConsent.test.tsx::manuelle_wahl_wird_von_spaeter_freigabe_nicht_ueberschrieben; web/passenger/src/booking/LocationConsent.test.tsx::dialog_per_tastatur_und_screenreader_bedienbar; web/passenger/src/booking/LocationConsent.test.tsx::standort_geht_nur_an_geocoding_adapter; web/passenger/src/booking/LocationConsent.test.tsx::freigabe_nach_abbruch_oder_seitenwechsel_ergibt_keinen_startvorschlag; backend/app/src/test/kotlin/de/taktovia/app/geo/PlaceSearchApiTest.kt::suchkoordinate_wird_nicht_persistiert_audit_outbox_travel_attempt_unveraendert

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datenschutz Standort).
