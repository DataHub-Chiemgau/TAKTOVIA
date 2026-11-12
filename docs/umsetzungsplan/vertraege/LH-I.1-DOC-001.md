# LH-I.1-DOC-001 – Belegerfassung per Kamera in der Fahrer-App

- **Verantwortlicher:** Rolle Finanzen/Belege (Modul `documents-finance`, Fahrer-App `mobile/driver`)
- **Arbeitspaket:** WP-M4-25
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrer können Papierbelege mit der Kamera erfassen, zuschneiden, drehen und in ausreichender Bildqualität an einen Vorgang anfügen.

**Originalabnahme (unverändert):** Ein fotografierter Tankbeleg kann in höchstens fünf Eingabeschritten als Entwurf gespeichert werden; Rohbild und bearbeitete Ansicht sind abrufbar.

## Vertrag

- **Tabellen / DDL:** `expense_document` (V1: `object_key`, `original_sha256`, `mime`, `state`, `revision`, `created_by`, `original_received_at`, `UNIQUE(tenant_id,object_key)`); Original und bearbeitete Ansicht als getrennte Objekte (Kap. 03 §3 Nr. 1), das Original bleibt unverändert (Hash). Die bearbeitete Ansicht und die Vorgangszuordnung (Fahrzeug/Schicht) brauchen zusätzliche Spalten/Tabelle `document_revision` (Kap. 04 §4.5 Familie DOC); Migration ab V4, exakte Namen mit dem Bau. Entwurf bleibt Zustand `state`; „synchronisiert“ und „freigegeben“ sind getrennte Zustände.
- **API:** `/v1/expenses/receipts` (Anlage Entwurf mit Rohbild und bearbeiteter Ansicht; Abruf beider Ansichten); Uploadticket mit Größe/MIME/Hash (Kap. 05 §5.7); Statuscodes 201, 400, 401, 403 (fremdes Fahrzeug), 413, 422, 429, 503. Gebaut in `api/receipts.yaml` (`requestReceiptUpload` mit `client_uuid`/`captured_at`, 201 neu bzw. 200 vorhandenes Ticket, `attachReceiptEditedView`, `getReceiptEditedView`); Zuordnung nur zu zugewiesenen Vorgängen (Fahrzeug einer eigenen Schicht bzw. eigene Schicht), sonst 403.
- **Zustandsautomat:** Beleg: Entwurf, danach Prüfung/Freigabe (nicht Gegenstand; siehe WP-M4-22/-23). Datei `vertraege/zustaende/expense_document.md`, falls vorhanden.
- **JSON-Schema / Ereignisse:** keine neuen.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** Fahrer-App, Belegerfassung im Bereich UI-10 laut WP (in Kap. 06 §6.7 ist UI-10 „Driver Fahrt/Schaden“, eine eigene Beleg-UI-ID fehlt): Kamera, Zuschnitt, Drehung, Qualitätsprüfung (Schärfe/Auflösung), Auswahl des Vorgangs (Fahrzeug/Schicht), Speichern als Entwurf in höchstens fünf Eingabeschritten; Screenreader und vergrößerte Schrift; während der Fahrbewegung kein Erfassungsdialog; verständlicher Hinweis bei verweigerter Kameraberechtigung.
- **Entscheidungen:** keine.
- **Offene Punkte:** Mindestbildqualität („ausreichende Bildqualität“) und Uploadgrenzen sind nicht beziffert (WP-M4 Offener Punkt 13); der Bau legt Schwellen als Konfigurationswerte fest, die vom Auftraggeber zu bestätigen sind; blockiert den Bau nicht. Schrittfolge (maximal fünf Eingabeschritte) und Vorgangsarten sind im WP-M4-25 vorab zu definieren; Offline-Erfassung ist nicht Umfang (WP-M4-26, DOC-013), OCR nicht Umfang (DOC-002).

## Tests

- **Positiv:** App-Integrationstest mit Schrittzählung: fotografierter Tankbeleg in höchstens fünf Eingabeschritten als Entwurf gespeichert; Rohbild und bearbeitete Ansicht beide abrufbar; Zuschnitt und Drehung verändern das Rohbild nicht (Hash gleich).
- **Negativ:** unscharfes Bild liefert Hinweis, Fahrer kann neu aufnehmen; verweigerte Kameraberechtigung (verständlicher Hinweis, kein Absturz); Zuordnung zu fremdem Fahrzeug wird abgewiesen, der Entwurf bleibt erhalten; Abbruch im Ablauf hinterlässt keinen halben Beleg.
- **Konkurrierend:** zwei parallele Speicherversuche desselben Entwurfs mit demselben Idempotency-Key ergeben genau einen Beleg (die Client-UUID mit Unique auf `(tenant_id, Client-UUID)` liefert erst WP-M4-26/DOC-013); Zugriff eines Fahrers anderen Mandanten auf den Beleg scheitert (RLS).
- **Referenztests:** E2E-064 (Teil).
- Testfälle: E2E-064; mobile/driver/test/beleg_erfassung_test.dart::tankbeleg_in_hoechstens_fuenf_schritten_als_entwurf; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ReceiptCaptureTest.kt::rohbild_und_bearbeitete_ansicht_abrufbar; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ReceiptCaptureTest.kt::zuschnitt_und_drehung_lassen_original_hash_unveraendert; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ReceiptCaptureTest.kt::fremdes_fahrzeug_403_entwurf_bleibt_erhalten; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ReceiptCaptureTest.kt::paralleler_speicherversuch_gleicher_idempotency_key_genau_ein_beleg; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ReceiptCaptureTest.kt::fremder_mandant_kein_zugriff_auf_beleg; mobile/driver/test/beleg_erfassung_test.dart::unscharfes_bild_hinweis_und_neu_aufnahme; mobile/driver/test/beleg_erfassung_test.dart::kameraberechtigung_verweigert_verstaendlicher_hinweis; mobile/driver/test/beleg_erfassung_test.dart::abbruch_hinterlaesst_keinen_halben_beleg; backend/app/src/test/kotlin/de/taktovia/app/documents/ReceiptCaptureApiTest.kt::fremdes_fahrzeug_403_und_bearbeitete_ansicht_getrennt_vom_rohbild; mobile/driver/test/beleg_erfassung_test.dart::fremdes_fahrzeug_wird_abgewiesen_und_der_ablauf_bleibt_offen; mobile/driver/test/beleg_erfassung_test.dart::waehrend_der_fahrbewegung_oeffnet_sich_kein_erfassungsdialog

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung: Zuordnung nur zu eigenen Vorgängen).
