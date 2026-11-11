# LH-I.7-CFG-006 – Validierung, Vier-Augen-Freigabe, Publikation und Rollback

- **Verantwortlicher:** Rolle Plattform (Modul `config`)
- **Arbeitspaket:** WP-M1-22
- **Status:** Bereit
- **Phase / Priorität:** P1 / MUSS

## Originaltext (unverändert)

> Bediengebiete, Betriebszeiten, maximale Wartezeiten und Haltestellenschema sind durch qualifizierte Betreiber administrierbar.

**Originalabnahme:** Eine konfigurationsändernde Testaktion wirkt erst nach erfolgreicher Validierung.

## Vertrag

- **Tabellen / DDL:** `config_revision` (V1) mit Ergänzungen aus WP-M1-20 (Statusspalte, Änderungsgrund); CHECK `published_at IS NULL OR approved_by IS NOT NULL` ergänzt um `approved_by <> created_by`; Invariante Kap. 04 §4.5 „Nicht überlappende aktive Policy-/Dienstversion" per Scope-Sperre, Intervallprüfung und ggf. Exclusion Constraint; Publikation schließt `effective_to` der Vorgängerrevision. Publizierte Zeilen unveränderlich.
- **API:** `POST /v1/config/revisions/{id}/validate`; Freigabeoperation durch zweite Person und Rollback als neue Revision (Pfade im Detailvertrag beim Bau, OP-19; `api/openapi.yaml` enthält bisher nur `publishConfig`); `POST /v1/config/revisions/{revision_id}/publish` (`publishConfig`, Scope `config:publish`, `Idempotency-Key`, `If-Match`; `PublishRequest{effective_from,reason,impact_report_id}` → `PublishedRevision` mit `audit_event_id`). Ohne `If-Match` 428, veralteter Stand 412, gleicher `Idempotency-Key` liefert dasselbe Ergebnis; ohne Validierung/Freigabe/Wirkungsbericht abgewiesen.
- **Zustandsautomat:** neu `zustaende/config-revision.md`: Entwurf, validiert, freigegeben, publiziert, abgelöst; Rollback erzeugt neue Revision mit früheren Werten.
- **JSON-Schema / Ereignisse:** `config.revision_published`, `config.revision_rolled_back` (Outbox, `api/events/`); Audit je Schritt (Validierung, Freigabe, Publikation, Rollback) mit Akteur, Zeitpunkt, Grund über WP-M1-46.
- **Parameter und Kennzahlen:** Betriebszeiten, `max_wait_min`, `min_booking_lead_min`, Haltestellenschema-Einstellungen des Dienstes; Bediengebiet als Konfigurationsbezug.
- **Oberfläche:** UI-17 Basis „Revision freigeben": Validator-Ergebnis, Prüfer, Wirksamkeitsdatum; Zustände „konkurrierende Version", unzulässiger Sicherheitswert blockiert Veröffentlichung, 412-Konflikt; per Tastatur und Screenreader bedienbar.
- **Entscheidungen:** keine. Rolle „qualifizierter Betreiber" (Recht `config:publish`) in der Rechtematrix festzulegen; Vier-Augen-Baustein „Prüfer ≠ Ersteller" ist wiederverwendbar (M3, M4).
- **Offene Punkte:** OP-19 (ob `effective_from` in der Vergangenheit zulässig ist, Freigabe-/Rollback-Endpunkte, Pfadparameter `{id}` vs. `{revision_id}`); OP-18; OP-01 (Nachweis für Bediengebiete und Haltestellen als administrierte Objekte sowie an echten Angeboten/Buchungen erst mit M2: WP-M2-04, -05, -09, -21, -23). Regel zu `effective_from` in der Vergangenheit im Detailvertrag vor Bau zu fixieren; kein Blocker für die Mechanik. Zusätzlich offen (Widerspruch im WP): publizierte Zeilen sind unveränderlich, die Publikation setzt aber `effective_to` der Vorgängerrevision (Runtime-Rolle hat UPDATE auf `config_revision`, `R__runtime_grants.sql`); welche Spalten der Unveränderlichkeits-Trigger nach Publikation noch zulässt (`effective_to`, Status „abgelöst"), ist im Detailvertrag vor Bau festzulegen.

## Tests

- **Positiv:** Entwurf, validieren, freigeben, publizieren, wirksam ab `effective_from`; Sonntag publizierte Änderung von `min_booking_lead_min` ab Montag lässt Sonntagswerte unverändert; Rollback erzeugt neue Revision, fehlerhafte bleibt lesbar, Testobjekt mit gebundener Revision zeigt Ursprungsversion; Audit enthält alle Schritte.
- **Negativ:** Publikation ohne Validierung, ohne Freigabe, mit Selbstfreigabe, mit ungültigem Wert, ohne Wirkungsbericht, ohne Recht `config:publish`, fremder Scope; ohne `If-Match` 428, veraltet 412; unzulässiger Sicherheitswert blockiert Veröffentlichung in API und Oberfläche.
- **Konkurrierend:** zwei Publikationen desselben Scopes mit überlappendem Intervall in parallelen Transaktionen – genau eine erfolgreich (Pflichttest zu §4.5); paralleler Publish und Rollback; 100 parallele Publish-Wiederholungen mit gleichem `Idempotency-Key` – eine Wirkung; Auflösung genau zum Zeitpunkt `effective_from` liefert trotz Cache nie den abgelösten Wert.
- **Referenztests:** E2E-096, E2E-097 (Publikationsteil), E2E-098, PH-AT-018 (Konfigurationsteil), AT-24 über PH-AT-011-Idempotenz.

Testfälle: E2E-096; E2E-097; E2E-098; PH-AT-018; geplant:backend/config/src/test/kotlin/de/taktovia/config/ConfigPublicationTest.kt::aenderung_wirkt_erst_nach_validierung_freigabe_und_publikation; geplant:web/operator/src/config/RevisionFreigabe.test.tsx::ui17_blockiert_unzulaessigen_sicherheitswert_und_zeigt_412_konflikt

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
