"""WP-M5-14: Wiederanlaufdrehbuch, Konsistenzprüfungen nach Restore und Freigabeschranke (echte lokale PostgreSQL, echte pg_dump/pg_restore).

Ausgeführt: Trockenlauf aller Schritte gegen eine Quelldatenbank mit Referenzdaten (Basis: logisches Backup aus WP-M1-39; die Wiederherstellung auf einen Zeitpunkt aus dem WAL ist in
test_pitr.py belegt), Messung von RPO und RTO nach den Messdefinitionen, Konsistenzprüfungen (positiv und je Befund negativ), Sperre der Freigabe bei fehlender Journalwirkung,
fehlendem Abgleich oder blockierendem Befund, verbindliche Schrittfolge.
Nicht ausgeführt: Ausführung des Drehbuchs durch eine Person, die es nicht geschrieben hat (Abnahme 2: dieser Trockenlauf ist automatisiert und ersetzt sie nicht), der PSP-Abgleich gegen den
Simulator im selben Lauf (er läuft in backend/modules/fare-payment: PaymentRestoreReconcileTest; hier ein ausdrücklich gekennzeichneter Platzhalter), die Synthetikprobe aus WP-M5-05
(hier eine Datenbank-Probe als Ersatz).
Mit TAKTOVIA_PROTOCOL_OUT=<Pfad> schreibt der Trockenlauf sein Protokoll dorthin.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "infra" / "backup"))

try:
    import psycopg
    from taktovia_backup import consistency, crypto, dbops, drehbuch
    from taktovia_backup.store import BackupStore
    IMPORT_ERROR = None
except ImportError as e:  # pragma: no cover
    IMPORT_ERROR = e

MISSING = [k for k in ("TAKTOVIA_DB_ADMIN_PASSWORD",) if not os.environ.get(k)]
REASON = None
if IMPORT_ERROR is not None:
    REASON = f"Abhängigkeit fehlt: {IMPORT_ERROR}"
elif MISSING:
    REASON = f"Datenbank-Zugangsdaten nicht gesetzt: {MISSING}"
if REASON and os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
    raise AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true): " + REASON)


class FakeClock:
    def __init__(self, start):
        self.t = start

    def __call__(self):
        return self.t

    def advance(self, **kw):
        self.t += timedelta(**kw)


@unittest.skipIf(REASON is not None, REASON or "")
class WiederanlaufDrehbuch(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cluster = dbops.Cluster()
        cls.src = "taktovia_wa_src_" + uuid.uuid4().hex[:8]
        env = {**os.environ, "TAKTOVIA_DB_NAME": cls.src}
        for step in ("reset", "migrate"):
            subprocess.run([sys.executable, str(REPO / "db" / "db.py"), step], env=env, check=True, capture_output=True)
        cls.tmp = tempfile.TemporaryDirectory(prefix="tkwa-")
        cls.root = Path(cls.tmp.name)
        cls.key = crypto.generate_key(cls.root / "backup.key")
        cls.store = BackupStore(cls.root / "store", "ops-backup")
        cls.rstore = BackupStore(cls.root / "store", "ops-restore")
        cls.restored = []
        cls.A = uuid.uuid4()
        cls.seed()

    @classmethod
    def seed(cls):
        a = cls.A
        cls.run_id, cls.vehicle, cls.hold1, cls.hold2, cls.booking1, cls.pax_deleted, cls.pax_keep = (uuid.uuid4() for _ in range(7))
        with cls.cluster.connect(cls.src) as c:
            c.execute("SET session_replication_role = replica")        # nur Testaufbau: Fachketten außerhalb der Prüfungen (Schicht, Dienst, Fahrgastkonto) nicht nötig
            c.execute("INSERT INTO taktovia.currency(code,exponent) VALUES ('EUR',2) ON CONFLICT DO NOTHING")
            c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'wa-A','ACTIVE')", (a,))
            le, org, cfg, svc, shift = (uuid.uuid4() for _ in range(5))
            cls.service = svc
            c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'Referenz-Rechtstraeger','DE')", (a, le))
            c.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,'Referenz-Org',%s,'{BROKER}')", (a, org, le, f"ref-{a}"))
            c.execute("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (%s,%s,'SERVICE',%s,1,'1.0','{}'::jsonb,now()-interval '1 day','user:a','Referenz')", (a, cfg, svc))
            c.execute("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id) VALUES (%s,%s,%s,'Referenzdienst','Europe/Berlin',%s)", (a, svc, org, cfg))
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'ref','{\"seats\": 3, \"wheelchair\": 1}')", (a, cls.vehicle))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,now(),now()+interval '8 hours','Europe/Berlin',1,'PLANNED')", (a, shift))
            c.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'ACTIVE')", (a, cls.run_id, cls.vehicle, shift))
            for h, seats in ((cls.hold1, 2), (cls.hold2, 1)):
                c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
                          "VALUES (%s,%s,%s,1,%s,%s::jsonb,0,4,now()+interval '1 day','CONSUMED')", (a, h, cls.run_id, f"fed-{h}", json.dumps({"seats": seats})))
            c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,%s,'CONFIRMED','{}'::jsonb,3)",
                      (a, cls.booking1, cls.hold1, svc, f"fed-b-{cls.booking1}"))
            for p, tok in ((cls.pax_deleted, "tok-del"), (cls.pax_keep, "tok-keep")):
                c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,contact_ciphertext,support_ciphertext,retention_until) VALUES (%s,%s,%s,%s,%s,now()+interval '1 year')",
                          (a, p, f"{tok}-{p}", b"KONTAKT", b"UNTERSTUETZUNG"))
            c.execute("INSERT INTO taktovia.passenger_contact(tenant_id,id,passenger_id,channel,contact_token) VALUES (%s,%s,%s,'EMAIL',%s)", (a, uuid.uuid4(), cls.pax_deleted, cls.pax_deleted.hex * 2))
            c.execute("INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) VALUES (%s,%s,'system:test','identity.tenant.created','tenant:x',now(),%s,'{}'::jsonb)",
                      (a, uuid.uuid4(), uuid.uuid4()))
            c.execute("INSERT INTO taktovia.outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload,recipient_tenant_id) VALUES (%s,%s,%s,1,'booking.confirmed','1.0',now()-interval '1 minute','{}'::jsonb,%s)",
                      (a, uuid.uuid4(), cls.booking1, a))

    @classmethod
    def tearDownClass(cls):
        for db in cls.restored + [cls.src]:
            dbops.drop_database(cls.cluster, db)
        cls.tmp.cleanup()

    def erase(self, passenger):
        """Löschvorgang (Anonymisierung) wie ihn die Löschengine schreibt; das Journal liegt außerhalb der Datenbank erst nach `dbops.export_journal`."""
        job = uuid.uuid4()
        with self.cluster.connect(self.src) as c:
            row = c.execute("SELECT id FROM taktovia.retention_rule WHERE tenant_id=%s AND data_class='LK_PII' AND status='APPROVED'", (self.A,)).fetchone()
            rule = row[0] if row else uuid.uuid4()
            if not row:
                c.execute("INSERT INTO taktovia.retention_rule(tenant_id,id,data_class,retention_days,action,changed_by,status,approved_by,approved_at) VALUES (%s,%s,'LK_PII',1,'ANONYMIZE','user:a','APPROVED','user:b',now())", (self.A, rule))
            c.execute("INSERT INTO taktovia.erasure_job(tenant_id,id,rule_id,rule_revision,data_class,action,retention_days,created_by) VALUES (%s,%s,%s,1,'LK_PII','ANONYMIZE',1,'user:a')", (self.A, job, rule))
            c.execute("UPDATE taktovia.passenger_private SET erased_at=now() WHERE id=%s", (passenger,))
            c.execute("DELETE FROM taktovia.passenger_contact WHERE passenger_id=%s", (passenger,))
            c.execute("INSERT INTO taktovia.erasure_journal(tenant_id,id,job_id,data_class,object_ref,action) VALUES (%s,%s,%s,'LK_PII',%s,'ANONYMIZE')", (self.A, uuid.uuid4(), job, f"passenger:{passenger}"))

    def restore(self, manifest, journal_store=None):
        target = "taktovia_wa_rst_" + uuid.uuid4().hex[:8]
        self.restored.append(target)
        proto = dbops.restore(self.cluster, self.rstore, self.key, manifest["backup_id"], target, run_isolation_tests=False, journal_store=journal_store,
                              target_environment="staging")
        return target, proto

    def report(self, db, journal=True):
        with self.cluster.connect(db) as c:
            return consistency.run_all(c, dbops.read_journal(self.rstore, self.key) if journal else None, REPO / "db" / "migrations")

    # --- Trockenlauf ---
    def test_trockenlauf_alle_schritte_mit_rpo_rto_messung(self):
        manifest = dbops.create_backup(self.cluster, self.src, self.store, self.key, environment="staging")
        self.erase(self.pax_deleted)
        dbops.export_journal(self.cluster, self.src, self.store, self.key)
        clock = FakeClock(datetime.now(timezone.utc))
        d = drehbuch.Drehbuch("staging", "Trockenlauf-Automat (WP-M5-14, nicht von Dritten ausgeführt)", clock=clock)
        t_backup = datetime.fromisoformat(manifest["taken_at"])
        outage = t_backup + timedelta(minutes=7)                 # Ausfall 7 min nach dem letzten wiederherstellbaren Stand
        clock.t = outage
        d.ausfall(outage)
        clock.advance(minutes=2); d.stoppen()
        state = {}

        def restore():
            clock.advance(minutes=20)
            state["db"], state["proto"] = self.restore(manifest)
            return {"last_commit": t_backup, "referenz": manifest["backup_id"]}
        d.wiederherstellen(restore)
        clock.advance(minutes=3)

        def struktur():
            return "structure_identical" in state["proto"]["steps"] and "checksums_identical" in state["proto"]["steps"], "Prüfsummen, RLS, Trigger, Rollen identisch zum gesicherten Stand"
        d.struktur_pruefen(struktur)
        clock.advance(minutes=5)
        d.konsistenz(self.report(state["db"]))
        clock.advance(minutes=10)
        d.abgleich(lambda: {"done": True, "by": "PLATZHALTER im Test; echter Abgleich: PaymentRestoreReconcileTest", "vorgaenge": []})
        clock.advance(minutes=5); d.freigeben()
        clock.advance(minutes=15)
        d.e2e_probe(lambda: self.probe(state["db"]))
        b = d.p["bewertung"]
        self.assertAlmostEqual(b["rpo_min_gemessen"], 7.0, places=2); self.assertTrue(b["rpo_erfuellt"])
        self.assertAlmostEqual(b["rto_h_gemessen"], (2 + 20 + 3 + 5 + 10 + 5 + 15) / 60.0, places=3); self.assertTrue(b["rto_erfuellt"])
        self.assertEqual(d.p["status"], "ABGESCHLOSSEN"); self.assertTrue(all(row["zeitpunkt"] for row in d.p["schritte"]))
        self.assertEqual(d.p["konsistenz"]["release_allowed"], True)
        out = os.environ.get("TAKTOVIA_PROTOCOL_OUT")
        if out:
            d.p["hinweis"] = ("Automatisierter Trockenlauf gegen lokale PostgreSQL (logisches Backup/Restore), Uhr SIMULIERT: die Zeitabstände zwischen den Schritten sind festgelegt, nicht gemessen; "
                              "RPO und RTO sind damit nur die Rechenregel, keine Messung. Abgleich ist ein Platzhalter, die Probe ein Datenbank-Ersatz. Keine Ausführung durch Dritte (Abnahme 2 offen).")
            d.save(Path(out))
        # das gelöschte Objekt ist im wiederhergestellten Bestand nicht wiederbelebt
        with self.cluster.connect(state["db"]) as c:
            self.assertTrue(c.execute("SELECT erased_at IS NOT NULL AND contact_ciphertext IS NULL FROM taktovia.passenger_private WHERE id=%s", (self.pax_deleted,)).fetchone()[0])

    def probe(self, db):
        """Ersatz für die End-to-End-Probe (WP-M5-05, hier nicht ausführbar): Lesen und Schreiben unter der Runtime-Rolle mit Mandantenkontext."""
        with psycopg.connect(host=self.cluster.host, port=self.cluster.port, dbname=db, user="taktovia_runtime", password=os.environ["TAKTOVIA_DB_RUNTIME_PASSWORD"]) as c:
            c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(self.A),))
            return c.execute("SELECT count(*) FROM taktovia.booking").fetchone()[0] == 1

    def test_zu_langes_rpo_und_rto_werden_als_verfehlt_bewertet(self):
        clock = FakeClock(datetime(2026, 12, 1, 10, 0, tzinfo=timezone.utc))
        d = drehbuch.Drehbuch("staging", "test", clock=clock)
        d.ausfall(); d.stoppen()
        d.wiederherstellen(lambda: {"last_commit": clock.t - timedelta(minutes=16)})
        d.struktur_pruefen(lambda: (True, "ok"))
        r = consistency.Report(); r.add("journal_wirksam", True); d.konsistenz(r)
        d.abgleich(lambda: {"done": True}); d.freigeben(); clock.advance(hours=2, minutes=1)
        d.e2e_probe(lambda: True)
        b = d.p["bewertung"]
        self.assertFalse(b["rpo_erfuellt"]); self.assertFalse(b["rto_erfuellt"])

    # --- Freigabeschranke ---
    def test_restore_ohne_wiedereinspielen_des_loeschjournals_blockiert_die_freigabe(self):
        pax2 = uuid.uuid4()
        with self.cluster.connect(self.src) as c:
            c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,contact_ciphertext,support_ciphertext,retention_until) VALUES (%s,%s,%s,%s,%s,now()+interval '1 year')", (self.A, pax2, f"tok-{pax2}", b"K", b"U"))
        manifest = dbops.create_backup(self.cluster, self.src, self.store, self.key, environment="staging")      # enthält pax2 mit Personendaten
        self.erase(pax2)
        dbops.export_journal(self.cluster, self.src, self.store, self.key)
        empty = BackupStore(Path(tempfile.mkdtemp(dir=self.root)) / "leer", "ops-backup")         # Restore OHNE Journal
        target, _ = self.restore(manifest, journal_store=empty)
        rep = self.report(target)                                                                    # Prüfung gegen das echte Journal
        self.assertIn("journal_wirksam", rep.as_dict()["violations"]); self.assertFalse(rep.release_allowed)
        d = drehbuch.Drehbuch("staging", "test")
        d.ausfall(); d.stoppen(); d.wiederherstellen(lambda: {"last_commit": datetime.now(timezone.utc)}); d.struktur_pruefen(lambda: (True, "ok"))
        with self.assertRaises(drehbuch.ReleaseBlocked):
            d.konsistenz(rep)
        self.assertEqual(d.p["status"], "FREIGABE_GESPERRT"); self.assertIsNotNone(d.p["konsistenz"], "Befund ist protokolliert")
        with self.assertRaises(drehbuch.OutOfOrder):
            d.freigeben()                                                # Schritt 7 vor Schritt 5/6 ist nicht möglich
        # Kontrolle: mit Wiedereinspielung ist dieselbe Prüfung grün (die Prüfung ist empfindlich, nicht blind)
        target2, _ = self.restore(manifest)
        self.assertTrue(self.report(target2).release_allowed)

    def test_ohne_journal_ist_die_freigabe_gesperrt(self):
        manifest = dbops.create_backup(self.cluster, self.src, self.store, self.key, environment="staging")
        target, _ = self.restore(manifest)
        rep = self.report(target, journal=False)
        self.assertFalse(rep.release_allowed); self.assertIn("journal_wirksam", rep.as_dict()["violations"])

    def test_freigabe_verweigert_ohne_bestaetigten_abgleich(self):
        d = drehbuch.Drehbuch("staging", "test")
        d.ausfall(); d.stoppen(); d.wiederherstellen(lambda: {"last_commit": datetime.now(timezone.utc)}); d.struktur_pruefen(lambda: (True, "ok"))
        r = consistency.Report(); r.add("journal_wirksam", True); d.konsistenz(r)
        with self.assertRaises(drehbuch.ReleaseBlocked):
            d.abgleich(lambda: {"done": False, "by": "offen", "vorgaenge": [{"id": "x"}]})
        self.assertEqual(d.p["abgleich_externe_vorgaenge"], [{"id": "x"}])
        with self.assertRaises(drehbuch.OutOfOrder):
            d.freigeben()

    def test_schrittfolge_ist_verbindlich(self):
        d = drehbuch.Drehbuch("staging", "test")
        with self.assertRaises(drehbuch.OutOfOrder):
            d.wiederherstellen(lambda: {"last_commit": datetime.now(timezone.utc)})
        d.ausfall()
        with self.assertRaises(drehbuch.OutOfOrder):
            d.ausfall()

    def test_struktur_befund_blockiert(self):
        d = drehbuch.Drehbuch("staging", "test")
        d.ausfall(); d.stoppen(); d.wiederherstellen(lambda: {"last_commit": datetime.now(timezone.utc)})
        with self.assertRaises(drehbuch.ReleaseBlocked):
            d.struktur_pruefen(lambda: (False, "RLS weicht ab"))

    # --- Konsistenzprüfungen: positiv und je Befund negativ ---
    def test_konsistenzpruefungen_positiv_auf_referenzbestand(self):
        manifest = dbops.create_backup(self.cluster, self.src, self.store, self.key, environment="staging")
        target, _ = self.restore(manifest)
        rep = self.report(target)
        self.assertEqual(rep.as_dict()["violations"], [], rep.as_dict())
        ids = {c.id for c in rep.checks}
        self.assertTrue({"migrationsstand", "outbox_zeitfolge", "outbox_eindeutigkeit", "inbox_zeitfolge_und_pruefsumme", "idempotenz_operation_vorhanden", "kapazitaet_nicht_ueberbucht",
                         "keine_buchung_ohne_kapazitaet", "audit_unveraenderlich_aktiv", "audit_zeitfolge", "journal_wirksam", "zahlungen_abzugleichen"} <= ids)
        self.assertTrue(rep.release_allowed)

    def inject(self, db, sql, args=()):
        with self.cluster.connect(db) as c:
            c.execute("SET session_replication_role = replica")
            c.execute(sql, args)

    def test_jeder_befund_wird_einzeln_erkannt_und_blockiert(self):
        manifest = dbops.create_backup(self.cluster, self.src, self.store, self.key, environment="staging")
        target, _ = self.restore(manifest)
        a = self.A
        cases = {
            "kapazitaet_nicht_ueberbucht": ("UPDATE taktovia.capacity_hold SET capacity_vector = '{\"seats\": 5}'::jsonb WHERE id = %s", (self.hold1,)),
            "keine_buchung_ohne_kapazitaet": ("UPDATE taktovia.capacity_hold SET state = 'RELEASED' WHERE id = %s", (self.hold1,)),
            "outbox_zeitfolge": ("UPDATE taktovia.outbox SET published_at = occurred_at - interval '1 hour' WHERE tenant_id = %s", (a,)),
            "outbox_nicht_aus_der_zukunft": ("UPDATE taktovia.outbox SET occurred_at = now() + interval '2 days' WHERE tenant_id = %s", (a,)),
            "inbox_zeitfolge_und_pruefsumme": ("INSERT INTO taktovia.inbox(tenant_id,consumer,event_id,payload_sha256,received_at,aggregate_id,aggregate_revision,event_type,occurred_at,outcome) "
                                                "VALUES (%s,'c',gen_random_uuid(),repeat('z',64),now(),gen_random_uuid(),1,'x.y',now(),'PROCESSED')", (a,)),
            "idempotenz_operation_vorhanden": ("INSERT INTO taktovia.idempotency_record(tenant_id,principal_ref,command_scope,key,request_sha256,operation_id,expires_at,state) "
                                                "VALUES (%s,'p','s','k',repeat('a',64),gen_random_uuid(),now()+interval '1 day','IN_PROGRESS')", (a,)),
            "audit_zeitfolge": ("INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) "
                                "VALUES (%s,gen_random_uuid(),'system:t','identity.tenant.created','tenant:x',now()+interval '3 days',gen_random_uuid(),'{}'::jsonb)", (a,)),
            "audit_unveraenderlich_aktiv": ("ALTER TABLE taktovia.audit_event DISABLE TRIGGER audit_immutable", ()),
            "migrationsstand": ("DELETE FROM taktovia_meta.schema_history WHERE script = 'V1020__waehrungskatalog.sql'", ()),
        }
        for expected, (sql, args) in cases.items():
            clean = self.restore(manifest)[0]                      # je Fall ein frischer Restore (keine Wechselwirkung)
            self.inject(clean, sql, args)
            rep = self.report(clean)
            self.assertIn(expected, rep.as_dict()["violations"], f"{expected}: {rep.as_dict()['violations']}")
            self.assertFalse(rep.release_allowed, expected)
        self.assertTrue(self.report(target).release_allowed, "Gegenprobe: unveränderter Restore bleibt frei")

    def test_restore_in_andere_umgebung_wird_vor_jeder_wirkung_abgewiesen(self):
        manifest = dbops.create_backup(self.cluster, self.src, self.store, self.key, environment="production")
        for env in ("staging", "development", None):
            target = "taktovia_wa_rst_" + uuid.uuid4().hex[:8]
            with self.assertRaises(Exception) as cm:
                dbops.restore(self.cluster, self.rstore, self.key, manifest["backup_id"], target, run_isolation_tests=False, target_environment=env)
            self.assertEqual(type(cm.exception).__name__, "EnvironmentMismatch")
            with self.cluster.connect("postgres") as c:
                self.assertIsNone(c.execute("SELECT 1 FROM pg_database WHERE datname=%s", (target,)).fetchone(), "keine Zieldatenbank angelegt")
        target, proto = self.restore_into(manifest, "production")
        self.assertEqual(proto["result"], "SUCCESS")

    def restore_into(self, manifest, env):
        target = "taktovia_wa_rst_" + uuid.uuid4().hex[:8]
        self.restored.append(target)
        return target, dbops.restore(self.cluster, self.rstore, self.key, manifest["backup_id"], target, run_isolation_tests=False, target_environment=env)


if __name__ == "__main__":
    unittest.main()
