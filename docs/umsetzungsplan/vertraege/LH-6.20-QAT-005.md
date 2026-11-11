# LH-6.20-QAT-005 – Stammdateneinspielung vor Betriebsstart mit Migrationsprotokoll

- **Verantwortlicher:** Rolle Plattform (Einspielwerkzeug, Modul `migration`)
- **Arbeitspaket:** WP-M5-25
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Vor Betriebsstart werden Bediengebiet, Tarif, Betreiber, Fahrzeuge und Benutzer datenkonsistent eingespielt.

**Originalabnahme (unverändert):** Migrationsprotokoll bestätigt Soll-/Ist-Bestände und Fehlerliste.

## Vertrag

- **Tabellen / DDL:** keine direkte SQL-Befüllung. Hinweis: `tariff_revision`, `tariff_rule`, `driver`, `qualification_revision` sind in Kap. 04 als Tabellenfamilien (PAY, DRV/FLT) genannt, aber weder in `schema/kernschema.sql` noch in `db/migrations/V1`..`V3` angelegt; ihre Anlage und Spalten kommen aus WP-M2-07 bzw. WP-M3-05 und sind Voraussetzung, hier nicht erfunden. Im Kernschema trägt `vehicle` `owner_org_ref` (Betreiber) und `capacity_config` (Ressourcenvektor), aber keine Dienst-Spalte; die Dienstzuordnung des Fahrzeugs ist im Bau (WP-M3-03) festzulegen. `service_area.area` ist `geometry(MultiPolygon,4326)` mit `CHECK(ST_IsValid(area))`, `stop_revision.point` `geometry(Point,4326)`; Zielbestände in `tenant`, `legal_entity`, `organization`, `membership`, `service`, `service_area`, `stop_revision`, `tariff_revision`, `tariff_rule`, `vehicle`, `driver`, `qualification_revision`, `config_revision`. Das Migrationsprotokoll wird als Dateiartefakt je Lauf geführt (Quelle, Prüfsumme, Soll-Anzahl je Objektart, Ist-Anzahl nach Lauf, abgelehnte Datensätze mit Grund, Build, Migrationsstand, Ausführender, Freigeber); ob eine eigene Tabelle nötig ist, legt der Bau fest (keine neue Migration vorab). Einspielung unter einer Rolle mit auf den Zielmandanten begrenzten Rechten (RLS `tenant_isolation`), Nachvollzug in `audit_event`.
- **API:** Einspielwerkzeug nutzt ausschließlich die fachlichen Schreibwege: `/v1/services`, `/v1/service-areas`, `/v1/stops`, Tarifpflege, Fahrzeug-/Fahrerstamm, Mitgliedschaften; Benutzer über den IdP. Kap. 05 §5.7 nennt keine Importendpunkte, es wird keiner erfunden. Modi: Trockenlauf, Wiederaufnahme, Idempotenz je Datensatz.
- **Reihenfolge der Objektarten (Abhängigkeiten):** Betreiber (`legal_entity`, `organization`) vor Bediengebiet/Dienst, Haltepunkte nach Gebiet, Tarif nach Dienst, Fahrzeuge nach Betreiber und Dienst, Benutzer mit `membership` und Rolle zuletzt.
- **Konsistenzregeln:** keine verwaisten Referenzen; Bediengebiet = valides Polygon; Haltepunkt im Gebiet; Tarif mit Gültigkeitsintervall; Fahrzeug mit Ressourcenvektor, einem Betreiber und Dienst zugeordnet; Kennzeichen eindeutig; Benutzer mit Mitgliedschaft, Rolle und Mandant. Erstwerte der Pilotparameter als freigegebene `config_revision` (fachlich freizugeben, Kap. 01 §1.6).
- **Zustandsautomat:** keiner neu (Lauf: Trockenlauf, Lauf, abgebrochen, fortgesetzt, abgeschlossen als Werkzeugzustand, im Bau festzulegen). **JSON-Schema / Ereignisse:** Quellformat je Objektart nicht festgelegt (OP-18); Schemas später unter `vertraege/schemas/`.
- **Parameter und Kennzahlen:** keine neuen. **Oberfläche:** keine; Protokoll als Dokument.
- **Datenschutz:** Quelldateien mit Personendaten (Benutzer, Fahrer) nur in der Zielumgebung, nicht im Repository oder in Logs, Löschung nach dokumentierter Frist; Protokoll enthält Zählwerte und Kennungen, keine Kontaktdaten.
- **Entscheidungen:** D-05 (Pilotbetreiber, Region, Flotte) blockiert nur die Einspielung der echten Pilotdaten, nicht Bau und Abnahme mit Referenzdaten; D-01 (Rollen der Rechtsträger) blockiert nur die Produktivschaltung.
- **Offene Punkte:** OP-18 (Quellsysteme, Dateiformate, Mengengerüst unbekannt; für Benutzer offen, ob im IdP angelegt, eingeladen oder importiert; ob Fahrer und Schichten zum Umfang zählen, Schichten sind ausgeschlossen); Löschfrist der Quelldateien ist festzulegen (D-11). Blockiert den Bau mit Referenzdaten nicht.

## Tests

- **Positiv:** vollständige Einspielung eines Referenzdatensatzes; Protokoll bestätigt je Objektart (Bediengebiet, Tarif, Betreiber, Fahrzeuge, Benutzer) Soll- und Ist-Bestand, Soll = Ist oder jede Differenz einem Fehlereintrag zugeordnet; zweiter Lauf derselben Quelle ändert nichts (idempotent); abgebrochener Lauf ist fortsetzbar; anschließende Probebuchung im eingespielten Gebiet mit Tarif und Fahrzeug gelingt.
- **Negativ:** ungültige Geometrie, Haltepunkt außerhalb des Gebiets, doppeltes Kennzeichen, Tarif ohne Gültigkeit, Benutzer ohne Rolle: jeweils Ablehnung mit Fehlerlisteneintrag, übrige Datensätze unberührt; Einspielung für Mandant A erzeugt nichts in Mandant B und scheitert mit der Rolle des falschen Mandanten; Protokoll und Logs enthalten keine Kontaktdaten.
- **Konkurrierend (Datentrennung):** Einspielung zweier Mandanten parallel; Einspielung parallel zu laufendem Buchungsbetrieb des anderen Mandanten (keine Beeinflussung, keine Datenvermischung).
- **Referenztests:** keine Katalog-ID im WP genannt (keine erfunden); Probebuchung wiederholt den Grundablauf von AT-01.
- Testfälle: geplant:tests/acceptance/test_stammdaten_migration.py::test_referenzdatensatz_soll_ist_im_migrationsprotokoll; geplant:tests/acceptance/test_stammdaten_migration.py::test_ungueltige_datensaetze_landen_in_fehlerliste_uebrige_unberuehrt; geplant:tests/acceptance/test_stammdaten_migration.py::test_zwei_mandanten_parallel_keine_datenvermischung; geplant:tests/acceptance/test_stammdaten_migration.py::test_einspielung_parallel_zu_buchungsbetrieb_anderer_mandant; geplant:tests/acceptance/test_stammdaten_migration.py::test_rolle_falscher_mandant_scheitert_und_audit_event; geplant:tests/acceptance/test_stammdaten_migration.py::test_konsistenz_keine_verwaisten_referenzen; geplant:tests/acceptance/test_stammdaten_migration.py::test_abgebrochener_lauf_fortsetzbar; geplant:tests/acceptance/test_stammdaten_migration.py::test_protokoll_und_logs_ohne_kontaktdaten; geplant:tests/acceptance/test_stammdaten_migration.py::test_probebuchung_im_eingespielten_gebiet; geplant:tests/acceptance/test_stammdaten_migration.py::test_zweiter_lauf_idempotent

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

Zweitprüfung erforderlich.
