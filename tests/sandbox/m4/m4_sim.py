"""Testsimulatoren für M4 (WP-M4-02): OCR-Simulator mit steuerbaren Ergebnissen, ERP-Empfangssimulator und Malware-Testdatei.

Nur Testinfrastruktur (Standardbibliothek, Loopback). Ergebnisse daraus sind Simulator-Ergebnisse, kein Nachweis gegen eine echte OCR-Engine oder ein echtes ERP-System.
Alle Daten sind synthetisch (Szenarien aus `tests/acceptance/fixtures/m4_referenz/ocr-szenarien.json`).

- OCR: `POST /v1/extract` `{"document_ref": "...", "scenario": "tankbeleg_ok"}` -> `{"engine": "ocr-sim-1", "fields": {name: {"value": .., "confidence": ..}}, "review_required": bool}`.
  Ohne `scenario` gilt die Zuordnung aus `POST /control/scenarios` (je `document_ref`), sonst `nicht_lesbar`. `review_required`, wenn ein Feld unter der Konfidenzschwelle (0,80) liegt oder keine Felder gelesen wurden.
- ERP: `POST /v1/ledger-batches` `{"batch_id": "...", "entries": [{"legal_entity": .., "currency": "EUR", "debit_minor": n, "credit_minor": n}]}`; je (Rechtsträger, Währung) muss die Summe der Soll- und
  Habenbeträge gleich sein (sonst 422, nie global ausgeglichen), derselbe `batch_id` mit gleichem Inhalt wird wiederholt beantwortet (`replayed: true`, keine zweite Buchung), mit anderem Inhalt 409.
  Fehlermodus über `POST /control/faults` `{"mode": "unavailable"|"reject"|"none", "times": n}`.
- Malware-Testdatei: `eicar_bytes()` (der standardisierte, harmlose EICAR-Teststring; wird zur Laufzeit zusammengesetzt, nie als Datei eingecheckt) und `clean_pdf_bytes()`.
"""
import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

FIXTURE = Path(__file__).resolve().parents[2] / "acceptance" / "fixtures" / "m4_referenz" / "ocr-szenarien.json"
CONFIDENCE_THRESHOLD = 0.80


def eicar_bytes() -> bytes:
    """EICAR-Teststring (Antivirus-Testdatei). Geteilt, damit der Quelltext selbst kein Fund auslöst."""
    return (r"X5O!P%@AP[4\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE" + r"!$H+H*").encode("ascii")


def clean_pdf_bytes() -> bytes:
    """Kleinstes gültiges PDF (eine leere Seite), harmlos."""
    return (b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj\n"
            b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R/Size 4>>\n%%EOF\n")


def scenarios() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))["szenarien"]


class OcrSimulator:
    """Deterministische OCR: gleiches Szenario, gleiches Ergebnis (kein Zufall)."""

    def __init__(self):
        self.assignments: dict[str, str] = {}
        self.calls: list[tuple[str, str]] = []

    def assign(self, document_ref: str, scenario: str):
        if scenario not in scenarios():
            raise KeyError(scenario)
        self.assignments[document_ref] = scenario

    def extract(self, document_ref: str, scenario: str | None = None) -> dict:
        name = scenario or self.assignments.get(document_ref, "nicht_lesbar")
        s = scenarios().get(name)
        if s is None:
            raise KeyError(name)
        self.calls.append((document_ref, name))
        fields = {k: {"value": v, "confidence": s["konfidenz"]} for k, v in s["felder"].items()}
        review = not fields or s["konfidenz"] < CONFIDENCE_THRESHOLD
        return {"engine": "ocr-sim-1", "scenario": name, "fields": fields, "review_required": review}


class ErpSimulator:
    """Empfänger des Ledger-Exports: prüft je Rechtsträger und Währung auf Ausgleich, dedupliziert je Batch."""

    def __init__(self):
        self.batches: dict[str, dict] = {}
        self.posted: list[dict] = []
        self.fault = ("none", 0)

    def receive(self, batch: dict) -> tuple[int, dict]:
        mode, left = self.fault
        if left > 0 and mode != "none":
            self.fault = (mode, left - 1)
            return (503, {"code": "UNAVAILABLE"}) if mode == "unavailable" else (422, {"code": "REJECTED"})
        bid, entries = batch.get("batch_id"), batch.get("entries")
        if not isinstance(bid, str) or not bid or not isinstance(entries, list) or not entries:
            return 422, {"code": "INVALID_BATCH"}
        totals: dict[tuple[str, str], int] = {}
        for e in entries:
            d, c = e.get("debit_minor"), e.get("credit_minor")
            if not all(isinstance(x, int) and not isinstance(x, bool) and x >= 0 for x in (d, c)):
                return 422, {"code": "NOT_INTEGER_MINOR"}   # Geld nie als Gleitkomma
            key = (e.get("legal_entity"), e.get("currency"))
            totals[key] = totals.get(key, 0) + d - c
        unbalanced = sorted(f"{k[0]}/{k[1]}" for k, v in totals.items() if v != 0)
        if unbalanced:
            return 422, {"code": "UNBALANCED", "unbalanced": unbalanced}
        canonical = json.dumps(batch, sort_keys=True)
        if bid in self.batches:
            return (200, {"batch_id": bid, "replayed": True}) if self.batches[bid]["canonical"] == canonical else (409, {"code": "BATCH_CONFLICT"})
        self.batches[bid] = {"canonical": canonical}
        self.posted.append(batch)
        return 201, {"batch_id": bid, "replayed": False, "entries": len(entries)}


class _Handler(BaseHTTPRequestHandler):
    ocr: OcrSimulator
    erp: ErpSimulator

    def log_message(self, *a):  # still
        pass

    def _send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        try:
            body = json.loads(raw or b"{}")
        except ValueError:
            return self._send(400, {"code": "BAD_JSON"})
        if self.path == "/v1/extract":
            try:
                return self._send(200, self.ocr.extract(body.get("document_ref", ""), body.get("scenario")))
            except KeyError:
                return self._send(422, {"code": "UNKNOWN_SCENARIO"})
        if self.path == "/control/scenarios":
            try:
                self.ocr.assign(body["document_ref"], body["scenario"])
            except KeyError:
                return self._send(422, {"code": "UNKNOWN_SCENARIO"})
            return self._send(204, {})
        if self.path == "/v1/ledger-batches":
            status, out = self.erp.receive(body)
            return self._send(status, out)
        if self.path == "/control/faults":
            self.erp.fault = (body.get("mode", "none"), int(body.get("times", 1)))
            return self._send(204, {})
        self._send(404, {"code": "NOT_FOUND"})


def serve(host="127.0.0.1", port=0):
    """Startet beide Simulatoren in einem Loopback-Server; liefert (Server, OCR, ERP). `0.0.0.0` wird nicht akzeptiert."""
    if host not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("nur Loopback")
    ocr, erp = OcrSimulator(), ErpSimulator()
    handler = type("H", (_Handler,), {"ocr": ocr, "erp": erp})
    server = ThreadingHTTPServer((host, port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, ocr, erp


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0]); ap.add_argument("--port", type=int, default=18095)
    srv, _, _ = serve(port=ap.parse_args().port)
    print(f"OCR-/ERP-Simulator auf 127.0.0.1:{srv.server_address[1]}"); threading.Event().wait()
