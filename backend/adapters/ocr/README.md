# adapter ocr

OCR-Adapter. Paket `de.taktovia.adapters.ocr`. Externe Aufrufe nie innerhalb einer offenen SQL-Transaktion (Leitlinie 3).
Capability-Nachweis und Sandbox-Tests je Operation vor Aktivierung (Kap. 08 §8.4).

## Stand (WP-M4-27)

Port `OcrAdapter` (Engine, Version, `recognize` mit Zeilen, Konfidenz und Fundstelle) und die lokale Umsetzung `PdfTextLayerOcr` (liest die Textebene textbasierter PDF-Belege, keine Bild-OCR; Rasterbilder und PDFs ohne Text: `OcrUnsupportedException`). Eine Bild-OCR-Engine und die Datenschutzprüfung eines externen Dienstes sind offen (D-06, Offener Punkt 11). Die Anwendung verbindet den Adapter über `OcrBridge` mit dem Port `ReceiptRecognizer` des Belegmoduls; die Feldextraktion aus dem erkannten Text liegt im Belegmodul. Vertrag: [m4-cluster-c10.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-c10.md).
