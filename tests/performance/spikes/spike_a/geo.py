"""PostGIS-Anteil des Buchungspfads (Kap. 01 §1.4 Schritte 1-2): Gebietsprüfung und Haltepunktsuche. Wegwerf-Prototyp.

SRID 4326; Gebietsprüfung mit geometry ST_Covers (Grenze gehört zum Gebiet; nutzt den GiST-Index service_area_geo), Entfernung metrisch
über geography (Kap. 04 §4.1). Wirksamkeit: valid_from <= t und (valid_to IS NULL oder valid_to > t). Alle Abfragen laufen unter der
Runtime-Rolle mit transaktionslokalem Mandantenkontext (RLS-Policy tenant_id = current_tenant() greift zusätzlich zur Mandantenbedingung).
"""
import uuid

AREA_SQL = """SELECT a.id, a.service_id, a.revision FROM taktovia.service_area a
              WHERE a.tenant_id = %(t)s AND ST_Covers(a.area, ST_SetSRID(ST_MakePoint(%(lon)s, %(lat)s), 4326))
                AND a.valid_from <= %(at)s AND (a.valid_to IS NULL OR a.valid_to > %(at)s)"""

_P = "ST_SetSRID(ST_MakePoint(%(lon)s, %(lat)s), 4326)"
_VALID = "s.valid_from <= %(at)s AND (s.valid_to IS NULL OR s.valid_to > %(at)s)"
_SEL = f"SELECT s.stop_id, s.revision, ST_Distance(s.point::geography, {_P}::geography) AS dist_m FROM taktovia.stop_revision s"

# Variante N (naiv): ST_DWithin auf dem geography-Cast; ein geometry-GiST-Index kann dafür nicht genutzt werden.
STOPS_NAIVE_SQL = f"{_SEL} WHERE s.tenant_id = %(t)s AND ST_DWithin(s.point::geography, {_P}::geography, %(r)s) AND {_VALID} ORDER BY dist_m LIMIT %(k)s"

# Variante B (Bounding-Box-Vorfilter mit &&, soll den GiST nutzen) + metrische Verfeinerung über geography.
STOPS_BBOX_SQL = (f"{_SEL} WHERE s.tenant_id = %(t)s AND s.point && ST_Expand({_P}, %(deg)s) AND ST_DWithin(s.point::geography, {_P}::geography, %(r)s) "
                  f"AND {_VALID} ORDER BY dist_m LIMIT %(k)s")

# Variante K (KNN über den GiST-Index mit <->; Ordnungsoperator, rechnet in Grad), dann metrische Verfeinerung der nächsten 50 Kandidaten.
STOPS_KNN_SQL = (f"SELECT * FROM (SELECT s.stop_id, s.revision, ST_Distance(s.point::geography, {_P}::geography) AS dist_m FROM taktovia.stop_revision s "
                 f"WHERE s.tenant_id = %(t)s AND {_VALID} ORDER BY s.point <-> {_P} LIMIT 50) c WHERE dist_m <= %(r)s ORDER BY dist_m LIMIT %(k)s")

# Variante G (Gitterzelle): ganzzahlige Zelle (0,01 Grad) als generierte Spalte stop_revision.cell mit btree (tenant_id, cell). Ganzzahlvergleiche
# sind leakproof und damit auch unter RLS als Indexbedingung nutzbar. Es werden die 3x3 Nachbarzellen abgefragt (Zellbreite >= Radius 500 m).
STOPS_GRID_SQL = (f"{_SEL} WHERE s.tenant_id = %(t)s AND s.cell = ANY(%(cells)s) AND ST_DWithin(s.point::geography, {_P}::geography, %(r)s) "
                  f"AND {_VALID} ORDER BY dist_m LIMIT %(k)s")

STOP_VARIANTS = {"naive_geography": STOPS_NAIVE_SQL, "bbox_prefilter": STOPS_BBOX_SQL, "knn_index": STOPS_KNN_SQL, "grid_cell": STOPS_GRID_SQL}
DEG = 0.01   # Vorfilter: ~1,1 km in Breite, ~0,7 km in Länge bei 51 Grad Nord; deckt 500 m ab
GRID_DDL = ("ALTER TABLE taktovia.stop_revision ADD COLUMN IF NOT EXISTS cell bigint GENERATED ALWAYS AS "
            "((floor(ST_X(point) * 100)::bigint) * 100000 + floor(ST_Y(point) * 100)::bigint) STORED",
            "CREATE INDEX IF NOT EXISTS stop_cell ON taktovia.stop_revision (tenant_id, cell)")


def cells(lon, lat):
    import math
    cx, cy = math.floor(lon * 100), math.floor(lat * 100)
    return [(cx + i) * 100000 + (cy + j) for i in (-1, 0, 1) for j in (-1, 0, 1)]


def params(tenant, lon, lat, at, r=500, k=5):
    return {"t": tenant, "lon": lon, "lat": lat, "at": at, "r": r, "k": k, "deg": DEG, "cells": cells(lon, lat)}


def _ctx(cur, tenant):
    cur.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))


def area_check(conn, tenant, lon, lat, at):
    cur = conn.cursor()
    _ctx(cur, tenant)
    rows = cur.execute(AREA_SQL, params(tenant, lon, lat, at)).fetchall()
    conn.commit()
    return rows


def nearest_stops(conn, tenant, lon, lat, at, variant="bbox_prefilter", r=500, k=5):
    cur = conn.cursor()
    _ctx(cur, tenant)
    rows = cur.execute(STOP_VARIANTS[variant], params(tenant, lon, lat, at, r, k)).fetchall()
    conn.commit()
    return rows


def explain(conn, tenant, sql, p):
    """EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) unter Mandantenkontext. Der Plan wird vor dem Commit verworfen (nur Lesen)."""
    cur = conn.cursor()
    _ctx(cur, tenant)
    plan = cur.execute("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, p).fetchone()[0][0]
    conn.rollback()
    return plan


def plan_summary(plan):
    """Verdichtet einen JSON-Plan: verwendete Indizes, Knotentypen, Zeilen/Filterung, Zeiten, Puffer."""
    nodes, indexes = [], []
    scans = []

    def walk(n):
        nodes.append(n["Node Type"])
        if "Index Name" in n:
            indexes.append(n["Index Name"])
        if n["Node Type"] in ("Seq Scan", "Index Scan", "Bitmap Heap Scan", "Index Only Scan"):
            scans.append({"node": n["Node Type"], "relation": n.get("Relation Name"), "index": n.get("Index Name"), "index_cond": n.get("Index Cond"), "filter": n.get("Filter"),
                          "rows": n.get("Actual Rows"), "loops": n.get("Actual Loops"), "removed_by_filter": n.get("Rows Removed by Filter"),
                          "removed_by_index_recheck": n.get("Rows Removed by Index Recheck")})
        for c in n.get("Plans", []):
            walk(c)
    walk(plan["Plan"])
    p = plan["Plan"]
    return {"indexes_used": sorted(set(indexes)), "nodes": nodes, "scans": scans, "execution_ms": plan.get("Execution Time"),
            "planning_ms": plan.get("Planning Time"), "shared_hit": p.get("Shared Hit Blocks"), "shared_read": p.get("Shared Read Blocks")}


# --- Korrektheitsfixture mit bekannten Punkten (innerhalb, außerhalb, auf der Grenze); liegt außerhalb der Zufallsdaten (BBOX in setup_data) --------------------------------------------------------
GEO_NS = uuid.UUID("5a5a5a5a-0000-4000-8000-00000000b002")
RECT = (12.200, 52.000, 12.300, 52.080)         # lon_min, lat_min, lon_max, lat_max (synthetisch)
RECT_SMALL = (12.220, 52.010, 12.280, 52.070)   # Revision 2 (kleiner)
T_REV1, T_REV2 = "2026-03-01T12:00:00+00", "2026-07-01T12:00:00+00"   # Revision 1: [2026-01-01, 2026-06-01), Revision 2: [2026-06-01, offen)
QUERY_POINT = (12.2500, 52.0400)


def fixture_ids(tenant):
    return {"service": uuid.uuid5(GEO_NS, f"{tenant}|svc"), "area1": uuid.uuid5(GEO_NS, f"{tenant}|a1"), "area2": uuid.uuid5(GEO_NS, f"{tenant}|a2")}


def create_fixture(conn, tenant, org_id, cfg_id):
    """Ein Dienst mit zwei Gebietsrevisionen (Rechteck) und Haltepunkte in 100/250/600 m Abstand (N/E/S) um QUERY_POINT sowie einer
    zweiten Revision, die einen Haltepunkt verschiebt. Alle Daten synthetisch."""
    cur = conn.cursor()
    _ctx(cur, tenant)
    ids = fixture_ids(tenant)
    cur.execute("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id) VALUES (%s,%s,%s,'Geo-Test','Europe/Berlin',%s) ON CONFLICT DO NOTHING",
                (tenant, ids["service"], org_id, cfg_id))
    for key, rect, rev, vf, vt in (("area1", RECT, 1, "2026-01-01 00:00+00", "2026-06-01 00:00+00"), ("area2", RECT_SMALL, 2, "2026-06-01 00:00+00", None)):
        cur.execute("INSERT INTO taktovia.service_area(tenant_id,id,service_id,revision,area,valid_from,valid_to) "
                    "VALUES (%s,%s,%s,%s,ST_Multi(ST_MakeEnvelope(%s,%s,%s,%s,4326)),%s,%s) ON CONFLICT DO NOTHING",
                    (tenant, ids[key], ids["service"], rev, *rect, vf, vt))
    lon, lat = QUERY_POINT
    for name, dist, az in (("N100", 100, 0), ("E250", 250, 90), ("S600", 600, 180)):
        sid = uuid.uuid5(GEO_NS, f"{tenant}|stop|{name}")
        cur.execute("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from,valid_to) "
                    "SELECT %s, %s, %s, 1, ST_SetSRID(ST_Project(ST_SetSRID(ST_MakePoint(%s,%s),4326)::geography, %s, radians(%s))::geometry,4326), %s, '{}', 'synthetic', "
                    "'2026-01-01 00:00+00', '2026-06-01 00:00+00' ON CONFLICT DO NOTHING", (tenant, uuid.uuid5(GEO_NS, f"{tenant}|rev|{name}|1"), sid, lon, lat, dist, az, "Test " + name))
        cur.execute("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from,valid_to) "
                    "SELECT %s, %s, %s, 2, ST_SetSRID(ST_Project(ST_SetSRID(ST_MakePoint(%s,%s),4326)::geography, %s, radians(%s))::geometry,4326), %s, '{}', 'synthetic', "
                    "'2026-06-01 00:00+00', NULL ON CONFLICT DO NOTHING",
                    (tenant, uuid.uuid5(GEO_NS, f"{tenant}|rev|{name}|2"), sid, lon, lat, dist * (3 if name == "N100" else 1), az, "Test " + name))
    conn.commit()
    return ids
