"""WP-M1-33 / LH-6.18-SEC-007 (Abnahme AK 6) und WP-M1-39: ein Restore belebt gelöschte Datensätze nicht wieder.

Der Löschzustand und das Lösch-/Sperrjournal werden hier mit denselben SQL-Wirkungen erzeugt wie der Löschlauf der Anwendung (Kotlin-Engine, getestet in
backend/platform `ErasureJobTest` und backend/modules/identity `PassengerRetentionTest`); der Python-Test selbst startet die Engine nicht. Backup, Journalausleitung und
Restore laufen echt (infra/backup, pg_dump/pg_restore, isolierte Datenbank).
"""
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "infra" / "backup"))

REASON = None
try:
    from taktovia_backup import crypto, dbops
    from taktovia_backup.store import BackupStore
except ImportError as e:
    REASON = f"Abhängigkeit fehlt: {e}"
if REASON is None and not all(os.environ.get(k) for k in ("TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_DB_RUNTIME_PASSWORD", "TAKTOVIA_DB_MIGRATOR_PASSWORD")):
    REASON = "Datenbank-Zugangsdaten nicht gesetzt"
if REASON is not None and os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
    raise AssertionError(f"Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true): {REASON}")


@unittest.skipIf(REASON is not None, REASON or "")
class LoeschjournalRestore(unittest.TestCase):
    def test_restore_belebt_geloeschtes_nicht_wieder(self):
        cluster = dbops.Cluster()
        src = "taktovia_lj_src_" + uuid.uuid4().hex[:8]; dst = "taktovia_lj_dst_" + uuid.uuid4().hex[:8]
        env = {**os.environ, "TAKTOVIA_DB_NAME": src}
        for step in ("reset", "migrate"):
            subprocess.run([sys.executable, str(REPO / "db" / "db.py"), step], env=env, check=True, capture_output=True)
        try:
            with tempfile.TemporaryDirectory(prefix="tklj-") as tmp:
                tmp = Path(tmp)
                key_file = tmp / "k"; key = crypto.generate_key(key_file)
                store = BackupStore(tmp / "store", "ops-backup")
                tenant, other = uuid.uuid4(), uuid.uuid4()
                gone, stays, foreign = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
                with cluster.connect(src) as c:
                    for t in (tenant, other):
                        c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,%s,'ACTIVE')", (t, f"lj-{t}"))
                    for t, p in ((tenant, gone), (tenant, stays), (other, foreign)):
                        c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,contact_ciphertext,support_ciphertext,retention_until) VALUES (%s,%s,%s,%s,%s,now()-interval '1 day')",
                                  (t, p, f"tok-{p}", b"KONTAKT", b"UNTERSTUETZUNG"))
                        c.execute("INSERT INTO taktovia.passenger_contact(tenant_id,id,passenger_id,channel,contact_token) VALUES (%s,%s,%s,'EMAIL',%s)", (t, uuid.uuid4(), p, p.hex * 2))
                manifest = dbops.create_backup(cluster, src, store, key)       # Backup vor dem Löschlauf: alle drei Datensätze mit Personendaten
                rule, job = uuid.uuid4(), uuid.uuid4()
                with cluster.connect(src) as c:                                  # Löschlauf für `gone` (Wirkung wie ErasureEngine + PassengerRetention)
                    c.execute("INSERT INTO taktovia.retention_rule(tenant_id,id,data_class,retention_days,action,changed_by,status,approved_by,approved_at) VALUES (%s,%s,'LK_PII',1,'ANONYMIZE','user:a','APPROVED','user:b',now())", (tenant, rule))
                    c.execute("INSERT INTO taktovia.erasure_job(tenant_id,id,rule_id,rule_revision,data_class,action,retention_days,created_by) VALUES (%s,%s,%s,1,'LK_PII','ANONYMIZE',1,'user:a')", (tenant, job, rule))
                    c.execute("UPDATE taktovia.passenger_private SET erased_at=now() WHERE id=%s", (gone,))
                    c.execute("DELETE FROM taktovia.passenger_contact WHERE passenger_id=%s", (gone,))
                    c.execute("INSERT INTO taktovia.erasure_journal(tenant_id,id,job_id,data_class,object_ref,action) VALUES (%s,%s,%s,'LK_PII',%s,'ANONYMIZE')", (tenant, uuid.uuid4(), job, f"passenger:{gone}"))
                self.assertEqual(dbops.export_journal(cluster, src, store, key), 1)
                proto = dbops.restore(cluster, BackupStore(store.root, "ops-restore"), key, manifest["backup_id"], dst, run_isolation_tests=False)
                self.assertEqual(proto["result"], "SUCCESS")
                with cluster.connect(dst) as c:
                    erased = c.execute("SELECT contact_ciphertext IS NULL AND support_ciphertext IS NULL AND erased_at IS NOT NULL AND subject_token = 'erased:' || id::text FROM taktovia.passenger_private WHERE id=%s", (gone,)).fetchone()[0]
                    self.assertTrue(erased, "gelöschter Datensatz wurde durch den Restore wiederbelebt")
                    self.assertEqual(c.execute("SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id=%s", (gone,)).fetchone()[0], 0)
                    # unbeteiligte Datensätze, auch des anderen Mandanten, bleiben unverändert erhalten
                    for p in (stays, foreign):
                        self.assertEqual(c.execute("SELECT contact_ciphertext IS NOT NULL AND erased_at IS NULL FROM taktovia.passenger_private WHERE id=%s", (p,)).fetchone()[0], True)
                    self.assertEqual(c.execute("SELECT count(*) FROM taktovia.passenger_private WHERE erased_at IS NOT NULL").fetchone()[0], 1)
        finally:
            dbops.drop_database(cluster, dst); dbops.drop_database(cluster, src)


if __name__ == "__main__":
    unittest.main()
