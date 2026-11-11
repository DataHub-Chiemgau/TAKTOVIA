# LH-6.18-SEC-003 – Transportverschlüsselung und Secret-Verwaltung

- **Verantwortlicher:** Rolle Plattform/Betrieb (Security), `infra/` und CI
- **Arbeitspaket:** WP-M1-36
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Geheimnisse und API-Schlüssel werden über dedizierte Secret-Verwaltung statt Quellcode verwaltet.

**Originalabnahme (unverändert):** Secret-Scan des Auslieferungsartefakts findet keine Zugangsschlüssel.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle. Invariante (Kap. 04 §4.4): Tabellen halten nur Secret-Referenzen, nie Secretwerte (z. B. `webhook_subscription` mit Secretreferenz; `adapter_capability`). Schema-Lint ergänzt eine Regel „keine Spalte mit Secretwert" (Vorschlag für das Namensmuster, im Pflichtenheft nicht festgelegt: `*_secret`, `*_password`, `*_api_key`, `*_token_value` ohne Suffix `_ref` scheitert; das Muster ist mit dem Bau zu bestätigen).
- **API:** keine. Konfiguration des Backends ausschließlich über Secret-Referenzen, aufgelöst zur Laufzeit je Umgebung aus der Secret-Verwaltung (Kap. 01 §1.8: getrennte Umgebungen Development, Staging, Performance, Production mit separaten Secrets); Secrets einer Umgebung sind in einer anderen nicht gültig.
- **Zustandsautomat:** keine.
- **JSON-Schema / Ereignisse:** keine. Befundbericht des Secret-Scans als CI-Artefakt (Auslieferungsartefakt: Images, Konfigurationsdateien, SBOM-Inhalt; zusätzlich Repository inkl. Git-Historie).
- **CI-Pflichtschritt:** `secret-scan` über Quellcode, Git-Historie und gebaute Images; Fund eines Zugangsschlüssels lässt den Build scheitern (Exit != 0; Ausgabe des Befunds ohne den Secretwert); der Schritt ist Pflicht für Merge und Release. Ausnahmen (Vorschlag, im Pflichtenheft nicht festgelegt) nur über dokumentierte, befristete Allowlist mit Begründung; jede Ausnahme wird im Abnahmelauf aufgeführt und zählt nicht als „keine Funde".
- **Rotation:** Secret wird in der Secret-Verwaltung rotiert, ohne neues Image; Dienste laden Secrets neu (Referenz bleibt, Wert wechselt).
- **Parameter und Kennzahlen:** keine aus kataloge.csv.
- **Oberfläche:** keine.
- **Entscheidungen:** D-05 (Hostingregion, verwaltete Dienste; bestimmt die Auswahl des Secret-Managers) – blockiert nur Produktivschaltung, nicht den Bau (Secret-Manager lokal/Staging über Port).
- **Offene Punkte:** Auswahl des Secret-Managers (hängt an D-05); Secret-Namensschema, Zugriffsrollen und Rotationsintervall (im Pflichtenheft nicht festgelegt, im WP als vorab zu klären geführt); TLS-Teil (SEC-002, OP-31) gehört nicht zu dieser Anforderung. Nichts davon blockiert den Bau; Intervalle sind vor Produktivschaltung festzulegen.

## Tests

- **Positiv:** Betrieb der Anwendung in lokaler Umgebung und Staging mit Secrets aus dem Manager; Secret-Scan über Repository, Git-Historie, Images, Konfigurationsdateien und SBOM meldet null Funde; Rotation eines Secrets ohne neuen Image-Build wird vom Dienst übernommen.
- **Negativ:** absichtlich eingechecktes Test-Secret (bekanntes Schlüsselmuster) lässt den Build scheitern; Image mit eingebackener `.env`-Datei wird abgewiesen; Migration mit Spalte für Secretwert scheitert am Schema-Lint; Secretwert in Logs/Konfigurationsdump wird vom Scan der Testläufe gefunden.
- **Konkurrierend:** Rotation eines Secrets unter Last (parallele Requests) ohne Ausfall und ohne Request mit gemischtem altem/neuem Secret-Zustand, der zu 5xx führt.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:tests/acceptance/security/test_secret_scan.py::test_testsecret_laesst_build_scheitern; geplant:tests/acceptance/security/test_secret_scan.py::test_git_historie_ohne_zugangsschluessel; geplant:tests/acceptance/security/test_secret_scan.py::test_image_mit_env_datei_wird_abgewiesen; geplant:db/tests/test_12_keine_secretspalten.py::test_keine_spalte_mit_secretwert; geplant:tests/acceptance/security/test_secret_scan.py::test_auslieferungsartefakt_ohne_zugangsschluessel; geplant:tests/acceptance/security/test_secret_rotation.py::test_rotation_unter_last_ohne_ausfall

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
