"""WP-M1-47: der Test-Empfänger (lokal/CI) nimmt Alarme per HTTPS an, antwortet bei Fehlerbetrieb mit 503 und lehnt Klartext ab.
Er ist der Gegenpart der Alarmweiterleitung; die Zustellung aus dem Backend prüft OperatorAlertServiceTest (Kotlin)."""
import json
import ssl
import sys
import unittest
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "infra" / "alerting"))
import test_receiver  # noqa: E402


def post(port, cert, payload):
    ctx = ssl.create_default_context(cafile=str(cert))
    req = urllib.request.Request(f"https://127.0.0.1:{port}/alert", json.dumps(payload).encode(), {"Content-Type": "application/json"})
    return urllib.request.urlopen(req, context=ctx, timeout=5).status


class Empfaenger(unittest.TestCase):
    def test_testwarnung_wird_zugestellt_und_ohne_nutzdaten_im_klartext(self):
        srv, got, cert = test_receiver.serve()
        self.addCleanup(srv.shutdown)
        self.assertEqual(post(srv.server_address[1], cert, {"dedup_key": "outbox-relay/dead_letter"}), 204)
        self.assertEqual(got, [{"dedup_key": "outbox-relay/dead_letter"}])
        with self.assertRaises((urllib.error.URLError, ConnectionError, OSError)):
            urllib.request.urlopen(f"http://127.0.0.1:{srv.server_address[1]}/alert", b"{}", timeout=3)
        self.assertEqual(len(got), 1, "Klartext-Anfrage wurde nicht verarbeitet")

    def test_kanal_nicht_erreichbar_antwortet_503_und_danach_ok(self):
        srv, got, cert = test_receiver.serve(fail_first=2)
        self.addCleanup(srv.shutdown)
        for _ in range(2):
            with self.assertRaises(urllib.error.HTTPError) as e:
                post(srv.server_address[1], cert, {"a": 1})
            self.assertEqual(e.exception.code, 503)
        self.assertEqual(post(srv.server_address[1], cert, {"a": 1}), 204)
        self.assertEqual(len(got), 1)


if __name__ == "__main__":
    unittest.main()
