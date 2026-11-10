# Befundbericht Kernschema (WP-M0-21)

Stand 10.11.2026. Das Kernschema wurde zum ersten Mal ausgeführt (PostgreSQL 16.15, PostGIS 3.4.2) und mit 49 Tests unter der nicht privilegierten Runtime-Rolle geprüft. Bisher war es nur geparst worden (Kap. 09). Die Tests liegen in `db/tests/`.

## Ergebnis

Das Entwurfs-DDL läuft ohne Änderung an den Anweisungen auf einer leeren Datenbank. 38 Tabellen, 4 Funktionen, 6 Trigger und die vier Indizes sind vorhanden. Auf den 36 Tabellen mit `tenant_id` und auf `tenant` ist RLS aktiv und erzwungen; `currency` ist bewusst global. Fehlender oder ungültiger Mandantenkontext liefert keine Zeilen beziehungsweise bricht ab. Zusammengesetzte Fremdschlüssel verhindern mandantenfremde Referenzen. Unveränderlichkeit, Journalausgleich und Periodenprüfung greifen, auch bei parallelen Transaktionen.

## Befunde

| ID | Befund | Maßnahme | Status |
|---|---|---|---|
| B-01 | Die Runtime-Rolle konnte eine geschlossene Periode per `UPDATE` wieder öffnen oder verschieben (`closed_at=NULL`, Grenzen ändern). Damit ließe sich `guard_journal()` umgehen. | `V2__haertung_periode.sql`: Trigger `fiscal_period_guard`; Regressionstest `test_05_haertung.py` | behoben |
| B-02 | Die Runtime-Rolle konnte Name und Status des eigenen Mandanten ändern. | Nur `SELECT` auf `tenant`; Verwaltung des Mandantenstamms über die Plattformverwaltung (M1) | behoben, M1 muss den Verwaltungsweg liefern |
| B-03 | Die Zeilentrigger lesen `journal` und `fiscal_period` mit `FOR UPDATE` unter Aufruferrechten. Die Runtime braucht deshalb `UPDATE` auf beide Tabellen; ohne `SECURITY DEFINER` lässt sich das nicht enger fassen. | Rechte bleiben; B-01 sperrt den kritischen Missbrauch. Offen: Spalten-`GRANT` auf `journal.state`/`posted_by` prüfen | offen (M4) |
| B-04 | `service_area_geo` und `stop_geo` sind GiST-Indizes ohne `tenant_id`. Geoabfragen filtern erst nach dem Indexzugriff nach Mandant. | Laufzeitmessung in Spike (a), WP-M0-26; gegebenenfalls `btree_gist` mit `tenant_id` | offen (Messung) |
| B-05 | Das DDL enthält `BEGIN`, `COMMIT` und `CREATE EXTENSION postgis`. Ein Migrationswerkzeug öffnet die Transaktion selbst, und Erweiterungen braucht Administratorrechte. | Entfernt beziehungsweise im Bootstrap des Administrators; V1 ist sonst zeilengleich, ein Test sichert das | übernommen |
| B-06 | `DELETE` ist für die Runtime nur auf `idempotency_record`, `inbox`, `outbox`, `journal`, `journal_line` vergeben (Aufräumen, Entwürfe). Das ist ein Vorschlag, im Pflichtenheft nicht festgelegt. | In `role_matrix.csv`; Löschjobs (M8) und Entwurfslöschung fachlich bestätigen | Vorschlag |

## Rückfluss ins Pflichtenheft

- Kap. 04 §4.5: Die Invariante „Journal ausgeglichen, offene Periode“ gilt zusätzlich nur, weil die Periode selbst unveränderlich geschlossen wird (B-01). Zeile „Kern-DDL: Enthalten“ gilt für V1 plus V2.
- Kap. 04 §4.6: Die Runtime-Rolle hat auf `tenant` nur Leserechte (B-02); Rechte je Tabelle stehen in `db/role_matrix.csv`.
- Kap. 09: Der Satz „SQL, RLS und Trigger wurden nicht ausgeführt“ gilt für das Kernschema nicht mehr. Nicht ausgeführt bleiben Last-, Restore- und Partnerprüfungen.

## Nicht geprüft

PostgreSQL 17 (CI-Lauf steht aus), Partitionierung, Lastverhalten der Sperrreihenfolge (Spike (a)), Tabellen aus Kap. 04 §4.4.
