# WP-M5-17 – Sicherheitsabnahme: Nachweis

Stand 15.11.2026, Entwicklungsstand des Branches `wave17-c1` (kein Abnahme-Build). Vertrag und Prüfplan: [m5-sicherheitsabnahme.md](../vertraege/m5-sicherheitsabnahme.md).
**Status: teilweise.** Der Penetrationstest durch eine unabhängige Stelle (AK 3) ist nicht durchgeführt und in dieser Umgebung nicht durchführbar; AK 6 (Testzugang des Prüfers) ist daher nur als Werkzeug und Regel belegt.

## Ausgeführte Läufe (Umgebung: PostgreSQL 16/PostGIS lokal, kein Docker, kein Keycloak, kein Flutter, kein Staging)

| Lauf | Ergebnis |
|---|---|
| `db/tests` vollständig (`unittest discover`, Datenbank `taktovia_wave17c1`) | 848 Tests, OK (davon `test_m5_17_isolation_p1.py`: 10) |
| `cd backend && ./gradlew build --continue` (Architekturtests, alle Modul- und Anwendungstests, JUnit-Ergebnisse) | BUILD SUCCESSFUL, 16 min 40 s; `SicherheitsabnahmeApiTest` 4 Tests grün |
| `tests/acceptance/m5_sicherheit` (`unittest discover`) | 23 Tests, OK (Authentisierungsangriffe 1704 Anfragen gegen 284 Operationen, Feldzugriffsmatrix 7, Register 12) |
| `tests/acceptance/security` (Regression Angriffskatalog u. a.) | 38 Tests, OK, 1 übersprungen |
| `tests/acceptance/m1_gate/run.py` | PH-AT-001 und zehn weitere Gate-Tests PASSED, „M1-Gate-Suite: GRÜN“ |
| `tests/acceptance/m2_gate/run.py`, `m4_gate/run.py` | Isolationsanteile (PH-AT-001 M2: 16 Prüfungen; TECH-M4-ISOLATION: 16 Prüfungen) PASSED; die Suiten insgesamt sind ROT wegen anderer, hier nicht untersuchter Szenarien (nicht Gegenstand dieses Pakets) |
| `tools/check_repo.py`, `tools/lint_sql.py`, `api/tools/lint.py` | PASS |
| `python tests/acceptance/m5_sicherheit/pentest/pentest_register.py` | NICHT BESTANDEN (3 Mängel: kein Bericht, keine unabhängige Stelle) – erwartet und ehrlich |

Nicht ausgeführt: Web-Lint/-Tests/-Build (keine Webänderung), Driver-App (Flutter), Staging, Keycloak, unabhängiger Penetrationstest.

## Akzeptanzkriterien

1. **Isolationssuite und Wächter – im Rahmen der Umgebung erfüllt.** `test_m5_17_isolation_p1.py`: 175 Mandantentabellen; in 124 wurden für zwei Mandanten Zeilen erzeugt und unter der Runtime-Rolle angegriffen (kein Fremdbestand, eigener Bestand vollständig, ohne und mit unbrauchbarem Kontext nichts; UPDATE/DELETE fremder Zeilen wirkungslos – 71 UPDATE, 14 DELETE; INSERT mit fremder `tenant_id` abgewiesen – 122, davon 108 durch die RLS-Policy selbst; Pool-Wiederverwendung mit leerem Kontext; zwölf Threads zweier Mandanten über drei gemeinsame Verbindungen). Die 51 nur strukturell geprüften Tabellen (CHECK-Regeln verhinderten synthetische Zeilen) stehen namentlich in der Testausgabe; die Zahl ist nach oben begrenzt. Wächter „Tabelle ohne Policy“ (`infra/rollout/.../tabellen_ohne_policy`) und die Konvention (USING, WITH CHECK, Primärschlüssel und Fremdschlüssel mit `tenant_id`) melden nichts; beide haben einen Selbsttest mit absichtlich undichten Tabellen. Wächter „Endpunkt ohne Negativtest“: `test_authentisierung_alle_endpunkte.py` fährt sechs Angriffe (ohne Token, Müll, fremder Schlüssel mit richtiger `kid`, abgelaufen, falsche Zielgruppe, `alg: none`) auf jede der 284 gebauten Operationen gegen die echte Anwendung und scheitert, wenn eine Operation fehlt; zusätzlich bestehend `CrossTenantIsolationTest` (fremde ID über alle Endpunkte mit Objektbezug, mit eigenem Vollständigkeitswächter).
2. **PH-AT-001-Regression – auf dem Entwicklungsstand erfüllt:** M1-Gate-Szenario, M2- und M4-Isolationsanteile PASSED; leerer Transaktionskontext und Pool-Wiederverwendung durch die Suite unter 1.
3. **Penetrationstestbericht – NICHT erfüllt.** Es gibt keine benannte Stelle, kein Staging, keinen Bericht, keine Befundliste, keinen Nachtest. Das Register (`pentest/register.json`) steht auf `AUSSTEHEND`; es wurden keine Befunde erfunden. Der unabhängige Test ersetzt sich nicht durch die Ersatzläufe der Entwicklung (Angriffskatalog WP-M1-38, Authentisierungsangriffe, Isolationssuite); sie sind ein Vorlauf.
4. **Upload- und Exportberechtigungen – erfüllt für die gebauten Wege:** `SicherheitsabnahmeApiTest` (erratene, abgelaufene, manipulierte und mandantenfremde Adressen für Ledger-Export, signierte Objekte und Berichtsaufträge ergeben gleichartige 404, ohne interne Details) und die bestehenden Modultests (Uploadticket abgelaufen/verbraucht, Quarantäne, Berichtslink abgelaufen/manipuliert).
5. **Isolation außerhalb der Datenbank – erfüllt für den lokalen Ersatz:** Objektspeicher, Cache-Schlüssel, Ereignisse, Listen (Plattformtests), Live-Kanal (`LiveChannelHttpTest`), Supportzugriff (neuer Test: Plattformbetreiber und Support A sehen nichts von B, kein Mandantenwechsel ohne Mitgliedschaft). Der Objektspeicher ist ein Ersatz, kein S3-kompatibler Dienst.
6. **Testzugang des Prüfers – nur als Regel und Werkzeug:** befristet (<= 30 Tage, Prüfzeitfenster), Protokollverweis, Prüfmandant `pentest-…`, Entzug (Register und `membership.revoked_at`, gegen die Datenbank getestet), Bericht ohne Personendaten (E-Mail außerhalb Testdomänen, Telefon, IBAN werden abgewiesen). Es wurde kein Zugang ausgestellt.

Feldzugriffsmatrix Rolle × Feldklasse: `tests/acceptance/m5_sicherheit/feldzugriffsmatrix.csv`. Auffälligkeiten für die fachliche Prüfung (OP-10, kein Befund der Tests): `planner` darf 22 von 38 HR-Operationen, `driver` 15 von 44 Finanzoperationen (eigene Belege/Kosten), `workshop` 1 PII-Operation. Der Administrator hat auf keine Feldklasse Zugriff.

## Geplante Nachweise nach Benennung der Prüfstelle

Siehe Vertrag §4. Offene Entscheidungen: D-12 (Personen, Prüfstelle), D-05 (Zeitfenster, Supportzeiten), D-11 (Supportzugriff über Mandanten hinweg), D-02 (Isolationsstufe, geprüft wurde der RLS-Default).
