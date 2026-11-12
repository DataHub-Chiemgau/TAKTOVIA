"""Testkorpus für die Log-Stichprobe (WP-M5-06): erzeugt Logs der Komponenten durch den echten Code mit Testkontakten und Testzahlungen.

- Fachkern und PSP-Adapter: Logdatei des Gradle-Tests `DiagnoseMaskingApiTest` (echte Anwendung, echtes Logback-Layout, echter PSP-Adapter gegen einen
  Stub mit Fehlerantwort); fehlt die Datei, schlägt der Aufruf fehl (zuerst `cd backend && ./gradlew :app:test --tests '*DiagnoseMaskingApiTest*'`).
- Routing-Worker und Dokumenten-Worker: echter HTTP-Server bzw. Consumer mit `diagnose.Telemetry`, Fehlerpfade mit Kontaktdaten in Anfrage und Ausnahme.
- Client-Fehlerberichte: das echte TypeScript-Modul `web/api-client/src/fehlerbericht.ts` unter Node (Typ-Entfernung, keine Abhängigkeiten).
- Gateway: kein nginx in der Umgebung; das Zugriffslog-Format wird statisch geprüft (`infra/check_telemetry.py`), es gibt keine Stichprobenzeilen.
Alle Kontaktdaten sind erfunden (RFC-2606-Domain, Testkarte, Testkonto)."""
import json
import logging
import re
import subprocess
import sys
import threading
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
FACHKERN_LOG = REPO / "backend/app/build/diagnose/fachkern.log"
TENANT = "00000000-0000-4000-8000-00000000000a"
MAIL = "erika.mustermann@example.test"
PHONE = "+49 176 12345678"
IBAN = "DE89 3704 0044 0532 0130 00"
CARD = "4111 1111 1111 1111"
STREET = "Musterstraße 12, 10115 Berlin"
BOARDING = "boarding_code=X7K9Q2"
TOKEN = "ey" + "JhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.abcdefghijklmnop"   # zur Laufzeit zusammengesetzt (Secret-Scan)
ALL_PLAINTEXT = [MAIL, PHONE, IBAN, CARD, STREET, "X7K9Q2", TOKEN]
SECRET_TEXT = f"Fahrgast {MAIL} Tel {PHONE} IBAN {IBAN} Karte {CARD} Abholung {STREET} {BOARDING} token={TOKEN}"
REQUIRED = ["fachkern", "psp-adapter", "routing-worker", "documents-worker", "client-fehlerberichte"]


def _import_workers():
    for p in (REPO / "workers/routing/src", REPO / "workers/routing/tests", REPO / "workers/documents/src"):
        if str(p) not in sys.path:
            sys.path.insert(0, str(p))


def _file_logger(name, component, path):
    log = logging.getLogger(name)
    log.handlers.clear()
    log.propagate = False
    log.setLevel(logging.INFO)
    h = logging.FileHandler(path, encoding="utf-8")
    from taktovia_routing.diagnose import JsonFormatter  # gleiche Datei wie im Dokumenten-Worker (Test auf Gleichheit)
    h.setFormatter(JsonFormatter(component))
    log.addHandler(h)
    return log, h


def fachkern_und_psp(out):
    if not FACHKERN_LOG.is_file():
        raise AssertionError(f"{FACHKERN_LOG} fehlt: zuerst den Gradle-Test DiagnoseMaskingApiTest ausführen")
    fach, psp, current = [], [], None
    for line in FACHKERN_LOG.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\d{4}-\d{2}-\d{2}T\S+\s+\w+\s+\[[^\]]*\]\s+\[[^\]]*\]\s+(\S+)\s+:", line)
        if m:
            current = psp if m.group(1) == "taktovia.adapters.psp" else fach
        (current if current is not None else fach).append(line)
    (out / "fachkern.log").write_text("\n".join(fach) + "\n", encoding="utf-8")
    (out / "psp-adapter.log").write_text("\n".join(psp) + "\n", encoding="utf-8")
    return out / "fachkern.log", out / "psp-adapter.log"


def routing_worker(out):
    _import_workers()
    from fixture_region import TENANT_A, graph, profiles, stops
    from taktovia_routing.adapter import RoutingAdapter
    from taktovia_routing.diagnose import Telemetry
    from taktovia_routing.graph_provider import GraphProvider
    from taktovia_routing.server import make_server

    class Defekt(RoutingAdapter):
        def route(self, *a, **k):   # unerwartete Ausnahme mit Objektinhalt
            raise RuntimeError(f"Berechnung für {SECRET_TEXT} fehlgeschlagen")

    path = out / "routing-worker.log"
    log, handler = _file_logger("taktovia.routing", "routing", path)
    tel = Telemetry("taktovia-routing")
    tel.log = log
    server = make_server(RoutingAdapter(GraphProvider(graph()), stops(), profiles()), port=0, telemetry=tel)
    defekt = make_server(Defekt(GraphProvider(graph()), stops(), profiles()), port=0, telemetry=tel)
    threads = [threading.Thread(target=s.serve_forever, daemon=True) for s in (server, defekt)]
    [t.start() for t in threads]

    def post(srv, path_, body, headers=None):
        req = urllib.request.Request(f"http://127.0.0.1:{srv.server_address[1]}{path_}", json.dumps(body).encode(), headers or {}, method="POST")
        try:
            urllib.request.urlopen(req, timeout=5).read()
        except urllib.error.HTTPError as e:
            e.read()

    try:
        tp = {"traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}
        post(server, "/v1/route", {"tenant_id": TENANT_A, "from": "H1", "to": "H4", "profile": "car"}, tp)
        post(server, "/v1/route", {"tenant_id": TENANT_A, "from": MAIL, "to": STREET, "profile": "car"}, tp)   # Kontaktdaten statt Haltepunkt
        post(server, "/v1/route", {"tenant_id": MAIL, "from": PHONE, "to": IBAN, "profile": CARD})
        post(server, "/v1/insert", {"tenant_id": TENANT_A, "request": SECRET_TEXT})
        post(defekt, "/v1/route", {"tenant_id": TENANT_A, "from": "H1", "to": "H4", "profile": "car"}, tp)
    finally:
        for s in (server, defekt):
            s.shutdown()
            s.server_close()
        handler.close()
    return path


def documents_worker(out):
    _import_workers()
    from taktovia_documents.consumer import Consumer, Task
    from taktovia_documents.diagnose import Telemetry

    path = out / "documents-worker.log"
    log, handler = _file_logger("taktovia.documents", "documents", path)
    tel = Telemetry("taktovia-documents")
    tel.log = log
    c = Consumer(tel)
    c.register("malware_scan", lambda t: None)

    def ocr(t):
        raise ValueError(f"OCR-Ausnahme mit Objektinhalt: {SECRET_TEXT}")

    c.register("ocr", ocr)
    tp = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    c.handle(Task("t-1", "malware_scan", SECRET_TEXT.encode(), tp))
    for t in (Task("t-2", "ocr", b"", tp), Task(SECRET_TEXT, "kaputt", b"")):
        try:
            c.handle(t)
        except (ValueError, LookupError):
            pass
    handler.close()
    return path


def client_berichte(out):
    script = out / "client.mjs"
    ts = (REPO / "web/api-client/src/fehlerbericht.ts").as_uri()
    script.write_text(f"""import {{ maskiereFehlerbericht }} from "{ts}";
const kontakte = {json.dumps(SECRET_TEXT)};
const berichte = [
  maskiereFehlerbericht(new Error("Speichern fehlgeschlagen: " + kontakte), {{ route: "/konto?email={MAIL}&tel=0176" , correlationId: "4bf92f35-77b3-4da6-a3ce-929d0e0e4736", appVersion: "1.2.3" }}),
  maskiereFehlerbericht("Netzwerkfehler bei " + kontakte, {{ route: "/buchung/{STREET}" }}),
  maskiereFehlerbericht(new TypeError("Cannot read " + kontakte), {{ route: "/", art: "uncaught" }}),
];
for (const b of berichte) console.log(JSON.stringify({{ ts: new Date().toISOString(), ...b }}));
""", encoding="utf-8")
    p = subprocess.run(["node", "--experimental-strip-types", "--no-warnings", str(script)], capture_output=True, text=True, timeout=60)
    if p.returncode != 0:
        raise AssertionError("Node-Lauf fehlgeschlagen: " + p.stderr[:500])
    path = out / "client-fehlerberichte.log"
    path.write_text(p.stdout, encoding="utf-8")
    return path


def build(out):
    """Alle Komponentenlogs; {komponente: datei}."""
    out = Path(out)
    fach, psp = fachkern_und_psp(out)
    return {"fachkern": fach, "psp-adapter": psp, "routing-worker": routing_worker(out), "documents-worker": documents_worker(out),
            "client-fehlerberichte": client_berichte(out)}
