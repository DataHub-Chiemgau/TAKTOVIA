"""WP-M5-06 (LH-6.19-NFR-015): Prüfskript "Log-Stichprobe" gegen die Logs der Komponenten aus einem Testlauf mit Testkontakten und Testzahlungen.
Grenzen: kein vollständiger E2E-Lauf in Staging (WP-M5-23), kein nginx-Gateway (Zugriffslog-Format nur statisch geprüft), keine echte Loki-Instanz."""
import json
import subprocess
import sys
import tempfile
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import korpus  # noqa: E402

REPO = korpus.REPO
TOOL = REPO / "tools" / "log_stichprobe.py"
sys.path.insert(0, str(REPO / "tools"))
import log_stichprobe  # noqa: E402


def run(*args):
    p = subprocess.run([sys.executable, str(TOOL), *map(str, args)], capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


class LogStichprobe(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dir = Path(cls.tmp.name)
        cls.logs = korpus.build(cls.dir)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def sources(self, extra=None):
        out = []
        for comp, path in {**self.logs, **(extra or {})}.items():
            out += ["--source", f"{comp}={path}"]
        return out

    def test_stichprobe_umfasst_alle_komponenten_und_protokolliert_trefferliste(self):
        report_path = self.dir / "bericht.json"
        code, out, err = run(*self.sources(), "--require", ",".join(korpus.REQUIRED), "--out", report_path)
        self.assertEqual(code, 0, out + err)
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(sorted(report["components"]), sorted(korpus.REQUIRED))
        for comp in korpus.REQUIRED:
            self.assertGreater(report["components"][comp]["lines"], 0, comp)
        self.assertEqual(report["total_lines"], sum(c["lines"] for c in report["components"].values()))
        self.assertIsNotNone(report["period"]["from"], "Zeitraum protokolliert")
        self.assertLessEqual(report["period"]["from"], report["period"]["to"])
        self.assertEqual(len(report["patterns"]), 7)
        self.assertEqual(report["findings"], [], "Trefferliste (leer) ist Teil des Berichts")
        self.assertEqual(report["missing_components"], [])
        # Der Bericht selbst enthält keinen Klartext
        text = report_path.read_text(encoding="utf-8")
        for w in korpus.ALL_PLAINTEXT:
            self.assertNotIn(w, text)

    def test_stichprobe_e2e_lauf_ohne_befund(self):
        code, out, err = run(*self.sources(), "--require", ",".join(korpus.REQUIRED))
        self.assertEqual(code, 0, out + err)
        self.assertEqual(json.loads(out)["result"], "OHNE_BEFUND")
        # Die Prüfung belegt etwas: der Korpus enthält maskierte Ersatzdarstellungen aus echten Fehlerpfaden aller Komponenten ...
        for comp in ("fachkern", "routing-worker", "documents-worker", "client-fehlerberichte"):
            self.assertIn("[maskiert:", self.logs[comp].read_text(encoding="utf-8"), comp)
        self.assertIn("PSP-Ergebnis unbestimmt", self.logs["psp-adapter"].read_text(encoding="utf-8"))
        # ... und dieselben Eingaben ungeschützt geloggt wären Treffer aller sieben Klassen (Positivkontrolle der Muster)
        klassen = {k for k, _ in log_stichprobe.classify(korpus.SECRET_TEXT)}
        self.assertEqual(klassen, {"karte", "iban", "email", "telefon", "adresse", "token", "boarding-code"})

    def test_absichtlicher_verstoss_laesst_ci_fehlschlagen(self):
        for name, line, klass in (("email", f"Kontakt {korpus.MAIL}", "email"), ("telefon", f"Rückruf {korpus.PHONE}", "telefon"),
                                  ("iban", f"Konto {korpus.IBAN}", "iban"), ("karte", f"Karte {korpus.CARD}", "karte"),
                                  ("adresse", f"Ziel {korpus.STREET}", "adresse"), ("token", f"Authorization: Bearer {korpus.TOKEN}", "token"),
                                  ("boarding", f"{korpus.BOARDING}", "boarding-code")):
            bad = self.dir / f"verstoss-{name}.log"
            bad.write_text(self.logs["fachkern"].read_text(encoding="utf-8") + f"2026-11-12T10:00:00.000Z  INFO [t] [] x.y : {line}\n", encoding="utf-8")
            report_path = self.dir / f"verstoss-{name}.json"
            code, out, _ = run("--source", f"fachkern={bad}", "--out", report_path)
            self.assertEqual(code, 1, name)
            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(report["result"], "BEFUND")
            self.assertIn(klass, {f["class"] for f in report["findings"]}, name)
            f = next(f for f in report["findings"] if f["class"] == klass)
            self.assertEqual((f["component"], len(f["hash"])), ("fachkern", 12))
            for w in korpus.ALL_PLAINTEXT:
                self.assertNotIn(w, out, "der Bericht nennt nie den Klartext")

    def test_fehlende_oder_leere_pflichtkomponente_laesst_fehlschlagen(self):
        leer = self.dir / "leer.log"
        leer.write_text("", encoding="utf-8")
        code, out, _ = run("--source", f"fachkern={self.logs['fachkern']}", "--source", f"gateway={leer}", "--require", "fachkern,gateway,routing-worker")
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(out)["missing_components"], ["gateway", "routing-worker"])

    def test_aufruffehler_exit_2(self):
        self.assertEqual(run("--source", "fachkern=/gibt/es/nicht.log")[0], 2)
        self.assertEqual(run()[0], 2)
        self.assertEqual(run("--unbekannt")[0], 2)

    def test_betriebspruefung_gegen_die_logablage_ueber_die_loki_http_api(self):
        """Gegen einen lokalen HTTP-Nachbau der Loki-API (query_range); keine echte Loki-Instanz."""
        sauber = {"fachkern": ["2026-11-12T10:00:00Z INFO ok [maskiert:email]"], "routing-worker": ["2026-11-12T10:00:01Z INFO ok " + korpus.TENANT]}
        verstoss = dict(sauber, **{"fachkern": [f"2026-11-12T10:00:00Z INFO Kontakt {korpus.MAIL}"]})

        def serve(streams):
            class H(BaseHTTPRequestHandler):
                def do_GET(self):
                    assert self.path.startswith("/loki/api/v1/query_range?")
                    body = json.dumps({"status": "success", "data": {"resultType": "streams", "result": [
                        {"stream": {"service_name": c}, "values": [[str(1731405600000000000 + i), l] for i, l in enumerate(ls)]} for c, ls in streams.items()]}}).encode()
                    self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

                def log_message(self, *a):
                    return
            srv = HTTPServer(("127.0.0.1", 0), H)
            threading.Thread(target=srv.serve_forever, daemon=True).start()
            self.addCleanup(srv.server_close)
            self.addCleanup(srv.shutdown)
            return f"http://127.0.0.1:{srv.server_address[1]}"

        code, out, err = run("--loki", serve(sauber), "--require", "fachkern,routing-worker")
        self.assertEqual(code, 0, out + err)
        code, out, _ = run("--loki", serve(verstoss), "--require", "fachkern,routing-worker")
        self.assertEqual(code, 1)
        self.assertEqual({f["class"] for f in json.loads(out)["findings"]}, {"email"})
        self.assertNotIn(korpus.MAIL, out)

    def test_client_fehlerberichte_ohne_kontaktdaten(self):
        lines = self.logs["client-fehlerberichte"].read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 3)
        for line in lines:
            rec = json.loads(line)
            self.assertTrue(set(rec) <= {"ts", "art", "meldung", "route", "correlationId", "appVersion"}, rec)
            for w in korpus.ALL_PLAINTEXT + ["Musterstraße", "mustermann", "12345678", "?"]:
                self.assertNotIn(w, line)
            self.assertEqual(log_stichprobe.classify(line), [])
        first = json.loads(lines[0])
        self.assertEqual(first["route"], "/konto")
        self.assertEqual(first["correlationId"], "4bf92f35-77b3-4da6-a3ce-929d0e0e4736")
        self.assertIn("[maskiert:email]", first["meldung"])


class WorkerKopien(unittest.TestCase):
    def test_diagnose_module_der_worker_sind_identisch(self):
        a = (REPO / "workers/routing/src/taktovia_routing/diagnose.py").read_bytes()
        b = (REPO / "workers/documents/src/taktovia_documents/diagnose.py").read_bytes()
        self.assertEqual(a, b, "Kopien auseinandergelaufen: Änderung in beiden Workern vornehmen")


if __name__ == "__main__":
    unittest.main()
