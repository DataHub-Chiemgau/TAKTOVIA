"""WP-M5-03 (Akzeptanzkriterium 3): die automatisierte Prüfung findet in Metriklabels keine Kontaktdaten, Namen oder Suchbegriffe; ein Label mit
Testkontakt wird erkannt. Eingaben: die echte Scrape-Ausgabe des Fachkerns (Gradle-Test TelemetryApiTest) und die des Routing-Workers."""
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOL = REPO / "tools" / "check_metric_labels.py"
SCRAPE = REPO / "backend/app/build/diagnose/prometheus.txt"
MAIL = "erika.mustermann@example.test"


def run(*args, stdin=None):
    p = subprocess.run([sys.executable, str(TOOL), *map(str, args)], capture_output=True, text=True, input=stdin)
    return p.returncode, p.stdout + p.stderr


class Metriklabels(unittest.TestCase):
    def test_echte_scrape_ausgabe_des_fachkerns_ohne_befund(self):
        self.assertTrue(SCRAPE.is_file(), f"{SCRAPE} fehlt: zuerst den Gradle-Test TelemetryApiTest ausführen")
        text = SCRAPE.read_text(encoding="utf-8")
        self.assertIn('route_class="offer"', text, "der Test belegt nichts ohne eigene Reihen")
        code, out = run(SCRAPE)
        self.assertEqual(code, 0, out)
        self.assertRegex(out, r"\d+ Reihen geprüft, 0 Befunde")

    def test_scrape_des_routing_workers_ohne_befund(self):
        for p in (REPO / "workers/routing/src", REPO / "workers/routing/tests"):
            sys.path.insert(0, str(p))
        from fixture_region import TENANT_A, graph, profiles, stops
        from taktovia_routing.adapter import RoutingAdapter
        from taktovia_routing.diagnose import Telemetry
        from taktovia_routing.graph_provider import GraphProvider
        from taktovia_routing.server import make_server

        tel = Telemetry("taktovia-routing")
        srv = make_server(RoutingAdapter(GraphProvider(graph()), stops(), profiles()), port=0, telemetry=tel)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            port = srv.server_address[1]
            body = ('{"tenant_id":"%s","from":"H1","to":"H4","profile":"car"}' % TENANT_A).encode()
            urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{port}/v1/route", body, method="POST"), timeout=5).close()
            tel.metrics.inc("taktovia_routing_requests_total", {"route_class": MAIL})   # Kontakt als Labelwert: wird verworfen
            for _ in range(200):
                text = urllib.request.urlopen(f"http://127.0.0.1:{port}/metrics", timeout=5).read().decode()
                if "taktovia_routing_requests_total" in text:
                    break
            self.assertNotIn("mustermann", text)
            code, out = run(f"http://127.0.0.1:{port}/metrics")
            self.assertEqual(code, 0, out)
        finally:
            srv.shutdown()
            srv.server_close()

    def test_label_mit_testkontakt_wird_erkannt_und_der_wert_nie_ausgegeben(self):
        cases = {
            "verbotener Labelschlüssel": f'taktovia_booking_total{{passenger_name="Erika"}} 1',
            "E-Mail im Labelwert": f'taktovia_booking_total{{result="{MAIL}"}} 1',
            "Telefonnummer im Labelwert": 'taktovia_booking_total{reason="+49 176 12345678"} 1',
            "IBAN im Labelwert": 'taktovia_booking_total{reason="DE89 3704 0044 0532 0130 00"} 1',
            "Kartennummer im Labelwert": 'taktovia_booking_total{reason="4111111111111111"} 1',
            "Adresse im Labelwert": 'taktovia_booking_total{reason="Musterstraße 12"} 1',
            "Labelschlüssel nicht in der Positivliste": 'taktovia_booking_total{ort="Berlin"} 1',
            "verbotener Labelschlüssel ": 'taktovia_search_total{search_query="Hauptbahnhof"} 1',
        }
        for rule, line in cases.items():
            code, out = run("-", stdin="# HELP x\n" + line + "\n")
            self.assertEqual(code, 1, line)
            self.assertIn(rule.strip(), out)
            for w in ("Erika", MAIL, "12345678", "3704", "4111", "Musterstraße", "Hauptbahnhof", "Berlin"):
                self.assertNotIn(w, out, "Ausgabe nennt nie den Wert")

    def test_technische_labels_werden_nicht_beanstandet(self):
        ok = ('taktovia_outbox_depth{tenant_id="a0000000-0000-4000-8000-0000000000a1"} 3.0\n'
              'taktovia_http_server_request_seconds_bucket{le="0.5",method="GET",route_class="offer",status_class="2xx"} 7\n'
              'hikaricp_connections_active{pool="HikariPool-1"} 1\n'
              'jvm_gc_pause_seconds_count{action="end of minor GC",cause="G1 Evacuation Pause"} 4\n')
        code, out = run("-", stdin=ok)
        self.assertEqual(code, 0, out)

    def test_aufruffehler(self):
        self.assertEqual(run()[0], 2)
        self.assertEqual(run("/gibt/es/nicht.txt")[0], 2)


if __name__ == "__main__":
    unittest.main()
