# adapter erp-export

ERP-/DATEV-Export. Paket `de.taktovia.adapters.erpexport`. Externe Aufrufe nie innerhalb einer offenen SQL-Transaktion (Leitlinie 3).
Capability-Nachweis und Sandbox-Tests je Operation vor Aktivierung (Kap. 08 §8.4).

## Stand (WP-M4-37)

Port `ErpExportAdapter` (Formatname, Version, `render` eines Exportbatchs: rein und deterministisch, damit die Wiederholung denselben Inhaltshash liefert) und das **neutrale Testformat** `NeutralJsonExport` (`taktovia-neutral-json` v1: kanonisches JSON mit sortierten Schlüsseln, Beträge als Ganzzahlen in kleinster Einheit). Ziel-ERP, Format und Kontenrahmen sind offen (D-06); ein DATEV-Adapter ist M8 (DOC-025). Die Anwendung verbindet den Adapter über `ErpBridge` mit dem Port `LedgerExportFormat` des Belegmoduls. Vertrag: [m4-cluster-wave11-c5.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-wave11-c5.md).
