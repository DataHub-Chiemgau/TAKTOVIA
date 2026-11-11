# Spikes M0 (Messberichte)

| Spike | WP | Bericht | Status |
|---|---|---|---|
| (a) Hold/Confirm unter 100 parallelen Transaktionen auf PostGIS | WP-M0-26 | [spike-a-hold-confirm.md](spike-a-hold-confirm.md), Rohdaten in `spike-a-data/` | ausgeführt (eigener PostgreSQL-Cluster, 100 Verbindungen) |
| (d) PSP-Sandbox | WP-M0-29 | [spike-d-psp-sandbox.md](spike-d-psp-sandbox.md) | Gegen reale Sandbox nicht ausführbar; Port, Konformitätsprüfung (Simulator-Ergebnis), Testplan, Raster |
| (e) Routingvergleich | WP-M0-30 | [spike-e-routingvergleich.md](spike-e-routingvergleich.md) | Anbietervergleich nicht ausführbar; Port, Stub, Katalog, Raster |

Die Spikes (b) und (c) (WP-M0-27, -28) gehören nicht zu diesem Paket. Berichtsformat und Kopffelder: [`../templates/messbericht-vorlage.md`](../templates/messbericht-vorlage.md).
Die Spike-Code-Verzeichnisse (`spike_a/`, `spike_d/`, `spike_e/`) sind Wegwerf-Prototypen und nicht Produktivcode.
