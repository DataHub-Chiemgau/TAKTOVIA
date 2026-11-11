# LH-6.24-TIM-001 – Ist-Arbeitszeit als Zeitereignisse

- **Verantwortlicher:** Rolle Personal/Zeit (Modul `staff-time`)
- **Arbeitspaket:** WP-M4-08
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Arbeitszeit wird unabhängig von geplanten Schichten als tatsächliche Arbeitszeit mit Beginn, Ende, Pausen und Erfassungsquelle verwaltet.

**Originalabnahme (unverändert):** Zwei unterschiedliche Ist-Zeit-Ereignisse zu einer geplanten Schicht sind nachvollziehbar.

## Vertrag

- **Tabellen / DDL:** `time_event` (neue Migration, Mandantenschlüssel `(tenant_id,id)`, RLS): Person, Ereignisart (Arbeitsbeginn, Arbeitsende, Pausenbeginn, Pausenende), Ereigniszeit, Gerätezeit, Serverempfangszeit, Erfassungsquelle (Enum: App, Web, Nacherfassung durch Berechtigte), Urheber/Akteur, stabile Ereignis-ID, optionale Schicht (Fremdschlüssel nullbar, keine Pflicht); append-only (UPDATE/DELETE scheitert); Unique `(tenant_id, Ereignis-ID)`. Eigener Modulzugang und Spaltenschutz für HR-Daten (Kap. 04 §4.6). Intervalle werden aus Ereignisfolgen abgeleitet, nicht gespeichert. Nacherfassung ist eigene Quelle mit Akteur und steht im `audit_event`; sie ersetzt kein vorhandenes Ereignis.
- **API:** `/v1/time-events` (erfassen mit `Idempotency-Key`; eigene Ereignisse lesen; Planersicht für berechtigte Rollen); Wiederholung mit gleicher Ereignis-ID erzeugt keinen zweiten Eintrag. Ein Mitarbeiter sieht nur eigene Zeitereignisse, fremde Person -> 404.
- **Zustandsautomat:** keiner; Intervallableitung: Beginn..Ende abzüglich Pausen; Ende ohne Beginn wird gekennzeichnet statt verworfen; Verhalten bei fehlendem Ende ist im Detailvertrag WP-M4-01 festzulegen (OP).
- **JSON-Schema / Ereignisse:** Outbox „Zeitereignis erfasst“ (`api/events/`, Schema mit WP-M4-01).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-13 (Eigene Zeit/Abwesenheit) bzw. Stempeln in der Fahrer-App (WP-M4-11); hier nur Backend.
- **Entscheidungen:** D-11 (Aufbewahrung von Arbeitszeitdaten; blockiert nur Produktivschaltung).
- **Offene Punkte:** Regel für fehlendes Ende (Auto-Abschluss oder Kennzeichnung) ist im Pflichtenheft nicht festgelegt; Korrekturen (`time_correction`, TIM-007) und Zeitkonten (TIM-011) sind P2 (AT-48, OP-2).

## Tests

- **Positiv:** Beginn, Pause, Ende erfassen; zwei unterschiedliche Ist-Zeit-Ereignisse zu derselben geplanten Schicht bleiben beide mit Zeit, Quelle und Urheber nachvollziehbar; Arbeitszeit ohne zugeordnete Schicht erfassbar; Nacherfassung durch berechtigte Rolle trägt eigene Quelle und Akteur und steht im `audit_event`.
- **Negativ:** Ende ohne Beginn wird gekennzeichnet statt verworfen; UPDATE/DELETE auf `time_event` scheitert; Mitarbeiter liest Ereignisse einer fremden Person -> 404; fremder Mandant abgewiesen; Nacherfassung ohne berechtigte Rolle -> 403; Nacherfassung überschreibt kein vorhandenes Ereignis.
- **Konkurrierend:** identisches Ereignis (gleiche Ereignis-ID) parallel gesendet ergibt genau eine Zeile; zwei verschiedene Ereignisse parallel zu derselben Schicht ergeben zwei Zeilen.
- **Referenztests:** AT-45 (Soll/Ist-Zeiten, Ermöglichung mit WP-M4-09/11; vom WP-Block M4-08 nicht ausdrücklich genannt, Ablauf Beginn/Pause/Ende entspricht dem Positivtest).
- Testfälle: AT-45; geplant:db/tests/test_time_event.py::test_time_event_append_only_und_idempotent; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventTest.kt::zwei_ist_zeit_ereignisse_zu_einer_schicht_bleiben_nachvollziehbar; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventTest.kt::paralleles_identisches_ereignis_genau_eine_zeile; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventTest.kt::arbeitszeit_ohne_schicht_erfassbar_und_intervall_abzueglich_pausen; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventTest.kt::mitarbeiter_sieht_nur_eigene_ereignisse_fremder_mandant_abgewiesen; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventTest.kt::nacherfassung_eigene_quelle_mit_akteur_im_audit_ohne_ueberschreiben

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung HR-Daten).
