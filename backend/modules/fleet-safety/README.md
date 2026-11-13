# fleet-safety – Flotte und Sicherheitsfreigabe

Paket `de.taktovia.fleetsafety` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Vehicle, Inspection, Defect, Release; Tabellen vehicle, resource_release.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

**Fahrzeugpool (WP-M3-06):** Port `FleetsafetyApi.vehiclePool` (`VehiclePool`): Dienstzuordnung mit Intervall und Gebietsbezug auf `vehicle_pool_assignment`; liest zum Hinweis beim Beenden die Tabellen `booking`, `capacity_hold` und `vehicle_run` des Moduls `booking-dispatch` nur lesend (wie der Wirkungsanbieter in `service-geo`).
## Schadenakte (WP-M3-43, -44, -47, -48)

`api.Defects` (Meldung mit Fallnummer, Statusautomat nach `zustaende/defect_case.md`, Nachtrag, Historie, Stand zum Zeitpunkt t) und `api.DefectEvidenceUploads` (Uploadticket, Quarantäne, Medienprüfung, signierter Abruf, Integritätsprüfung). Objektspeicher, Medienprüfer und Linkschlüssel liefert die Anwendung (`DefectEvidenceWiring`); Zugriffe auf Speicher und Prüfer laufen nie innerhalb einer SQL-Transaktion. Die Prüf- und Integritätsläufe (`reviewPending`, `verifyIntegrity`) sind je Mandant aufrufbar; ihr Zeitplan ist ein Betriebsauftrag (nicht ausgeführt). Siehe [m3-schadenakte.md](../../../docs/umsetzungsplan/vertraege/m3-schadenakte.md).

## Bekannte Altschäden (WP-M3-21)

`api.KnownDefects` (Fahrerprojektion offener Akten je Prüflauf und je Fahrzeug, Quittierung ohne Verursacherangabe, `requireAllAcknowledged` für `submitInspection`); Hinweis auf offene Fälle vor der Neuanlage (`createDefect` eines Fahrers: 409 `KNOWN_OPEN_DEFECTS`, bestätigbar mit `confirm_new_despite_known`). Regel „Altschaden“ als Arbeitsannahme in `KnownDefectCatalog` und `db/migrations/V30210__bekannte_altschaeden_quittierung.sql`. Verträge: `docs/umsetzungsplan/vertraege/LH-6.21-CHK-012.md`, `LH-6.22-DMG-012.md`.

## Klassifikation, Sofortsperre, Schadenliste, Reparatur und Wiederfreigabe (WP-M3-45, -46, -49, -51)

- `api.DefectClassification` (Regelmatrix `dmg-klassen-v1`, reine Funktion) und `api.DefectSettings` (`workshop_auto_block_severity`, nur die vier Klassen, `SAFETY_CRITICAL` sperrt immer). `Defects.report` klassifiziert bei der Anlage, `Defects.triage` bestätigt oder korrigiert (Korrektur mit Grund, Herabstufung nur Werkstatt) und führt den Folgestatus.
- `internal.CriticalDefectBlocker`: sperrende Einstufung = im selben Commit Sperre über den Sperrkern (Quelle `SAFETY`, Bezug Fallnummer), Widerruf der Einsatzfreigaben, Alarm `VEHICLE_BLOCK_CRITICAL` nach der wirksamen Alarmrichtlinie (bereits eskaliert, mit Fallnummer und Zahl der betroffenen Fahrten) und Störungsablauf (`incident` mit Verknüpfung zu Akte, Alarm und betroffenen Umläufen). Sperrreihenfolge: Akte, Auftrag, Fahrzeug.
- `Defects.listPage` (`internal.JdbcDefectList`): sechs Filter, Sortierung `(Wert, id)`, Keyset-Paginierung mit Stand der ersten Seite (Status, Klasse, Zuständiger aus den Historien), Dienstscope, Projektion ohne Beschreibung und vertrauliche Inhalte.
- `RepairOrders` und `VehicleReleases` (`internal.JdbcRepairRelease`): Reparaturauftrag mit Nachweis, Wiederfreigabe mit Vieraugenpflicht (`If-Match` = `safety_revision`), Fehlversuche in eigener Transaktion (`ReleaseAttempts`). Die Entscheidung hebt nur die Sperre ihrer Akte auf und erteilt keine Einsatzfreigabe; eine davor abgeschlossene Prüfung begründet keine neue (`JdbcReadiness.blockers`).
Verträge: `docs/umsetzungsplan/vertraege/m3-schadenakte.md` §7, §8 und `m3-wiederfreigabe.md`.
