# LH-6.23-SCH-007 – Fahrerschicht, Fahrzeugbereitstellung und Disponierbarkeit

- **Verantwortlicher:** Rolle Personal/Zeit (Modul `staff-time`), Verbraucher Disposition/Flotte
- **Arbeitspaket:** WP-M4-17
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrereinsatz, Fahrzeugbereitstellung und geplanter Betrieb müssen verknüpft sein; ein nicht besetzter Fahrerdienst erzeugt kein disponierbares Fahrzeug.

**Originalabnahme (unverändert):** Eine offene Fahrerschicht führt nicht zu einem fälschlich verfügbaren Fahrzeug.

## Vertrag

- **Tabellen / DDL:** `staff_shift` (`driver_ref`, `vehicle_id`, Dienstbezug, `roster_revision`, `state`; V1 hat `driver_ref`/`vehicle_id NOT NULL`, Anpassung mit WP-M4-03, OP-8); `resource_release` (V1: `vehicle_id`, `shift_id`, `safety_revision`, `inspection_ref`, `qualification_revision`, `valid_until`, `revoked_at`; FK `(tenant_id,shift_id)` -> `staff_shift`). Invariante „Einsatz nur mit gültiger Schicht, Qualifikation, Check und ohne Sperre“ (Kap. 04 §4.3/§4.5). RLS über `tenant_id` (V1).
- **API / Port:** Leseport „Schichtbesetzung“ des Moduls `staff-time`: je Fahrzeug und Zeitraum besetzt/unbesetzt mit Schicht- und Qualifikationsrevision. Besetzt ist ein Fahrzeug nur, wenn eine veröffentlichte (nicht Entwurf) Schicht mit zugeordnetem Fahrer den Zeitraum abdeckt; offene Fahrerschicht, Entwurfsschicht und Schicht außerhalb des Zeitraums ergeben unbesetzt. Besetzt ersetzt keine technische Freigabe (`resource_release`). Verknüpfung: Port liefert je Schicht die Bezüge Fahrer (`driver_ref`), Fahrzeug (`vehicle_id`) und geplanter Betrieb (Dienst); die Spalte für den Dienstbezug ist mit WP-M4-03 festzulegen (OP-10).
- **Zustandsautomat:** Schicht offen / besetzt, Entwurf / veröffentlicht (siehe `vertraege/zustaende/dienstplan.md`, WP-M4-01).
- **JSON-Schema / Ereignisse:** Outbox „Schichtbesetzung geändert“ (für Kandidatenfilter und Ablehnungsgrund „kein Fahrer“). Zusage an M1: `ImpactProvider` des Moduls `staff-time` für die Wirkungsanalyse (WP-M1-23): betroffene veröffentlichte Schichten als Objektverweise und Anzahl, ohne Personendaten, nur aktiver Mandant, ohne Entwurfsschichten.
- **Parameter und Kennzahlen:** `min_rest_hours`, `shift_publish_notice_hours` als mögliche impact-pflichtige Parameter (OP-43 klärt die Liste).
- **Oberfläche:** keine eigene; Anzeige in Disposition über WP-M4-65.
- **Entscheidungen:** keine.
- **Offene Punkte:** Eigentum an `resource_release` und Verknüpfungsmechanik Schicht <-> Dienst (Modul Staff/Time vs. Fleet/Safety, OP-10); Verhalten bei Wegfall der Besetzung nach Veröffentlichung (im Portvertrag zu klären); impact-pflichtige Parameter (OP-43). Vollabnahme erst mit WP-M4-65 (Anbindung Kandidatenfilter und Hold: E2E-081 und Hold-Konkurrenztest dort); in diesem WP nachgewiesen sind Port und `resource_release`. Blockiert den Bau nicht.

## Tests

- **Positiv:** Fahrereinsatz, Fahrzeug und geplanter Betrieb sind je Schicht über den Port verknüpft abrufbar (Originaltext); besetzte, veröffentlichte Schicht -> Port meldet Fahrzeug im Schichtzeitraum als besetzt (Personalvoraussetzung erfüllt); `ImpactProvider` meldet für eine Testrevision (z. B. geändertes `min_rest_hours`) die betroffenen veröffentlichten Schichten des aktiven Mandanten.
- **Negativ:** offene Fahrerschicht -> Port meldet unbesetzt, Fahrzeug nicht als verfügbar (Originalabnahme); Entwurfsschicht -> unbesetzt; Schicht außerhalb des Zeitraums -> unbesetzt; Anfrage bei freiem Fahrzeug ohne einsatzbereiten Fahrer -> kein Angebot, Grund Personalmangel (E2E-081, nachzuweisen mit WP-M4-65); `ImpactProvider` enthält weder Entwurfsschichten noch fremde Mandanten noch Personendaten; Portabfrage für ein Fahrzeug eines fremden Mandanten liefert nichts (RLS); Port erteilt keine `resource_release`.
- **Konkurrierend:** Besetzung wird entfernt, während ein Hold läuft: der Hold prüft die Schichtrevision erneut und scheitert (Nachweis mit WP-M4-65); Zuweisung der Person parallel zur Portabfrage liefert nie einen Zwischenstand.
- **Referenztests:** E2E-081 (Ermöglichung, Vollabnahme WP-M4-65).
- Testfälle: E2E-081; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStaffingPortTest.kt::offene_fahrerschicht_ergibt_fahrzeug_unbesetzt; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStaffingPortTest.kt::entwurfsschicht_macht_fahrzeug_nicht_besetzt; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStaffingPortTest.kt::besetzung_entfaellt_waehrend_hold; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStaffingPortTest.kt::schicht_fahrzeug_betrieb_verknuepft_abrufbar; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStaffingImpactProviderTest.kt::nur_veroeffentlichte_schichten_aktiver_mandant_ohne_personendaten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Kapazität/Sicherheit).
