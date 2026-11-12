# M1-Gate-Tests: in M1 prüfbarer Umfang (WP-M1-45, OP-02)

Die elf Gate-Tests laufen als Szenarien unter der Runtime-Rolle (`tests/acceptance/m1_gate/`, Aufruf `python tests/acceptance/m1_gate/run.py`). Jeder Test prüft nur, was in M1 mit vorhandenen Objekten
nachweisbar ist; die übrigen Teilschritte sind je Test benannt und einem späteren Meilenstein zugeordnet. **Die Festlegung des Umfangs je Test mit dem Auftraggeber steht aus (OP-02, D-12);** diese Tabelle ist der
Vorschlag, gegen den die Szenarien laufen. Ein Test gilt erst mit dem Nachweis des liefernden Meilensteins als vollständig bestanden; `e2e.csv` führt deshalb `Bestanden (M1-Umfang)` und die Entscheidung bleibt `Ausstehend`.

| Test | In M1 geprüft | Nicht prüfbar in M1 (Teilschritt) | Zielmeilenstein |
|---|---|---|---|
| PH-AT-001 | Isolation über alle Mandantentabellen der Fixture (Fahrgast, Fahrzeug, Beleg-Zeile, identisch benannte Kostenstelle) unter der Runtime-Rolle: Detail, Liste, Änderung, fremder FK, kein Kontext, Verbindungswiederverwendung; alle M1-Endpunkte (`CrossTenantIsolationTest`), manipulierter Mandantenkontext, Sicherheitsereignis ohne B-Inhalt | Such-, Export- und Medienendpunkt; Belegobjekt im Objektspeicher; Fahrzeugstamm | M2 (Suche), M3 (Fahrzeug), M4 (Beleg, Export, Medien) |
| PH-AT-011 | Idempotenzregister: gleicher Schlüssel mit gleichem und mit abweichendem Request (Fingerabdruck), keine Kollision über Akteure, Operationen, Mandanten; 409 am Filter | Konflikt am Buchungsbefehl mit Angebot Q1/Q2 und Betrag; gleicher Operation-Identifier für Buchung | M2 (`confirmBooking`), M4 (Zahlung) |
| PH-AT-017 | Inbox-Deduplikation je Event und Consumer, kein Rücksprung aus Endzuständen (Übergangsregeln und Datenbankguard), Dedupe bei 100 Zustellungen | Reihenfolge `completed` Rev. 8 vor `in_progress` Rev. 7 an einem Fachaggregat (Buchung/Fahrt), Nachlesen bei Lücke | M2 (Buchung), M3 (Fahrt) |
| PH-AT-018 | DST-Mehrdeutigkeit 25.10.2026 Europe/Berlin (zwei Zeitpunkte, Schichtdauer aus absoluten Zeitpunkten), Konfigurationswechsel ändert gespeicherte Zusage nicht (Snapshot unveränderlich), wirksame Version je Zeitpunkt | Tarif- und Pünktlichkeitsschwelle (CFG-011/021), historischer Berichtstand (KPI-006/020), lokaler Betriebstag in der Ansicht | M2 (Tarif), M8 (Kennzahlen) |
| AT-18 | Mitgliedschaft/Rolle eines Disponenten aus Betreiber B im Kontext A: nicht sichtbar, nicht änderbar, nicht anlegbar; Rechtematrix, Tenantwechsel (403/404) | Support/Disponent mit Dienstzuordnung am Fachobjekt (Disposition, Fahrt) | M2/M3 |
| AT-24 | 100 identische Webhook-Eingänge (ein Eintrag, 99 Duplikate), verspätetes Ereignis, einmalige Wirkung eines Befehls bei Wiederholung (Idempotenz, `publishConfig`), UI zwei Tabs | Doppelte und verspätete Events an Buchungs-/Zahlungsaggregaten (Konsistenter Endstatus) | M2, M4 |
| AT-49 | Arbeitszeit (Schicht) und Fahrerdaten fremder Mandant: nicht lesbar/änderbar; Ciphertext für die Runtime-Rolle nicht lesbar; Fremdzugriff protokolliert, Audit nicht änderbar | Fotos/Schadensevidenz, Arbeitszeitnachweis und Krankheitsdetails | M3 (Fotos), M4/M7d (Zeit, Abwesenheit) |
| E2E-091 | Beleg-Zeile und Fahrerprofil von B im Kontext A: nicht abrufbar, nicht auflistbar, nicht änderbar; serverseitige Abweisung und Sicherheitsprotokoll | Rechnung und Personalprofil als Fachobjekte (Endpunkte) | M4 |
| E2E-096 | Konfigurationsänderung ab Montag: Sonntag alter Wert, ab Montag neuer Wert, Zusage unverändert, Mandant B unberührt | Sonntagsfahrten und künftige Angebote an Buchungen | M2 |
| E2E-097 | Negative Wartezeit, abgeschaltete Pflichtkontrolle (Sicherheitsinvariante) und unbekannter Parameter werden von der Datenbank abgewiesen; Anwendung (422) und UI-Sperre | Wirkung auf laufenden Betrieb | M2/M3 |
| E2E-098 | Rollback als neue Revision, fehlerhafte Revision bleibt lesbar, Historie unveränderlich, Zusage bleibt; Audit und Parallelität (Backend-Tests) | Rollback einer Tarifversion mit Buchungen | M2 (Tarif) |

## Semantische Contract-Fälle und UI-Abnahmefälle (CI-Pflicht in M1, AK 5)

| Fall | Nachweis |
|---|---|
| „100 identische Webhooks“ | `InboxProcessorTest` (100 identische Zustellungen), `InboundWebhookHttpTest.hundert_identische_eingaenge_…`, `ProviderEventApiTest.hundert_identische_webhooks_parallel_…`, Gate AT-24 |
| „Manipulation tenant_id“ | `ProviderEventsTest.mandant_folgt_nur_aus_dem_schluessel_manipulierte_tenant_id_ist_wirkungslos`, `DriverApiTest.rechte_fremde_schicht_fremder_mandant_und_tenant_id_im_body`, `TenantSwitchApiTest` |
| „unzulässige Fremd-ID“ | `CrossTenantIsolationTest.fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen` (alle gebauten Endpunkte mit Pfadparameter), Gate PH-AT-001/AT-18 |
| UI „Mandantenwechsel mit zwei Tabs“ | `web/api-client/src/auth/auth.test.tsx` („zwei Tabs …“) |
| UI „412-Konflikt ohne stilles Überschreiben“ | `web/operator/src/identity/CompanyProfileForm.test.tsx` („412-Konflikt lädt neu statt zu überschreiben“), `RevisionFreigabe.test.tsx` |

## Invarianten aus Kap. 04 §4.5 mit zwei parallelen Transaktionen (AK 6)

`db/tests/test_m1_invarianten_parallel.py` (Runtime-Rolle, je ein Thread und eine Verbindung je Transaktion): Cross-Tenant-Referenz, kein Kontext keine Zeile, Gegenereignis statt Änderung (`audit_event`), nicht überlappende Policyversion (`config_revision`), Idempotenz. Weitere parallele Negativtests je Baustein stehen in den Tests der jeweiligen Arbeitspakete (z. B. `test_m1_22_publikation.py`, `test_m1_06_mitgliedschaft.py`).

## Ergebnisse und Grenzen

- Evidenz: `tests/acceptance/evidence/m1/<Test-ID>-<Lauf-ID>.json` (Schema `schema/evidence.schema.json`, ohne Personendaten, Entscheidung `PENDING`); Zusammenfassung `zusammenfassung.json`. `docs/pflichtenheft/e2e.csv` wird nur von `m1_gate/run.py --write-e2e` aus einem Lauf befüllt, `m1_gate/traceability.py` prüft das (R3).
- Build-ID: Git-Commit des geprüften Standes (`+lokal`, wenn der Lauf auf nicht eingecheckten Änderungen stand).
- Browser-End-to-End (Playwright o. Ä.) gehört nicht zu M1; die UI-Fälle laufen als Komponententests (`npm test`).
- Nicht ausgeführt in dieser Umgebung: Gate-Läufe gegen PostgreSQL 17 und gegen Keycloak (CI-Umgebung); die Szenarien nutzen die lokale PostgreSQL 16.
