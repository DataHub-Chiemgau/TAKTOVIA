"""Synthetischer Datengenerator für die Staging- und Performance-Umgebung (WP-M5-02, Kap. 08 §8.6).

Je Mandant: Rechtsträger, Organisation, Region, Depots, Dienste mit Bediengebiet und Tarif, Haltepunkte, Fahrzeuge (Pool), Fahrer mit Qualifikation und Mitgliedschaft, veröffentlichter
Dienstplan mit Schichten, Einsatzfreigaben, Umläufe, Fahrgäste und ein Buchungsbestand (Reiseversuch, Preisstand, Reise, Hold, Buchung, Zahlungsabsicht) über mehrere Betriebstage, dazu Positionsproben.

Regeln (Detailvertrag m4-cluster-wave12-c7.md, Abschnitt WP-M5-02):
- **Befüllung nur unter der Runtime-Rolle** (RLS, Tenantkontext transaktionslokal, Rechte der Anwendung): `require_runtime_connection` weist Superuser, BYPASSRLS und den Tabelleneigentümer (Migrator) ab.
  Mandanten legt der Plattformbetrieb an (`provision_tenants`, Eigentümer/Administrator); der Generator selbst legt nie einen Mandanten an.
- **Deterministisch:** feste Kennungen aus `md5(seed|mandant|art|index)`, fester Zeitanker, `random.Random(seed)` je Mandant. Gleicher Seed und gleiche Skala ergeben denselben Datenstand (Prüfsumme `data_state_sha256`).
- **Synthetisch:** Namen enden auf „(synthetisch)“, Fahrgastpseudonyme beginnen mit `synthetic-token-`, es gibt weder Klarnamen noch Mailadressen noch Telefonnummern (`scan_synthetic`).
- Befüllt wird per gestapeltem `INSERT` in einer Transaktion je Mandant (`COPY FROM` ist mit RLS nicht zulässig); zwei Mandanten dürfen parallel befüllt werden (jede Zeile trägt nur die Kennungen des eigenen Mandanten).
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import random
import time
from dataclasses import dataclass, asdict, field

SEED_DEFAULT = 20261201
ANCHOR_DAY = dt.date(2026, 12, 1)          # Dienstag
ACTOR = 'system:datagen-wp-m5-02'
RUNTIME_ROLE = 'taktovia_runtime'
ALLOWED_ENVIRONMENTS = ('development', 'staging', 'performance')   # nie production (Kap. 01 §1.7)
MODULE_PW_NOTE = 'Zugangsdaten stehen nicht im Repository'


@dataclass(frozen=True)
class Scale:
    """Größe je Mandant. `REFERENCE` ist das Referenzvolumen aus Kap. 08 §8.6 (250 Fahrzeuge, 30.000 Buchungen/Tag, mehrere Betriebstage)."""
    vehicles: int = 250
    drivers: int = 250
    depots: int = 4
    services: int = 3
    stops: int = 200
    passengers: int = 5000
    days: int = 3
    bookings_per_day: int = 30000
    position_interval_s: int = 300


REFERENCE = Scale()
SMALL = Scale(vehicles=250, drivers=250, depots=4, services=3, stops=60, passengers=300, days=2, bookings_per_day=40, position_interval_s=3600)


def gid(seed: int, tenant: str, kind: str, i: int | str = 0) -> str:
    return str(__import__('uuid').UUID(hashlib.md5(f'{seed}|{tenant}|{kind}|{i}'.encode()).hexdigest()))


def tenant_id(seed: int, index: int) -> str:
    return gid(seed, 'platform', 'tenant', index)


class ForbiddenConnection(Exception):
    """Die Befüllung läuft nur unter der Runtime-Rolle (kein Migrator, kein Superuser, kein BYPASSRLS, kein Tabelleneigentümer)."""


def require_runtime_connection(conn, runtime_role: str = RUNTIME_ROLE, allow_environments=ALLOWED_ENVIRONMENTS) -> None:
    row = conn.execute("SELECT current_user, r.rolsuper, r.rolbypassrls, r.rolcreaterole, "
                       "(SELECT tableowner FROM pg_tables WHERE schemaname = 'taktovia' AND tablename = 'staff_shift') "
                       "FROM pg_roles r WHERE r.rolname = current_user").fetchone()
    user, superuser, bypass, createrole, owner = row
    if user != runtime_role:
        raise ForbiddenConnection(f'Befüllung abgewiesen: Rolle {user!r} ist nicht die Runtime-Rolle {runtime_role!r}')
    if superuser or bypass or createrole or user == owner:
        raise ForbiddenConnection(f'Befüllung abgewiesen: Rolle {user!r} ist privilegiert (Superuser, BYPASSRLS, CREATEROLE oder Tabelleneigentümer)')
    # Umgebungskennung: nur wenn lesbar (die Runtime-Rolle darf sie lesen, sonst wird die Kennung vom Betrieb geprüft)
    try:
        env = conn.execute('SELECT environment FROM taktovia_meta.environment_marker').fetchall()
    except Exception:  # pragma: no cover - Rechte der Umgebung
        conn.rollback()
        env = []
    if len(env) == 1 and env[0][0] not in allow_environments:
        raise ForbiddenConnection(f'Befüllung abgewiesen: Umgebungskennung {env[0][0]!r} ist keine Staging-, Performance- oder Entwicklungsumgebung')


def provision_tenants(admin_conn, seed: int, count: int = 2) -> list[str]:
    """Betriebsschritt (Eigentümer/Administrator): legt die synthetischen Mandanten an. Idempotent."""
    ids = []
    for i in range(1, count + 1):
        t = tenant_id(seed, i)
        admin_conn.execute('INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,%s,%s) ON CONFLICT DO NOTHING', (t, f'Mandant S{i} (synthetisch)', 'ACTIVE'))
        ids.append(t)
    admin_conn.execute("INSERT INTO taktovia.currency(code,exponent) VALUES ('EUR',2) ON CONFLICT DO NOTHING")
    return ids


# Spalten je Tabelle (Reihenfolge = COPY-Reihenfolge = Abhängigkeitsreihenfolge)
TABLES = ['legal_entity', 'organization', 'region', 'depot', 'config_revision', 'service', 'service_area', 'stop_revision', 'tariff_revision', 'membership', 'driver',
          'qualification_revision', 'vehicle', 'vehicle_pool_assignment', 'roster_revision', 'staff_shift', 'vehicle_run', 'resource_release', 'passenger_private',
          'travel_attempt', 'fare_quote', 'journey', 'capacity_hold', 'booking', 'payment_intent', 'position_sample']


def _ts(d: dt.datetime) -> str:
    return d.strftime('%Y-%m-%d %H:%M:%S+00')


@dataclass
class Report:
    seed: int
    scale: dict
    tenant: str
    rows: dict = field(default_factory=dict)
    seconds: float = 0.0
    data_state_sha256: str = ''


BATCH = 2000


def _copy(conn, table: str, columns: list[str], rows, digest, report: Report) -> None:
    """Fügt die Zeilen per `INSERT` in Stapeln ein (`COPY FROM` ist mit RLS nicht zulässig; die Runtime-Rolle schreibt wie die Anwendung)."""
    sql = f'INSERT INTO taktovia.{table} ({", ".join(columns)}) VALUES ({", ".join(["%s"] * len(columns))})'  # sql-lint: ok festes Tabellen- und Spaltenverzeichnis des Generators, keine Eingabe
    n = 0; batch = []
    with conn.cursor() as cur:
        for r in rows:
            batch.append(r)
            digest.update((table + '|' + '|'.join('' if v is None else str(v) for v in r) + '\n').encode())
            n += 1
            if len(batch) >= BATCH:
                cur.executemany(sql, batch); batch = []
        if batch:
            cur.executemany(sql, batch)
    report.rows[table] = report.rows.get(table, 0) + n


def fill_tenant(conn, tenant: str, seed: int, scale: Scale, index: int = 1) -> Report:
    """Befüllt einen Mandanten in einer Transaktion unter der Runtime-Rolle. [conn] ist eine Runtime-Verbindung (autocommit aus)."""
    require_runtime_connection(conn)
    t0 = time.monotonic()
    rnd = random.Random(f'{seed}|{tenant}')
    report = Report(seed, asdict(scale), tenant)
    digest = hashlib.sha256()
    g = lambda kind, i=0: gid(seed, tenant, kind, i)        # noqa: E731
    conn.execute("SELECT set_config('app.tenant_id', %s, true)", (tenant,))
    conn.execute("SELECT set_config('app.roster_draft_access', 'on', true)")      # Dienstplanrevision wird als Entwurf angelegt und veröffentlicht
    issuer = f'https://idp.synthetic.taktovia.example/realms/taktovia-perf-{index}'
    le, org, region = g('le'), g('org'), g('region')
    _copy(conn, 'legal_entity', ['tenant_id', 'id', 'name', 'country'], [(tenant, le, f'Rechtsträger S{index} (synthetisch)', 'DE')], digest, report)
    _copy(conn, 'organization', ['tenant_id', 'id', 'legal_entity_id', 'name', 'federation_ref', 'roles'], [(tenant, org, le, f'Betreiber S{index} (synthetisch)', f'fed-org-s{index}', '{TRANSPORT_COMPANY}')], digest, report)
    _copy(conn, 'region', ['tenant_id', 'id', 'name', 'created_by'], [(tenant, region, f'Region S{index} (synthetisch)', ACTOR)], digest, report)
    depots = [g('depot', i) for i in range(scale.depots)]
    _copy(conn, 'depot', ['tenant_id', 'id', 'region_id', 'name', 'created_by'], [(tenant, d, region, f'Depot {i + 1} (synthetisch)', ACTOR) for i, d in enumerate(depots)], digest, report)
    services = [g('svc', i) for i in range(scale.services)]
    cfgs = [g('cfg', i) for i in range(scale.services)]
    _copy(conn, 'config_revision', ['tenant_id', 'id', 'scope_kind', 'scope_id', 'revision', 'schema_version', 'values_json', 'effective_from', 'created_by', 'reason'],
          [(tenant, cfgs[i], 'SERVICE', services[i], 1, '1', '{}', '2026-01-01 00:00:00+00', ACTOR, 'Synthetischer Referenzbestand') for i in range(scale.services)], digest, report)
    _copy(conn, 'service', ['tenant_id', 'id', 'operator_id', 'name', 'timezone', 'config_revision_id', 'created_by'],
          [(tenant, services[i], org, f'Rufbus {i + 1} (synthetisch)', 'Europe/Berlin', cfgs[i], ACTOR) for i in range(scale.services)], digest, report)
    box = 'MULTIPOLYGON(((13.60 51.12,13.90 51.12,13.90 50.98,13.60 50.98,13.60 51.12)))'
    _copy(conn, 'service_area', ['tenant_id', 'id', 'service_id', 'revision', 'area', 'valid_from'],
          [(tenant, g('area', i), services[i], 1, f'SRID=4326;{box}', '2026-01-01 00:00:00+00') for i in range(scale.services)], digest, report)
    stops = [g('stoprev', i) for i in range(scale.stops)]
    acc = json.dumps({'schema_version': '1.0', 'step_free': 'YES', 'wheelchair': 'YES'})
    _copy(conn, 'stop_revision', ['tenant_id', 'id', 'stop_id', 'revision', 'point', 'name', 'accessibility', 'approved_by', 'valid_from'],
          [(tenant, stops[i], g('stop', i), 1, f'SRID=4326;POINT({13.62 + rnd.random() * 0.26:.5f} {50.99 + rnd.random() * 0.12:.5f})', f'Haltepunkt {i + 1:04d} (synthetisch)', acc, ACTOR, '2026-01-01 00:00:00+00')
           for i in range(scale.stops)], digest, report)
    tariffs = [g('tariff', i) for i in range(scale.services)]
    _copy(conn, 'tariff_revision', ['tenant_id', 'id', 'service_id', 'tariff_class', 'revision_no', 'valid_from', 'currency', 'reason', 'created_by'],
          [(tenant, tariffs[i], services[i], 'STANDARD', 1, '2026-01-01 00:00:00+00', 'EUR', 'Synthetischer Referenzbestand', ACTOR) for i in range(scale.services)], digest, report)
    # Fahrer: Mitgliedschaft (synthetische Identität), Stamm ohne Kontaktdaten, Qualifikation
    members = [g('member', i) for i in range(scale.drivers)]
    _copy(conn, 'membership', ['tenant_id', 'id', 'issuer', 'subject', 'role_code'], [(tenant, members[i], issuer, f'synthetic-driver-{index}-{i + 1:04d}', 'driver') for i in range(scale.drivers)], digest, report)
    drivers = [g('driver', i) for i in range(scale.drivers)]
    _copy(conn, 'driver', ['tenant_id', 'id', 'membership_id', 'display_name', 'contact_channel', 'contact_value', 'status'],
          [(tenant, drivers[i], members[i], f'Fahrer {i + 1:04d} (synthetisch)', 'APP', 'synthetic:kein-kontakt', 'ACTIVE') for i in range(scale.drivers)], digest, report)
    _copy(conn, 'qualification_revision', ['tenant_id', 'id', 'driver_id', 'qualification_code', 'revision', 'valid_from'],
          [(tenant, g('qual', i), drivers[i], 'PERSONENBEFOERDERUNG', 1, '2026-01-01 00:00:00+00') for i in range(scale.drivers)], digest, report)
    vehicles = [g('veh', i) for i in range(scale.vehicles)]
    cap = json.dumps({'schema_version': '1.0', 'seats': 4, 'wheelchair': 1, 'equipment': ['RAMP']})
    _copy(conn, 'vehicle', ['tenant_id', 'id', 'owner_org_ref', 'capacity_config', 'active_block', 'safety_revision'], [(tenant, vehicles[i], f'fed-org-s{index}', cap, False, 1) for i in range(scale.vehicles)], digest, report)
    _copy(conn, 'vehicle_pool_assignment', ['tenant_id', 'id', 'service_id', 'vehicle_id', 'valid_from', 'created_by'],
          [(tenant, g('pool', i), services[i % scale.services], vehicles[i], '2026-01-01 00:00:00+00', ACTOR) for i in range(scale.vehicles)], digest, report)
    # Dienstplan: je Depot und Kalenderwoche eine veröffentlichte Revision 1, Schichten je Fahrzeug und Betriebstag
    days = [ANCHOR_DAY + dt.timedelta(days=d) for d in range(scale.days)]
    weeks = sorted({d - dt.timedelta(days=d.weekday()) for d in days})
    rosters = {(di, w): g('roster', f'{di}|{w}') for di in range(scale.depots) for w in weeks}
    _copy(conn, 'roster_revision', ['tenant_id', 'id', 'roster_id', 'depot_id', 'week_start', 'revision', 'author'],
          [(tenant, rid, rid, depots[di], w, 1, ACTOR) for (di, w), rid in rosters.items()], digest, report)
    conn.execute("UPDATE taktovia.roster_revision SET state = 'PUBLISHED', published_at = clock_timestamp(), published_by = %s, effective_from = '2026-01-01 00:00:00+00', publish_reason = 'Synthetischer Referenzbestand' WHERE state = 'DRAFT'", (ACTOR,))
    shift_rows, run_rows, rel_rows = [], [], []
    for d_i, day in enumerate(days):
        start = dt.datetime(day.year, day.month, day.day, 4, tzinfo=dt.timezone.utc); end = start + dt.timedelta(hours=10)
        week = day - dt.timedelta(days=day.weekday())
        for v in range(scale.vehicles):
            di = v % scale.depots; sid = g('shift', f'{d_i}|{v}')
            shift_rows.append((tenant, sid, drivers[v % scale.drivers], vehicles[v], _ts(start), _ts(end), 'Europe/Berlin', 1, 'PUBLISHED', rosters[(di, week)], org, depots[di], 'Fahrdienst', 'PERSONENBEFOERDERUNG', 1, ACTOR, services[v % scale.services]))
            run_rows.append((tenant, g('run', f'{d_i}|{v}'), vehicles[v], sid, 1, 'ACTIVE'))
            rel_rows.append((tenant, g('rel', f'{d_i}|{v}'), vehicles[v], sid, 1, g('insp', f'{d_i}|{v}'), 1, _ts(end)))
    _copy(conn, 'staff_shift', ['tenant_id', 'id', 'driver_ref', 'vehicle_id', 'starts_at', 'ends_at', 'timezone', 'roster_revision', 'state', 'roster_id', 'operator_id', 'depot_id', 'shift_function', 'required_qualification', 'staffing_need', 'created_by', 'service_id'], shift_rows, digest, report)
    _copy(conn, 'vehicle_run', ['tenant_id', 'id', 'vehicle_id', 'shift_id', 'revision', 'state'], run_rows, digest, report)
    _copy(conn, 'resource_release', ['tenant_id', 'id', 'vehicle_id', 'shift_id', 'safety_revision', 'inspection_ref', 'qualification_revision', 'valid_until'], rel_rows, digest, report)
    # Fahrgäste und Buchungsbestand
    pax = [g('pax', i) for i in range(scale.passengers)]
    _copy(conn, 'passenger_private', ['tenant_id', 'id', 'subject_token', 'retention_until'], [(tenant, pax[i], f'synthetic-token-{index}-{i + 1:06d}', '2030-12-31 00:00:00+00') for i in range(scale.passengers)], digest, report)
    total = scale.bookings_per_day * scale.days
    if total:
        _copy(conn, 'travel_attempt', ['tenant_id', 'id', 'created_at', 'channel'], ((tenant, g('att', b), _ts(dt.datetime.combine(days[b // scale.bookings_per_day], dt.time(3, 0), tzinfo=dt.timezone.utc)), 'APP') for b in range(total)), digest, report)
        base_t = dt.datetime.combine(days[0], dt.time(3, 0), tzinfo=dt.timezone.utc)

        def quote_rows():
            for b in range(total):
                price = 250 + (b % 17) * 10
                snap = {'schema_version': '1.0', 'currency': 'EUR', 'pay_at_platform_minor': price, 'tariff_class': 'STANDARD', 'seq': b}
                yield (tenant, g('quote', b), g('att', b), 'EUR', price, 0, '{}', _ts(base_t + dt.timedelta(days=scale.days + 1)), tariffs[b % scale.services], cfgs[b % scale.services],
                       json.dumps(snap, sort_keys=True), hashlib.sha256(json.dumps(snap, sort_keys=True).encode()).hexdigest())
        _copy(conn, 'fare_quote', ['tenant_id', 'id', 'attempt_id', 'currency', 'pay_at_platform_minor', 'external_known_minor', 'external_unknown_leg_ids', 'expires_at', 'tariff_revision_id', 'config_revision_id', 'snapshot', 'content_hash'], quote_rows(), digest, report)
        _copy(conn, 'journey', ['tenant_id', 'id', 'passenger_id', 'quote_id', 'state', 'created_at', 'currency'], ((tenant, g('journey', b), pax[b % scale.passengers], g('quote', b), 'BOOKED', _ts(base_t), 'EUR') for b in range(total)), digest, report)
        per_run = max(1, scale.bookings_per_day // scale.vehicles)
        vec = json.dumps({'schema_version': '1.0', 'seats': 1})

        def hold_rows():
            for b in range(total):
                d_i, k = divmod(b, scale.bookings_per_day); v = k % scale.vehicles; seg = (k // scale.vehicles) % 60
                yield (tenant, g('hold', b), g('run', f'{d_i}|{v}'), 1, f'fed-hold-{index}-{b:08d}', vec, seg, seg + 1, _ts(base_t + dt.timedelta(days=d_i, hours=12)), 'CONSUMED')
        _copy(conn, 'capacity_hold', ['tenant_id', 'id', 'run_id', 'run_revision', 'federation_ref', 'capacity_vector', 'interval_from', 'interval_to', 'expires_at', 'state'], hold_rows(), digest, report)

        def booking_rows():
            for b in range(total):
                d_i, k = divmod(b, scale.bookings_per_day)
                pick = base_t + dt.timedelta(days=d_i, hours=3, minutes=(k * 7) % 540)
                snap = {'schema_version': '1.0', 'pickup_earliest': pick.isoformat(), 'pickup_latest': (pick + dt.timedelta(minutes=10)).isoformat(), 'arrival_earliest': (pick + dt.timedelta(minutes=15)).isoformat(),
                        'arrival_latest': (pick + dt.timedelta(minutes=30)).isoformat(), 'passengers': 1}
                yield (tenant, g('booking', b), g('hold', b), services[b % scale.services], f'fed-booking-{index}-{b:08d}', 'CONFIRMED', json.dumps(snap), 1)
        _copy(conn, 'booking', ['tenant_id', 'id', 'hold_id', 'service_id', 'federation_ref', 'state', 'promise_snapshot', 'revision'], booking_rows(), digest, report)
        _copy(conn, 'payment_intent', ['tenant_id', 'id', 'journey_id', 'provider', 'provider_ref', 'state', 'currency', 'authorized_minor'],
              ((tenant, g('pay', b), g('journey', b), 'simulator', f'sim-{index}-{b:08d}', 'AUTHORIZED', 'EUR', 250 + (b % 17) * 10) for b in range(total)), digest, report)
    # Positionsproben: je Fahrzeug und Betriebstag im Raster der Schicht
    def position_rows():
        for d_i, day in enumerate(days):
            start = dt.datetime(day.year, day.month, day.day, 4, tzinfo=dt.timezone.utc)
            for v in range(scale.vehicles):
                sid = g('shift', f'{d_i}|{v}')
                for s in range(0, 10 * 3600, scale.position_interval_s):
                    yield (tenant, g('pos', f'{d_i}|{v}|{s}'), vehicles[v], sid, f'SRID=4326;POINT({13.62 + ((v * 37 + s // 60) % 260) / 1000:.5f} {50.99 + ((v * 17 + s // 90) % 120) / 1000:.5f})', 10, _ts(start + dt.timedelta(seconds=s)), ACTOR)
    _copy(conn, 'position_sample', ['tenant_id', 'id', 'vehicle_id', 'shift_id', 'position', 'accuracy_m', 'measured_at', 'created_by'], position_rows(), digest, report)
    conn.commit()
    report.seconds = round(time.monotonic() - t0, 2)
    report.data_state_sha256 = digest.hexdigest()
    return report


def row_counts(conn, tenant: str) -> dict:
    """Zeilenzahlen je Tabelle für den Mandanten (unter der Runtime-Rolle, Tenantkontext gesetzt)."""
    conn.execute("SELECT set_config('app.tenant_id', %s, true)", (tenant,))
    conn.execute("SELECT set_config('app.roster_draft_access', 'on', true)")
    out = {t: conn.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0] for t in TABLES}  # sql-lint: ok festes Tabellenverzeichnis des Generators, keine Eingabe
    conn.rollback()
    return out


def scan_synthetic(conn, tenant: str) -> list[str]:
    """Funde, die nicht synthetisch aussehen (Klarnamen, Mailadressen, Telefonnummern, fremde Pseudonyme); leer = nur synthetisch."""
    conn.execute("SELECT set_config('app.tenant_id', %s, true)", (tenant,))
    finds: list[str] = []
    checks = [
        ("SELECT name FROM taktovia.legal_entity UNION ALL SELECT name FROM taktovia.organization UNION ALL SELECT name FROM taktovia.region UNION ALL SELECT name FROM taktovia.depot UNION ALL SELECT name FROM taktovia.service "
         "UNION ALL SELECT name FROM taktovia.stop_revision UNION ALL SELECT display_name FROM taktovia.driver", lambda s: s.endswith('(synthetisch)')),
        ('SELECT subject_token FROM taktovia.passenger_private', lambda s: s.startswith('synthetic-token-')),
        ('SELECT contact_value FROM taktovia.driver', lambda s: s == 'synthetic:kein-kontakt'),
        ('SELECT issuer || subject FROM taktovia.membership', lambda s: 'synthetic' in s and '@' not in s),
    ]
    for sql, ok in checks:
        for (v,) in conn.execute(sql).fetchall():  # sql-lint: ok feste Prüfanweisungen des Generators
            if not ok(v):
                finds.append(f'{v!r}')
    conn.rollback()
    return finds
