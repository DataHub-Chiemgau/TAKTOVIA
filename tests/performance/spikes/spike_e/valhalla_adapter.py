"""Adapter auf die HTTP-API von Valhalla (/route, /sources_to_targets). Gegen einen echten Valhalla-Dienst NICHT ausgeführt (Docker-Daemon und
Kartenextrakte nicht verfügbar); der Vertragstest läuft gegen den Stub FakeValhalla (test_spike_e.py) und weist nur nach, dass der Adapter die
dokumentierten Antwortformen richtig abbildet und Fehler richtig übersetzt.

Costing-Abbildung: auto -> "auto", pedestrian -> "pedestrian", wheelchair -> "pedestrian" mit costing_options.pedestrian.type = "wheelchair".
Datenstand: Valhalla liefert die Kartenversion nicht in jeder Antwort; der Adapter erwartet sie aus der Konfiguration (data_version) oder aus dem
Kachelverzeichnis des Betreibers und nimmt sie in den Cache-Schlüssel auf.
"""
import time

from taktovia_testkit.jsonhttp import NoResponse, http_call

from .routing_port import MatrixResult, NoRoute, OutOfCoverage, ProviderTimeout, ProviderUnavailable, RouteResult, RoutingPort

COSTING = {"auto": ("auto", None), "pedestrian": ("pedestrian", None), "wheelchair": ("pedestrian", {"pedestrian": {"type": "wheelchair"}})}


class ValhallaAdapter(RoutingPort):
    name = "valhalla"

    def __init__(self, base_url, data_version, timeout_s=2.0):
        self.base, self._version, self.timeout = base_url.rstrip("/"), data_version, timeout_s

    def data_version(self):
        return self._version

    def _post(self, path, body):
        try:
            status, data, _, _ = http_call("POST", self.base + path, body, timeout=self.timeout)
        except NoResponse as e:
            if "timed out" in str(e).lower() or "timeout" in str(e).lower():
                raise ProviderTimeout(str(e)) from e
            raise ProviderUnavailable(str(e)) from e
        if status >= 500:
            raise ProviderUnavailable(f"HTTP {status}")
        if status >= 400:
            code, msg = (data or {}).get("error_code"), (data or {}).get("error", "")
            if code in (171, 170, 172, 199) or "No suitable edges" in msg or "outside" in msg.lower():
                raise OutOfCoverage(msg)
            if code in (442, 443) or "No path" in msg:
                raise NoRoute(msg)
            raise ProviderUnavailable(f"HTTP {status}: {msg}")
        return data

    @staticmethod
    def _loc(p):
        return {"lat": p[0], "lon": p[1]}

    def route(self, origin, dest, profile="auto"):
        costing, opts = COSTING[profile]
        body = {"locations": [self._loc(origin), self._loc(dest)], "costing": costing, "units": "kilometers"}
        if opts:
            body["costing_options"] = opts
        t0 = time.perf_counter()
        d = self._post("/route", body)
        s = d["trip"]["summary"]
        return RouteResult(round(s["time"]), round(s["length"] * 1000), self._version, profile, self.name, (time.perf_counter() - t0) * 1000)

    def matrix(self, origins, dests, profile="auto"):
        costing, opts = COSTING[profile]
        body = {"sources": [self._loc(p) for p in origins], "targets": [self._loc(p) for p in dests], "costing": costing}
        if opts:
            body["costing_options"] = opts
        t0 = time.perf_counter()
        d = self._post("/sources_to_targets", body)
        rows = [[(c["time"] if c.get("time") is not None else None) for c in row] for row in d["sources_to_targets"]]
        return MatrixResult(rows, self._version, profile, self.name, (time.perf_counter() - t0) * 1000)
