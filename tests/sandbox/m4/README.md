# OCR-/ERP-Simulator und Malware-Testdatei (WP-M4-02)

Siehe Kopf von `m4_sim.py` (Aufruf, Szenarien, Fehlermodi). Test: `python -m unittest discover -s tests/sandbox/m4 -p 'test_*.py'`. Nur Testinfrastruktur, nur Loopback, ausschließlich synthetische Daten; die Szenarien stehen in `tests/acceptance/fixtures/m4_referenz/ocr-szenarien.json`. Die Malware-Testdatei (EICAR) wird zur Laufzeit zusammengesetzt und nie eingecheckt. Ergebnisse sind Simulator-Ergebnisse, kein Nachweis gegen eine echte OCR-Engine oder ein echtes ERP.
