# M4: Modulzugänge, RLS und Isolationsnachweis (WP-M4-64)

**Status:** Bau-Detailvertrag zu WP-M4-64 (Enabler, keine Einzelanforderung). Fachlich nicht freigegeben: Rechtematrix Rolle × Endpunkt (OP-10), Aufbewahrung der HR- und Finanzdaten (D-11, blockiert nur die Produktivschaltung).

## Bausteine

| Ebene | Umsetzung |
|---|---|
| Modulzugänge | Drei eigene Datenbankanmeldungen: `taktovia_mod_stafftime` (Modul `staff-time`), `taktovia_mod_docsfin` (`documents-finance`), `taktovia_mod_analytics` (`analytics`); kein Superuser, kein `BYPASSRLS`, kein Eigentum, keine Rollenmitgliedschaft, kein DDL. `db.py bootstrap` legt sie an (Passwort `TAKTOVIA_DB_MODULE_PASSWORD_STAFFTIME` / `_DOCSFIN` / `_ANALYTICS`; in Staging und Produktion ausdrücklich zu setzen, sonst Abbruch, in Entwicklung und Test aus dem Runtime-Passwort abgeleitet, wie `taktovia_fields`). Bestehende Datenbanken: vor `migrate` einmal `bootstrap`. |
| Rechte | `db/migrations/R__zz_modulzugaenge_m4_64.sql` vergibt jedem Modulzugang auf die Tabellen **seines** Moduls dieselben DML-Rechte wie der Runtime-Rolle und auf alle anderen Tabellen nichts. Der Besitz folgt aus `db/table_owners.d` (von `db.py migrate` nach `taktovia_meta.table_owner` gespiegelt, nur der Migrator liest); die Datei trägt `-- repeat: always` und läuft bei jedem `migrate` erneut, damit Tabellen späterer Arbeitspakete ohne Änderung der Rechtedatei berechtigt werden. Neue Tabelle eines M4-Moduls: nur Eintrag in `db/table_owners.d/<wp>.csv` und in `db/m4_tabellenkatalog.csv`. |
| Anwendung | Die Anwendung nutzt weiter die Runtime-Rolle (ein Pool); die Modulzugänge sind für Worker und Betriebsaufgaben der Module vorgesehen (`workers/documents`, Projektionsworker). **Nicht ausgeführt:** Anbindung eigener DataSources je Modul in `app` (kein Verbraucher in P1). |
| Katalog | `db/m4_tabellenkatalog.csv`: Tabelle, Modul, Unveränderlichkeitsklasse (`APPEND_ONLY`, `FREIGABE_UNVERAENDERLICH`, `VERAENDERLICH`), HR-/Finanzspalten, Arbeitspaket. Enthält auch Tabellen, die erst spätere Arbeitspakete anlegen (dann nur Eintragsprüfung entfällt, bis sie existieren). `db/tests/test_m4_64_katalog.py` prüft für jede existierende Tabelle: RLS aktiviert und erzwungen, Policy `tenant_isolation` mit `USING` und `WITH CHECK` gegen `current_tenant()`, `tenant_id` NOT NULL, Primärschlüssel und Indizes beginnend mit `tenant_id`, Runtime weder Eigentümer noch `TRUNCATE`/`REFERENCES`/`TRIGGER`, `reject_mutation()` bzw. Schutztrigger gemäß Klasse, Eigentümer wie im Katalog; jede Tabelle eines M4-Moduls ohne Katalogeintrag lässt den Test scheitern. Roter Selbsttest gegen ein Probe-Schema (ohne RLS, ohne Policy, falscher Index, fehlender Trigger, fehlender Eigentümer). |
| Architektur | `M4TableOwnershipTest` (Backend): die drei M4-Module schreiben nur eigene Tabellen; Katalog und `table_owners.d` stimmen überein; roter Selbsttest. |
| Isolationsharness | `CrossTenantIsolationTest` führt jeden gebauten M4-Endpunkt (`/v1/absences`, `/v1/time-events`, `/v1/expenses/receipts`) mit fremder ID, manipulierter Mandantenangabe und Listen; Vollständigkeitsprüfung: ein gebauter Endpunkt ohne Fall lässt den Test scheitern. Noch nicht gebaute M4-Pfade stehen in `api/contract-pending.txt`. |

## Unveränderlichkeitsklassen und HR-/Finanzspalten

Siehe Katalog. HR-Spalten: `absence_request.kind` (Art der Abwesenheit; Dispositionssichten erhalten nur „nicht verfügbar“), `time_event.person_ref`, `driver_device.subject`. Der Schutz der Art ist eine **Projektion im Dienst** (Port liefert für Planer und Disposition ausschließlich die Restriktion ohne Art; Views und `SECURITY DEFINER` sind per Konventionstest verboten); eine Spaltenrechte-Trennung der Runtime-Rolle für `kind` ist **nicht umgesetzt** (sie würde einen zweiten Pool in `app` verlangen, siehe „Anwendung“).

## Zuordnung zu PH-AT-001 / AT-49 / E2E-091 (M4-Anteil)

Abwesenheit und Arbeitszeit (WP-M4-06, WP-M4-08) und Belegoriginal (WP-M4-21) laufen im Cross-Tenant-Scan mit; Kostenereignis, Export und Kennzahl folgen mit den Arbeitspaketen, die diese Endpunkte bauen. Das Sicherheitsereignis bei Fremdzugriff ist `security.tenant.mismatch` (manipulierte Mandantenangabe) bzw. 404 ohne Fremdwerte (fremde ID).
