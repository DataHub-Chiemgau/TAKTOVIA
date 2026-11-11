# LH-6.17-INT-004 – Idempotenzregister und Kollisionskontrolle

- **Verantwortlicher:** Rolle Plattform (Modul `platform`)
- **Arbeitspaket:** WP-M1-13
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Schreibende Buchungs- und Zahlungsvorgänge unterstützen Idempotenz und Kollisionskontrolle.

**Originalabnahme (unverändert):** Gleichzeitiger Retransmit erzeugt kein Duplikat.

## Vertrag

- **Tabellen / DDL:** `idempotency_record` (V1): PK `(tenant_id,principal_ref,command_scope,key)`, `request_sha256 char(64)`, `operation_id` (nullable, FK `(tenant_id,operation_id)` -> `operation`; `operation.journey_id` ist im V1-DDL NOT NULL mit FK auf `journey`, für generische M1-Operationen muss das angepasst werden, siehe OP-15 und WP-M1-15), `response_json` (nullable), `expires_at`; RLS wie alle Mandantentabellen (Besitzer `platform`, `db/table_owners.csv`). `command_scope` entspricht dem `operation_name` des API-Scopes; `principal_ref` dem `auth_principal`. Bereinigungsjob nach `expires_at`, der keinen laufenden Befehl löscht. Invariante §4.5 "Bestätigung/PSP-Retry exakt gleiche Operation" (Registerteil). `outbox` (UNIQUE `(tenant_id,aggregate_id,aggregate_revision,event_type)`) stellt genau einen Outbox-Eintrag je Wirkung sicher.
- **API:** Header `Idempotency-Key` (Parameter `IdempotencyKey`: 16-128 Zeichen, Muster `^[A-Za-z0-9._:-]+$`, `required: true`) an allen schreibenden Endpunkten. Filter-Scope `(auth_principal, tenant_context, operation_name, Idempotency-Key)`; kanonischer Request-Fingerprint inkl. Ressourcen-ID und fachlich relevanter Header; Speicherung von Operations-ID und HTTP-Ergebnis; Prüfung nach Authentisierung, vor erneutem ETag-/Ablauftest (Kap. 05 §5.1). Gleicher Schlüssel, anderer Fingerprint -> `409 IDEMPOTENCY_CONFLICT`; laufende Erstverarbeitung -> `409 OPERATION_IN_PROGRESS`; Wiederholung liefert dieselbe Ressourcen-/Operations-ID und HTTP-Ergebnis; Authentisierung und Leseberechtigung werden bei Wiederholung erneut geprüft. Ein angenommenes Kommando erscheint bei Wiederholung nicht nachträglich als abgelaufen.
- **Kollisionskontrolle (zweiter Teil der Originalaussage):** zwei verschiedene Schlüssel auf derselben Ressourcenrevision verhindern `If-Match`/ETag (412 `REVISION_MISMATCH`, WP-M1-02) bzw. fachliche Unique-Constraints (M2/M4, Kap. 05 §5.1); das Register ersetzt sie nicht. In M1 nachgewiesen am `publishConfig`-Befehl (`/v1/config/revisions/{revision_id}/publish`, WP-M1-22).
- **Zustandsautomat / Schema / Ereignisse:** keine eigenen.
- **Parameter und Kennzahlen:** Response-Cachehaltung 72 h (Entwurfswert des Pflichtenhefts, als Planungsdefault gekennzeichnet).
- **Oberfläche:** keine.
- **Entscheidungen:** keine.
- **Offene Punkte:** Kanonisierungsregel des Fingerprints und Liste "fachlich relevanter Header" als Detailvertrag vorab; OP-17 (Umfang der Webhook-Grundlage, Bezug AT-24 in P1); OP-01 – Buchungs-/Zahlungsbefehle entstehen erst in M2 (Vollabnahme mit WP-M2-24, WP-M2-30); in M1 Nachweis an einem Testbefehl und am `publishConfig`-Befehl. Blockiert den Bau nicht. Fachliche Unique-Schlüssel und PSP-Idempotenz sind nicht Teil (M2/M4). OP-15 (Operation an `journey` gebunden; für den M1-Testbefehl nötige DDL-Anpassung liegt bei WP-M1-15, Register-Bau setzt sie voraus oder nutzt bis dahin einen Testaggregat-Journey-Datensatz).

## Tests

- **Positiv:** Wiederholung nach Timeout oder Verbindungsabbruch liefert das gespeicherte Ergebnis mit derselben Operations-ID, ohne zweite Wirkung; Wiederholung nach Abbruch zwischen Commit und Antwort liefert das gespeicherte Ergebnis; neuer Schlüssel für neuen Vorgang wird neu ausgeführt.
- **Negativ:** gleicher Schlüssel mit anderem Body, anderem Pfad oder anderem Akteur -> `409 IDEMPOTENCY_CONFLICT` bzw. kein Zugriff auf fremdes Ergebnis, keine zweite Ausführung; Schlüssel kollidieren nicht zwischen Mandanten, Akteuren und Operationstypen; zu kurzer Schlüssel (<16), zu langer (>128), Zeichen außerhalb des Musters oder fehlender Header an schreibendem Endpunkt -> abgewiesen als Feldfehler (Status/Code im Fehlercode-Katalog aus WP-M1-02 festzulegen);  Wiederholung ohne gültige Authentisierung wird abgewiesen; Ablauf des Antwortcaches (`expires_at`, 72 h Planungsdefault) erlaubt keine zweite fachliche Wirkung, soweit ein fachlicher Unique-Schlüssel existiert; Fremdmandant sieht keinen Eintrag (RLS), Wiederholung mit Schlüssel eines anderen Mandanten liefert kein fremdes Ergebnis; Wiederholung nach Rechteentzug wird abgewiesen (Leseberechtigung wird erneut geprüft).
- **Konkurrierend (Geld/Kapazität):** zwei gleichzeitige identische Requests und 100 parallele identische Requests erzeugen genau ein Ergebnisobjekt und genau einen Outbox-Eintrag; paralleler Retransmit während laufender Erstverarbeitung -> `OPERATION_IN_PROGRESS` bzw. dasselbe Ergebnis; Bereinigung entfernt keinen laufenden Eintrag; zwei verschiedene Schlüssel mit gleichem `If-Match` am `publishConfig`-Befehl: genau einer gewinnt, der andere erhält 412, kein Duplikat und keine zweite Wirkung; gleicher Schlüssel von zwei Mandanten/Akteuren parallel bleibt getrennt.
- **Referenztests:** PH-AT-011 (am Testbefehl, am `publishConfig`-Befehl mit WP-M1-22), AT-24.
- Testfälle: PH-AT-011; AT-24; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/idempotency/IdempotencyFilterTest.kt::wiederholung_nach_timeout_liefert_gespeichertes_ergebnis; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/idempotency/IdempotencyFilterTest.kt::hundert_parallele_identische_requests_eine_wirkung; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/idempotency/IdempotencyFilterTest.kt::gleicher_schluessel_anderer_fingerprint_409; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/idempotency/IdempotencyFilterTest.kt::retransmit_waehrend_erstverarbeitung_operation_in_progress; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/idempotency/IdempotencyFilterTest.kt::schluessel_isoliert_nach_mandant_akteur_operation; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/idempotency/IdempotencyFilterTest.kt::wiederholung_ohne_authentisierung_abgewiesen; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/idempotency/IdempotencyCleanupTest.kt::bereinigung_entfernt_keinen_laufenden_eintrag; geplant:backend/app/src/test/kotlin/de/taktovia/app/PublishConfigIdempotencyTest.kt::zwei_schluessel_gleiche_revision_genau_einer_gewinnt; db/tests/test_04_trigger.py::test_idempotenzschluessel_eindeutig_je_mandant_akteur_scope

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Geld, Kapazität und Konkurrenz).
