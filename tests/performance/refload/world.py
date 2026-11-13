"""Mandantenwelt für die Lastläufe: Mandant, Mitgliedschaften, Fahrgastkonten, Dienst mit Betriebszeiten, Bediengebiet, Haltepunkten und veröffentlichtem Tarif (alles über die API der
echten Anwendung, Vier-Augen-Veröffentlichung des Tarifs) sowie eine Testflotte (Fahrzeuge, Schichten, Freigaben, Poolzuordnung, Umläufe), die per SQL entsteht (Testaufbau, der Fahrzeugstamm hat in
der Produktion eigene Pflegewege). Beide Mandanten tragen bewusst **gleiche Objektnamen** (Dienst „Rufbus“, Haltepunkte „Markt“ und „Bahnhof“), damit Verwechslungen auffallen.

Planbindung des Angebots: Die Produktions-Verdrahtung bindet Angebote noch nicht an eine Planrevision (Tor `CandidateGate.PASS_THROUGH`, siehe BookingConfiguration); für Hold und Confirm
unter Last bindet der Testaufbau jedes Angebot per SQL an einen Umlauf der Testflotte (wie `OfferHttpSupport.bind` im Backend-Test). Diese Bindung wird nicht mitgemessen und ist
kein Ersatz für den Nachweis der Verdrahtung."""
from __future__ import annotations

import itertools
import json
import threading
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

import psycopg

HOURS = {"traffic_product": "AREA_ON_DEMAND", "weekly_hours": [{"weekdays": ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"], "from": "00:00", "to": "24:00"}], "exceptions": [],
         "valid_from": "2026-01-01T00:00:00+01:00", "valid_to": None, "reason": "Last"}
AREA = {"type": "MultiPolygon", "coordinates": [[[[13.70, 51.00], [13.80, 51.00], [13.80, 51.10], [13.70, 51.10], [13.70, 51.00]]]]}


class ApiError(RuntimeError):
    pass


def call(base: str, method: str, path: str, token: str, body=None, if_match: str | None = None, key: bool = True, timeout: float = 30.0) -> tuple[int, dict | None, dict]:
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    h = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    if data is not None:
        h["Content-Type"] = "application/json"
    if method != "GET" and key:
        h["Idempotency-Key"] = "w-" + uuid.uuid4().hex
    if if_match is not None:
        h["If-Match"] = if_match if if_match.startswith('"') else f'"{if_match}"'
    req = urllib.request.Request(base + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None), dict(r.headers)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None, dict(e.headers)
        except ValueError:
            return e.code, {"raw": raw[:200].decode(errors="replace")}, dict(e.headers)


def need(resp, expected, what):
    status, body, _ = resp
    if status != expected:
        raise ApiError(f"{what}: HTTP {status} {json.dumps(body)[:300]}")
    return body


@dataclass
class TenantWorld:
    label: str
    tenant_id: str
    service_id: str = ""
    days: int = 3                                              # Angebote gelten für den Tag in [days] Tagen (Schichten der Testflotte decken ihn ab)
    admin_sub: str = ""
    planner_sub: str = ""
    passengers: list[str] = field(default_factory=list)       # Subjects der Fahrgastkonten
    runs: list[str] = field(default_factory=list)             # Umläufe der Testflotte
    last_seat_run: str = ""                                    # Umlauf mit genau einem Sitz
    _seg: dict = field(default_factory=dict)
    _lock: threading.Lock = field(default_factory=threading.Lock)
    _rr: itertools.count = field(default_factory=itertools.count)

    def next_run_segment(self) -> tuple[str, int]:
        """Umlauf (reihum) und ein bisher nie benutzter Abschnitt: Angebote der Last überlappen sich nie, Kapazitätsrennen prüft ein eigener Umlauf."""
        with self._lock:
            run = self.runs[next(self._rr) % len(self.runs)]
            seg = self._seg.get(run, 0)
            self._seg[run] = seg + 1
            return run, seg


def admin_conn(db: str, dbtool) -> psycopg.Connection:
    return dbtool.admin(db)


def create_tenant(admin, name: str) -> str:
    tid = str(uuid.uuid4())
    admin.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,%s,'ACTIVE')", (tid, name))
    return tid


def grant(admin, tenant: str, issuer: str, subject: str, role: str) -> None:
    admin.execute("SELECT set_config('app.tenant_id', %s, false)", (tenant,))
    admin.execute("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,%s,%s,%s)", (tenant, uuid.uuid4(), issuer, subject, role))


def build_tenant(app, idp, admin, label: str, passengers: int, runs: int, seats: int = 8, name: str | None = None) -> TenantWorld:
    """Legt Mandant, Konten, Dienst, Gebiet, Haltepunkte, Tarif und Testflotte an. [admin] ist eine Administratorverbindung (autocommit)."""
    tid = create_tenant(admin, name or f"Mandant {label}")
    w = TenantWorld(label, tid)
    admin.execute("SELECT set_config('app.tenant_id', %s, false)", (tid,))
    w.admin_sub, w.planner_sub = str(uuid.uuid4()), str(uuid.uuid4())
    grant(admin, tid, idp.issuer, w.admin_sub, "tenant_admin")
    grant(admin, tid, idp.issuer, w.planner_sub, "planner")
    rows_m, rows_p = [], []
    for _ in range(passengers):
        sub = str(uuid.uuid4()); w.passengers.append(sub)
        rows_m.append((tid, uuid.uuid4(), idp.issuer, sub, "passenger"))
        pid = uuid.uuid4()
        rows_p.append((tid, pid, f"tok-{pid}", idp.issuer, sub))
    with admin.cursor() as cur:
        cur.executemany("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,%s,%s,%s)", rows_m)
        cur.executemany("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until,idp_issuer,idp_subject,status) VALUES (%s,%s,%s,now() + interval '5 years',%s,%s,'ACTIVE')", rows_p)
    t_admin, t_plan = idp.token(w.admin_sub), idp.token(w.planner_sub)
    b = app.base
    le = need(call(b, "POST", "/v1/legal-entities", t_admin, {"name": "LE", "country": "DE"}), 201, "legal-entity")["id"]
    op = need(call(b, "POST", "/v1/organizations", t_admin, {"legal_entity_id": le, "name": "Betreiber", "federation_ref": f"f-{uuid.uuid4()}", "roles": ["TRANSPORT_COMPANY"]}), 201, "organization")["id"]
    w.service_id = need(call(b, "POST", "/v1/services", t_admin, {"operator_id": op, "name": "Rufbus", "timezone": "Europe/Berlin"}), 201, "service")["id"]
    need(call(b, "PUT", f"/v1/services/{w.service_id}/operating-hours", t_admin, HOURS, if_match="0"), 200, "operating-hours")
    need(call(b, "POST", "/v1/service-areas", t_admin, {"service_id": w.service_id, "area": AREA, "valid_from": "2026-01-01T00:00:00+01:00", "valid_to": None}), 201, "service-area")
    for nm, lat, lon in (("Markt", 51.05, 13.74), ("Bahnhof", 51.08, 13.78)):
        need(call(b, "POST", "/v1/stops", t_admin, {"name": nm, "point": {"latitude": lat, "longitude": lon},
                                                    "accessibility": {"schema_version": "1.0", "step_free": "YES", "wheelchair_boarding": "YES"}, "traffic_products": ["AREA_ON_DEMAND"],
                                                    "active": True, "valid_from": "2026-01-01T00:00:00+01:00"}), 201, f"stop {nm}")
    tariff = need(call(b, "POST", "/v1/tariffs", t_admin, {"service_id": w.service_id, "tariff_class": "STANDARD", "valid_from": "2026-01-01T00:00:00+01:00", "valid_to": None, "currency": "EUR",
                                                           "reason": "Lasttarif", "rules": [{"rule_type": "FESTPREIS", "amount_minor": 450, "tax_rate": "0.07", "tax_rule_revision": "DE-2026-1"}]}), 201, "tariff")["id"]
    need(call(b, "POST", f"/v1/tariffs/{tariff}/review", t_plan, {}, if_match="1"), 200, "tariff review")
    need(call(b, "POST", f"/v1/tariffs/{tariff}/publish", t_plan, {}, if_match="2"), 200, "tariff publish")
    _build_fleet(admin, w, runs, seats)
    return w


def _build_fleet(admin, w: TenantWorld, runs: int, seats: int) -> None:
    """Testflotte: [runs] Fahrzeuge mit [seats] Sitzen und einem Rollstuhlplatz, je Fahrzeug eine Schicht (veröffentlicht, 30 Tage), Freigabe, Poolzuordnung und ein aktiver Umlauf; dazu ein Umlauf mit einem Sitz."""
    tid = w.tenant_id
    admin.execute("SELECT set_config('app.tenant_id', %s, false)", (tid,))
    centre = desired_at(w.days)
    start, end = centre - timedelta(hours=11), centre + timedelta(hours=11)   # Schichten sind auf 24 Stunden begrenzt (staff_shift_max_24h)
    veh, shift, rel, pool, run = [], [], [], [], []
    for i in range(runs + 1):
        v, s, r = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
        cap = json.dumps({"schema_version": "1.0", "seats": 1 if i == runs else seats, "wheelchair": 1, "equipment": ["RAMP"]})
        veh.append((tid, v, "fed-org-last", cap, False, 1))
        shift.append((tid, s, uuid.uuid4(), v, start, end, "Europe/Berlin", 1, "PUBLISHED"))
        rel.append((tid, uuid.uuid4(), v, s, 1, uuid.uuid4(), 1, end))
        pool.append((tid, uuid.uuid4(), w.service_id, v, "2026-01-01T00:00:00Z", "system:last"))
        run.append((tid, r, v, s, 1, "ACTIVE"))
        if i == runs:
            w.last_seat_run = str(r)
        else:
            w.runs.append(str(r))
    with admin.cursor() as cur:
        cur.executemany("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (%s,%s,%s,CAST(%s AS jsonb),%s,%s)", veh)
        cur.executemany("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)", shift)
        cur.executemany("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)", rel)
        cur.executemany("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (%s,%s,%s,%s,%s,%s)", pool)
        cur.executemany("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,%s,%s)", run)


def bind_offer(admin, tenant: str, offer_id: str, run: str, seg: int) -> None:
    """Bindet das Angebot an einen Umlauf (Testaufbau): Wächtertrigger ruhen nur in dieser Sitzung (`session_replication_role = replica`), keine Tabellensperre."""
    with admin.transaction():
        admin.execute("SET LOCAL session_replication_role = replica")
        admin.execute("SELECT set_config('app.tenant_id', %s, true)", (tenant,))
        admin.execute("UPDATE taktovia.ride_offer SET run_id=%s, plan_revision=1, interval_from=%s, interval_to=%s WHERE tenant_id=%s AND id=%s", (run, seg, seg + 1, tenant, offer_id))


def desired_at(days: int) -> datetime:
    """Abfahrtswunsch in [days] Tagen um 10:00 Ortszeit Berlin."""
    from zoneinfo import ZoneInfo
    return (datetime.now(ZoneInfo("Europe/Berlin")) + timedelta(days=days)).replace(hour=10, minute=0, second=0, microsecond=0)


def offer_body(service_id: str, days: int = 3, passengers: int = 1, wheelchair: int = 0) -> dict:
    when = desired_at(days)
    body = {"service_id": service_id, "origin": {"point": {"latitude": 51.0501, "longitude": 13.7401}}, "destination": {"point": {"latitude": 51.0801, "longitude": 13.7801}},
            "time_kind": "DEPARTURE", "desired": {"desired_at": when.isoformat(timespec="seconds"), "time_zone": "Europe/Berlin"}, "passengers": passengers}
    if wheelchair:
        body["wheelchair_spaces"] = wheelchair
    return body


# --- Fahrerwelt (Fahrersitzungen) -----------------------------------------------------------------------------------------------------------------

@dataclass
class DriverRig:
    subject: str
    vehicle_id: str
    shift_id: str
    device_id: str
    sid: str = ""


def provision_drivers(app, idp, admin, w: TenantWorld, count: int, workers: int = 8) -> list[DriverRig]:
    """Je Fahrer: Mitgliedschaft, Stamm, Fahrzeug (SQL), Schicht (API, Disposition), Gerät (API) und Dienstantritt (API). So wie im Backend-Test `DriverPositionApiTest.World`."""
    from concurrent.futures import ThreadPoolExecutor
    tid = w.tenant_id
    disp_sub = str(uuid.uuid4())
    admin.execute("SELECT set_config('app.tenant_id', %s, false)", (tid,))
    grant(admin, tid, idp.issuer, disp_sub, "dispatcher")
    rows = []
    for i in range(count):
        sub, mem, drv, veh = str(uuid.uuid4()), uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
        rows.append((sub, mem, drv, veh))
    with admin.cursor() as cur:
        cur.executemany("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,%s,%s,'driver')", [(tid, r[1], idp.issuer, r[0]) for r in rows])
        cur.executemany("INSERT INTO taktovia.driver(tenant_id,id,membership_id,display_name,contact_channel,contact_value) VALUES (%s,%s,%s,'Fahrer','APP','x')", [(tid, r[2], r[1]) for r in rows])
        cur.executemany("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (%s,%s,'fixture','{}',%s)", [(tid, r[3], f"L-{k:04d}") for k, r in enumerate(rows)])
    now = datetime.now(timezone.utc)

    def setup(r):
        sub, _mem, drv, veh = r
        sid = "sid-" + str(uuid.uuid4())
        td, tdr = idp.token(disp_sub), idp.token(sub, sid=sid)
        sh = need(call(app.base, "POST", "/v1/shifts", td, {"driver_id": str(drv), "vehicle_id": str(veh), "starts_at": (now - timedelta(minutes=10)).isoformat(), "ends_at": (now + timedelta(hours=8)).isoformat(),
                                                           "timezone": "Europe/Berlin"}), 201, "shift")["id"]
        dev = str(uuid.uuid4())
        need(call(app.base, "POST", "/v1/driver/devices", tdr, {"device_id": dev, "platform": "ANDROID", "app_version": "1.0.0"}), 201, "device")
        need(call(app.base, "POST", "/v1/driver/shift-starts", tdr, {"shift_id": sh, "vehicle_id": str(veh), "device_id": dev}), 200, "shift-start")
        return DriverRig(sub, str(veh), sh, dev, sid)

    with ThreadPoolExecutor(workers) as ex:
        return list(ex.map(setup, rows))
