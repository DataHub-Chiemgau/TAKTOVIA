# WP-M5-17 – Sicherheitsabnahme: Prüfplan, Isolationssuite, Feldzugriffsmatrix, Penetrationstest

Stand 15.11.2026. Verantwortlich: Plattform (Security, QA) (Rolle laut D-12, keine Personen benannt). Abnahmebezug `LH-6.18-SEC-009` (Penetrationstest ohne kritische offene Befunde),
Kap. 08 §8.4 (Sicherheits-Gate), Kap. 01 §1.7 (Mandantentrennung außerhalb der Datenbank, Feldklassen). Das Arbeitspaket ist ein Enabler (keine eigene Anforderung); der Nachweis liegt in
[WP-M5-17-sicherheitsabnahme.md](../nachweise/WP-M5-17-sicherheitsabnahme.md).

Der in WP-M5-01 vorgesehene „Prüfplan Sicherheit“ war im Repository nicht vorhanden; dieses Dokument enthält ihn in dem Umfang, den WP-M5-17 braucht (Umfang, Regeln, Testkonten, Zeitfenster,
Befundklassen). Er ist vorläufig, bis der Auftraggeber die Prüfstelle benennt (D-12) und das Zeitfenster festlegt.

## 1 Prüfplan Sicherheit

**Umfang des unabhängigen Penetrationstests** (gegen Staging, nie gegen Produktion, nie mit echten Personendaten): Web (Operator-, Passenger-Oberfläche), API (alle Operationen der OpenAPI-Verträge,
`api/*.yaml`), Driver-App (Flutter; nicht in dieser Umgebung ausführbar), Upload (Uploadticket, Quarantäne, Abruf signierter Objekte) und Webhook-Eingang `/v1/payments/provider-events/{provider}`.
Register-Kennungen: `web`, `api`, `driver-app`, `upload`, `webhook-psp`.

**Regeln:** Prüfung nur innerhalb des vereinbarten Zeitfensters; nur synthetische Mandanten `pentest-…`; keine Ausnutzung gegen Dritte (PSP-Sandbox, Kartenorganisationen); keine Datenexfiltration über
das für den Nachweis nötige Maß; Denial-of-Service nur mit Absprache; Funde kritischer Schwere sofort melden (nicht bis zum Bericht warten).

**Testkonten:** je Rolle ein Konto (`passenger`, `driver`, `callcenter`, `support`, `dispatcher`, `planner`, `tenant_admin`, `finance`, `staff_planning`, `workshop`) in zwei Prüfmandanten (A, B), damit
Mandantenübergriffe prüfbar sind. Jeder Zugang ist befristet (höchstens 30 Tage, innerhalb des Zeitfensters), mit Protokollverweis im Audit, und wird nach Abschluss entzogen
(`membership.revoked_at`); die Entzugsprüfung läuft gegen die Datenbank (`pentest_register.zugang_datenbankbefunde`).

**Zeitfenster:** festzulegen (D-12, D-05 Supportzeiten); das Register hält Beginn und Ende; Zugänge außerhalb des Fensters (mit einem Tag Vorlauf und sieben Tagen Nachtest) sind ein Mangel.

**Zuordnung der Befundklassen des Prüfers zu den Mangelklassen** (identisch zu `tools/findings_gate.py`, Kap. 08 §8.4, `tests/acceptance/docs/mangelklassen.md`):

| Prüferschwere (CVSS) | Mangelklasse | Wirkung |
|---|---|---|
| KRITISCH (>= 9.0) | A | blockiert die Produktion; nur behoben mit bestandenem Nachtest oder begründete Falschmeldung |
| HOCH (>= 7.0) | B | behoben mit Nachtest, oder freigegebener Workaround und nicht abgelaufener Behebungstermin |
| MITTEL, NIEDRIG, INFO | C | Maßnahmenplan |

Ein Befund, der unabhängig von der Schwere die Kategorien `DATA_DISCLOSURE`, `DOUBLE_BOOKING`, `TRAFFIC_SAFETY` oder `CORE_BOOKING_OUTAGE` trifft, ist Klasse A (Mangelklassen-Regel WP-M0-22).

## 2 Was dieses Paket liefert und was nicht

| Akzeptanzkriterium | Lieferung | Grenze |
|---|---|---|
| 1 Isolationssuite alle P1-Tabellen und -Endpunkte, Wächter | `db/tests/test_m5_17_isolation_p1.py` (Zeilen in 124 von 175 Mandantentabellen, Lese-, Schreib-, Pool-Angriffe, Wächter „Tabelle ohne Policy“ mit Selbsttest); `tests/acceptance/m5_sicherheit/test_authentisierung_alle_endpunkte.py` (Wächter „Operation ohne Negativtest“, sechs Authentisierungsangriffe auf jede gebaute Operation); bestehend: `CrossTenantIsolationTest` (alle Endpunkte mit Objektbezug, mit Vollständigkeitswächter), `RechtematrixApiTest` | 51 Tabellen ließen sich nicht synthetisch befüllen (CHECK-Regeln) und sind nur strukturell geprüft, namentlich ausgewiesen; Obergrenze im Test |
| 2 PH-AT-001 Regression | Lauf der Gate-Szenarien PH-AT-001 (M1) und der M2-/M4-Anteile, siehe Nachweis | Verhalten auf dem Entwicklungsstand, kein Abnahme-Build |
| 3 Penetrationstestbericht | `tests/acceptance/m5_sicherheit/pentest/` (Register, Gate-Regeln, Selbsttests) | **Der unabhängige Penetrationstest ist nicht durchgeführt.** Es gibt keinen Bericht, keine benannte Stelle, keinen Nachtest; das Gate ist bewusst nicht bestanden |
| 4 Upload-/Exportberechtigungen | `SicherheitsabnahmeApiTest.erratene_abgelaufene_und_fremde_abrufadressen_...` (HTTP); bestehend: `UploadTicketTest`, `DefectEvidenceUploadTest`, `ReportJobsTest`, `ReportJobApiTest`, `LedgerExportTest` | Objektspeicher ist in der Entwicklungsumgebung ein lokaler Ersatz, kein S3-kompatibler Dienst |
| 5 Isolation außerhalb der Datenbank | Objektspeicher `ObjectStorageIsolationTest`, Cache `TenantCacheIsolationTest`, Ereignisse `EventEnvelopeIsolationTest`, Listen `TenantListingIsolationTest`, Live-Kanal `LiveChannelHttpTest`; neu: Supportzugriff `SicherheitsabnahmeApiTest.plattformbetreiber_und_supportrolle_...` | Es gibt in P1 keinen mandantenübergreifenden Supportzugriff (nur die Rolle `support` je Mandant); die Vorgaben dazu stehen unter D-11 offen |
| 6 Testzugang befristet, protokolliert, entzogen; Bericht ohne Personendaten | `pentest_register.py` (Befristung <= 30 Tage, Protokollverweis, Prüfmandant `pentest-…`, Entzug, Personendatenprüfung des Berichts) | Es wurde kein Testzugang ausgestellt |
| Feldzugriffsmatrix Rolle × Feldklasse | `tests/acceptance/m5_sicherheit/feldzugriffsmatrix.csv` (erzeugt aus Rechtematrix, Feldrechten, Systemkatalog; `test_feldzugriffsmatrix.py`) | Die Rechtematrix ist fachlich nicht freigegeben (OP-10); die Matrix belegt den Ist-Stand, nicht seine fachliche Richtigkeit |
| Rolle ohne Feldrecht, Rollenentzug unter Last, parallele Mandanten über einen Pool | `SicherheitsabnahmeApiTest` (HTTP), `test_m5_17_isolation_p1.py` (Datenbankpool) | Lastgröße im Sekundenbereich: Nebenläufigkeits-, kein Leistungstest |

## 3 Geplante Tests (Testfälle)

Das Arbeitspaket hat keine Anforderung mit Detailvertrag (Enabler); die Tests sind im Nachweis aufgeführt. Pfade:

- `db/tests/test_m5_17_isolation_p1.py`
- `backend/app/src/test/kotlin/de/taktovia/app/isolation/SicherheitsabnahmeApiTest.kt`
- `tests/acceptance/m5_sicherheit/test_authentisierung_alle_endpunkte.py`
- `tests/acceptance/m5_sicherheit/test_feldzugriffsmatrix.py`
- `tests/acceptance/m5_sicherheit/test_pentest_register.py`

## 4 Ablauf, sobald die Prüfstelle benannt ist

1. Staging aus dem Repository aufbauen (WP-M5-02), Prüfmandanten `pentest-a`, `pentest-b` anlegen, Konten je Rolle über die Mitgliedschaftsverwaltung ausstellen; Zugänge im Register eintragen.
2. Prüfung im Zeitfenster; Befunde mit Prüferschwere im Register (`befunde`), Bericht ablegen (`bericht.datei`, SHA-256).
3. Klasse A/B beheben, Nachtest durch die Prüfstelle, Eintrag `nachtest`; Klasse C mit Maßnahmenplan.
4. Zugänge entziehen (`entzogen_am`, `membership.revoked_at`); `python tests/acceptance/m5_sicherheit/pentest/pentest_register.py` muss Exitcode 0 liefern; erst dann `LH-6.18-SEC-009` als vollständig abgenommen führen.
5. Entscheidung D-02 (Isolationsstufe): geprüft wurde der RLS-Default; ein abweichender Beschluss verlangt Wiederholung der Isolationssuite.
