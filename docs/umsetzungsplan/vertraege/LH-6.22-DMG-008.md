# LH-6.22-DMG-008 – Unveränderliche Historie von Meldungen und Anhängen

- **Verantwortlicher:** Rolle Fleet/Safety (Modul `fleet-safety`)
- **Arbeitspaket:** WP-M3-48
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Meldungen und Anhänge werden unveränderlich historisiert; Nachträge erhalten Autor, Zeit, Änderungsgrund und Version.

**Originalabnahme (unverändert):** Vorherige Inhalte bleiben im Audit für berechtigte Prüfer lesbar.

## Vertrag

- **Tabellen / DDL:** Revisionstabelle zu `defect_case` (Arbeitsname `defect_case_revision`: `tenant_id`, `defect_id`, `version` fortlaufend, Inhaltssnapshot der Meldung, `author`, `created_at`, `change_reason NOT NULL`; `UNIQUE(tenant_id, defect_id, version)`), `defect_evidence` unveränderlich; ein „ersetzter" Anhang bleibt als frühere Version erhalten (Folgeversion verweist auf Vorgänger). Trigger gegen UPDATE und DELETE auf Revisions- und Evidenzzeilen (Muster der Unveränderlichkeit aus WP-M3-02 / V2), Tests auf Datenbankebene. Löschen nur über kontrollierten Lifecycleprozess (WP-M1-33), nicht per DELETE. Nachtrag und Lesezugriff auf die Historie erzeugen zusätzlich Einträge in `audit_event`, damit vorherige Inhalte „im Audit“ für berechtigte Prüfer lesbar bleiben (Originalabnahme); Abgrenzung fachliche Historie ↔ `audit_event` (append-only, WP-M1-12) ist Detailvertrag vorab. RLS wie V1.
- **API:** Nachtrag und Historie unterhalb `/v1/defects/{id}` (Pfade im Detailvertrag festzulegen, Arbeitsnamen `POST /v1/defects/{id}/amendments`, `GET /v1/defects/{id}/history`, Leseansicht „Stand zum Zeitpunkt t"). Nachtrag ohne Änderungsgrund 422; Historie ohne Prüferrolle 403/404; fremder Mandant 404.
- **Zustandsautomat:** keine.
- **JSON-Schema / Ereignisse:** Ereignis „Schaden nachgetragen" über Outbox (Arbeitsname).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-11 Versionsverlauf mit Vergleich der Versionen; ohne Prüferrolle nicht sichtbar.
- **Entscheidungen:** D-11 (Aufbewahrung), blockiert nur die Produktivschaltung.
- **Offene Punkte:** Prüferrolle („berechtigte Prüfer") nicht abgegrenzt (OP-34); DDL der Revisionen und Verhältnis zu Löschfristen sind Detailverträge vorab (D-11); Export der Akte in M5 (AT-53) nicht im Umfang. Kein Bau-Blocker.

## Tests

- **Positiv:** zwei Nachträge erzeugen Versionen 2 und 3 mit Autor, Zeit und Grund; Version 1 bleibt für Prüfer lesbar (Stand zum Zeitpunkt t); ersetzter Anhang bleibt als frühere Version erhalten.
- **Positiv (Audit):** nach einem Nachtrag steht der Vorgängerinhalt (Version 1) weiterhin im Audit/Historie lesbar; Nachtrag erzeugt Eintrag in `audit_event` und Outbox-Ereignis.
- **Negativ:** direktes UPDATE/DELETE auf Meldungs-, Revisions- und Evidenzzeilen scheitert auf Datenbankebene; Nachtrag ohne Grund abgewiesen; Lesen der Historie ohne Prüferrolle abgewiesen; Akte anderen Mandanten 404.
- **Konkurrierend:** zwei gleichzeitige Nachträge ergeben fortlaufende Versionen ohne Verlust und ohne doppelte Versionsnummer.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:db/tests/test_defect_history.py::test_update_delete_auf_revision_und_evidenz_scheitert; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectAmendmentTest.kt::nachtrag_ohne_grund_wird_abgewiesen; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectAmendmentTest.kt::version_eins_bleibt_fuer_pruefer_lesbar; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectAmendmentTest.kt::gleichzeitige_nachtraege_fortlaufende_versionen; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectAmendmentTest.kt::ersetzter_anhang_bleibt_als_fruehere_version; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectAmendmentTest.kt::historie_ohne_pruferrolle_abgewiesen_fremder_mandant_404; geplant:db/tests/test_defect_history.py::test_version_unique_je_akte

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
