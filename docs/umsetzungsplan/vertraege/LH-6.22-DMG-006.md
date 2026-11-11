# LH-6.22-DMG-006 – Sicherheitskritische Meldung: Sofortsperre und priorisierter Alarm

- **Verantwortlicher:** Rolle Fleet/Safety (Modul `fleet-safety`)
- **Arbeitspaket:** WP-M3-46
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Eine neue sicherheitskritische Meldung versetzt das Fahrzeug in nicht disponierbar und informiert die Leitstelle priorisiert.

**Originalabnahme (unverändert):** Nach Meldung werden keine neuen Buchungen an das betroffene Fahrzeug vergeben.

## Vertrag

- **Tabellen / DDL:** Annahme der Meldung, Regelklassifikation und Sperre in einer Transaktion: `defect_case`, `vehicle.active_block`, `vehicle.safety_revision` (Erhöhung bei jeder Sperre), `resource_release.revoked_at` (Widerruf jeder gültigen Freigabe des Fahrzeugs im selben Commit), `business_alert` (Alarm höchster Priorität, ohne Fahrgastdaten und Diagnosen), `audit_event` (Sperre und Widerruf mit Fallnummer als Grund). Zeilensperren in stabiler Reihenfolge (Run vor Fahrzeug, WP-M3-08, Kap. 04 §4.5). Sperre idempotent: Doppelmeldung erzeugt weder zweiten Sperrgrund noch zweiten Revisionssprung noch zweiten Alarm. RLS wie V1.
- **API:** Anlage über `POST /v1/defects` (DMG-002), Sperre wirkt synchron über `assertDispatchable(vehicle, shift, safety_revision)` aus WP-M3-08; Dispositions-Commit mit veralteter `safety_revision` scheitert (Konflikt, keine Zusage). Meldung zu fremdem Fahrzeug 404. Offline erfasste kritische Meldung (Kap. 05 §5.5, `POST /v1/driver/events`): Sperre ab Serverempfang; ein späteres, älteres Fahrerereignis hebt die Sperre nicht auf.
- **Auslösung Störungsablauf:** Das Ereignis „Fahrzeug gesperrt“ (Quelle Schaden) überführt bestehende Zuweisungen in den Störungsablauf (WP-M3-46 Krit. 4; Workflow WP-M3-53, Ersatzdisposition WP-M3-54 nicht Teil dieses Vertrags).
- **Zustandsautomat:** Fahrzeug `nicht disponierbar` über Sperrkern WP-M3-08; Alarm-Zustandsautomat aus WP-M3-52; bestehende Zuweisungen gehen in den Störungsablauf über (Ersatzdisposition WP-M3-54, nicht Teil dieses Vertrags).
- **JSON-Schema / Ereignisse:** Ereignis „Fahrzeug gesperrt" (Quelle Schaden) über Outbox; Projektionen werden informiert, die Sperre selbst gilt synchron.
- **Parameter und Kennzahlen:** `alert_escalation_delay_min` (Validierung „Kritische Ereignisse ohne Verzögerung": kritische Alarme umgehen die Verzögerung); Datengrundlage für `Ausfallminuten kritisch`.
- **Oberfläche:** UI-10: Sperrmeldung mit Vorgangsnummer und Kontakt zur Leitstelle, bei Offline-Erfassung sofortige Anweisung, die Leitstelle zu kontaktieren; UI-07: priorisierter Alarm, Fahrzeug „gesperrt · kritischer Mangel", Alarm nennt Fahrzeug, Fallnummer und Zahl der betroffenen Fahrten.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-01 (Sperrgründe im Schema; mehrere gleichzeitige Gründe nicht abbildbar, Default von `active_block` unklar), OP-10 (Schweregrade/„S0"), Alarmpriorität und Empfängerkreis als Detailvertrag vorab festzulegen, OP-34 (Rollen). OP-01 ist vor Codebeginn zu entscheiden, sonst ist die Sperrgrundtrennung nicht baubar; die Sofortsperre selbst ist über `active_block`/`safety_revision` definiert.

## Tests

- **Positiv:** AT-28: Reifen-/Bremsmangel melden, Fahrzeug sofort gesperrt, Leitstelle alarmiert; nach Meldung keine neue Buchung auf dem Fahrzeug; Alarm nennt Fahrzeug, Fallnummer, Zahl betroffener Fahrten; Sperre und Widerruf mit Fallnummer im `audit_event`.
- **Positiv (Störungsablauf/Offline):** bestehende Zuweisungen des Fahrzeugs bleiben nicht still bestehen, sondern erscheinen im Störungsablauf; offline erfasste kritische Meldung sperrt ab Serverempfang, die App zeigt sofort die Anweisung, die Leitstelle zu kontaktieren.
- **Negativ:** Meldung zu fremdem Fahrzeug (404); Doppelmeldung erzeugt keinen zweiten Alarm und keinen zweiten Revisionssprung; Alarm enthält keine Fahrgastdaten; älteres Offline-Ereignis hebt Sperre nicht auf; Alarm ohne Eskalationsverzögerung trotz hohem `alert_escalation_delay_min`.
- **Konkurrierend:** PH-AT-020: Schaden und Zuweisung in zwei parallelen Transaktionen, beide Reihenfolgen; in keiner Serialisierung entsteht eine Fahrtzusage mit dem unsicheren Fahrzeug.
- **Referenztests:** AT-28, PH-AT-020.
- Testfälle: AT-28; PH-AT-020; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/CriticalDefectBlockTest.kt::kritische_meldung_sperrt_und_alarmiert; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/CriticalDefectBlockTest.kt::schaden_und_zuweisung_parallel_keine_zusage; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/CriticalDefectBlockTest.kt::doppelmeldung_erzeugt_einen_alarm; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/CriticalDefectBlockTest.kt::bestehende_zuweisungen_gehen_in_stoerungsablauf; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/CriticalDefectBlockTest.kt::aelteres_offline_ereignis_hebt_sperre_nicht_auf; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/CriticalDefectBlockTest.kt::alarm_ohne_fahrgastdaten_und_ohne_eskalationsverzoegerung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
