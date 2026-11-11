# config – Konfiguration und Freigaben

Paket `de.taktovia.config` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Konfigurationsrevision, Freigabe; Tabellen `config_revision`, `policy_schema`, `policy_schema_version`.

## Stand

- **Parameterkatalog und Systemgrenzen (WP-M1-19):** `ConfigApi.parameters` liest den Katalog (`policy_schema`, global, nur per Migration änderbar) und prüft Werte gegen Name, Ebene,
  Datentyp, Einheit, Bereich (= Systemgrenze) und Sicherheitsinvarianten; Verstöße sind 422 mit Feldverletzungen und erzeugen keine Zeile. Die Datenbank prüft dieselben Regeln noch einmal.
- **Revisionen (WP-M1-20):** `ConfigApi.revisions` legt Entwürfe an und ändert sie (Ersteller aus dem Principal, Grund und Beginn Pflicht, `If-Match`), liest, listet, liefert das
  Änderungsprotokoll mit Diff Alt/Neu und rekonstruiert den zu einem Zeitpunkt wirksamen Stand (`asOf`). Publizierte Revisionen sind in der Datenbank unveränderlich. `revalidate` prüft einen Entwurf
  gegen die jetzt gültige Schema-Version (Baustein für die Publikation, WP-M1-22).
- Details und Entscheidungen (OP-03, OP-08, OP-18): [konfigurationsrevisionen.md](../../../docs/umsetzungsplan/vertraege/konfigurationsrevisionen.md).

Tests: `ParameterRegistryTest`, `ParameterRegisterTest`, `ConfigRevisionTest` (echte Datenbank, Runtime-Rolle; Zugangsdaten wie `backend/README.md`), dazu `db/tests/test_policy_schema.py`,
`db/tests/test_config_revision.py` und in der Anwendung `ConfigApiTest`.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.
