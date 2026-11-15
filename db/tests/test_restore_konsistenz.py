"""WP-M5-15 / LH-6.19-NFR-007: Freigabeschranken nach einem Restore, geprüft gegen echte wiederhergestellte Datenbanken (logisches Backup aus WP-M1-39 in eine isolierte Datenbank der lokalen PostgreSQL;
die Wiederherstellung auf einen Zeitpunkt aus dem WAL und die komplette Übung stehen in tests/performance/test_ausfalluebung.py).

Geprüft: (1) Restore ohne Wiedereinspielen des Lösch-/Sperrjournals wird blockiert (wiederauferstandene Löschung ist ein Befund, die Freigabe des Drehbuchs bleibt gesperrt; mit Journal ist dieselbe
Prüfung grün); (2) Restore in eine fremde Umgebung ist vor jeder Wirkung unterbunden (keine Zieldatenbank, kein Schreibzugriff); (3) ein verletzter Konsistenzbefund blockiert die Freigabe.
Nicht geprüft: WAL-Beschädigung und Zeitpunktwiederherstellung (tests/performance/test_pitr.py)."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "infra" / "backup"))

try:
    import psycopg  # noqa: F401
    from taktovia_backup import consistency, crypto, dbops, drehbuch
    from taktovia_backup.environment import EnvironmentMismatch
    from taktovia_backup.store import BackupStore
    REASON = None if os.environ.get("TAKTOVIA_DB_ADMIN_PASSWORD") else "Datenbank-Zugangsdaten nicht gesetzt"
except ImportError as e:  # pragma: no cover
    REASON = f"Abhängigkeit fehlt: {e}"
if REASON and os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
    raise AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true): " + REASON)


@unittest.skipIf(REASON is not None, REASON or "")
class RestoreKonsistenz(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cluster = dbops.Cluster()
        cls.src = "taktovia_rk_src_" + uuid.uuid4().hex[:8]
        env = {**os.environ, "TAKTOVIA_DB_NAME": cls.src}
        for step in ("reset", "migrate"):
            subprocess.run([sys.executable, str(REPO / "db" / "db.py"), step], env=env, check=True, capture_output=True)
        cls.tmp = tempfile.TemporaryDirectory(prefix="tkrk-")
        root = Path(cls.tmp.name)
        cls.key = crypto.generate_key(root / "backup.key")
        cls.store, cls.rstore = BackupStore(root / "store", "ops-backup"), BackupStore(root / "store", "ops-restore")
        cls.root, cls.restored, cls.t = root, [], uuid.uuid4()
        cls.pax = [uuid.uuid4() for _ in range(3)]
        with cls.cluster.connect(cls.src) as c:
            c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'rk','ACTIVE')", (cls.t,))
            for p in cls.pax:
                c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,contact_ciphertext,support_ciphertext,retention_until) VALUES (%s,%s,%s,%s,%s,now()+interval '1 year')",
                          (cls.t, p, f"tok-{p}", b"K", b"U"))
            c.execute("INSERT INTO taktovia.retention_rule(tenant_id,id,data_class,retention_days,action,changed_by,status,approved_by,approved_at) VALUES (%s,%s,'LK_PII',1,'ANONYMIZE','user:a','APPROVED','user:b',now())", (cls.t, uuid.uuid4()))

    @classmethod
    def tearDownClass(cls):
        for db in cls.restored + [cls.src]:
            dbops.drop_database(cls.cluster, db)
        cls.tmp.cleanup()

    def erase(self, passenger):
        with self.cluster.connect(self.src) as c:
            rule = c.execute("SELECT id FROM taktovia.retention_rule WHERE tenant_id=%s AND data_class='LK_PII'", (self.t,)).fetchone()[0]
            job = uuid.uuid4()
            c.execute("INSERT INTO taktovia.erasure_job(tenant_id,id,rule_id,rule_revision,data_class,action,retention_days,created_by) VALUES (%s,%s,%s,1,'LK_PII','ANONYMIZE',1,'user:a')", (self.t, job, rule))
            c.execute("UPDATE taktovia.passenger_private SET erased_at=now(), contact_ciphertext=NULL, support_ciphertext=NULL WHERE id=%s", (passenger,))
            c.execute("INSERT INTO taktovia.erasure_journal(tenant_id,id,job_id,data_class,object_ref,action) VALUES (%s,%s,%s,'LK_PII',%s,'ANONYMIZE')", (self.t, uuid.uuid4(), job, f"passenger:{passenger}"))

    def restore(self, manifest, journal_store=None, env="staging"):
        target = "taktovia_rk_rst_" + uuid.uuid4().hex[:8]
        self.restored.append(target)
        dbops.restore(self.cluster, self.rstore, self.key, manifest["backup_id"], target, run_isolation_tests=False, journal_store=journal_store, target_environment=env)
        return target

    def report(self, db, entries):
        with self.cluster.connect(db) as c:
            return consistency.run_all(c, entries, REPO / "db" / "migrations")

    def test_restore_ohne_loeschjournal_wird_blockiert(self):
        manifest = dbops.create_backup(self.cluster, self.src, self.store, self.key, environment="staging")      # Stand VOR der Löschung: Personendaten sind enthalten
        self.erase(self.pax[0])                                                                                  # Löschung liegt im Verlustfenster (nach dem Backup)
        dbops.export_journal(self.cluster, self.src, self.store, self.key)
        journal = dbops.read_journal(self.rstore, self.key)
        self.assertEqual(len(journal), 1)
        leer = BackupStore(self.root / "leer", "ops-backup")                                                     # Restore OHNE das Journal (Betriebsfehler)
        ohne = self.restore(manifest, journal_store=leer)
        with self.cluster.connect(ohne) as c:
            self.assertFalse(c.execute("SELECT erased_at IS NOT NULL FROM taktovia.passenger_private WHERE id=%s", (self.pax[0],)).fetchone()[0], "die gelöschte Person ist ohne Journal wiederauferstanden")
        rep = self.report(ohne, journal)                                                                         # Prüfung gegen das echte Journal
        self.assertIn("journal_wirksam", rep.as_dict()["violations"]); self.assertFalse(rep.release_allowed)
        d = drehbuch.Drehbuch("staging", "test")
        d.ausfall(); d.stoppen(); d.wiederherstellen(lambda: {"last_commit": __import__("datetime").datetime.now(__import__("datetime").timezone.utc)}); d.struktur_pruefen(lambda: (True, "ok"))
        with self.assertRaises(drehbuch.ReleaseBlocked):
            d.konsistenz(rep)
        self.assertEqual(d.p["status"], "FREIGABE_GESPERRT")
        with self.assertRaises(drehbuch.OutOfOrder):
            d.abgleich(lambda: {"done": True, "vorgaenge": []})                                                  # nächster Schritt erst nach bestandener Konsistenzprüfung
        # ohne übergebenes Journal ist die Prüfung nicht erbracht: ebenfalls gesperrt
        self.assertFalse(self.report(ohne, None).release_allowed)
        # Kontrolle: mit Wiedereinspielen ist dieselbe Prüfung grün (die Prüfung ist empfindlich, nicht blind)
        mit = self.restore(manifest)
        with self.cluster.connect(mit) as c:
            self.assertTrue(c.execute("SELECT erased_at IS NOT NULL AND contact_ciphertext IS NULL FROM taktovia.passenger_private WHERE id=%s", (self.pax[0],)).fetchone()[0])
        self.assertTrue(self.report(mit, journal).release_allowed)

    def test_restore_in_fremde_umgebung_unterbunden(self):
        manifest = dbops.create_backup(self.cluster, self.src, self.store, self.key, environment="production")
        for env in ("staging", "development", "performance", None):
            target = "taktovia_rk_rst_" + uuid.uuid4().hex[:8]
            with self.assertRaises(EnvironmentMismatch):
                dbops.restore(self.cluster, self.rstore, self.key, manifest["backup_id"], target, run_isolation_tests=False, target_environment=env)
            with self.cluster.connect("postgres") as c:
                self.assertIsNone(c.execute("SELECT 1 FROM pg_database WHERE datname=%s", (target,)).fetchone(), f"Ziel {env}: keine Zieldatenbank angelegt, nichts geschrieben")
        ziel = self.restore(manifest, env="production")                                                          # Kontrolle: in die Quellumgebung gelingt der Restore
        with self.cluster.connect("postgres") as c:
            self.assertIsNotNone(c.execute("SELECT 1 FROM pg_database WHERE datname=%s", (ziel,)).fetchone())
        # eine Sicherung ohne Umgebungskennung im Manifest (Altbestand) wird ebenfalls nicht in eine beliebige Umgebung gespielt, sobald die Kennung geprüft wird
        with self.assertRaises(EnvironmentMismatch):
            from taktovia_backup.environment import assert_restore_target
            assert_restore_target(None, "staging")

    def test_verletzter_konsistenzbefund_blockiert_die_freigabe(self):
        manifest = dbops.create_backup(self.cluster, self.src, self.store, self.key, environment="staging")
        clean = self.restore(manifest)
        self.assertEqual([v for v in self.report(clean, []).as_dict()["violations"]], [])
        bad = self.restore(manifest)
        with self.cluster.connect(bad) as c:
            c.execute("INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) "
                      "VALUES (%s,gen_random_uuid(),'system:t','identity.tenant.created','tenant:x',now()+interval '3 days',gen_random_uuid(),'{}'::jsonb)", (self.t,))
        rep = self.report(bad, [])
        self.assertIn("audit_zeitfolge", rep.as_dict()["violations"]); self.assertFalse(rep.release_allowed)
        d = drehbuch.Drehbuch("staging", "test")
        d.ausfall(); d.stoppen(); d.wiederherstellen(lambda: {"last_commit": __import__("datetime").datetime.now(__import__("datetime").timezone.utc)}); d.struktur_pruefen(lambda: (True, "ok"))
        with self.assertRaises(drehbuch.ReleaseBlocked):
            d.konsistenz(rep)
        self.assertIn("audit_zeitfolge", d.p["konsistenz"]["violations"], "der Befund ist protokolliert")


if __name__ == "__main__":
    unittest.main()
