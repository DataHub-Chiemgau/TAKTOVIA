"""Last für die Ausfallübung (WP-M5-15): Buchungen mit Kapazitätshold, Zahlungen gegen den PSP-Simulator, Löschvorgänge und Fahrzeugsperren unter der **Runtime-Rolle** (RLS) auf dem Quellcluster.

Jede Transaktion committet einzeln; **nach** dem Commit wird das Ereignis in den Ereignisstrom (außerhalb des Clusters) geschrieben und der Commit mit Zeitstempel als bestätigt vermerkt
(`acks`). Die Reihenfolge „Anbieter-Aufruf, dann lokaler Commit“ erzeugt im Ausfall das echte Muster: der Anbieter ist weiter als die wiederhergestellte Datenbank.
Nicht abgebildet (nicht ausgeführt): die Fachlogik der Anwendung (Hold-Befehl, Zahlungsautomat in Kotlin); die Last schreibt dieselben Tabellen mit denselben Zustandsübergängen per SQL."""
import json
import threading
import time
import uuid
from datetime import datetime, timezone

import psycopg

from .umgebung import DB, jetzt


def seed_mandant(adm, name: str, journeys: int = 2500, fahrzeuge_fuer_sperren: int = 40, sitze: int = 100000) -> dict:
    """Mandant, Dienst, Fahrzeug mit Umlauf, vorbereitete Journeys (Zahlungsplätze), Fahrzeuge für Sperren; Administratorverbindung, Fachketten ohne Prüfungen (nur Testaufbau)."""
    t, le, org, cfg, svc, veh, shift, run = (uuid.uuid4() for _ in range(8))
    adm.execute("SET session_replication_role = replica")
    adm.execute("INSERT INTO taktovia.currency(code,exponent) VALUES ('EUR',2) ON CONFLICT DO NOTHING")
    adm.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,%s,'ACTIVE')", (t, name))
    adm.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'Übung-Rechtsträger','DE')", (t, le))
    adm.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,'Übung-Betreiber',%s,'{BROKER}')", (t, org, le, f"uebung-{t}"))
    adm.execute("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) "
                "VALUES (%s,%s,'SERVICE',%s,1,'1.0','{}'::jsonb,now()-interval '1 day','user:uebung','Übung')", (t, cfg, svc))
    adm.execute("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id) VALUES (%s,%s,%s,'Übungsdienst','Europe/Berlin',%s)", (t, svc, org, cfg))
    adm.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'uebung',%s::jsonb)", (t, veh, json.dumps({"seats": sitze, "wheelchair": 1})))
    adm.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,now(),now()+interval '8 hours','Europe/Berlin',1,'PLANNED')", (t, shift))
    adm.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'ACTIVE')", (t, run, veh, shift))
    js = [uuid.uuid4() for _ in range(journeys)]
    with adm.cursor() as cur:
        cur.executemany("INSERT INTO taktovia.journey(tenant_id,id,passenger_id,quote_id,state,created_at,currency) VALUES (%s,%s,%s,%s,'OPEN',now(),'EUR')", [(t, j, uuid.uuid4(), uuid.uuid4()) for j in js])
    spare = [uuid.uuid4() for _ in range(fahrzeuge_fuer_sperren)]
    with adm.cursor() as cur:
        cur.executemany("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (%s,%s,'uebung',%s::jsonb,%s)", [(t, v, json.dumps({"seats": 4, "wheelchair": 0}), f"U-{i:03d}") for i, v in enumerate(spare)])
    pax = [uuid.uuid4() for _ in range(120)]           # Fahrgastkonten VOR der Basissicherung: ihre spätere Löschung kann im Verlustfenster liegen (Wiederauferstehung ohne Journal)
    with adm.cursor() as cur:
        cur.executemany("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,now()+interval '1 year')", [(t, p, f"tok-{p}") for p in pax])
    rule = uuid.uuid4()
    adm.execute("INSERT INTO taktovia.retention_rule(tenant_id,id,data_class,retention_days,action,changed_by,status,approved_by,approved_at) VALUES (%s,%s,'LK_PII',1,'ANONYMIZE','user:a','APPROVED','user:b',now())", (t, rule))
    return {"tenant": t, "service": svc, "vehicle": veh, "run": run, "journeys": js, "sperr_fahrzeuge": spare, "passagiere": pax, "rule": rule, "name": name}


class Last:
    def __init__(self, uebung, mandanten: list[dict], sim, tempo_s: float = 0.05, schreiber_je_mandant: int = 2):
        self.u, self.m, self.sim, self.tempo, self.n = uebung, mandanten, sim, tempo_s, schreiber_je_mandant
        self.stop_ev = threading.Event()
        self.threads: list[threading.Thread] = []
        self.lock = threading.Lock()
        self.acks: list[tuple[datetime, str, str]] = []          # (Zeitpunkt nach dem Commit, Art, Kennung)
        self.fehler: list[str] = []
        self.zaehler = {"buchungen": 0, "zahlungen_erfasst": 0, "loeschungen": 0, "sperren": 0}
        self.journeys = {str(m["tenant"]): list(m["journeys"]) for m in mandanten}
        self.seq = 0

    def _ack(self, art: str, ident):
        with self.lock:
            self.acks.append((jetzt(), art, str(ident)))

    def _next(self) -> int:
        with self.lock:
            self.seq += 1
            return self.seq

    def _tx(self, tenant):
        c = self.u.cluster.runtime_connect()
        c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))
        return c

    # --- Schreiber ----------------------------------------------------------------------------------------------------------
    def buchung(self, m: dict) -> None:
        t, svc, run = m["tenant"], m["service"], m["run"]
        with self.lock:
            q = self.journeys[str(t)]
            if not q:
                return                                       # keine freien Zahlungsplätze mehr: dieser Schreiber ruht (die übrige Last läuft weiter)
            journey = q.pop()
        n = self._next()
        mref = f"u-{str(t)[:8]}-{n:07d}"
        intent, hold, booking = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
        # T1: Zahlung angelegt (Zustand CREATED)
        c = self._tx(t)
        try:
            c.execute("INSERT INTO taktovia.payment_intent(tenant_id,id,journey_id,provider,state,currency,authorized_minor,merchant_ref,requested_minor,service_id) "
                      "VALUES (%s,%s,%s,'simpsp','CREATED','EUR',950,%s,950,%s)", (t, intent, journey, mref, svc))
            c.commit()
        finally:
            c.close()
        self._ack("payment.created", mref)
        # Anbieter: Autorisierung (Wirkung beim Anbieter, danach erst lokal festgehalten)
        resp, _ = self.sim.execute("authorize", "", {"merchant_ref": mref, "amount_minor": 950, "currency": "EUR", "payment_method": "pm_ok"}, f"auth-{mref}")
        if resp.status >= 300:
            raise RuntimeError(f"PSP authorize {resp.status} {resp.body}")
        pay = resp.body
        # T2: Autorisierung lokal
        c = self._tx(t)
        try:
            c.execute("UPDATE taktovia.payment_intent SET state='AUTHORIZED', provider_ref=%s, psp_payment_id=%s, revision=revision+1, updated_at=clock_timestamp() WHERE tenant_id=%s AND id=%s",
                      (pay["provider_ref"], pay["payment_id"], t, intent))
            c.commit()
        finally:
            c.close()
        self._ack("payment.authorized", mref)
        self.u.bus.publish("payment.authorized", t, merchant_ref=mref, payment_id=pay["payment_id"])
        # T3: Kapazitätshold, Bestätigung und Kopplung in EINER Transaktion (Kapazität nur in PostgreSQL: Sperre auf den Umlauf, dann Belegung prüfen)
        c = self._tx(t)
        try:
            c.execute("SELECT revision FROM taktovia.vehicle_run WHERE tenant_id=%s AND id=%s FOR UPDATE", (t, run))
            belegt = c.execute("SELECT coalesce(sum((capacity_vector->>'seats')::int), 0) FROM taktovia.capacity_hold WHERE tenant_id=%s AND run_id=%s AND state IN ('HELD','CONSUMED') "
                               "AND interval_from < 1 AND interval_to > 0", (t, run)).fetchone()[0]
            if belegt + 1 > 100000:
                c.rollback()
                return
            c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
                      "VALUES (%s,%s,%s,1,%s,'{\"seats\":1}'::jsonb,0,1,now()+interval '1 hour','HELD')", (t, hold, run, f"fh-{hold}"))
            c.execute("UPDATE taktovia.capacity_hold SET state='CONSUMED' WHERE tenant_id=%s AND id=%s", (t, hold))
            c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,%s,'CONFIRMED','{}'::jsonb,1)", (t, booking, hold, svc, f"fb-{booking}"))
            c.execute("UPDATE taktovia.payment_intent SET booking_id=%s, coupled_at=clock_timestamp(), revision=revision+1, updated_at=clock_timestamp() WHERE tenant_id=%s AND id=%s", (booking, t, intent))
            c.execute("INSERT INTO taktovia.outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload,recipient_tenant_id) "
                      "VALUES (%s,%s,%s,1,'booking.confirmed','1.0',clock_timestamp(),%s::jsonb,%s)", (t, uuid.uuid4(), booking, json.dumps({"merchant_ref": mref}), t))
            c.commit()
        finally:
            c.close()
        self._ack("booking.confirmed", booking)
        self.u.bus.publish("booking.confirmed", t, booking_id=booking, hold_id=hold, merchant_ref=mref)
        # Anbieter: Einzug; danach lokal
        resp, _ = self.sim.execute("capture", pay["payment_id"], {"amount_minor": 950}, f"cap-{mref}")
        if resp.status >= 300:
            raise RuntimeError(f"PSP capture {resp.status} {resp.body}")
        c = self._tx(t)
        try:
            c.execute("UPDATE taktovia.payment_intent SET state='CAPTURE_PENDING', revision=revision+1, updated_at=clock_timestamp() WHERE tenant_id=%s AND id=%s", (t, intent))
            c.execute("UPDATE taktovia.payment_intent SET state='CAPTURED', captured_minor=950, revision=revision+1, updated_at=clock_timestamp() WHERE tenant_id=%s AND id=%s", (t, intent))
            c.execute("INSERT INTO taktovia.outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload,recipient_tenant_id) "
                      "VALUES (%s,%s,%s,5,'payment.captured','1.0',clock_timestamp(),%s::jsonb,%s)", (t, uuid.uuid4(), intent, json.dumps({"merchant_ref": mref}), t))
            c.commit()
        finally:
            c.close()
        self._ack("payment.captured", mref)
        self.u.bus.publish("payment.captured", t, merchant_ref=mref, payment_id=pay["payment_id"])
        with self.lock:
            self.zaehler["buchungen"] += 1
            self.zaehler["zahlungen_erfasst"] += 1

    def loeschung(self, m: dict) -> None:
        t, rule = m["tenant"], m["rule"]
        job = uuid.uuid4()
        with self.lock:
            if not m["passagiere"]:
                return
            pax = m["passagiere"].pop()
        c = self._tx(t)
        try:
            c.execute("INSERT INTO taktovia.erasure_job(tenant_id,id,rule_id,rule_revision,data_class,action,retention_days,created_by) VALUES (%s,%s,%s,1,'LK_PII','ANONYMIZE',1,'user:uebung')", (t, job, rule))
            c.execute("UPDATE taktovia.passenger_private SET erased_at=clock_timestamp() WHERE tenant_id=%s AND id=%s", (t, pax))
            c.execute("INSERT INTO taktovia.erasure_journal(tenant_id,id,job_id,data_class,object_ref,action) VALUES (%s,%s,%s,'LK_PII',%s,'ANONYMIZE')", (t, uuid.uuid4(), job, f"passenger:{pax}"))
            c.commit()
        finally:
            c.close()
        # Eine Löschung gilt erst als bestätigt, wenn das Journal AUSSERHALB der Datenbank das Segment hat (synchroner Export); sonst könnte sie im Verlustfenster spurlos verloren gehen.
        if self.u.journal is None or not self.u.journal.einmal():
            return
        self._ack("passenger.erased", pax)
        with self.lock:
            self.zaehler["loeschungen"] += 1

    def sperre(self, m: dict) -> None:
        t = m["tenant"]
        with self.lock:
            if not m["sperr_fahrzeuge"]:
                return
            veh = m["sperr_fahrzeuge"].pop()
        block = uuid.uuid4()
        c = self._tx(t)
        try:
            c.execute("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,set_by) VALUES (%s,%s,%s,'SAFETY','DEFECT_CRITICAL','user:uebung')", (t, block, veh))
            c.commit()
        finally:
            c.close()
        if not self.u.bus.publish("vehicle.blocked", t, block_id=block, vehicle_id=veh, reason_code="DEFECT_CRITICAL"):      # außerhalb der Datenbank festgehalten, erst dann bestätigt
            return
        self._ack("vehicle.blocked", block)
        with self.lock:
            self.zaehler["sperren"] += 1

    # --- Steuerung ----------------------------------------------------------------------------------------------------------
    def _schleife(self, fn, m, takt):
        while not self.stop_ev.is_set():
            try:
                fn(m)
            except (psycopg.OperationalError, psycopg.errors.AdminShutdown, psycopg.errors.CrashShutdown) as e:
                self.fehler.append(f"Verbindungsabbruch ({type(e).__name__}) beim Ausfall")
                return
            except Exception as e:  # noqa: BLE001
                self.fehler.append(f"{type(e).__name__}: {str(e)[:200]}")
                if self.stop_ev.is_set():
                    return
                time.sleep(0.2)
            self.stop_ev.wait(takt)

    def start(self):
        for m in self.m:
            for _ in range(self.n):
                self.threads.append(threading.Thread(target=self._schleife, args=(self.buchung, m, self.tempo), daemon=True))
            self.threads.append(threading.Thread(target=self._schleife, args=(self.loeschung, m, 0.5), daemon=True))
            self.threads.append(threading.Thread(target=self._schleife, args=(self.sperre, m, 3.0), daemon=True))
        [t.start() for t in self.threads]

    def stop(self):
        self.stop_ev.set()
        [t.join(15) for t in self.threads]
