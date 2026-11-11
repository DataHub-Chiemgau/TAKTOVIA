"""Lokaler Valhalla-Probelauf (PyPI-Paket pyvalhalla) auf dem synthetischen Gitternetz aus osm_pbf.py.

Was das belegt: Der Valhalla-Adapter (valhalla_adapter.py) funktioniert gegen die echten Antwortformen und Fehlercodes von Valhalla, und Profile/Restriktionen
(Einbahn, Fußgängerzone, Treppe, Rollstuhl) werden in einer echten Routing-Engine auf konstruierten Fällen eingehalten. Was es NICHT belegt: Eigenschaften einer realen
Region, reale Datenqualität, Lizenz, Vergleich mit einer zweiten Variante. Der HTTP-Dienst ist ein dünner Wrapper (Actor -> /route, /sources_to_targets),
kein Valhalla-HTTP-Server (prime_server ist im Wheel nicht enthalten).

Voraussetzung: Python-Umgebung mit pyvalhalla (nicht Teil von tests/requirements.txt; in der Entwicklungsumgebung als Wheel von PyPI installiert).
"""
import json
import subprocess
import time
from pathlib import Path

from taktovia_testkit.jsonhttp import App, Response

from . import osm_pbf


def _bin(name):
    import valhalla
    return Path(valhalla.PYVALHALLA_DIR) / "bin" / name


MATRIX_PAIR_LIMIT = 100_000   # Valhalla-Vorgabe ist 2.500 Ortspaare (max_matrix_location_pairs); für die Messgrößen angehoben (Konfigurationsentscheidung des Betreibers)


def build(workdir: Path, grid=None, concurrency=2):
    """Erzeugt PBF, Konfiguration und Kacheln (Kacheln werden je Arbeitsverzeichnis wiederverwendet). -> (config_path, build_seconds, pbf_bytes)."""
    import valhalla
    workdir.mkdir(parents=True, exist_ok=True)
    g = grid or osm_pbf.GRID
    pbf, cfg_path, tiles = workdir / "synthetic.osm.pbf", workdir / "valhalla.json", workdir / "tiles"
    tiles.mkdir(parents=True, exist_ok=True)
    cfg = valhalla.get_config(tile_extract="", tile_dir=str(tiles), verbose=False)
    cfg["mjolnir"].update({"admin": "", "timezone": "", "landmarks": "", "concurrency": concurrency, "tile_dir": str(tiles)})
    cfg["logging"] = {"type": "file", "file_name": str(workdir / "valhalla.log")}
    for costing in ("auto", "pedestrian"):
        cfg["service_limits"][costing]["max_matrix_location_pairs"] = MATRIX_PAIR_LIMIT
    cfg_path.write_text(json.dumps(cfg, indent=1))
    if pbf.exists() and any(tiles.rglob("*.gph")):
        return cfg_path, 0.0, pbf.stat().st_size
    nodes, ways = osm_pbf.synthetic_network(g)
    size = osm_pbf.write_pbf(pbf, nodes, ways)
    t = time.perf_counter()
    subprocess.run([str(_bin("valhalla_build_tiles")), "-c", str(cfg_path), str(pbf)], check=True, capture_output=True, text=True)
    return cfg_path, time.perf_counter() - t, size


def make_actor(cfg_path):
    import valhalla
    return valhalla.Actor(str(cfg_path))


def serve(actor, delay_s=0.0):
    """HTTP-Wrapper mit den Pfaden und Fehlerformen der Valhalla-API: Fehlerantwort {error_code, error, status_code, status}."""
    import valhalla
    app = App()

    def call(fn, req):
        if delay_s:
            time.sleep(delay_s)
        try:
            return Response(200, json.loads(fn(req.raw_body.decode())))
        except valhalla.ValhallaError as e:
            v = vars(e)
            return Response(v.get("http_code", 400), {"error_code": v.get("code"), "error": v.get("message", str(e)), "status_code": v.get("http_code", 400), "status": v.get("http_message", "Bad Request")})
    app.route("POST", r"/route")(lambda req: call(actor.route, req))
    app.route("POST", r"/sources_to_targets")(lambda req: call(actor.matrix, req))
    return app
