# LH-6.22-DMG-009 – Schadenliste der Leitstelle mit Filtern

- **Verantwortlicher:** Rolle Fleet-Safety (Modul `fleet-safety`), Oberfläche Rolle Web/Operator
- **Arbeitspaket:** WP-M3-49
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Operator-Oberfläche bietet eine Schadenliste mit Filtern nach Fahrzeug, Priorität, Status, Zeitraum, Depot und Verantwortlichem.

**Originalabnahme (unverändert):** Testfälle sind eindeutig auffindbar und sortierbar.

## Vertrag

- **Tabellen / DDL:** `defect_case` (aus WP-M3-43; im Kernschema V1/V2 noch nicht vorhanden, Kap. 04 §4.4 nennt die Tabelle für DMG; Primärschlüssel `(tenant_id,id)`, RLS `tenant_isolation`). Laut WP-M3-45 trägt `defect_case` Klasse, Regelversion, Klassifikationshistorie und Zuständigen. Neue Indizes laut WP-M3-49: `(tenant_id,state,updated_at)` sowie Filterindizes auf Fahrzeug, Status und Zeitraum (Meldezeitpunkt); Spaltennamen (z. B. `vehicle_id`, `reported_at`) sind Arbeitsnamen und werden in WP-M3-43 festgelegt. Sortierung enthält immer den Tiebreaker `id` (Kap. 05 §5.1 Listen: stabiler Sortierschlüssel `(created_at,id)`; bei Sortierung nach angezeigter Spalte gilt `(<Spalte>,id)`). Die Listenprojektion liest ausschließlich nichtvertrauliche Spalten (Trennung nach WP-M3-50 / LH-6.22-DMG-011).
- **API:** `GET /v1/defects` (Kap. 05 §5.7, Schäden/Werkstatt) mit Query-Filtern für die sechs Kriterien: Fahrzeug, Priorität, Status, Zeitraum (`from`/`to`), Depot, Verantwortlicher; dazu `sort`, `limit` (1-100, Default 50 laut Kap. 05 Listenregel), opaker signierter `cursor`. Antwort `{items,next_cursor,as_of}`; Cursor enthält Sortierschlüssel plus `id`. Fehler als `application/problem+json` mit stabilem `code` und `correlation_id`. Filter dürfen die Autorisierung nur weiter einschränken (Kap. 05): Akten außerhalb von Rolle/Dienst/Depot-Berechtigung erscheinen nie, auch nicht bei passendem Filter (Port `canRead`, Vorbild LH-6.2-IAM-005); fehlender Funktionsscope 403; nicht sichtbare Einzelakte/fremder Mandant 404. Ungültiger Filterwert oder `limit` außerhalb 1-100: Abweisung mit 422 (Arbeitsannahme); manipulierter/abgelaufener Cursor: Abweisung als 4xx-Problem-Antwort, Statuscode im Pflichtenheft nicht festgelegt. Fehlercodenamen nicht festgelegt.
- **Zustandsautomat:** keiner (Status kommt aus WP-M3-47; die Liste filtert nur nach dem vorhandenen Status).
- **JSON-Schema / Ereignisse:** Filterschema der Anfrage als OpenAPI-Parameter von `/v1/defects` (Datei unter `api/`, `api/operations.yaml` enthält bisher nur `/v1/operations/{operation_id}`; Zielort mit dem Bau); keine Ereignisse.
- **Parameter und Kennzahlen:** keine (`Werkstattquote` ist ausdrücklich nicht im Umfang, M4/M7).
- **Oberfläche:** UI-11 Flotte/Werkstatt (Kap. 06 §6.9): Schadenliste mit Filterleiste (sechs Filter), sortierbaren Spalten, Leerzustand, Ladefehler mit Datenstand (`as_of`), Zustand „keine Berechtigung“ (vertrauliche Inhalte erscheinen nicht, statt ausgegraut zu sein). Konkrete Element-IDs sind im Pflichtenheft nicht festgelegt und werden mit dem Bau vergeben.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-22 (WP-M3-Dokument): Herkunft von „Depot“ (Betriebshof aus WP-M1-09; die Zuordnung zum Fahrzeug fehlt am `vehicle`, das nur `owner_org_ref` führt) und Form des Ereignisorts. Außerdem nicht festgelegt: „Priorität“ (Ableitung aus der Klasse WP-M3-45 oder eigenes Feld; WP nennt beide Varianten) und „Verantwortlicher“ (Arbeitsannahme: Zuständiger aus der Triage WP-M3-45; Zuweisungsprozess DMG-014 ist P2). Blockiert den Bau des Depot-Filters bis zur Festlegung (Zuordnung Fahrzeug-Betriebshof); die Filter Fahrzeug, Status, Zeitraum, Priorität (Arbeitsannahme Klasse) und Verantwortlicher (Arbeitsannahme Zuständiger) sind baubar.

## Tests

- **Positiv:** Je Filter ein Fall (Fahrzeug, Priorität, Status, Zeitraum, Depot, Verantwortlicher) findet genau die erwartete Akte (Abnahme „eindeutig auffindbar“); Filterkombination; Sortierung nach jeder angezeigten Spalte auf- und absteigend (Abnahme „sortierbar“) mit stabiler Reihenfolge bei gleichen Werten; Default `limit` 50; Listenindizes vorhanden.
- **Negativ:** Fremder Mandant: keine Akten sichtbar, Einzelakte 404; nicht berechtigtes Depot/Dienst: Akten erscheinen nicht; ungültiger Filterwert → 422; `limit` > 100 → 422; manipulierter Cursor wird abgewiesen; Aufruf ohne Funktionsscope → 403; vertrauliche Inhalte fehlen in der Listenprojektion.
- **Konkurrierend:** Parallele Abrufe zweier Mandanten über wiederverwendete Poolverbindungen liefern nur eigene Akten; eine Akte wechselt während der Paginierung den Status oder `updated_at` – keine Akte erscheint doppelt oder fehlt (stabile Sortierung mit Tiebreaker `id`).
- **Referenztests:** Das WP nennt keine Katalog-ID für diese Anforderung; Testfälle aus dem WP: je Filter, Negativ- und Konkurrenztests.
- Testfälle: geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectListTest.kt::filter_je_kriterium_findet_eindeutig; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectListTest.kt::sortierung_je_spalte_stabil_mit_tiebreaker; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectListTest.kt::sortierung_stabil_bei_statuswechsel_waehrend_paginierung; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectListTest.kt::ungueltiger_filter_limit_cursor_abgewiesen; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectListTest.kt::fremder_mandant_und_nicht_berechtigtes_depot_nicht_sichtbar; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectListTest.kt::fremder_mandant_poolverbindung_isoliert; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectListTest.kt::projektion_ohne_vertrauliche_inhalte

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
