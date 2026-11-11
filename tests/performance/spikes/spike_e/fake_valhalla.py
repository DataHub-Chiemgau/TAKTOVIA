"""Stub mit den dokumentierten Antwortformen von Valhalla (/route, /sources_to_targets) für den Vertragstest des Adapters. KEIN Valhalla."""
import time

from taktovia_testkit.jsonhttp import App, Response

from .grid_router import SyntheticGridRouter
from .routing_port import NoRoute, OutOfCoverage


def build(delay_s=0.0, fail_status=None):
    app = App()
    grid = SyntheticGridRouter()

    def prof(b):
        c = b.get("costing", "auto")
        return "wheelchair" if (c == "pedestrian" and b.get("costing_options", {}).get("pedestrian", {}).get("type") == "wheelchair") else c

    def guard():
        if delay_s:
            time.sleep(delay_s)
        if fail_status:
            return Response(fail_status, {"error": "internal"})

    @app.route("POST", r"/route")
    def route(req):
        g = guard()
        if g:
            return g
        b = req.json()
        a, d = (b["locations"][0]["lat"], b["locations"][0]["lon"]), (b["locations"][1]["lat"], b["locations"][1]["lon"])
        try:
            r = grid.route(a, d, prof(b))
        except OutOfCoverage:
            return Response(400, {"error_code": 171, "error": "No suitable edges near location"})
        except NoRoute:
            return Response(400, {"error_code": 442, "error": "No path could be found for input"})
        return Response(200, {"trip": {"summary": {"time": r.duration_s, "length": r.distance_m / 1000.0}, "status": 0}})

    @app.route("POST", r"/sources_to_targets")
    def s2t(req):
        g = guard()
        if g:
            return g
        b = req.json()
        src = [(p["lat"], p["lon"]) for p in b["sources"]]
        tgt = [(p["lat"], p["lon"]) for p in b["targets"]]
        try:
            m = grid.matrix(src, tgt, prof(b))
        except OutOfCoverage:
            return Response(400, {"error_code": 171, "error": "No suitable edges near location"})
        return Response(200, {"sources_to_targets": [[{"time": t, "distance": None} for t in row] for row in m.durations_s]})
    return app
