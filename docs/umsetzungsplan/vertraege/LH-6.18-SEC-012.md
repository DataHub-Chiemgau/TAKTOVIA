# LH-6.18-SEC-012 – Trennung von Test- und Produktionsdaten und -zugängen

- **Verantwortlicher:** Rolle Betrieb/Infrastruktur (`infra/`), Startprüfung in `backend/app`
- **Arbeitspaket:** WP-M1-40
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Trennung zwischen Test- und Produktionsdaten sowie Test-/Produktivzugängen wird erzwungen.

**Originalabnahme (unverändert):** Entwicklungssystem besitzt keine unkontrollierte Kopie echter Fahrgastdaten.

## Vertrag

- **Tabellen / DDL:** Umgebungskennung in der Datenbank (Ablageort – Tabelle oder Datenbankeinstellung – im WP nicht festgelegt, Entwurf im Bau; Migration unter `db/migrations/`, unveränderlich nach Anlage); Importprüfung verweigert den Import eines als Produktion gekennzeichneten Dumps in Nicht-Produktionsumgebungen.
- **API:** keine neue API. Backend-Startprüfung „Umgebungskennung der Anwendung = Umgebungskennung der Datenbank“ (Start scheitert sonst); Token eines fremden Issuers werden abgelehnt (WP-M1-04).
- **Infrastruktur (`infra/`):** getrennte Konten/Netze je Umgebung (Entwicklung, Staging, Performance, Produktion), getrennte IdP-Realms und Secrets (Kap. 01 §1.8); kein Netzpfad von Entwicklung zur Produktionsdatenbank; synthetischer Seed-Datengenerator für Mandanten A/B; technische Sperre für Produktions-Dumps in Nicht-Produktionsumgebungen.
- **Zustandsautomat / Schema / Ereignisse:** keine. **Parameter und Kennzahlen:** Konfigurationswert Umgebungskennung (Name im Bau festzulegen). **Oberfläche:** keine.
- **Umgebungsmatrix:** Zugänge, Rollen, Datenherkunft je Umgebung als Detailvertrag im Bau (Dokument unter `infra/`).
- **Entscheidungen:** D-05 (Hosting) – blockiert nur Produktivschaltung.
- **Offene Punkte:** Verfahren für kontrollierte, anonymisierte Datenübernahme in Nicht-Produktionssysteme: im Pflichtenheft nur „Produktionsdaten werden nicht in Entwicklung kopiert“; falls gewünscht, ist ein eigener Beschluss nötig, bis dahin ist jede Übernahme verboten. Ablageort der Umgebungskennung offen (Bau-Detail, nicht blockierend). Performance-Umgebung im Vollausbau ist M5.

## Tests

- **Positiv:** Seed-Lauf in Entwicklung erzeugt ausschließlich synthetische Daten der Mandanten A/B; Prüfung des Entwicklungssystems (Scan der Entwicklungsdatenbank, Abgleich mit der Datenherkunft laut Umgebungsmatrix) findet keine echten Fahrgastdaten; Anwendung mit passender Umgebungskennung startet normal.
- **Negativ:** Import eines als Produktion gekennzeichneten Dumps in eine Nicht-Produktionsumgebung wird technisch abgewiesen; Test-/Entwicklungstoken gegen die Produktions-API (und umgekehrt) werden abgelehnt; Entwicklungsdienst mit Produktions-DB-URL startet nicht (Umgebungskennungen verschieden); Zugriff mit Entwicklungszugängen auf Produktionsdatenbank/-secrets ist ohne Netzpfad nicht möglich; umgekehrt akzeptiert die Entwicklungs-API keine Produktionstoken (Issuer-Prüfung, WP-M1-04); Entwicklungs-, Staging-, Performance- und Produktions-IdP-Realm und -Secrets sind je Umgebung getrennt.
- **Konkurrierend:** nicht einschlägig (statische Umgebungstrennung, so im WP festgehalten); ersatzweise zwei parallele Importversuche desselben Produktions-Dumps werden beide abgewiesen.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/app/src/test/kotlin/de/taktovia/app/EnvironmentSeparationTest.kt::start_mit_abweichender_umgebungskennung_scheitert; geplant:db/tests/test_umgebungstrennung.py::test_produktions_dump_import_wird_abgewiesen; geplant:db/tests/test_umgebungstrennung.py::test_seed_enthaelt_nur_synthetische_daten; geplant:backend/app/src/test/kotlin/de/taktovia/app/EnvironmentSeparationTest.kt::token_fremder_umgebung_wird_abgelehnt; geplant:db/tests/test_umgebungstrennung.py::test_zwei_parallele_dump_importe_werden_beide_abgewiesen; geplant:db/tests/test_umgebungstrennung.py::test_entwicklungsdatenbank_enthaelt_keine_echten_fahrgastdaten; geplant:tests/acceptance/test_umgebungsmatrix.py::test_kein_netzpfad_und_keine_geteilten_secrets_zwischen_entwicklung_und_produktion

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
