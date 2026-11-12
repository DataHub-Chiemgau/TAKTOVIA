"""WP-M4-02: OCR-Simulator, ERP-Empfangssimulator und Malware-Testdatei (nur Testinfrastruktur)."""
import json
import sys
import unittest
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import m4_sim as sim  # noqa: E402


def post(server, path, body):
    req = urllib.request.Request(f"http://127.0.0.1:{server.server_address[1]}{path}", json.dumps(body).encode(), {"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read(); return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read(); return e.code, (json.loads(raw) if raw else {})


class Ocr(unittest.TestCase):
    def test_ok_falscher_betrag_und_niedrige_konfidenz_sind_steuerbar_und_deterministisch(self):
        o = sim.OcrSimulator()
        ok = o.extract("d1", "tankbeleg_ok")
        self.assertEqual(ok["fields"]["brutto_minor"]["value"], 11900); self.assertFalse(ok["review_required"])
        wrong = o.extract("d1", "falscher_betrag")
        self.assertEqual(wrong["fields"]["brutto_minor"]["value"], 119000); self.assertEqual(wrong["fields"]["netto_minor"]["value"], 100000)   # Zehnerfehler
        low = o.extract("d1", "niedrige_konfidenz")
        self.assertTrue(low["review_required"]); self.assertLess(low["fields"]["brutto_minor"]["confidence"], sim.CONFIDENCE_THRESHOLD)
        self.assertEqual(o.extract("d1", "tankbeleg_ok"), ok, "gleiches Szenario, gleiches Ergebnis")
        self.assertTrue(o.extract("d2")["review_required"], "ohne Zuordnung nicht lesbar, Prüfung erzwungen")
        with self.assertRaises(KeyError): o.extract("d3", "gibt_es_nicht")

    def test_aehnlicher_beleg_hat_gleichen_betrag_aber_anderes_datum(self):
        o = sim.OcrSimulator(); a, b = o.extract("x", "tankbeleg_ok")["fields"], o.extract("y", "aehnlicher_beleg")["fields"]
        self.assertEqual(a["brutto_minor"]["value"], b["brutto_minor"]["value"]); self.assertNotEqual(a["datum"]["value"], b["datum"]["value"])

    def test_http_zuordnung_je_dokument(self):
        server, _, _ = sim.serve()
        try:
            self.assertEqual(post(server, "/control/scenarios", {"document_ref": "beleg-1", "scenario": "falscher_betrag"})[0], 204)
            s, out = post(server, "/v1/extract", {"document_ref": "beleg-1"})
            self.assertEqual(s, 200); self.assertEqual(out["fields"]["brutto_minor"]["value"], 119000)
            self.assertEqual(post(server, "/v1/extract", {"document_ref": "b", "scenario": "xx"})[0], 422)
        finally:
            server.shutdown()

    def test_nur_loopback(self):
        with self.assertRaises(ValueError): sim.serve("0.0.0.0")


class Erp(unittest.TestCase):
    BATCH = {"batch_id": "b-1", "entries": [{"legal_entity": "le-y", "currency": "EUR", "debit_minor": 10000, "credit_minor": 0}, {"legal_entity": "le-y", "currency": "EUR", "debit_minor": 1900, "credit_minor": 0},
                                              {"legal_entity": "le-y", "currency": "EUR", "debit_minor": 0, "credit_minor": 11900}]}

    def test_ausgeglichen_je_rechtstraeger_und_waehrung_idempotent(self):
        e = sim.ErpSimulator()
        self.assertEqual(e.receive(self.BATCH)[0], 201)
        s, out = e.receive(self.BATCH); self.assertEqual((s, out["replayed"]), (200, True)); self.assertEqual(len(e.posted), 1, "keine zweite Buchung")
        changed = json.loads(json.dumps(self.BATCH)); changed["entries"][0]["debit_minor"] = 9999
        self.assertEqual(e.receive(changed)[0], 422)   # unausgeglichen
        other = dict(self.BATCH, entries=self.BATCH["entries"][:2] + [dict(self.BATCH["entries"][2], credit_minor=11900, legal_entity="le-x")])
        self.assertEqual(e.receive(dict(other, batch_id="b-2"))[1]["code"], "UNBALANCED", "nur global ausgeglichener Satz wird abgewiesen")
        conflict = dict(self.BATCH, entries=self.BATCH["entries"][:2] + [dict(self.BATCH["entries"][2], credit_minor=11900, currency="EUR")], extra=1)
        self.assertEqual(e.receive(conflict)[0], 409)

    def test_geld_nur_ganzzahlig_und_fehlermodi(self):
        e = sim.ErpSimulator()
        bad = {"batch_id": "b-3", "entries": [{"legal_entity": "le-y", "currency": "EUR", "debit_minor": 100.5, "credit_minor": 0}]}
        self.assertEqual(e.receive(bad)[1]["code"], "NOT_INTEGER_MINOR")
        e.fault = ("unavailable", 1)
        self.assertEqual(e.receive(self.BATCH)[0], 503); self.assertEqual(e.receive(self.BATCH)[0], 201, "Fehlermodus wirkt genau einmal")

    def test_http(self):
        server, _, erp = sim.serve()
        try:
            self.assertEqual(post(server, "/v1/ledger-batches", self.BATCH)[0], 201); self.assertEqual(post(server, "/v1/ledger-batches", {"batch_id": "x"})[0], 422)
            self.assertEqual(len(erp.posted), 1)
        finally:
            server.shutdown()


class Malware(unittest.TestCase):
    def test_eicar_ist_der_standardstring_und_wird_nicht_eingecheckt(self):
        b = sim.eicar_bytes()
        self.assertEqual(len(b), 68); self.assertTrue(b.startswith(b"X5O!P%@AP")); self.assertTrue(b.endswith(b"TEST-FILE!$H+H*"))
        root = Path(__file__).resolve().parents[3]
        for p in (root / "tests" / "sandbox" / "m4").glob("*.py"):
            self.assertNotIn(b, p.read_bytes(), f"{p.name} enthält den EICAR-String am Stück")
        self.assertTrue(sim.clean_pdf_bytes().startswith(b"%PDF-"))
        self.assertNotIn(b"EICAR", sim.clean_pdf_bytes())


if __name__ == "__main__":
    unittest.main()
