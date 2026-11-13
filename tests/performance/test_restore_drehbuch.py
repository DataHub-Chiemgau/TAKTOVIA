"""WP-M1-39 / LH-6.18-SEC-010: verschlüsselte Backups, Restore-Test in eine isolierte Datenbank, Zugriffstrennung (echte lokale PostgreSQL, echte `pg_dump`/`pg_restore`).

Was hier ausgeführt wird: konsistentes logisches Backup (Snapshot) mit AES-256-GCM, Manipulationserkennung, falscher Schlüssel, Prüfsummenvergleich je Tabelle beider
Testmandanten, Struktur- (RLS, Trigger, Rollen, Rechte) und Isolationsprüfung nach dem Restore, Wiedereinspielung des Lösch-/Sperrjournals, Schreiblast beider
Mandanten während des Backups, Zugriffstrennung (Backupspeicher, Betriebssystem, Datenbankrollen).
Was NICHT ausgeführt wird (kein Docker, keine verwalteten Dienste): kontinuierliches WAL/PITR gegen einen Cluster, S3 Object Lock und IAM, der geplante Job im Betrieb
(nur die Einrichtungsdateien werden geprüft), RPO/RTO-Messung (M5, WP-M5-14/-15).
"""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "infra" / "backup"))

try:
    import psycopg
    from taktovia_backup import crypto, dbops, wal
    from taktovia_backup.store import BackupStore, StoreDenied
    IMPORT_ERROR = None
except ImportError as e:  # pragma: no cover
    IMPORT_ERROR = e

NEEDED = ("TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_DB_RUNTIME_PASSWORD", "TAKTOVIA_DB_MIGRATOR_PASSWORD")
MISSING = [k for k in NEEDED if not os.environ.get(k)]
if IMPORT_ERROR is not None and os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
    raise IMPORT_ERROR
ENV_REASON = None
if IMPORT_ERROR is not None:
    ENV_REASON = f"Abhängigkeit fehlt: {IMPORT_ERROR} (pip install -r tests/requirements.txt)"
elif MISSING:
    if os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
        raise AssertionError(f"Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true), es fehlen: {MISSING}")
    ENV_REASON = f"Datenbank-Zugangsdaten nicht gesetzt: {MISSING}"
elif not all(subprocess.run(["which", t], capture_output=True).returncode == 0 for t in ("pg_dump", "pg_restore", "tar")):
    ENV_REASON = "pg_dump/pg_restore/tar nicht installiert"
    if os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
        raise AssertionError(ENV_REASON)


def runtime_conn(db, tenant=None):
    c = psycopg.connect(host=os.environ.get("TAKTOVIA_DB_HOST", "127.0.0.1"), port=os.environ.get("TAKTOVIA_DB_PORT", "5432"), dbname=db, user="taktovia_runtime",
                        password=os.environ["TAKTOVIA_DB_RUNTIME_PASSWORD"], autocommit=False)
    if tenant:
        c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))
    return c


@unittest.skipIf(ENV_REASON is not None, ENV_REASON or "")
class RestoreDrehbuch(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cluster = dbops.Cluster()
        cls.src = "taktovia_bk_src_" + uuid.uuid4().hex[:8]
        env = {**os.environ, "TAKTOVIA_DB_NAME": cls.src}
        for step in ("reset", "migrate"):
            subprocess.run([sys.executable, str(REPO / "db" / "db.py"), step], env=env, check=True, capture_output=True)
        cls.tmp = tempfile.TemporaryDirectory(prefix="tkbk-test-")
        cls.root = Path(cls.tmp.name)
        cls.key_file = cls.root / "backup.key"
        cls.key = crypto.generate_key(cls.key_file)
        cls.store = BackupStore(cls.root / "store", "ops-backup")
        cls.restore_store = BackupStore(cls.root / "store", "ops-restore")
        cls.restored = []
        cls.A, cls.B = uuid.uuid4(), uuid.uuid4()
        cls.secret_marker = "Mandant-A-Geheimname-" + uuid.uuid4().hex
        cls.ref = {}
        with cls.cluster.connect(cls.src) as c:
            c.execute("INSERT INTO taktovia.currency(code,exponent) VALUES ('EUR',2) ON CONFLICT DO NOTHING")
            for t, name in ((cls.A, cls.secret_marker), (cls.B, "Mandant-B-restore")):
                c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,%s,'ACTIVE')", (t, name))
                le, org, veh, cost, p = (uuid.uuid4() for _ in range(5))
                cls.ref[t] = {"legal_entity": le, "organization": org, "vehicle": veh, "cost_object": cost, "passenger_private": p}
                c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'Referenz-Rechtstraeger','DE')", (t, le))
                c.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,'Referenz-Org',%s,'{BROKER}')", (t, org, le, f"ref-{t}"))
                c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'ref','{}')", (t, veh))
                c.execute("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref) VALUES (%s,%s,%s,'COST_CENTER','KS-1')", (t, cost, le))
                c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,contact_ciphertext,support_ciphertext,retention_until) VALUES (%s,%s,%s,%s,%s,now()+interval '1 year')",
                          (t, p, f"tok-{p}", b"KONTAKT", b"UNTERSTUETZUNG"))
        cls.objects = cls.root / "objects"
        cls.object_hashes = {}
        for t in (cls.A, cls.B):
            d = cls.objects / "tenants" / str(t) / "belege"
            d.mkdir(parents=True)
            data = os.urandom(4096) + cls.secret_marker.encode() if t == cls.A else os.urandom(4096)
            (d / "original-1.pdf").write_bytes(data)
            cls.object_hashes[f"tenants/{t}/belege/original-1.pdf"] = hashlib.sha256(data).hexdigest()

    @classmethod
    def tearDownClass(cls):
        for db in cls.restored + [cls.src]:
            dbops.drop_database(cls.cluster, db)
        cls.tmp.cleanup()

    # -- Hilfen ----------------------------------------------------------------------------------------------------------------------------------
    def _backup(self, **kw):
        return dbops.create_backup(self.cluster, self.src, self.store, self.key, **kw)

    def _restore(self, manifest, journal_store=None, out_dir=None, objects_target=None, isolation=False):
        target = "taktovia_bk_rst_" + uuid.uuid4().hex[:8]
        self.restored.append(target)
        return target, dbops.restore(self.cluster, self.restore_store, self.key, manifest["backup_id"], target, out_dir=out_dir, run_isolation_tests=isolation,
                                     journal_store=journal_store, objects_target=objects_target)

    def _scalar(self, db, query, *args):
        with self.cluster.connect(db) as c:
            return c.execute(query, args).fetchone()[0]

    # -- Tests -----------------------------------------------------------------------------------------------------------------------------------
    def test_restore_referenzdaten_pruefsummen_identisch(self):
        manifest = self._backup()
        # Nach dem Backup (Zeitpunkt T) ändert sich der Bestand; der Restore stellt den Stand zu T her, nicht den aktuellen
        with self.cluster.connect(self.src) as c:
            c.execute("UPDATE taktovia.legal_entity SET name='nach-dem-backup' WHERE tenant_id=%s AND id=%s", (self.A, self.ref[self.A]["legal_entity"]))
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'spaeter','{}')", (self.B, uuid.uuid4()))
        target, proto = self._restore(manifest)
        self.assertEqual(proto["result"], "SUCCESS")
        self.assertEqual(proto["snapshot_t"], manifest["taken_at"])
        self.assertTrue(all(t["match"] for t in proto["tables"]), [t for t in proto["tables"] if not t["match"]])
        for tenant in (self.A, self.B):   # Referenzdaten beider Testmandanten sind vollständig da
            for table, rid in self.ref[tenant].items():
                self.assertEqual(self._scalar(target, f"SELECT count(*) FROM taktovia.{table} WHERE tenant_id=%s AND id=%s", tenant, rid), 1, f"{table} {tenant}")
        self.assertEqual(self._scalar(target, "SELECT name FROM taktovia.legal_entity WHERE tenant_id=%s AND id=%s", self.A, self.ref[self.A]["legal_entity"]), "Referenz-Rechtstraeger", "Stand zu T, nicht der spätere")
        with self.cluster.connect(self.src) as c:   # Gegenprobe: die Prüfsummen unterscheiden sich vom aktuellen Stand (Test ist empfindlich)
            now = dbops.table_checksums(c)
        self.assertNotEqual(now["legal_entity"], manifest["tables"]["legal_entity"])
        self.assertNotEqual(now["vehicle"], manifest["tables"]["vehicle"])
        with self.cluster.connect(self.src) as c:
            c.execute("UPDATE taktovia.legal_entity SET name='Referenz-Rechtstraeger' WHERE tenant_id=%s AND id=%s", (self.A, self.ref[self.A]["legal_entity"]))
            c.execute("DELETE FROM taktovia.vehicle WHERE owner_org_ref='spaeter'")

    def test_restore_mit_falschem_schluessel_scheitert(self):
        manifest = self._backup()
        wrong = os.urandom(32)
        target = "taktovia_bk_rst_" + uuid.uuid4().hex[:8]
        with self.assertRaises(crypto.WrongKeyError):
            dbops.restore(self.cluster, self.restore_store, wrong, manifest["backup_id"], target, run_isolation_tests=False)
        with self.cluster.connect("postgres") as c:   # nichts wurde angelegt
            self.assertIsNone(c.execute("SELECT 1 FROM pg_database WHERE datname=%s", (target,)).fetchone())
        # auch auf Dateiebene: ohne passenden Schlüssel ist der Inhalt nicht lesbar
        with self.assertRaises(crypto.WrongKeyError):
            list(crypto.decrypt_path_chunks(self.store.get(f"{manifest['backup_id']}.db.enc"), wrong))

    def test_backup_unter_schreiblast_transaktionskonsistent(self):
        stop = threading.Event(); errors = []; counts = {self.A: 0, self.B: 0}

        def writer(tenant):
            c = runtime_conn(self.src, tenant)
            try:
                n = 0
                while not stop.is_set():
                    # eine Transaktion = zwei Zeilen (Rechtsträger und Organisation); eine halbe Transaktion darf nie im Backup sein
                    le = uuid.uuid4()
                    c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))
                    c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,%s,'DE')", (tenant, le, f"wl-{n}"))
                    c.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,%s,%s,'{BROKER}')", (tenant, uuid.uuid4(), le, f"wl-org-{n}", f"wl-{le}"))
                    c.commit(); n += 1; counts[tenant] = n
            except Exception as e:  # noqa: BLE001
                errors.append(e)
            finally:
                c.close()
        threads = [threading.Thread(target=writer, args=(t,)) for t in (self.A, self.B)]
        [t.start() for t in threads]
        time.sleep(0.6)
        manifest = self._backup()          # Backup mitten in der Schreiblast
        time.sleep(0.3)
        stop.set(); [t.join() for t in threads]
        self.assertEqual(errors, [])
        target, proto = self._restore(manifest)
        self.assertEqual(proto["result"], "SUCCESS")
        for tenant in (self.A, self.B):
            le = self._scalar(target, "SELECT count(*) FROM taktovia.legal_entity WHERE tenant_id=%s AND name LIKE 'wl-%%'", tenant)
            org = self._scalar(target, "SELECT count(*) FROM taktovia.organization WHERE tenant_id=%s AND name LIKE 'wl-org-%%'", tenant)
            self.assertGreater(le, 0, "Schreiblast war vor dem Snapshot nicht aktiv")
            self.assertEqual(le, org, f"halbe Transaktionen im Backup (Mandant {tenant})")
            orphans = self._scalar(target, """SELECT count(*) FROM taktovia.organization o WHERE o.tenant_id=%s AND o.name LIKE 'wl-org-%%'
                                              AND NOT EXISTS (SELECT 1 FROM taktovia.legal_entity l WHERE l.tenant_id=o.tenant_id AND l.id=o.legal_entity_id)""", tenant)
            self.assertEqual(orphans, 0)
            self.assertEqual(self._scalar(target, "SELECT count(*) FROM taktovia.organization o JOIN taktovia.legal_entity l ON l.id=o.legal_entity_id WHERE o.tenant_id<>l.tenant_id"), 0, "Mandanten vermischt")
            self.assertLess(le, self._scalar(self.src, "SELECT count(*) FROM taktovia.legal_entity WHERE tenant_id=%s AND name LIKE 'wl-%%'", tenant), "Stand zu T, nicht der spätere")

    def test_restore_job_protokolliert_ergebnis_und_dauer(self):
        manifest = self._backup()
        out = self.root / "protokolle"
        target, proto = self._restore(manifest, out_dir=out)
        written = json.loads((out / f"restore-protokoll-{manifest['backup_id']}.json").read_text())
        self.assertEqual(written["result"], "SUCCESS"); self.assertGreater(written["duration_s"], 0)
        self.assertEqual({t["table"] for t in written["tables"]}, set(manifest["tables"]))
        self.assertIn("checksums_identical", written["steps"]); self.assertIn("structure_identical", written["steps"]); self.assertIn("journal_replayed", written["steps"])
        # Fehlschlag wird ebenfalls protokolliert und nie als Erfolg gemeldet
        out2 = self.root / "protokolle2"
        with self.assertRaises(crypto.WrongKeyError):
            dbops.restore(self.cluster, self.restore_store, os.urandom(32), manifest["backup_id"], "taktovia_bk_rst_x", out_dir=out2)
        failed = json.loads((out2 / f"restore-protokoll-{manifest['backup_id']}.json").read_text())
        self.assertEqual(failed["result"], "FAILED"); self.assertIn("WrongKeyError", failed["error"])
        # Einrichtung als regelmäßiger Job (nicht im Betrieb ausgeführt): Timer mit Kalender, Dienst ruft restore-test auf
        timer = (REPO / "infra" / "backup" / "systemd" / "taktovia-restore-test.timer").read_text()
        service = (REPO / "infra" / "backup" / "systemd" / "taktovia-restore-test.service").read_text()
        self.assertRegex(timer, r"(?m)^OnCalendar=\S+")
        self.assertIn("python3 -m taktovia_backup restore-test", service)
        self.assertIn("TAKTOVIA_BACKUP_PRINCIPAL=ops-restore", service)

    def test_backup_im_ruhezustand_verschluesselt_und_objektspeicher_restore_identisch(self):
        manifest = self._backup(objects_dir=self.objects)
        # Gegenprobe: im unverschlüsselten Dump steht der Marker, im Backup nicht
        plain = subprocess.run(["pg_dump", "--format=plain", "--dbname", self.src], env=self.cluster.env(), capture_output=True, check=True).stdout
        self.assertIn(self.secret_marker.encode(), plain)
        for name in manifest["files"]:
            blob = Path(self.store.get(name)).read_bytes()
            self.assertTrue(blob.startswith(crypto.MAGIC))
            self.assertNotIn(self.secret_marker.encode(), blob, name)
            self.assertNotIn(b"PGDMP", blob[:64], name); self.assertNotIn(b"ustar", blob, name)
        # ohne Schlüssel nicht lesbar: pg_restore kann die Datei nicht verwenden
        p = subprocess.run(["pg_restore", "--list", str(self.store.get(f"{manifest['backup_id']}.db.enc"))], capture_output=True)
        self.assertNotEqual(p.returncode, 0)
        # Objektspeicher: Wiederherstellung identisch (Prüfsumme je Objekt, wie original_sha256 der Belegoriginale)
        target_dir = self.root / "objects-restored"
        target, proto = self._restore(manifest, objects_target=target_dir)
        self.assertEqual(proto["objects"], {"expected": 2, "mismatch": []})
        for rel, digest in self.object_hashes.items():
            self.assertEqual(hashlib.sha256((target_dir / rel).read_bytes()).hexdigest(), digest, rel)
        self.assertEqual(manifest["objects"], self.object_hashes)

    def test_runtime_und_migrator_koennen_backup_nicht_aendern_oder_loeschen(self):
        manifest = self._backup()
        name = f"{manifest['backup_id']}.db.enc"
        # 1. Backupspeicher: nur Betriebsrollen; Anwendungs- und Migratorrolle haben keinen Zugriff, niemand überschreibt oder löscht vor Ablauf
        for principal in ("app-runtime", "app-migrator", "taktovia_runtime", "taktovia_migrator", "irgendwer"):
            s = BackupStore(self.store.root, principal)
            for action in (lambda: s.get(name), lambda: s.list(), lambda: s.delete(name), lambda: s.put("x", self.key_file), lambda: s.overwrite(name, self.key_file)):
                with self.assertRaises(StoreDenied, msg=principal):
                    action()
        with self.assertRaises(FileExistsError):
            self.store.put(name, self.key_file)                       # kein Überschreiben, auch nicht durch die Backuprolle
        for principal in ("ops-backup", "ops-restore", "ops-retention"):
            with self.assertRaises(StoreDenied, msg=principal):       # vor Ablauf der Aufbewahrung löscht niemand
                BackupStore(self.store.root, principal).delete(name)
        with self.assertRaises(StoreDenied):                          # nach Ablauf nur die Aufbewahrungsrolle
            BackupStore(self.store.root, "ops-backup").delete(name, now=time.time() + 400 * 86400)
        # 2. Betriebssystem: die Identitäten der Anwendung (hier `nobody`) können die Dateien weder ändern noch löschen
        if os.geteuid() == 0:
            code = ("import os,sys\nfrom pathlib import Path\np=Path(sys.argv[1])\nres=[]\n"
                    "for f in (lambda: open(p,'ab').write(b'x'), lambda: os.remove(p), lambda: open(p.with_name('neu'),'wb')):\n"
                    "    try: f(); res.append('ERLAUBT')\n    except PermissionError: res.append('VERWEIGERT')\nprint(','.join(res))")
            def drop(): os.setgid(65534); os.setuid(65534)
            out = subprocess.run(["/usr/bin/python3", "-c", code, str(self.store.get(name))], preexec_fn=drop, capture_output=True, text=True)
            self.assertEqual(out.stdout.strip(), "VERWEIGERT,VERWEIGERT,VERWEIGERT", out.stderr)
        # 3. Datenbankrollen: weder Runtime noch Migrator dürfen Serverdateien lesen oder schreiben (kein Weg an den Backupspeicher über die Datenbank)
        for user, pw in (("taktovia_runtime", os.environ["TAKTOVIA_DB_RUNTIME_PASSWORD"]), ("taktovia_migrator", os.environ["TAKTOVIA_DB_MIGRATOR_PASSWORD"])):
            for stmt in (f"COPY (SELECT 1) TO '{self.store.root}/evil'", f"COPY taktovia.tenant TO PROGRAM 'rm -f {self.store.path(name)}'",
                         f"SELECT pg_read_file('{self.store.path(name)}')", f"SELECT lo_export(0, '{self.store.root}/evil2')"):
                c = psycopg.connect(host=os.environ.get("TAKTOVIA_DB_HOST", "127.0.0.1"), dbname=self.src, user=user, password=pw, autocommit=True)
                try:
                    with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=f"{user}: {stmt}"):
                        c.execute(stmt)
                finally:
                    c.close()
        self.assertTrue(Path(self.store.get(name)).exists())

    def test_manipuliertes_backup_wird_erkannt(self):
        manifest = self._backup()
        bid = manifest["backup_id"]
        src_store = self.store.root
        def clone():
            d = Path(tempfile.mkdtemp(dir=self.root)); store = BackupStore(d / "s", "ops-backup")
            for p in src_store.iterdir():
                if p.name.startswith(bid):
                    (store.root / p.name).write_bytes(p.read_bytes())
            return store
        enc_name = f"{bid}.db.enc"
        # (a) ein Bit im Chiffretext: Prüfsumme des Manifests und Authentifizierung schlagen an
        s = clone(); data = bytearray((s.root / enc_name).read_bytes()); data[len(data) // 2] ^= 0x01; (s.root / enc_name).write_bytes(bytes(data))
        with self.assertRaises(crypto.BackupIntegrityError):
            dbops.verify_backup(BackupStore(s.root, "ops-restore"), self.key, bid)
        with self.assertRaises(crypto.BackupIntegrityError):   # auch ohne Manifest erkennt die Entschlüsselung die Änderung
            list(crypto.decrypt_path_chunks(s.root / enc_name, self.key))
        target = "taktovia_bk_rst_" + uuid.uuid4().hex[:8]
        with self.assertRaises(crypto.BackupIntegrityError):   # nie als Erfolg gemeldet, nichts angelegt
            dbops.restore(self.cluster, BackupStore(s.root, "ops-restore"), self.key, bid, target, run_isolation_tests=False)
        with self.cluster.connect("postgres") as c:
            self.assertIsNone(c.execute("SELECT 1 FROM pg_database WHERE datname=%s", (target,)).fetchone())
        # (b) abgeschnittene Datei
        s = clone(); (s.root / enc_name).write_bytes((s.root / enc_name).read_bytes()[:-40])
        with self.assertRaises(crypto.BackupIntegrityError):
            list(crypto.decrypt_path_chunks(s.root / enc_name, self.key))
        # (c) Angreifer passt die Prüfsumme im Manifest an: der Authentifizierungscode des Manifests schlägt an
        s = clone(); m = json.loads((s.root / f"{bid}.manifest.json").read_text()); m["files"][enc_name]["sha256"] = "0" * 64
        (s.root / f"{bid}.manifest.json").write_text(json.dumps(m))
        with self.assertRaises(crypto.BackupIntegrityError):
            dbops.verify_backup(BackupStore(s.root, "ops-restore"), self.key, bid)
        # (d) unverändertes Backup besteht die Prüfung
        self.assertEqual(dbops.verify_backup(self.restore_store, self.key, bid)["backup_id"], bid)

    def test_geloeschter_datensatz_bleibt_nach_restore_und_journal_geloescht(self):
        p = uuid.uuid4(); rule = uuid.uuid4(); job = uuid.uuid4()
        with self.cluster.connect(self.src) as c:
            c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,contact_ciphertext,support_ciphertext,retention_until) VALUES (%s,%s,%s,%s,%s,now()-interval '1 day')",
                      (self.A, p, f"tok-{p}", b"KONTAKT-ZU-LOESCHEN", b"UNTERSTUETZUNG"))
            c.execute("INSERT INTO taktovia.passenger_contact(tenant_id,id,passenger_id,channel,contact_token) VALUES (%s,%s,%s,'EMAIL',%s)", (self.A, uuid.uuid4(), p, p.hex * 2))
        manifest = self._backup()   # Backup enthält den Datensatz noch mit Personendaten
        # Löschlauf nach dem Backup: Zustand der Zeile und Journal (so schreibt ihn die Löschengine WP-M1-33)
        with self.cluster.connect(self.src) as c:
            c.execute("INSERT INTO taktovia.retention_rule(tenant_id,id,data_class,retention_days,action,changed_by,status,approved_by,approved_at) VALUES (%s,%s,'LK_PII',1,'ANONYMIZE','user:a','APPROVED','user:b',now())", (self.A, rule))
            c.execute("INSERT INTO taktovia.erasure_job(tenant_id,id,rule_id,rule_revision,data_class,action,retention_days,created_by) VALUES (%s,%s,%s,1,'LK_PII','ANONYMIZE',1,'user:a')", (self.A, job, rule))
            c.execute("UPDATE taktovia.passenger_private SET erased_at=now() WHERE id=%s", (p,))
            c.execute("DELETE FROM taktovia.passenger_contact WHERE passenger_id=%s", (p,))
            c.execute("INSERT INTO taktovia.erasure_journal(tenant_id,id,job_id,data_class,object_ref,action) VALUES (%s,%s,%s,'LK_PII',%s,'ANONYMIZE')", (self.A, uuid.uuid4(), job, f"passenger:{p}"))
        self.assertEqual(dbops.export_journal(self.cluster, self.src, self.store, self.key), 1)
        self.assertEqual(dbops.export_journal(self.cluster, self.src, self.store, self.key), 0, "zweiter Export ohne neue Zeilen hängt nichts an")
        # Kontrolle: ohne Wiedereinspielung würde der Datensatz mit Personendaten wieder erscheinen (der Test ist empfindlich)
        empty = BackupStore(Path(tempfile.mkdtemp(dir=self.root)) / "leer", "ops-backup")
        t0, _ = self._restore(manifest, journal_store=empty)
        self.assertEqual(self._scalar(t0, "SELECT contact_ciphertext FROM taktovia.passenger_private WHERE id=%s", p), b"KONTAKT-ZU-LOESCHEN")
        # mit Journal: der Datensatz ist vor der Freigabe wieder gelöscht, ohne Personenangaben, und das Journal ist wieder in der Datenbank
        target, proto = self._restore(manifest)
        self.assertEqual(proto["journal"], {"entries": 1, "newly_applied": 1})
        row = self._scalar(target, "SELECT (contact_ciphertext IS NULL AND support_ciphertext IS NULL AND erased_at IS NOT NULL AND subject_token = 'erased:' || id::text) FROM taktovia.passenger_private WHERE id=%s", p)
        self.assertTrue(row)
        self.assertEqual(self._scalar(target, "SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id=%s", p), 0)
        self.assertEqual(self._scalar(target, "SELECT count(*) FROM taktovia.erasure_journal WHERE object_ref=%s", f"passenger:{p}"), 0, "Job fehlt im älteren Stand: kein Journalverweis ohne Job")

    def test_isolation_und_rls_nach_restore_wirksam(self):
        manifest = self._backup()
        target, proto = self._restore(manifest, isolation=True)
        self.assertIn("structure_identical", proto["steps"]); self.assertIn("isolation_tests_passed", proto["steps"])
        self.assertTrue(proto["isolation_tests"]["ok"], proto["isolation_tests"])
        # RLS wirksam: ohne Kontext keine Zeile, mit Kontext A nur A, Zeilen von B unsichtbar und nicht schreibbar
        c = runtime_conn(target)
        try:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle").fetchone()[0], 0)
            c.rollback(); c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(self.A),))
            self.assertEqual(c.execute("SELECT count(DISTINCT tenant_id) FROM taktovia.vehicle").fetchone()[0], 1)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id=%s", (self.B,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.Error):
                c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (self.B, uuid.uuid4()))
            c.rollback()
            # Trigger wirksam: Auditeinträge sind unveränderlich, die tenant_id ist unveränderlich
            c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(self.A),))
            c.execute("INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) VALUES (%s,%s,'system:restore','restore.test.done','tenant:x',now(),%s,'{}'::jsonb)", (self.A, uuid.uuid4(), uuid.uuid4()))
            c.commit(); c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(self.A),))
            with self.assertRaises(psycopg.errors.Error):
                c.execute("UPDATE taktovia.audit_event SET action='manipuliert'")
            c.rollback(); c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(self.A),))
            with self.assertRaises(psycopg.errors.Error):
                c.execute("UPDATE taktovia.vehicle SET tenant_id=%s", (self.B,))
        finally:
            c.rollback(); c.close()
        # Rollen unverändert: Runtime weder Superuser noch BYPASSRLS noch Eigentümer
        with self.cluster.connect(target) as a:
            self.assertEqual(a.execute("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname='taktovia_runtime'").fetchone(), (False, False))
            self.assertEqual(a.execute("""SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='taktovia' AND pg_get_userbyid(c.relowner)='taktovia_runtime'""").fetchone()[0], 0)

    def test_wal_segment_verschluesselt_archiviert_und_authentifiziert(self):
        """archive_command/restore_command: Verschlüsselung und Rückweg eines WAL-Segments (das Abspielen gegen einen Cluster ist in test_pitr.py belegt, WP-M5-14)."""
        seg = self.root / "000000010000000000000007"; seg.write_bytes(os.urandom(3 * 1024 * 1024))
        dbops.archive_wal(seg, seg.name, self.store, self.key)
        stored = Path(self.store.get(f"wal-{seg.name}.enc")).read_bytes()
        self.assertNotIn(seg.read_bytes()[:64], stored)
        out = self.root / "wal-back"; dbops.restore_wal(seg.name, out, self.restore_store, self.key)
        self.assertEqual(out.read_bytes(), seg.read_bytes())
        dbops.archive_wal(seg, seg.name, self.store, self.key)        # gleicher Inhalt: Erfolg (PostgreSQL wiederholt archive_command nach einem Abbruch; sonst bliebe das Archiv stehen, WP-M5-14)
        other = self.root / "000000010000000000000007.neu"; other.write_bytes(os.urandom(3 * 1024 * 1024))
        with self.assertRaises(wal.WalArchiveConflict):               # abweichender Inhalt: Segmente werden nie überschrieben
            dbops.archive_wal(other, seg.name, self.store, self.key)
        self.assertEqual(self.restore_store.get(f"wal-{seg.name}.enc").stat().st_size, stored and len(stored))
        with self.assertRaises(crypto.WrongKeyError):
            dbops.restore_wal(seg.name, self.root / "wal-bad", self.restore_store, os.urandom(32))


if __name__ == "__main__":
    unittest.main()
