# LH-6.1-SYS-007 – Zustandsautomaten-Rahmen mit serverseitiger Übergangsprüfung

- **Verantwortlicher:** Rolle Plattform (Modul `backend/platform`)
- **Arbeitspaket:** WP-M1-17
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrtstatus, Buchungsstatus, Fahrzeugstatus, Zahlungsstatus und Störungsstatus besitzen getrennte Zustandsautomaten.

**Originalabnahme (unverändert):** Unerlaubte Statusübergänge werden serverseitig abgewiesen.

## Vertrag

- **Tabellen / DDL:** Härtung der `state`-Textspalten als versionierte CHECKs/Transitionsregeln (Kap. 04 §4.1). In M1 für `operation.status` (Werte `QUEUED`, `RUNNING`, `RECONCILING`, `SUCCEEDED`, `FAILED`, `MANUAL_REVIEW`, Kap. 02 §7.3) und `tenant.status` (`ACTIVE`, `SUSPENDED`, `CLOSED`, bereits CHECK im Kern-DDL). Muster-Migration für `booking.state`, `vehicle_run.state`, `payment_intent.state`, `capacity_hold.state` (Anwendung M2/M3). Jede Zustandsspalte gehört genau einem Automaten; Revision/CAS über `revision`.
- **API:** Unerlaubter Übergang: 409 `application/problem+json` mit stabilem `code`; der Codename ist in Kap. 05 nicht vergeben und im Bau festzulegen (OP-33). Der aktuelle Zustand ist im ETag-geschützten Lesen sichtbar; veraltete Revision 412.
- **Zustandsautomat:** fünf eigenständige Definitionen unter `vertraege/zustaende/` (<aggregat>.md): Fahrt (Journey-/Segment-Ausführung), Buchung (Journey-Buchung/Segment-Buchung, Kap. 02 §7.3), Fahrzeug, Zahlung (Kap. 03: `CREATED → REQUIRES_ACTION → AUTHORIZED → CAPTURE_PENDING → CAPTURED` samt Nebenpfaden), Störung. Regel "keine gegenseitige Ableitung" (Zahlungsstatus kein impliziter Gleichlauf, Kap. 02 §7.3). Wächter mit Kapazität/Safety/PSP gehören zu M2/M3.
- **JSON-Schema / Ereignisse:** Outbox `<aggregat>.state_changed` mit alter/neuer Zustandsangabe und Revision (Name Vorschlag, OP-33); ältere Revision bewirkt keinen Rücksprung (PH-AT-017).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** keine in M1.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-21 – Für Fahrzeugstatus und Störungsstatus gibt es im Pflichtenheft keine Zustands- und Übergangslisten; "Fahrtstatus" ist nur für Journey/Segment der Föderation beschrieben, nicht für den lokalen `vehicle_run`. Diese beiden Definitionen sowie die lokale Fahrt-Definition **müssen vor Codebeginn dieser Definitionen spezifiziert werden** (blockiert deren Bau; der Rahmen und die Definitionen für Operation, Tenant, Buchung und Zahlung sind nicht blockiert). OP-01/OP-38: Nachweis an echten Aggregaten folgt mit M2 (Fahrt, Buchung, Zahlung) und M3 (Fahrzeug, Störung); Vollabnahme WP-M2-01, -16, -23, -30, WP-M3-01, -31, -53. OP-33: Fehlercode.

## Tests

- **Positiv:** jeder definierte Übergang (generiert aus der Übergangstabelle) ist erlaubt und erzeugt genau ein `state_changed`-Ereignis; für jeden der fünf Automaten eigene Definition mit eigener Zustandsspalte.
- **Negativ:** jeder nicht definierte Übergang (generiert) liefert 409, Zustand unverändert, kein Outbox-Ereignis; unbekannter Zustand; direkter Schreibversuch eines unzulässigen Zustandswerts scheitert am Datenbank-CHECK; kein Automat schreibt den Zustand eines anderen; verspätetes Ereignis mit älterer Revision bewirkt keinen Rücksprung.
- **Konkurrierend:** zwei widersprüchliche Übergänge gleichzeitig auf derselben Revision – genau einer gewinnt, der andere 409/412.
- **Referenztests:** PH-AT-017.

Testfälle: PH-AT-017; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/statemachine/StateMachineTransitionTest.kt::unerlaubter_uebergang_wird_mit_409_abgewiesen; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/statemachine/StateMachineTransitionTest.kt::widerspruechliche_uebergaenge_genau_einer_gewinnt; geplant:db/tests/test_08_zustandshaertung.py::test_unzulaessiger_zustandswert_scheitert_am_check

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
