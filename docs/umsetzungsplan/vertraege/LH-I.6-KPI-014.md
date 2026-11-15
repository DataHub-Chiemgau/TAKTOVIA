# LH-I.6-KPI-014 – Live-Dashboard für den laufenden Betrieb

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`) mit Operator-Web
- **Arbeitspaket:** WP-M4-60
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Live-Dashboard zeigt offene Anfragen, verfügbare Kapazität, kritische Verspätungen und Fahrzeugausfälle.

**Originalabnahme (unverändert):** Änderung eines Fahrzeugstatus wirkt sich innerhalb der definierten Aktualisierungsfrist sichtbar aus.

## Vertrag

- **Tabellen / DDL:** Live-Projektion im Modul `analytics` aus Ereignissen zu Versuchen/Angeboten/Holds, Kapazität, Fahrtfortschritt und Fahrzeugstatus (Sperre/Freigabe); jeder Wert trägt `as_of` (Zeitstempel der letzten Aktualisierung). Projektionstabellen mit `tenant_id`, RLS und Dienstberechtigung nach Kap. 04 §4.5. Exakte DDL kommt mit dem Bau.
- **API:** `GET /v1/analytics/metrics` (in Kap. 05 genannt, noch nicht in `api/openapi.yaml`; Live-Werte mit `as_of`); Transport (SSE/WebSocket/Polling) im Bau festzulegen. Vier getrennte Werte: offene Anfragen, verfügbare Kapazität, kritische Verspätungen, Fahrzeugausfälle. Bei unterbrochener Datenzufuhr wird das Datenalter ausgewiesen, nie ein scheinbar aktueller Wert. Nur Daten des aktiven Mandanten und der berechtigten Dienste.
- **Zustandsautomat:** keiner (Fahrzeugstatus liegt in M3, WP-M3-31).
- **JSON-Schema / Ereignisse:** konsumiert Ereignisse aus WP-M2-11/-22/-14 und WP-M3-31/-08/-29.
- **Parameter und Kennzahlen:** `vehicle_unavailability_alert_min` (Fahrzeugausfall), `on_time_pickup_tolerance_min` (Schwelle „kritisch“ laut Detailvertrag); Aktualisierungsfrist als Planungsannahme aus D-05.
- **Oberfläche:** Live-Kacheln im Operator-Web (Kontext UI-07 Operator Livebetrieb bzw. UI-19; Zuordnung in WP-M4-01): vier Kacheln, Datenalter sichtbar, zurückhaltende Live-Region; Tastatur und Screenreader lesbar, Aktualisierung verschiebt weder Fokus noch Bildschirmposition (Kap. 06 §6.1); Zustände leer, veraltet, Fehler, kein Recht.
- **Entscheidungen:** D-05 (SLA/Aktualisierungsziel des Pilots; Planungsannahme, blockiert den Bau nicht).
- **Umsetzung (Planungsannahmen, per D-05 freizugeben):** Abfragekanal Polling `GET /v1/analytics/metrics?breakdown=live` im Abstand `refresh_seconds` = 30 s (Aktualisierungsfrist; `taktovia.analytics.live.refresh-seconds`), Datenstand älter als `stale_after_seconds` = 120 s ist `STALE`. Offene Anfrage = nicht abgelaufenes, ungebuchtes Angebot; verfügbare Kapazität = Sitz- und Rollstuhlplätze nicht gesperrter Fahrzeuge in laufender Schicht (`STARTED`); kritische Verspätung = bestätigte, nicht eingestiegene Buchung, deren zugesagter spätester Abholzeitpunkt plus `on_time_pickup_tolerance_min` überschritten ist (höchstens 12 h zurück); Fahrzeugausfall = Fahrzeug mit offener Sperre, `long_outages` ab `vehicle_unavailability_alert_min`. Datenalter = Alter der jüngsten verarbeiteten Ereigniszeit der speisenden Quelle (ein ruhiger Betrieb ohne Ereignisse erscheint dadurch als veraltet statt als scheinbar aktuell). Keine neue Tabelle: Lesefunktion `live_dashboard` (V40600) über die Faktenbasis (`fact_*`, RLS) und `staff_shift`/`vehicle`/`vehicle_pool_assignment`.
- **Offene Punkte:** WP-Dokument OP 32: Die „definierte Aktualisierungsfrist“ steht weder im Pflichtenheft noch im Parameterkatalog; „kritische Verspätung“, „offene Anfrage“ und „verfügbare Kapazität“ sind nicht definiert; vorläufige Werte sind im Bau als Planungsannahme zu dokumentieren und per D-05 freizugeben. OP 23: Zuordnung der Oberfläche (UI-07/UI-19). Nicht blockierend.

## Tests

- **Positiv:** Statusänderung eines Fahrzeugs (Sperre) wird mit Zeitmessung innerhalb der definierten Aktualisierungsfrist im Dashboard sichtbar; vier Werte erscheinen getrennt.
- **Negativ:** Ereignisstau oder unterbrochene Datenzufuhr zeigt Datenalter statt scheinbar aktuellem Wert; Dienst ohne Berechtigung erscheint nicht (fremder Dienst); fremder Mandant erscheint nicht; Aktualisierung verschiebt Fokus nicht (Barrierefreiheitsprüfung).
- **Konkurrierend (Datentrennung):** zwei Mandanten mit gleichzeitigen Statusänderungen sehen jeweils nur eigene Werte.
- **Referenztests:** keine Katalog-Testfälle im WP-Dokument genannt.
- Testfälle: backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/LiveDashboardTest.kt::fahrzeugstatus_innerhalb_aktualisierungsfrist_sichtbar; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/LiveDashboardTest.kt::zwei_mandanten_gleichzeitige_statusaenderungen_ohne_vermischung; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/LiveDashboardTest.kt::ereignisstau_zeigt_datenalter_statt_scheinbar_aktuellem_wert; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/LiveDashboardTest.kt::fremder_dienst_und_fremder_mandant_erscheinen_nicht; backend/app/src/test/kotlin/de/taktovia/app/analytics/LiveDashboardApiTest.kt::vier_getrennte_werte_mit_datenstand_und_sperre_sichtbar; backend/app/src/test/kotlin/de/taktovia/app/analytics/LiveDashboardApiTest.kt::dienstbeschraenkter_benutzer_fremder_mandant_und_ungueltige_parameter; web/operator/src/kennzahlen/LiveDashboard.test.tsx::aktualisiert_im_abfrageintervall_ohne_fokus_oder_elementwechsel_und_meldet_die_aenderung_hoeflich

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
