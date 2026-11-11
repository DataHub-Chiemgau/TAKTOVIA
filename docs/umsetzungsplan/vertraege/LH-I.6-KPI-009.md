# LH-I.6-KPI-009 – Nicht durchgeführte Fahrten: Ursache und Verursachungskategorie

- **Verantwortlicher:** Rolle Analytik (Modul `analytics`)
- **Arbeitspaket:** WP-M4-55
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jede tatsächlich nicht durchgeführte Fahrt erhält Ursache und Verursachungskategorie mit Korrekturvermerk.

**Originalabnahme (unverändert):** Fahrzeugausfall und Fahrgast-No-Show werden getrennt erfasst.

## Vertrag

- **Tabellen / DDL:** Ursachenkatalog (Ursache, Verursachungskategorie, gültig von/bis) und Fakt "Fahrt nicht durchgeführt" mit genau einer aktuellen Ursache je bestätigter, tatsächlich nicht durchgeführter Fahrt; Korrekturvermerke append-only mit Akteur, Zeit, Grund, altem und neuem Wert (Update/Delete per Trigger und Recht gesperrt). Fehlende Ursache wird als Zustand "Ursache offen" geführt und bleibt in der Auswertung sichtbar. Alle Faktentabellen im Modul `analytics` tragen `tenant_id` mit RLS wie in `db/migrations/V1__kernschema.sql`; kein Schreibzugriff auf Tabellen anderer Module (Architekturtest); keine Fahrgast-Klartextdaten in Fakten.
- **API:** `GET /v1/analytics/metrics` (Auswertung nicht durchgeführter Fahrten nach Ursache und Kategorie mit Definitionsversion, Filter, Datenstand, Vollständigkeit, Nenner; WP-M4-46); `api/analytics.yaml` existiert noch nicht und ist mit dem Bau anzulegen. Korrekturbefehl für berechtigte Rollen (Unterpfad Vorschlag des Plans, im Bau festzulegen); ohne Recht 403, ohne Grund 422.
- **Zustandsautomat:** keiner; Zustand der Ursache: offen -> gesetzt -> korrigiert (Vermerke bleiben).
- **JSON-Schema / Ereignisse:** Ableitung aus Storno- und Störungsereignissen (M2: Storno mit Grund, Stornoursache Fahrzeugausfall; M3: No-Show, Störungsablauf).
- **Parameter und Kennzahlen:** Ursachen (Arbeitsvorschlag): Fahrzeugausfall, Fahrgast-No-Show, Storno; Verursachungskategorien (Vorschlag): Betreiber, Fahrgast, extern. Fahrzeugausfall und Fahrgast-No-Show haben verschiedene Ursache und Kategorie. Kennzahlen `No-Show-Rate` (Nenner: bestätigte Abholungen mit geplanter Leistung) und `Buchungsstornoquote Betreiber` (Fahrzeugausfall getrennt). Keine automatische Schuldzuweisung.
- **Oberfläche:** Ansicht nicht durchgeführter Fahrten mit Ursache, Kategorie, Korrekturhistorie (UI-19 bzw. offen, OP 23); Zustände: Ursache offen, Korrektur ohne Recht, leer.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP 30: Katalog der Ursachen und Verursachungskategorien sowie die zur Korrektur berechtigten Rollen sind nicht definiert; Arbeitsvorschlag oben, Rollen aus `db/role_matrix.csv` zu wählen. Die Korrekturfunktion ist bis zur Rollenfestlegung nicht freigabefähig; Erfassung und Auswertung sind baubar.

## Tests

- **Positiv:** Fahrzeugausfall und Fahrgast-No-Show werden als verschiedene Ursachen mit verschiedener Verursachungskategorie erfasst; jede nicht durchgeführte bestätigte Fahrt hat genau eine Ursache; Korrektur der Ursache erhält den alten Wert als Vermerk mit Akteur und Zeit; `No-Show-Rate` und `Buchungsstornoquote Betreiber` (Fahrzeugausfall getrennt) rechnen mit dem im Kennzahlregister definierten Nenner; Auswertung nach Ursache und Kategorie ist über `/v1/analytics/metrics` abrufbar.
- **Negativ:** Korrektur ohne Recht wird abgewiesen; Korrektur ohne Grund wird abgewiesen; Fahrt ohne Ursache erscheint als "Ursache offen" und wird nicht stillschweigend ausgeschlossen; Update oder Löschen eines Korrekturvermerks per API und SQL unter der Runtime-Rolle scheitert; Fahrt eines fremden Mandanten nicht korrigierbar; die Auswertung enthält keine automatische Schuldzuweisung an Einzelpersonen (keine Fahrerzuordnung in der Kategorie).
- **Konkurrierend:** Zwei parallele Korrekturen derselben Fahrt: beide Vermerke bleiben, letzter Stand ist eindeutig (Versionsprüfung); gleichzeitiger Eingang von Fahrzeugausfall- und No-Show-Ereignis ergibt genau eine Ursache nach festgelegter Rangfolge bzw. Konfliktmarkierung (Rangfolge ist im WP nicht festgelegt, Teil von OP 30; bis dahin Konfliktmarkierung statt stiller Wahl).
- **Referenztests:** Keine Katalog-IDs im WP-Dokument genannt.
- Testfälle: geplant:db/tests/test_trip_not_performed.py::test_korrekturvermerk_unveraenderlich_und_ursache_offen_sichtbar; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/NotPerformedCauseTest.kt::parallele_korrekturen_behalten_beide_vermerke

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
