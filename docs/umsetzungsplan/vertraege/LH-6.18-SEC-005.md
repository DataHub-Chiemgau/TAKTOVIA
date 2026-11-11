# LH-6.18-SEC-005 – Feldschutz für Mobilitäts- und Unterstützungsmerkmale

- **Verantwortlicher:** Rolle Plattform (Feldschutzbaustein in `backend/platform`)
- **Arbeitspaket:** WP-M1-31
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Berechtigungen für Mobilitäts-/Unterstützungsmerkmale sind streng eingeschränkt.

**Originalabnahme (unverändert):** Unbeteiligte Personen erhalten keinen Zugriff auf sensibles Profilmerkmal.

## Vertrag

- **Tabellen / DDL:** `passenger_private.support_ciphertext bytea` und `passenger_private.contact_ciphertext bytea` (V1, PK `(tenant_id,id)`, RLS `tenant_isolation` mit FORCE). Neue Migration: Spaltenrechte so, dass die allgemeine Runtime-Rolle `taktovia_runtime` diese Spalten nicht selektieren kann (aktuell erteilt `R__runtime_grants.sql` SELECT auf die ganze Tabelle `passenger_private` und ist anzupassen: Spalten-GRANT ohne die Ciphertext-Spalten); eigener Modulzugang (eigene Rolle/Verbindung des Moduls für sensible Felder) liest sie. Spalten sind im Ruhezustand verschlüsselt (Envelope-Verschlüsselung, Schlüsselreferenz aus der Secret-Verwaltung, WP-M1-36). Schema-Lint: keine sensiblen Spalten in allgemeinen Aggregattabellen. Klassifikation im Dateninventar (SEC-004). Schreibrechte (INSERT/UPDATE) auf die Ciphertext-Spalten erhält ebenfalls nur der Modulzugang; `db/role_matrix.csv` und `db/table_owners.csv` sind entsprechend fortzuschreiben (Kap. 04 §4.6).
- **API:** getrennte Schemas für sensible Felder; Standardantworten enthalten sie nicht; Zugriff nur mit Zweck und Feldscope; unbeteiligte Akteure erhalten das Feld nicht bzw. 404, Fremdmandant 404 (Kap. 05 §5.1).
- **Port:** Feldschutzbaustein (Klassifizierung PII/HR/Finanzen/Unterstützung, DTO-Trennung, Zweckprüfung, feldbezogene Rechte) in `backend/platform`; Regel „keine Diagnosen", „erforderliche Unterstützung ohne Diagnose" (Kap. 02 §3); Lesezugriff erzeugt Auditeintrag mit Akteur und Zweck (WP-M1-46, `audit_event`); Merkmal erscheint nie in Logs, Metriken oder Outbox-Payloads.
- **Zustandsautomat / Schema / Ereignisse:** keine; Rollen×Feld-Matrix als Anlage des Vertrags (Bau legt sie als `vertraege/schemas/feldrechte.v1.schema.json` bzw. Matrixdatei an).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-05 Basis – Komponente „Unterstützungsbedarf" als transportrelevante Auswahl ohne Diagnoseabfrage (eingebettet durch WP-M1-29); UI-20 Basis – Zugriff nur mit Zweckangabe.
- **Entscheidungen:** D-11 (Zweck, Rechtsgrundlage, Löschfrist) – blockiert nur Produktivschaltung.
- **Offene Punkte:** OP-24 (Katalog transportrelevanter Unterstützungsmerkmale fehlt; „beteiligt" vs. „unbeteiligt" nicht rollenscharf definiert – bis dahin gilt fail-closed: ohne ausdrückliches Feldrecht kein Zugriff); OP-34 (UI-05 Basis in M1 oder nur API-Abnahme). Nachweis am minimierten Betreiberpayload der Buchung (M2: WP-M2-23) und an Arbeitszeit-/Abwesenheitsdaten des vollständigen AT-49 (M4: WP-M4-06, WP-M4-08) erfolgt dort; kein Bauhindernis für M1.

## Tests

- **Positiv:** berechtigte Rolle mit Feldrecht und Zweck erhält das Merkmal als Auswahl ohne Diagnose; Auditeintrag mit Akteur und Zweck ist abfragbar; Standardantworten (z. B. Fahrgastprofil) enthalten das Merkmal nicht; UI-05 Basis „Unterstützungsbedarf" bietet nur transportrelevante Auswahl, keine Diagnoseabfrage (Umfang nach OP-34).
- **Negativ:** jede Rolle der Rechtematrix ohne Feldrecht (anderer Fahrgast, Mitarbeiter ohne Feldrecht, Tenantadmin ohne gesonderte Zuweisung) erhält das Feld nicht bzw. 404 über jeden M1-Endpunkt; fehlender Zweck wird abgewiesen; Fremdmandant erhält 404; direkter SQL-`SELECT support_ciphertext` unter `taktovia_runtime` scheitert; Spalte ist im Ruhezustand nicht im Klartext lesbar; Scan der Testläufe findet das Merkmal nicht in Logs, Metriken und Outbox-Payloads.
- **Konkurrierend:** Entzug des Feldrechts parallel zum Lesen – nach Commit des Entzugs kein Zugriff mehr; zwei Mandanten lesen parallel ohne Vermischung.
- **Referenztests:** AT-49 (Merkmalsteil; WP-M1-31).
- Testfälle: AT-49; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/FieldProtectionTest.kt::unbeteiligte_rolle_erhaelt_unterstuetzungsmerkmal_nicht; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/FieldProtectionTest.kt::fehlender_zweck_und_fremdmandant_werden_abgewiesen; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/FieldProtectionTest.kt::merkmal_nicht_in_logs_metriken_outbox; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/FieldProtectionTest.kt::ciphertext_ist_im_ruhezustand_nicht_klartext; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/FieldProtectionTest.kt::zwei_mandanten_lesen_parallel_ohne_vermischung; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/FieldProtectionTest.kt::feldrecht_entzug_parallel_zum_lesen; geplant:db/tests/test_08_feldschutz.py::test_runtime_kann_support_ciphertext_nicht_lesen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
