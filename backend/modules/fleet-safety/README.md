# fleet-safety – Flotte und Sicherheitsfreigabe

Paket `de.taktovia.fleetsafety` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Vehicle, Inspection, Defect, Release; Tabellen vehicle, resource_release.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

**Fahrzeugpool (WP-M3-06):** Port `FleetsafetyApi.vehiclePool` (`VehiclePool`): Dienstzuordnung mit Intervall und Gebietsbezug auf `vehicle_pool_assignment`; liest zum Hinweis beim Beenden die Tabellen `booking`, `capacity_hold` und `vehicle_run` des Moduls `booking-dispatch` nur lesend (wie der Wirkungsanbieter in `service-geo`).
## Schadenakte (WP-M3-43, -44, -47, -48)

`api.Defects` (Meldung mit Fallnummer, Statusautomat nach `zustaende/defect_case.md`, Nachtrag, Historie, Stand zum Zeitpunkt t) und `api.DefectEvidenceUploads` (Uploadticket, Quarantäne, Medienprüfung, signierter Abruf, Integritätsprüfung). Objektspeicher, Medienprüfer und Linkschlüssel liefert die Anwendung (`DefectEvidenceWiring`); Zugriffe auf Speicher und Prüfer laufen nie innerhalb einer SQL-Transaktion. Die Prüf- und Integritätsläufe (`reviewPending`, `verifyIntegrity`) sind je Mandant aufrufbar; ihr Zeitplan ist ein Betriebsauftrag (nicht ausgeführt). Siehe [m3-schadenakte.md](../../../docs/umsetzungsplan/vertraege/m3-schadenakte.md).
