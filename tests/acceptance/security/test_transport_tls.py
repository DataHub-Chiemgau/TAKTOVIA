"""WP-M1-36 (LH-6.18-SEC-002). Zwei Gruppen:

1. Selbsttests des TLS-Scanners gegen lokale Testserver (echte Handshakes): beweisen, dass der Scanner unsichere Versionen und
   Klartext-Nutzdaten findet und saubere Endpunkte nicht beanstandet.
2. Scan echter Endpunkte (`TAKTOVIA_TLS_TARGETS=host:port,…`, z. B. lokale Compose-Umgebung oder Staging). NICHT AUSGEFÜHRT in
   Umgebungen ohne Docker/Staging: der Test wird dann ausdrücklich übersprungen und nicht als bestanden gezählt.

Das Backend selbst (HTTPS, HSTS, keine alten Protokolle, Klartextantwort, Start-Abbruch bei Klartext-Konfiguration) wird von
backend/app/src/test/kotlin/de/taktovia/app/TransportSecurityTest.kt mit echten Handshakes geprüft.
"""
import os
import ssl
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[2] / "tools"))
import tls_scan  # noqa: E402
from _tls_server import TlsServer  # noqa: E402

TARGETS = [t for t in os.environ.get("TAKTOVIA_TLS_TARGETS", "").split(",") if t]


class ScannerSelbsttest(unittest.TestCase):
    def test_tls_scan_keine_unsicheren_protokollversionen(self):
        s = TlsServer(ssl.TLSVersion.TLSv1_2)
        self.addCleanup(s.close)
        r = tls_scan.scan("127.0.0.1", s.port)
        self.assertEqual(r["unsafe"], [])
        self.assertIn("TLSv1.2", r["accepted"])
        self.assertTrue(r["ok"], r)

    def test_scanner_findet_server_mit_alten_protokollversionen(self):
        s = TlsServer(ssl.TLSVersion.TLSv1)
        self.addCleanup(s.close)
        r = tls_scan.scan("127.0.0.1", s.port)
        if {"TLSv1", "TLSv1.1"} <= set(r["not_testable"]):
            self.skipTest("lokale OpenSSL-Bibliothek bietet TLS 1.0/1.1 nicht an (nicht prüfbar)")
        self.assertTrue(r["unsafe"], r)
        self.assertFalse(r["ok"])

    def test_klartext_http_und_dienstverbindungen_abgewiesen(self):
        # Klartext-Anfrage an einen TLS-Port liefert keine Nutzdaten; ein Server, der Klartext beantwortet, wird vom Scanner beanstandet.
        s = TlsServer(ssl.TLSVersion.TLSv1_2)
        self.addCleanup(s.close)
        self.assertEqual(tls_scan.plaintext_http_payload("127.0.0.1", s.port), "")
        leaky = TlsServer(ssl.TLSVersion.TLSv1_2, plaintext_ok=True)   # Server, der Klartext beantwortet
        self.addCleanup(leaky.close)
        r = tls_scan.scan("127.0.0.1", leaky.port)
        self.assertFalse(r["ok"])
        self.assertIn("hello", r["plaintext"])


@unittest.skipUnless(TARGETS, "NICHT AUSGEFÜHRT: TAKTOVIA_TLS_TARGETS nicht gesetzt (keine Compose-/Staging-Umgebung erreichbar)")
class EchteEndpunkte(unittest.TestCase):
    def test_endpunkte_ohne_unsichere_protokolle_und_ohne_klartext(self):
        for t in TARGETS:
            host, _, port = t.rpartition(":")
            r = tls_scan.scan(host, int(port))
            self.assertTrue(r["ok"], f"{t}: {r['accepted']} unsicher={r['unsafe']}")


if __name__ == "__main__":
    unittest.main()
