# LH-I.1-DOC-013 – Offline-Belegerfassung mit einmaliger Synchronisation

- **Verantwortlicher:** Rolle Finanzen/Belege (Modul `documents-finance`) mit Fahrer-App
- **Arbeitspaket:** WP-M4-26
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Quittungen können ohne Netzwerkverbindung lokal zwischengespeichert und später synchronisiert werden.

**Originalabnahme (unverändert):** Ein offline erfasster Beleg erscheint nach Synchronisation genau einmal mit unverändertem Original.

## Vertrag

- **Tabellen / DDL:** `expense_document` (V1): `original_sha256`, `original_received_at` (Eingangszeit am Server). Neu per Migration: Spalte `client_uuid uuid NOT NULL` mit Unique `(tenant_id, client_uuid)` und Spalte für die Erfassungszeit am Gerät (`captured_at timestamptz`, getrennt von `original_received_at`). Der Server berechnet den Hash serverseitig und vergleicht mit dem lokal berechneten; zwei Hash-Fälle sind getrennt: (a) die hochgeladenen Bytes ergeben serverseitig einen anderen Hash als den im Uploadticket deklarierten (Übertragungsfehler): Upload abgewiesen, kein Beleg angelegt, Wiederholung möglich (Problem-Code mit dem Bau); (b) die Client-UUID ist bereits mit anderem `original_sha256` vorhanden: 409 ohne Überschreiben. Ein abweichender Hash wird nie still übernommen. Der Beleg durchläuft denselben Annahme-, Quarantäne- und Integritätsablauf wie der Online-Weg (WP-M4-23/24/25). RLS mandantengetrennt; Original bleibt unverändert (kein UPDATE auf `object_key`/`original_sha256`).
- **API:** `/v1/expenses/receipts` (Anlage idempotent über die Client-UUID; Wiederholung mit gleicher UUID und gleichem Hash liefert denselben Beleg, nicht einen zweiten; gleiche UUID mit abweichendem Hash ergibt 409). Upload über Uploadticket mit Größe/MIME/Hash (Kap. 05 §5.7). Statuscodes: 201 neu, 200 bereits vorhanden, 409 Hash-Konflikt, 401 ohne Authentisierung, 403 fehlendes Recht (z. B. Zuordnung zu fremdem Fahrzeug, Entwurf bleibt lokal erhalten, Kap. 03 §3 Nr. 1), 404 fremder Mandant. Die Wiederaufnahme eines abgebrochenen Uploads setzt mit demselben Uploadticket bzw. derselben Client-UUID und demselben Hash fort und legt keinen zweiten Beleg an. Gebaut in `api/receipts.yaml`: `requestReceiptUpload` liefert 201 (neu) oder 200 (Wiederaufnahme bzw. Beleg schon vorhanden, Ticket mit `document_id`), `completeReceiptUpload` ist für Tickets mit Client-UUID idempotent (200 statt 409); Migration `V40260__beleg_client_uuid.sql`.
- **Zustandsautomat:** Fahrer-App-Entwurf `lokal` → `synchronisiert`; serverseitig Beleg im Zustandsautomaten aus WP-M4-34 (`vertraege/zustaende/beleg.md`).
- **JSON-Schema / Ereignisse:** Anlage erzeugt das Ereignis „Beleg eingegangen“ genau einmal (Outbox, `UNIQUE(tenant_id,aggregate_id,aggregate_revision,event_type)`).
- **Parameter und Kennzahlen:** keine neuen.
- **Oberfläche:** Fahrer-App (mobile/driver): lokaler Entwurf mit vor dem Upload erzeugter stabiler UUID, verschlüsselte Ablage (WP-M3-24, Offline-Queue), Statusanzeige „lokal“/„synchronisiert“, Fehlerzustand bei Hash-Konflikt und Upload-Abbruch (Wiederaufnahme), Anzeige der Erfassungszeit; ohne Netz kein Fehlerdialog, sondern Status „lokal“.
- **Entscheidungen:** keine.
- **Offene Punkte:** Sync-Protokoll für Wiederaufnahme abgebrochener Uploads, Verhalten bei abweichendem Hash und Erfassungszeit gegenüber Eingangszeit sind laut WP als Detailverträge vorab zu fixieren (hier festgelegt: Idempotenzschlüssel `(tenant_id, client_uuid)`, 409 bei Hash-Abweichung, beide Zeiten getrennt gespeichert); Uploadgrenzen und Bildqualität nicht beziffert (OP 13); E2E-069 liegt im Gate M7, Vorziehen nach M4 laut OP 35 vorgeschlagen. Nicht im Umfang: Offline-OCR auf dem Gerät. Blockiert den Bau nicht.

## Tests

- **Positiv:** offline erfassen, später synchronisieren; der Beleg erscheint genau einmal, `original_sha256` auf dem Server entspricht dem lokal erfassten Bild, Erfassungszeit und Eingangszeit sind getrennt gespeichert (E2E-069).
- **Negativ:** Upload-Abbruch nach 50 % und Wiederaufnahme ergibt keinen zweiten Beleg; App-Neustart vor der Synchronisation erhält den Entwurf mit derselben UUID; gleiche Client-UUID mit abweichendem Hash ergibt 409 und überschreibt nichts; Beleg eines anderen Mandanten mit gleicher UUID ist getrennt (Unique nur je `tenant_id`) und nicht lesbar; kein Zugriff ohne Authentisierung (401); UPDATE von `object_key`/`original_sha256` per SQL scheitert; Hash-Abweichung der übertragenen Bytes (Übertragungsfehler) legt keinen Beleg an.
- **Konkurrierend (Geld):** derselbe Entwurf wird parallel von zwei Synchronisationsläufen übertragen und ergibt genau einen Beleg und genau ein Ereignis „Beleg eingegangen“.
- **Referenztests:** E2E-069 (Gate M7; ermöglicht durch WP-M4-26; erwartet „Genau ein Beleg mit unverändertem Original und Uhrzeit“, daher wird auch die Erfassungszeit des Geräts unverändert ausgewiesen).
- Testfälle: E2E-069; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/OfflineReceiptSyncTest.kt::offline_beleg_erscheint_nach_sync_genau_einmal_mit_unveraendertem_hash; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/OfflineReceiptSyncTest.kt::derselbe_entwurf_parallel_von_zwei_sync_laeufen_genau_ein_beleg; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/OfflineReceiptSyncTest.kt::abgebrochener_upload_wird_wiederaufgenommen_ohne_doppelbeleg; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/OfflineReceiptSyncTest.kt::gleiche_uuid_anderer_hash_409_ohne_ueberschreiben_und_fremder_mandant_getrennt; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/OfflineReceiptSyncTest.kt::uebertragungsfehler_hash_legt_keinen_beleg_an; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/OfflineReceiptSyncTest.kt::ereignis_beleg_eingegangen_genau_einmal; mobile/driver/test/offline_beleg_sync_test.dart::app_neustart_vor_sync_behaelt_entwurf_mit_gleicher_uuid_und_status_lokal; mobile/driver/test/offline_beleg_sync_test.dart::hash_konflikt_und_upload_abbruch_zeigen_fehlerzustand_mit_wiederaufnahme; db/tests/test_expense_client_uuid.py::test_unique_tenant_client_uuid; db/tests/test_expense_client_uuid.py::test_original_felder_unveraenderlich; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/OfflineReceiptSyncTest.kt::derselbe_entwurf_mit_zwei_tickets_parallel_abgeschlossen_ergibt_einen_beleg; backend/app/src/test/kotlin/de/taktovia/app/documents/ReceiptCaptureApiTest.kt::idempotente_anlage_ueber_client_uuid_ueber_http; mobile/driver/test/beleg_sqlite_store_test.dart::entwurf_ueberlebt_neustart_mit_gleicher_uuid_und_ist_verschluesselt

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
