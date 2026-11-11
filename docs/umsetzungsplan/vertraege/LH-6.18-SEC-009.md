# LH-6.18-SEC-009 – Eingabehärtung und Sicherheits-Scans in CI

- **Verantwortlicher:** Rolle Plattform (Security, CI)
- **Arbeitspaket:** WP-M1-38
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Eingaben werden serverseitig validiert und gegen typische API-/Webangriffe abgesichert.

**Originalabnahme (unverändert):** DAST/SAST und Penetrationstest finden keine kritischen offenen Befunde.

## Vertrag

- **Tabellen / DDL:** keine. Parametrisierte Queries ausschließlich (Lint); Mandanten-RLS bleibt unberührt.
- **API:** serverseitige Validierung aller Eingaben gegen `api/*.yaml` (`additionalProperties:false` gegen Mass Assignment), strikte Content-Types, Größen- und Tiefenlimits für JSON, Ausgabe-Encoding, Security-Header, CORS-Whitelist, CSRF-Schutz für Browserflüsse, SSRF-Baustein (gemeinsam mit WP-M1-16), Rate-Limit. Abgewiesene Angriffe liefern 4xx ohne interne Details.
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **CI:** SAST, Abhängigkeits-/Image-Scan als Pflichtschritte; DAST gegen Staging anhand der OpenAPI-Dateien über alle M1-Endpunkte; Befundverwaltung mit Mangelklassen (Klasse A blockiert, Kap. 08 §8.4); jeder Befund mit dokumentierter Entscheidung; Scan-Berichte und Testzugang für den Penetrationstest in M5.
- **Parameter und Kennzahlen:** Rate-Limit-Quoten, maximale Body-Größe und Verschachtelungstiefe sind nicht beziffert (OP-20).
- **Oberfläche:** Content-Security-Policy, keine Inline-Skripte, sichere Token-Aufbewahrung im Web-Rahmen (WP-M1-43).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-20 (Grenzwerte; Bau mit konfigurierbaren Vorgabewerten, vor Produktivschaltung festzulegen); Schwellen „kritisch" je Werkzeug, Pflicht-Header-Liste und Prüfkatalog (OWASP API Top 10 nur als Arbeitsgrundlage, vom Pflichtenheft nicht vorgegeben) sind im Bau festzulegen; OP-01: der Penetrationstest des Lastenhefts erfolgt in M5 (WP-M5-17), Vollabnahme der Originalabnahme erst dort. Upload-/Malware-Prüfung (M4) ist nicht Umfang.

## Tests

- **Positiv:** Regressionslauf mit gültigen Requests über alle M1-Endpunkte ohne Fehlalarme; SAST und DAST laufen in CI bzw. gegen Staging, kein offener kritischer Befund, jeder Befund mit Entscheidung dokumentiert.
- **Negativ:** aus OpenAPI generierter Angriffskatalog je Endpunkt wird abgewiesen: SQL-Injection, XSS, übergroße und tief verschachtelte Bodies, Mass Assignment (unbekannte Felder), Pfadmanipulation, Header-Injection, falscher Content-Type, fremde CORS-Origin, fehlender CSRF-Schutz; ein absichtlich eingebauter kritischer Befund (Klasse A) lässt die Pipeline scheitern; SSRF-Ziele (interne/Loopback-Adressen) werden vom Baustein abgewiesen; Abweisung enthält keine internen Details (Stacktrace, SQL, Pfade).
- **Konkurrierend:** Rate-Limit unter parallelem Missbrauch (viele parallele Clients) greift ohne Beeinträchtigung legitimer Mandanten.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:tests/acceptance/security/test_angriffskatalog.py::test_standardangriffe_werden_abgewiesen; geplant:tests/acceptance/security/test_angriffskatalog.py::test_abweisung_ohne_interne_details; geplant:tests/acceptance/security/test_angriffskatalog.py::test_ssrf_ziele_werden_abgewiesen; geplant:tests/acceptance/security/test_ci_gate.py::test_klasse_a_befund_laesst_pipeline_scheitern; geplant:tests/acceptance/security/test_angriffskatalog.py::test_gueltige_requests_ohne_fehlalarm; geplant:tests/acceptance/security/test_ratelimit.py::test_rate_limit_unter_parallelem_missbrauch

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
