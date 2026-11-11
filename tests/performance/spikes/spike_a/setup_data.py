"""Messaufbau Spike (a): migriertes Kernschema, zwei Mandanten, Betriebstag-Datenvolumen, Geodaten, Indexvarianten.

Alle Daten sind synthetisch und deterministisch (md5-abgeleitete UUIDs, setseed). Die Geodaten liegen in einer synthetischen
Stellvertreterregion (D-05: Pilotregion offen); die Koordinaten sind nicht als Aussage über eine reale Region zu lesen.
Rollen: Daten legt die Runtime-Rolle unter transaktionslokalem Mandantenkontext an; Indexe und ANALYZE der Migrator; die
Erweiterung btree_gist der Administrator (Messvariante B-04).
"""
import contextlib
import io
import os

import db as dbtool
from taktovia_at import db as atdb
from taktovia_at import seed

SPIKE_DB = os.environ.get("TAKTOVIA_SPIKE_DB", "taktovia_spike_a")
VEHICLES_PER_TENANT = 250
HOLDS_PER_RUN = 60          # 250 Fahrzeuge x 60 Buchungen x 2 Mandanten = 30.000 Buchungen/Tag (Referenzlast Kap. 08 §8.6)
SEGMENTS_PER_RUN = 120
SPARE_RUNS_PER_TENANT = 6000
STOPS_PER_TENANT = 20000
SERVICES_PER_TENANT = 40
BBOX = (13.55, 50.95, 14.00, 51.20)   # lon_min, lat_min, lon_max, lat_max (synthetische Stellvertreterregion)
TENANTS = seed.TENANTS


def tid(label):
    return TENANTS[label]


def prepare_database(name=SPIKE_DB, reset=True):
    if reset:
        atdb.reset_database(name)
    else:
        atdb.ensure_database(name)


def load_base_data(name=SPIKE_DB):
    seed.seed_two_tenants(name)
    for label in TENANTS:
        _load_tenant(name, label)
    analyze(name)


def _load_tenant(name, label):
    t = str(tid(label))
    ids = seed.fixture_ids()[label]
    c = atdb.runtime_conn(name)
    try:
        atdb.set_tenant(c, t)
        x = c.execute
        x("SET LOCAL search_path = taktovia, public")
        x("SELECT setseed(0.42)")
        x("""INSERT INTO config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason)
             VALUES (%s, md5(%s||'cfg')::uuid, 'SERVICE', md5(%s||'cfgscope')::uuid, 1, '1', '{}', '2026-01-01', 'user:spike', 'Lasttestdaten')""", (t, t, t))
        x("""INSERT INTO service(tenant_id,id,operator_id,name,timezone,config_revision_id)
             SELECT %s, md5(%s||'svc'||g)::uuid, %s, 'Dienst '||g, 'Europe/Berlin', md5(%s||'cfg')::uuid FROM generate_series(0,%s) g""",
          (t, t, ids["organization"], t, SERVICES_PER_TENANT))
        x("""INSERT INTO vehicle(tenant_id,id,owner_org_ref,capacity_config)
             SELECT %s, md5(%s||'veh'||g)::uuid, %s, '{"seats":4,"wheelchair":1}' FROM generate_series(1,%s) g""",
          (t, t, f"fed-org-{label.lower()}", VEHICLES_PER_TENANT))
        x("""INSERT INTO staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state)
             SELECT %s, md5(%s||'shift'||g)::uuid, md5(%s||'drv'||g)::uuid, md5(%s||'veh'||g)::uuid, '2026-06-01 04:00+00', '2026-06-01 14:00+00', 'Europe/Berlin', 1, 'PUBLISHED'
             FROM generate_series(1,%s) g""", (t, t, t, t, VEHICLES_PER_TENANT))
        # Basisläufe (ein Lauf je Fahrzeug) und Reserveläufe für Messiterationen
        x("""INSERT INTO vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state)
             SELECT %s, md5(%s||'run'||g)::uuid, md5(%s||'veh'||g)::uuid, md5(%s||'shift'||g)::uuid, 1, 'ACTIVE' FROM generate_series(1,%s) g""",
          (t, t, t, t, VEHICLES_PER_TENANT))
        # Seit WP-M2-16 gilt höchstens ein Umlauf je Schicht (vehicle_run_one_per_shift): jeder Reservelauf bekommt eine eigene Reserveschicht.
        x("""INSERT INTO staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state)
             SELECT %s, md5(%s||'spareshift'||g)::uuid, md5(%s||'sparedrv'||g)::uuid, md5(%s||'veh'||(1+g%%%s))::uuid, '2026-06-02 04:00+00', '2026-06-02 14:00+00', 'Europe/Berlin', 1, 'PUBLISHED'
             FROM generate_series(1,%s) g""", (t, t, t, t, VEHICLES_PER_TENANT, SPARE_RUNS_PER_TENANT))
        x("""INSERT INTO vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state)
             SELECT %s, md5(%s||'spare'||g)::uuid, md5(%s||'veh'||(1+g%%%s))::uuid, md5(%s||'spareshift'||g)::uuid, 1, 'ACTIVE'
             FROM generate_series(1,%s) g""", (t, t, t, VEHICLES_PER_TENANT, t, SPARE_RUNS_PER_TENANT))
        # Betriebstag: HOLDS_PER_RUN Holds je Basislauf, höchstens zwei gleichzeitig je Abschnitt, Kapazität 4 Sitze/1 Rollstuhl
        x("""INSERT INTO capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state)
             SELECT %s, md5(%s||'hold'||r||'/'||k)::uuid, md5(%s||'run'||r)::uuid, 1, 'seed-'||md5(%s||'hold'||r||'/'||k),
                    CASE WHEN k%%15=0 THEN '{"seats":0,"wheelchair":1}'::jsonb ELSE '{"seats":1,"wheelchair":0}'::jsonb END,
                    k*2, k*2+3, '2026-06-01 06:00+00'::timestamptz + k*interval '1 minute',
                    CASE WHEN k%%50=0 THEN 'EXPIRED' WHEN k%%47=0 THEN 'RELEASED' ELSE 'CONSUMED' END
             FROM generate_series(1,%s) r, generate_series(0,%s-1) k""", (t, t, t, t, VEHICLES_PER_TENANT, HOLDS_PER_RUN))
        x("""INSERT INTO booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision)
             SELECT tenant_id, md5(id::text||'bk')::uuid, id, md5(%s||'svc0')::uuid, 'bk-'||federation_ref, 'CONFIRMED', '{}', 1
             FROM capacity_hold WHERE tenant_id=%s AND state='CONSUMED' AND federation_ref LIKE 'seed-%%'""", (t, t))
        x("""INSERT INTO outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload,published_at)
             SELECT tenant_id, md5(id::text||'ob')::uuid, id, 1, 'CapacityHeld', '1.0', expires_at, '{}', expires_at FROM capacity_hold
             WHERE tenant_id=%s AND federation_ref LIKE 'seed-%%'""", (t,))
        # Geodaten: Haltepunkte (20 % mit zweiter Revision) und Bediengebiete (3 Revisionen je Dienst, ~200 Stützpunkte)
        lo, la, hi, ha = BBOX
        x("""INSERT INTO stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from,valid_to)
             SELECT %s, md5(%s||'stoprev'||g||'/1')::uuid, md5(%s||'stop'||g)::uuid, 1,
                    ST_SetSRID(ST_MakePoint(%s + random()*(%s-%s), %s + random()*(%s-%s)),4326), 'Haltestelle '||g,
                    '{"step_free":true}', 'synthetic', '2026-01-01 00:00+00', CASE WHEN g%%5=0 THEN '2026-06-01 00:00+00'::timestamptz END
             FROM generate_series(1,%s) g""", (t, t, t, lo, hi, lo, la, ha, la, STOPS_PER_TENANT))
        x("""INSERT INTO stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from,valid_to)
             SELECT tenant_id, md5(stop_id::text||'/2')::uuid, stop_id, 2, ST_SetSRID(ST_MakePoint(ST_X(point)+0.0005, ST_Y(point)),4326),
                    name, accessibility, approved_by, '2026-06-01 00:00+00', NULL
             FROM stop_revision WHERE tenant_id=%s AND valid_to IS NOT NULL AND revision=1""", (t,))
        x("""INSERT INTO service_area(tenant_id,id,service_id,revision,area,valid_from,valid_to)
             SELECT %s, md5(%s||'area'||s||'/'||rev)::uuid, md5(%s||'svc'||s)::uuid, rev,
                    ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(%s + random()*(%s-%s), %s + random()*(%s-%s)),4326), 0.02+0.01*rev+random()*0.03, 50)),
                    CASE rev WHEN 1 THEN '2026-01-01'::timestamptz WHEN 2 THEN '2026-04-01' ELSE '2026-09-01' END,
                    CASE rev WHEN 1 THEN '2026-04-01'::timestamptz WHEN 2 THEN '2026-09-01' END
             FROM generate_series(1,%s) s, generate_series(1,3) rev""", (t, t, t, lo, hi, lo, la, ha, la, SERVICES_PER_TENANT))
        c.commit()
    finally:
        c.close()


def analyze(name=SPIKE_DB):
    c = atdb.migrator_conn(name)
    c.autocommit = True
    try:
        c.execute("ANALYZE")
    finally:
        c.close()


def table_counts(name=SPIKE_DB):
    """Zeilenzahlen je Mandant (unter RLS mit Mandantenkontext) und Gesamtgröße der Tabellen."""
    out = {}
    for label, t in TENANTS.items():
        with atdb.tx(name, t) as c:
            out[label] = {tb: c.execute(f"SELECT count(*) FROM taktovia.{tb}").fetchone()[0]
                          for tb in ("vehicle", "staff_shift", "vehicle_run", "service", "capacity_hold", "booking", "outbox",
                                     "stop_revision", "service_area")}
    c = atdb.migrator_conn(name)
    try:
        sizes = c.execute("""SELECT relname, pg_total_relation_size(c.oid)/1024 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                             WHERE nspname='taktovia' AND relkind='r' AND relname IN ('capacity_hold','booking','outbox','stop_revision','service_area')""").fetchall()
    finally:
        c.close()
    return {"rows_per_tenant": out, "total_size_kb": dict(sizes)}


HOLD_INDEXES = {
    "capacity_hold_run": "CREATE INDEX IF NOT EXISTS capacity_hold_run ON taktovia.capacity_hold (tenant_id, run_id) INCLUDE (state, interval_from, interval_to)",
    "capacity_hold_expiry": "CREATE INDEX IF NOT EXISTS capacity_hold_expiry ON taktovia.capacity_hold (tenant_id, expires_at) WHERE state = 'HELD'",
}
GEO_INDEXES = {
    "stop_geo_tenant": "CREATE INDEX IF NOT EXISTS stop_geo_tenant ON taktovia.stop_revision USING gist (tenant_id, point)",
    "service_area_geo_tenant": "CREATE INDEX IF NOT EXISTS service_area_geo_tenant ON taktovia.service_area USING gist (tenant_id, area)",
}


def ensure_btree_gist(name=SPIKE_DB):
    with dbtool.admin(name) as c:
        c.execute("CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public")


def create_indexes(defs, name=SPIKE_DB):
    c = atdb.migrator_conn(name)
    c.autocommit = True
    try:
        for sql in defs.values():
            c.execute(sql)
        c.execute("ANALYZE")
    finally:
        c.close()


def drop_indexes(names, name=SPIKE_DB):
    c = atdb.migrator_conn(name)
    c.autocommit = True
    try:
        for n in names:
            c.execute(f"DROP INDEX IF EXISTS taktovia.{n}")
        c.execute("ANALYZE")
    finally:
        c.close()


def add_grid_cells(name=SPIKE_DB):
    """Messvariante G: generierte Gitterzellenspalte und btree (tenant_id, cell) auf stop_revision (Schemaänderung nur in der Spike-Datenbank)."""
    from spike_a import geo
    c = atdb.migrator_conn(name)
    c.autocommit = True
    try:
        for ddl in geo.GRID_DDL:
            c.execute(ddl)
        c.execute("ANALYZE taktovia.stop_revision")
    finally:
        c.close()
