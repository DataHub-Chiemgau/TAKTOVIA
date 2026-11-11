"""WP-M2-10 / LH-6.5-OPT-007: Routingadapter auf der Referenzregion (Referenzimplementierung
``GraphProvider``).

Die Erwartungen stammen aus ``erwartete-fahrzeiten.json`` (von Hand berechnet). Die
Referenzimplementierung ist kein Kartenanbieter: die Tests belegen Port,
Fehlerverhalten und Cache, nicht das Verhalten von Valhalla in einer realen Region (der Valhalla-
Adapter läuft nur gegen einen Stub).
"""

from __future__ import annotations

import json
import threading
import time
import urllib.request
from collections.abc import Iterator
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fixture_region import (
    TENANT_A,
    TENANT_B,
    expectations,
    graph,
    graph_without_closure,
    profiles,
    stops,
)
from taktovia_routing.adapter import RoutingAdapter
from taktovia_routing.cache import CacheEntry, CacheKey, InMemoryRouteCache
from taktovia_routing.graph_provider import GraphProvider
from taktovia_routing.port import (
    Cell,
    MatrixResult,
    NoRoute,
    NotAStop,
    ProviderTimeout,
    ProviderUnavailable,
    RouteResult,
    Stop,
    UnknownProfile,
    VehicleProfile,
)
from taktovia_routing.valhalla import ValhallaAdapter

EXP = expectations()
NODE = {s.id: (s.lat, s.lon) for s in stops().values()}


def make(
    provider: GraphProvider | None = None, **kw: object
) -> tuple[RoutingAdapter, GraphProvider]:
    prov = provider or GraphProvider(graph())
    return RoutingAdapter(prov, stops(), profiles(), **kw), prov  # type: ignore[arg-type]


def rel(adapter: RoutingAdapter, rid: str, tenant: str = TENANT_A) -> RouteResult:
    r = EXP[rid]
    return adapter.route(tenant, r["from"], r["to"], r["profile"])


def test_referenzrouten_mit_erwarteten_zeiten() -> None:
    adapter, _ = make()
    for rid in ("R1", "R2", "R3", "R4a", "R4b", "R5", "R6"):
        r = rel(adapter, rid)
        assert (r.time_s, r.distance_m) == (EXP[rid]["time_s"], EXP[rid]["distance_m"]), rid
        assert r.map_version == "referenz-2026-11-01" and r.profile == EXP[rid]["profile"]


def test_gesperrter_abschnitt_wird_umfahren() -> None:
    adapter, provider = make()
    r = rel(adapter, "R3")
    # Umfahrung statt Direktweg: 240 s statt 120 s ohne Sperre
    assert r.time_s == 240 and r.distance_m == 2000
    closed = (
        NODE["H1"],
        NODE["H2"],
    )  # N1 / N3 liegen nicht benachbart; geprüft wird die gesperrte Kante N2-N3
    n2, n3 = (51.05, 13.7472), (51.05, 13.7544)
    pairs = list(zip(r.geometry, r.geometry[1:], strict=False))
    assert all({a, b} != {n2, n3} for a, b in pairs), (
        "Route darf nicht über die gesperrte Kante e23 führen"
    )
    assert r.geometry[0] == NODE["H1"] and r.geometry[-1] == NODE["H2"] and closed
    # Auch die Matrix umfährt die Sperre, in beiden Fahrtrichtungen
    m = adapter.matrix(TENANT_A, ["H1", "H2"], ["H1", "H2"], "car")
    assert m.cells[0][1] == Cell(240, 2000)
    assert m.cells[1][0] is not None and m.cells[1][0].time_s >= 240
    # Ohne Sperre (neuer Kartenstand) gilt die kürzere Verbindung: der Unterschied kommt
    # allein von der Sperre
    provider.load(graph_without_closure())
    assert rel(adapter, "R3").time_s == EXP["R3"]["zeit_ohne_sperre_s"]


def test_einbahnstrasse_profilrestriktion_und_haltepunktbindung() -> None:
    adapter, _ = make()
    # Einbahnstraße N5->N6: Hinrichtung 180 s, Gegenrichtung nur über den Umweg
    hin, zurueck = rel(adapter, "R1"), rel(adapter, "R2")
    assert hin.time_s == 180 and zurueck.time_s == 320 > hin.time_s
    n5, n6 = (51.0455, 13.7472), (51.0455, 13.7544)
    assert all(
        {a, b} != {n5, n6} for a, b in zip(zurueck.geometry, zurueck.geometry[1:], strict=False)
    )
    # Fußgänger dürfen entgegen der Kfz-Einbahnrichtung und über die Fußwegverbindung
    walk = rel(adapter, "R5")
    assert (walk.time_s, walk.distance_m) == (1000, 1200)
    # Profil mit Restriktion (3,1 m > 2,5 m) erhält eine andere Route als das Profil ohne
    car, van = rel(adapter, "R4a"), rel(adapter, "R4b")
    assert (car.time_s, van.time_s) == (120, 240) and car.geometry != van.geometry
    # Haltepunktbindung: Start und Ziel sind die Haltepunktkoordinaten
    assert hin.geometry[0] == NODE["H1"] and hin.geometry[-1] == NODE["H4"]
    assert (hin.origin, hin.destination) == ("H1", "H4")
    by_point = adapter.route_points(TENANT_A, NODE["H1"], NODE["H4"], "car")
    assert by_point.time_s == hin.time_s
    # frei gesnappte Koordinaten und unbekannte Haltepunkte werden abgewiesen
    with pytest.raises(NotAStop):
        adapter.route_points(TENANT_A, (51.0501, 13.7401), NODE["H4"], "car")
    with pytest.raises(NotAStop):
        adapter.route(TENANT_A, "H1", "H99", "car")
    with pytest.raises(UnknownProfile):
        adapter.route(TENANT_A, "H1", "H4", "tank")


class SlowProvider(GraphProvider):
    def __init__(self, delay_s: float) -> None:
        super().__init__(graph())
        self.delay_s = delay_s

    def route(self, origin: Stop, destination: Stop, profile: VehicleProfile) -> RouteResult:
        time.sleep(self.delay_s)
        return super().route(origin, destination, profile)


class DownProvider(GraphProvider):
    """Fault Injection: Kartenquelle nicht erreichbar."""

    def route(self, origin: Stop, destination: Stop, profile: VehicleProfile) -> RouteResult:
        raise ProviderUnavailable("Kartenquelle nicht erreichbar")

    def matrix(
        self, origins: list[Stop], destinations: list[Stop], profile: VehicleProfile
    ) -> MatrixResult:
        raise ProviderUnavailable("Kartenquelle nicht erreichbar")


def test_timeout_und_unerreichbares_ziel_liefern_definierten_fehler() -> None:
    # Timeout: definierter Fehler, kein geschätztes Ergebnis, nichts im Cache
    cache = InMemoryRouteCache()
    adapter = RoutingAdapter(SlowProvider(0.5), stops(), profiles(), cache=cache, timeout_s=0.05)
    with pytest.raises(ProviderTimeout) as t:
        rel(adapter, "R1")
    assert t.value.code == "PROVIDER_TIMEOUT"
    assert adapter.cached_route(TENANT_A, "H1", "H4", "car") is None
    adapter.close()
    # Ausfall der Kartenquelle (Route und Matrix)
    down, _ = make(DownProvider(graph()))
    for call in (lambda: rel(down, "R1"), lambda: down.matrix(TENANT_A, ["H1"], ["H4"], "car")):
        with pytest.raises(ProviderUnavailable) as e:
            call()
        assert e.value.code == "PROVIDER_UNAVAILABLE"
    assert down.cached_route(TENANT_A, "H1", "H4", "car") is None
    # unerreichbares Ziel (Haltepunkt auf nicht angebundenem Knoten): NoRoute statt Route
    adapter2, _ = make()
    with pytest.raises(NoRoute) as n:
        rel(adapter2, "R7")
    assert n.value.code == "NO_ROUTE"
    m = adapter2.matrix(TENANT_A, ["H1"], ["H4", "H7"], "car")
    assert m.cells[0][0] is not None and m.cells[0][1] is None, (
        "unerreichbar bleibt unerreichbar (kein Schätzwert)"
    )
    assert adapter2.cached_route(TENANT_A, "H1", "H7", "car") is None, (
        "Unerreichbarkeit wird nicht gecacht"
    )


class VersionBlindCache(InMemoryRouteCache):
    """Ein Speicher, der den Schlüssel ohne Kartenversion abbildet (z. B. fehlerhafter
    Fremdcache): liefert den zuletzt gespeicherten Wert für die Relation."""

    def get(self, key: CacheKey) -> CacheEntry | None:
        for k in list(self._data):
            if (k.tenant_id, k.origin, k.destination, k.profile) == (
                key.tenant_id,
                key.origin,
                key.destination,
                key.profile,
            ):
                return self._data[k]
        return None


def test_cache_treffer_anderer_kartenversion_wird_verworfen() -> None:
    # 1. Der Schlüssel enthält die Version: nach einem neuen Kartenstand gibt es keinen
    # Treffer, das Ergebnis kommt neu vom Anbieter
    adapter, provider = make()
    assert rel(adapter, "R3").time_s == 240
    assert adapter.cached_route(TENANT_A, "H1", "H2", "car") == Cell(240, 2000)
    provider.load(graph_without_closure())
    assert adapter.cached_route(TENANT_A, "H1", "H2", "car") is None
    again = rel(adapter, "R3")
    assert again.time_s == 120 and again.map_version == "referenz-2026-12-01"
    # 2. Auch wenn ein Speicher den alten Eintrag unter dem neuen Schlüssel liefert, prüft
    # der Adapter die Version im Eintrag und verwirft ihn
    blind = VersionBlindCache()
    stale = CacheKey(TENANT_A, "H1", "H2", "car", "referenz-2026-11-01")
    blind.put(stale, CacheEntry(Cell(240, 2000), "referenz-2026-11-01"))
    adapter2, _ = make(GraphProvider(graph_without_closure()), cache=blind)
    assert adapter2.cached_route(TENANT_A, "H1", "H2", "car") is None, (
        "Treffer mit anderer Kartenversion darf nicht verwendet werden"
    )
    m = adapter2.matrix(TENANT_A, ["H1"], ["H2"], "car")
    assert m.cells[0][0] == Cell(120, 1000) and m.map_version == "referenz-2026-12-01"
    assert blind.discarded >= 1
    # 3. Invalidierung: alte Versionen des Mandanten werden entfernt
    cache = InMemoryRouteCache()
    cache.put(CacheKey(TENANT_A, "H1", "H2", "car", "alt"), CacheEntry(Cell(1, 1), "alt"))
    cache.put(CacheKey(TENANT_B, "H1", "H2", "car", "alt"), CacheEntry(Cell(2, 2), "alt"))
    assert cache.invalidate_other_versions(TENANT_A, "neu") == 1
    assert cache.get(CacheKey(TENANT_B, "H1", "H2", "car", "alt")) is not None


def test_cache_ist_mandantengebunden_und_parallele_kartenstandwechsel_mischen_keine_versionen() -> (
    None
):
    adapter, provider = make()
    # zwei Mandanten, identische Relation und identisches Profil: getrennte Einträge
    adapter.route(TENANT_A, "H1", "H4", "car")
    assert adapter.cached_route(TENANT_A, "H1", "H4", "car") == Cell(180, 1500)
    assert adapter.cached_route(TENANT_B, "H1", "H4", "car") is None
    with pytest.raises(ValueError):
        CacheKey("", "H1", "H4", "car", "v1")
    # paralleler Kartenstandwechsel und Abfrage: nie ein Ergebnis mit gemischter Version
    v1, v2 = graph(), graph_without_closure()
    stop = threading.Event()
    bad: list[str] = []

    def switcher() -> None:
        flip = False
        while not stop.is_set():
            provider.load(v2 if flip else v1)
            flip = not flip

    def reader() -> None:
        for _ in range(300):
            try:
                m = adapter.matrix(TENANT_A, ["H1", "H3"], ["H2", "H4"], "car")
            except ProviderUnavailable:
                continue  # Wechsel mitten im Aufruf: erlaubt, aber kein gemischtes Ergebnis
            expected = 240 if m.map_version == "referenz-2026-11-01" else 120
            cell = m.cells[0][0]
            if cell is None or cell.time_s != expected:
                bad.append(f"{m.map_version}: {cell}")

    s = threading.Thread(target=switcher)
    readers = [threading.Thread(target=reader) for _ in range(4)]
    s.start()
    for r in readers:
        r.start()
    for r in readers:
        r.join()
    stop.set()
    s.join()
    assert bad == []


# ---- Valhalla-Adapter gegen einen Stub (NICHT gegen einen echten Valhalla-Dienst)
# -------------------


@contextmanager
def stub(handler_fn: object) -> Iterator[str]:
    class H(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            status, body, delay = handler_fn(
                self.path, json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            )  # type: ignore[operator]
            time.sleep(delay)
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, format: str, *args: object) -> None:
            return

    srv = HTTPServer(("127.0.0.1", 0), H)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        yield f"http://127.0.0.1:{srv.server_address[1]}"
    finally:
        srv.shutdown()
        srv.server_close()


def test_valhalla_adapter_bildet_antwortformen_und_fehler_ab_stub() -> None:
    seen: list[dict[str, object]] = []

    def ok(path: str, body: dict[str, object]) -> tuple[int, dict[str, object], float]:
        seen.append(body)
        if path == "/route":
            return 200, {"trip": {"summary": {"time": 180.4, "length": 1.5}}}, 0
        return (
            200,
            {
                "sources_to_targets": [
                    [{"time": 180, "distance": 1.5}, {"time": None, "distance": None}]
                ]
            },
            0,
        )

    s = stops()
    with stub(ok) as url:
        v = ValhallaAdapter(url, "osm-2026-11", timeout_s=1.0)
        r = v.route(s["H1"], s["H4"], profiles()["van_high"])
        assert (r.time_s, r.distance_m, r.map_version, r.provider) == (
            180,
            1500,
            "osm-2026-11",
            "valhalla",
        )
        m = v.matrix([s["H1"]], [s["H4"], s["H7"]], profiles()["car"])
        assert m.cells == ((Cell(180, 1500), None),)
    assert seen[0]["costing"] == "auto" and seen[0]["costing_options"] == {
        "auto": {"height": 3.1, "weight": 3.2}
    }
    assert seen[0]["locations"][0]["minimum_reachability"] == 0  # type: ignore[index]

    def no_path(path: str, body: dict[str, object]) -> tuple[int, dict[str, object], float]:
        return 400, {"error_code": 442, "error": "No path could be found for input"}, 0

    def broken(path: str, body: dict[str, object]) -> tuple[int, dict[str, object], float]:
        return 500, {"error": "boom"}, 0

    def slow(path: str, body: dict[str, object]) -> tuple[int, dict[str, object], float]:
        return 200, {"trip": {"summary": {"time": 1, "length": 0.001}}}, 0.5

    with stub(no_path) as url, pytest.raises(NoRoute):
        ValhallaAdapter(url, "v").route(s["H1"], s["H7"], profiles()["car"])
    with stub(broken) as url, pytest.raises(ProviderUnavailable):
        ValhallaAdapter(url, "v").route(s["H1"], s["H4"], profiles()["car"])
    with stub(slow) as url, pytest.raises(ProviderTimeout):
        ValhallaAdapter(url, "v", timeout_s=0.05).route(s["H1"], s["H4"], profiles()["car"])
    with pytest.raises(ProviderUnavailable):  # nichts hört zu
        ValhallaAdapter("http://127.0.0.1:9", "v", timeout_s=0.5).route(
            s["H1"], s["H4"], profiles()["car"]
        )


def test_adapter_ueber_valhalla_stub_cached_mandantengebunden() -> None:
    calls: list[str] = []

    def ok(path: str, body: dict[str, object]) -> tuple[int, dict[str, object], float]:
        calls.append(path)
        return 200, {"sources_to_targets": [[{"time": 100, "distance": 1.0}]]}, 0

    with stub(ok) as url:
        adapter = RoutingAdapter(ValhallaAdapter(url, "osm-1"), stops(), profiles())
        adapter.matrix(TENANT_A, ["H1"], ["H4"], "car")
        adapter.matrix(TENANT_A, ["H1"], ["H4"], "car")  # Treffer
        adapter.matrix(TENANT_B, ["H1"], ["H4"], "car")  # anderer Mandant: eigener Aufruf
        assert len(calls) == 2


def test_server_antwortet_mit_definierten_fehlern() -> None:
    from taktovia_routing.server import make_server

    adapter, _ = make()
    server = make_server(adapter, port=0)
    port = server.server_address[1]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()

    def post(path: str, body: dict[str, object]) -> tuple[int, dict[str, object]]:
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}{path}", data=json.dumps(body).encode(), method="POST"
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, json.load(resp)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    try:
        status, body = post(
            "/v1/route", {"tenant_id": TENANT_A, "from": "H1", "to": "H4", "profile": "car"}
        )
        assert (
            status == 200 and body["time_s"] == 180 and body["map_version"] == "referenz-2026-11-01"
        )
        status, body = post(
            "/v1/matrix",
            {"tenant_id": TENANT_A, "from": ["H1"], "to": ["H4", "H7"], "profile": "car"},
        )
        assert status == 200 and body["cells"] == [[{"time_s": 180, "distance_m": 1500}, None]]
        assert (
            post("/v1/route", {"tenant_id": TENANT_A, "from": "H1", "to": "H7", "profile": "car"})[
                0
            ]
            == 422
        )
        status, body = post(
            "/v1/route", {"tenant_id": TENANT_A, "from": "X", "to": "H4", "profile": "car"}
        )
        assert status == 422 and body["error"]["code"] == "NOT_A_STOP"  # type: ignore[index]
        assert post("/v1/route", {"from": "H1"})[0] == 400
        assert post("/v1/unbekannt", {"tenant_id": TENANT_A})[0] == 404
        status, body = post("/v1/walk", {"tenant_id": TENANT_A, "from": "H1", "to": "H4"})
        assert status == 200 and body["time_s"] == 1000
    finally:
        server.shutdown()
        server.server_close()
