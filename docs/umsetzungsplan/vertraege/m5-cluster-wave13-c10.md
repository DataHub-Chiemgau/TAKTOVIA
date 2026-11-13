# Detailvertrag Welle 13, Cluster 10 (WP-M5-20)

Gebaut mit den Annahmen und Grenzen dieser Datei; sie ergänzt die Verträge `LH-6.24-TIM-002` und `LH-6.19-NFR-020` und die WP-Dokumente und ersetzt kein Fachreview.

## WP-M5-20 – Standortdaten- und Medienminimierung (AT-59): P1-Mindestumfang, teilweise

### Entscheidungen (Planungsannahmen, nicht freigegeben)

- **OP-15, Original gegenüber EXIF-Minimierung.** Zwei Wege, je nach Beweiskraft des Objekts:
  - **Schadens- und Prüffotos (`defect_evidence`, WP-M3-44):** bereinigt **vor** der Ablage der abrufbaren Fassung. Die Datenbank hält zwei Hashes: `sha256` der gespeicherten (bereinigten) Fassung und `original_sha256` der hochgeladenen Fassung als Herkunftsnachweis. Das Original mit Metadaten wird nicht aufbewahrt (die Quarantänekopie entfällt nach dem Urteil). Dieses Verhalten bestand seit WP-M3-44; neu ist die gemeinsame Regel und der Metadatenvergleich mit echten EXIF-Strukturen.
  - **Belege (`expense_document`, WP-M4-21/-24):** das Original bleibt **unverändert mit Hash im geschützten Originalbestand** (GoBD-nahe Unveränderlichkeit, `original_sha256`, Integritätsprüfung bei jeder Auslieferung). Es ist nur für den Besitzer und die Finanzrolle über `GET /v1/expenses/{id}/original` abrufbar. Neu: `GET /v1/expenses/{id}/view` (`Uploads.openDeliveryVersion`) liefert die **Ansichtsfassung ohne Bildmetadaten** mit eigenem Hash (`X-Content-SHA256`) und Verweis auf den Originalhash (`X-Original-SHA256`). PDF wird unverändert geliefert (`X-Metadata-Minimized: false`; PDF-Metadaten sind nicht Teil der Regel).
- **Regel „welche Metadaten entfallen“:** Positivliste, nur Pixel bleiben. Entfernt werden EXIF (GPS-Verzeichnis, Kamera-Hersteller, -Modell, -Software, Seriennummern), eingebettete Vorschau (mit eigenem EXIF), XMP, IPTC/Photoshop, Kommentare und PNG-Textchunks, ICC-Profile. Erhalten bleibt allein die Ausrichtung, eingerechnet in die Pixel (EXIF-Orientation 3, 6, 8; gespiegelte Werte werden nicht umgerechnet). Umsetzung: `de.taktovia.platform.media.ImageMetadataMinimizer` (Dekodieren und Neukodieren, JPEG Qualität 0,92); eine Regel für Schadensfotos (`MediaInspectors`) und Belegansicht. Ein nicht dekodierbares Bild wird nie unbereinigt ausgeliefert (422).
- **Nachweis „bereinigt“:** `de.taktovia.platform.media.ImageMetadata` ist ein eigener Containerparser (JPEG-Segmente, PNG-Chunks, TIFF-Verzeichnisse), unabhängig vom Kodierer; er meldet nur Metadatenklassen, nie Werte. Ein defekter EXIF-Block gilt als „Metadaten vorhanden“, nie als sauber.
- **Freigabe der Geozone (D-10/D-11):** Parameter `clock_in_geofence_enabled` (boolean, Standard `false`, nur Mandantenebene) und `clock_in_geofence_release_ref` (Verweis auf die dokumentierte Freigabe, 3 bis 200 Zeichen; Planungsannahme, Inhalt und Mitbestimmungsverfahren offen) in Katalogversion 1 (V50200). Die Revision eines Mandanten mit `clock_in_geofence_enabled = true` ist nur **mit nicht leerem Freigabeverweis in derselben Revision** validier- und publizierbar (`GeofenceReleaseValidator` im Modul `staff-time`, läuft bei Validierung und Publikation; 422 `REQUIRED` am Verweis). Ausgeschaltet braucht keinen Verweis.
- **Koordinaten beim Stempeln:** `POST /v1/time-events` kennt einen optionalen Block `location`; der Server **verwirft** ihn (kein Feld in `time_event`, Antwort, Outbox, Audit, Idempotenzregister oder Logs), der Stempel wird angenommen (201, Wiederholung 200). Andere Standortfelder auf oberster Ebene bleiben 422 (`additionalProperties: false`). Auch bei aktiver Geozone speichert P1 keinen Standort (kein Standortfeld; Speicherung ist P2, WP-M7d-40).

### Tests

- `backend/platform/src/test/kotlin/de/taktovia/platform/media/ImageMetadataMinimizerTest.kt` (6 Fälle: JPEG mit GPS-EXIF samt Vorschau, XMP und Kommentar; PNG mit eXIf/tEXt; Ausrichtung; ungültige/übergroße Bilder; defekter EXIF-Block).
- `backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectEvidenceMetadataTest.kt`, `backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ReceiptDeliveryVersionTest.kt` (3 Fälle), `backend/app/src/test/kotlin/de/taktovia/app/privacy/ReceiptViewApiTest.kt` (HTTP, Upload bis Ansicht).
- `backend/app/src/test/kotlin/de/taktovia/app/privacy/LocationMinimizationApiTest.kt` (5 Fälle, HTTP: Stempeln ohne Standort, Koordinaten verworfen mit Volltextsuche über alle Tabellen samt Positivkontrolle und mitgeschnittenen Logs, Freigaberegel, Wirksamkeitszeitpunkt).
- Vertragsfall `W13C10ContractCases` (`getExpenseDeliveryView`), Isolationsprobe in `CrossTenantIsolationTest`.
- Akzeptanzszenario AT-59: `tests/acceptance/m5_at59/` (Datenbankteil unter der Runtime-Rolle, verknüpft die JUnit-Ergebnisse; Läufer schreibt Evidenz nach `tests/acceptance/evidence/m5/` und mit `--write-e2e` den Eintrag in `e2e.csv`, Entscheidung bleibt „Ausstehend“).

### Nicht ausgeführt und offen (deshalb teilweise)

1. **App fragt keine Standortberechtigung (Kriterium 1, zweiter Halbsatz):** kein Flutter in der Umgebung; `mobile/` enthält keine Standortanfrage für das Stempeln, ein Gerätetest fehlt.
2. **Kriterium 6, „Prüfung mit dem Skript aus WP-M5-06“:** `tools/log_stichprobe.py` sucht nach Kartennummern, IBAN, E-Mail, Telefon, Adresse, Token und Boarding-Code, nicht nach Koordinaten oder Bildmetadaten; es kann den Nachweis nicht führen. Ersatz: Volltextsuche über alle Tabellen und Mitschnitt der Logzeilen im JUnit-Test. Traces und die Offline-Outbox der App sind nicht geprüft (kein Tracing-Backend, keine App).
3. **Beleg-Original mit Metadaten** liegt weiterhin im geschützten Originalbestand (Entscheidung OP-15, Auslieferung nur über `/original` an Besitzer und Finanzrolle). Ob stattdessen vor dem Hash bereinigt werden soll, entscheidet die Fachseite; Änderung wäre ein Eingriff in die Beleg-Integrität (WP-M4-24).
4. **Die Geozone selbst** (P2) und die Mitbestimmung (D-10/D-11) sind nicht gebaut; der Freigabeverweis ist nur ein Verweistext.
5. **Eine Freigabe, die ein Fachexperte als Inhalt prüft**, fehlt: die Validierung prüft nur, dass ein Verweis vorhanden ist.
