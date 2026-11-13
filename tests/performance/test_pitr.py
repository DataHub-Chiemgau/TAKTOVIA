"""WP-M5-14: kontinuierliche WAL-Archivierung und PITR gegen einen ECHTEN, privaten PostgreSQL-Cluster (initdb, pg_ctl, pg_basebackup, Wiederherstellung per restore_command).

Ausgeführt: verschlüsseltes WAL-Archiv (archive_command im Clusterbenutzer), Basissicherung, Wiederherstellung auf einen gewählten Zeitpunkt, beschädigtes und fehlendes WAL-Segment
(erkannt, Restore blockiert), Restore in eine andere Umgebung (abgewiesen), Archivierungsverzug- und Fehlermetrik gegen einen Cluster mit ausgefallener Archivierung.
NICHT ausgeführt (kein Docker, keine verwalteten Dienste): Streaming-Standby über Hosts hinweg, S3 Object Lock, die Zielumgebung des Betriebs (D-05); der Speicher ist ein lokales Verzeichnis.
Der Test braucht `initdb`/`pg_ctl`/`pg_basebackup` und (bei root) den Benutzer `postgres`; ohne sie wird übersprungen, mit TAKTOVIA_REQUIRE_DB=true schlägt er fehl.
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "infra" / "backup"))

SKIP_REASON = None
try:
    import psycopg  # noqa: F401
    from taktovia_backup import crypto, metrics, pitr, wal
    from taktovia_backup.store import BackupStore
except ImportError as e:  # pragma: no cover
    SKIP_REASON = f"Abhängigkeit fehlt: {e}"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def environment_reason():
    if SKIP_REASON:
        return SKIP_REASON
    try:
        pitr.pg_bindir()
        if os.geteuid() == 0:
            import pwd
            pwd.getpwnam("postgres")
        pitr.wal_python(pitr.default_run_as())
    except Exception as e:  # noqa: BLE001
        return f"PostgreSQL-Cluster nicht startbar: {e}"
    return None


REASON = environment_reason()
if REASON and os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
    raise AssertionError("Cluster-Test erforderlich (TAKTOVIA_REQUIRE_DB=true): " + REASON)


def ins(c, marke, n=5):
    c.execute("INSERT INTO belege(marke) SELECT %s FROM generate_series(1, %s)", (marke, n))


def chown_pg(path: Path):
    if os.geteuid() == 0:
        for p in [path, *path.rglob("*")]:
            shutil.chown(p, "postgres", "postgres")


def wait_for(cond, timeout=60.0, step=0.3, what="Bedingung"):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if cond():
            return
        time.sleep(step)
    raise AssertionError(f"Zeitüberschreitung: {what}")


@unittest.skipIf(REASON is not None, REASON or "")
class PitrCluster(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = Path(tempfile.mkdtemp(prefix="tkpitr-"))
        os.chmod(cls.base, 0o755)
        chown_pg(cls.base)
        cls.key_file = cls.base / "backup.key"
        cls.key = crypto.generate_key(cls.key_file)
        chown_pg(cls.base)                                     # der Clusterbenutzer spielt hier die Betriebsidentität der WAL-Archivierung
        cls.store_dir = cls.base / "archiv"
        cls.store_dir.mkdir(); chown_pg(cls.store_dir)
        cls.store = BackupStore(cls.store_dir, "ops-backup")
        cls.src = pitr.PgCluster(cls.base / "quelle", free_port(), cls.base / "sock-quelle")
        cls.src.initdb()
        cls.src.configure(cls.src.base_settings())
        cls.src.enable_archiving(cls.store_dir, cls.key_file, archive_timeout_s=2)
        cls.src.start()
        cls.clusters = [cls.src]
        with cls.src.connect() as c:
            c.execute("CREATE TABLE belege(id serial PRIMARY KEY, marke text NOT NULL, ts timestamptz NOT NULL DEFAULT clock_timestamp())")
            ins(c, "A")
        cls.backup_id = "pitr-" + time.strftime("%Y%m%dT%H%M%S")
        cls.manifest = pitr.base_backup(cls.src, cls.store, cls.key, cls.backup_id, "staging")
        with cls.src.connect() as c:
            ins(c, "B")
            time.sleep(0.3)
            cls.target_ts = c.execute("SELECT clock_timestamp()").fetchone()[0]
            time.sleep(0.6)
            ins(c, "C")
            cls.last_commit = c.execute("SELECT max(ts) FROM belege WHERE marke IN ('A', 'B', 'C')").fetchone()[0]
        cls.wait_archived(cls.src)

    @classmethod
    def wait_archived(cls, cluster):
        with cluster.connect() as c:
            seg = c.execute("SELECT pg_walfile_name(pg_switch_wal() - 1)").fetchone()[0]
            wait_for(lambda: (c.execute("SELECT last_archived_wal FROM pg_stat_archiver").fetchone()[0] or "") >= seg, what="WAL archiviert")

    @classmethod
    def tearDownClass(cls):
        for c in cls.clusters:
            c.stop("immediate")
        shutil.rmtree(cls.base, ignore_errors=True)

    def new_target(self):
        t = pitr.PgCluster(self.base / f"ziel-{len(self.clusters)}", free_port(), self.base / f"sock-{len(self.clusters)}")
        self.clusters.append(t)
        return t

    def marks(self, cluster):
        with cluster.connect() as c:
            return dict(c.execute("SELECT marke, count(*) FROM belege WHERE marke IN ('A', 'B', 'C') GROUP BY 1").fetchall())

    # --- positiv ---
    def test_archiv_ist_verschluesselt_und_basissicherung_signiert(self):
        names = wal.archived_segments(self.store)
        self.assertGreaterEqual(len(names), 2)
        raw = (self.store_dir / f"wal-{names[0]}.enc").read_bytes()
        self.assertNotIn(b"PostgreSQL", raw[:200]); self.assertNotEqual(raw[:4], b"\xd1\x0d\x0d\x00", "WAL-Magic darf im Archiv nicht lesbar sein")
        self.assertTrue(self.manifest["manifest_mac"]); self.assertEqual(self.manifest["environment"], "staging")
        self.assertRegex(self.manifest["start_wal_segment"], r"^[0-9A-F]{24}$")
        self.assertTrue(wal.verify_archive(self.store, self.key, self.manifest["start_wal_segment"])["ok"])

    def test_pitr_auf_gewaehlten_zeitpunkt(self):
        # Ausfall der Quelle (hart), Wiederherstellung auf den Zeitpunkt zwischen B und C
        t = self.new_target()
        proto = pitr.restore_pitr(self.store, self.key, self.backup_id, t, "staging", target_time=self.target_ts.isoformat(), archive_store_dir=self.store_dir, key_file=self.key_file)
        self.assertEqual(proto["result"], "SUCCESS")
        self.assertEqual(self.marks(t), {"A": 5, "B": 5}, "genau der Stand bis zum gewählten Zeitpunkt, nichts danach")
        with t.connect() as c:
            self.assertFalse(c.execute("SELECT pg_is_in_recovery()").fetchone()[0], "hochgestuft")
            c.execute("INSERT INTO belege(marke) VALUES ('nach-restore')")        # beschreibbar
        self.assertEqual([s["step"] for s in proto["steps"]], ["manifest_verified", "environment_checked", "wal_chain_verified", "base_extracted", "recovery_configured", "recovered_and_promoted"])
        self.assertLessEqual(proto["replay_timestamp"] and __import__("datetime").datetime.fromisoformat(proto["replay_timestamp"]), self.target_ts)

    def test_pitr_ohne_zeitpunkt_spielt_das_ganze_archiv_ab(self):
        t = self.new_target()
        proto = pitr.restore_pitr(self.store, self.key, self.backup_id, t, "staging", archive_store_dir=self.store_dir, key_file=self.key_file)
        self.assertEqual(proto["result"], "SUCCESS")
        self.assertEqual(self.marks(t), {"A": 5, "B": 5, "C": 5})
        with t.connect() as c:
            self.assertEqual(c.execute("SELECT max(ts) FROM belege WHERE marke IN ('A', 'B', 'C')").fetchone()[0], self.last_commit, "letzter wiederherstellbarer Commit")

    # --- negativ ---
    def tampered_store(self):
        d = self.base / f"kopie-{time.monotonic_ns()}"
        shutil.copytree(self.store_dir, d)
        os.chmod(d, 0o755)
        return d, BackupStore(d, "ops-restore")

    def test_beschaedigtes_wal_segment_wird_erkannt_und_blockiert_den_restore(self):
        d, store = self.tampered_store()
        seg = [n for n in wal.archived_segments(store) if n >= self.manifest["start_wal_segment"]][1]
        f = d / f"wal-{seg}.enc"
        os.chmod(f, 0o640)
        data = bytearray(f.read_bytes()); data[len(data) // 2] ^= 0x01; f.write_bytes(bytes(data))
        chain = wal.verify_archive(store, self.key, self.manifest["start_wal_segment"])
        self.assertFalse(chain["ok"]); self.assertTrue(any(c.startswith(seg) for c in chain["corrupt"]), chain)
        # restore_command liefert für das beschädigte Segment einen Fehler und nie eine Teildatei
        out = Path(self.base) / "wal-out"
        env = {**os.environ, "TAKTOVIA_BACKUP_DIR": str(d), "TAKTOVIA_BACKUP_KEY_FILE": str(self.key_file), "TAKTOVIA_BACKUP_PRINCIPAL": "ops-restore", "PYTHONPATH": str(REPO / "infra" / "backup")}
        r = subprocess.run([sys.executable, "-m", "taktovia_backup.wal", "restore", seg, str(out)], env=env, capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0); self.assertFalse(out.exists()); self.assertFalse(Path(str(out) + ".tk-part").exists())
        # der Restore wird vor jeder Wirkung abgewiesen
        t = self.new_target()
        with self.assertRaises(pitr.PitrFailed) as cm:
            pitr.restore_pitr(store, self.key, self.backup_id, t, "staging", archive_store_dir=d, key_file=self.key_file)
        self.assertIn("beschädigt", str(cm.exception))
        self.assertFalse(t.datadir.exists() and any(t.datadir.iterdir()), "nichts wurde entpackt")

    def test_fehlendes_wal_segment_ist_eine_luecke_und_blockiert(self):
        d, store = self.tampered_store()
        seg = [n for n in wal.archived_segments(store) if n >= self.manifest["start_wal_segment"]][1]
        os.chmod(d, 0o755); (d / f"wal-{seg}.enc").unlink()
        chain = wal.verify_archive(store, self.key, self.manifest["start_wal_segment"])
        self.assertFalse(chain["ok"]); self.assertIn(seg, chain["missing"])
        with self.assertRaises(pitr.PitrFailed):
            pitr.restore_pitr(store, self.key, self.backup_id, self.new_target(), "staging", archive_store_dir=d, key_file=self.key_file)

    def test_falscher_schluessel_und_veraendertes_manifest(self):
        other = crypto.generate_key(self.base / "other.key")
        with self.assertRaises(crypto.WrongKeyError):
            pitr.restore_pitr(self.store, other, self.backup_id, self.new_target(), "staging", archive_store_dir=self.store_dir, key_file=self.key_file)
        d, store = self.tampered_store()
        m = d / f"{self.backup_id}.manifest.json"
        os.chmod(m, 0o640)
        doc = json.loads(m.read_text()); doc["environment"] = "production"; m.write_text(json.dumps(doc))
        with self.assertRaises(crypto.BackupIntegrityError):
            pitr.restore_pitr(store, self.key, self.backup_id, self.new_target(), "production", archive_store_dir=d, key_file=self.key_file)

    def test_restore_in_andere_umgebung_ist_technisch_unterbunden(self):
        for env in ("production", "development", "performance", None, ""):
            t = self.new_target()
            with self.assertRaises(pitr.EnvironmentMismatch):
                pitr.restore_pitr(self.store, self.key, self.backup_id, t, env, archive_store_dir=self.store_dir, key_file=self.key_file)
            self.assertFalse(t.datadir.exists() and any(t.datadir.iterdir()), "vor der Abweisung wurde nichts geschrieben")
            self.assertFalse(t.running())
        with self.assertRaises(pitr.EnvironmentMismatch):
            pitr.assert_restore_target("production", "staging")
        with self.assertRaises(pitr.EnvironmentMismatch):
            pitr.assert_restore_target("bogus", "bogus")
        pitr.assert_restore_target("production", "production")

    def test_restore_in_nicht_leeres_verzeichnis_wird_abgewiesen(self):
        t = self.new_target(); t.prepare_dirs(); (t.datadir / "datei").write_text("x"); chown_pg(t.datadir)
        with self.assertRaises(pitr.PitrFailed):
            pitr.restore_pitr(self.store, self.key, self.backup_id, t, "staging", archive_store_dir=self.store_dir, key_file=self.key_file)

    def test_archiv_ablage_ist_idempotent_aber_ueberschreibt_nie(self):
        seg = wal.archived_segments(self.store)[0]
        with tempfile.TemporaryDirectory() as tmp:
            plain = Path(tmp) / "seg"
            crypto.decrypt_file(self.store.get(f"wal-{seg}.enc"), plain, self.key)
            wal.archive_wal(plain, seg, self.store, self.key)                 # gleicher Inhalt: Erfolg (PostgreSQL wiederholt nach Abbruch)
            b = bytearray(plain.read_bytes()); b[100] ^= 0xFF; plain.write_bytes(bytes(b))
            with self.assertRaises(wal.WalArchiveConflict):
                wal.archive_wal(plain, seg, self.store, self.key)

    # --- Metriken (Archivierungsverzug, Fehler) ---
    def test_metriken_im_normalbetrieb_verzug_unter_15_minuten(self):
        with self.src.connect() as c:
            for _ in range(3):
                c.execute("INSERT INTO belege(marke) VALUES ('last')"); time.sleep(0.5)
            st = metrics.wal_archive_stats(c)
        self.assertIsNotNone(st["lag_seconds"]); self.assertLess(st["lag_seconds"], 900); self.assertEqual(st["failed"], 0)
        text = metrics.render(backup_state={"last_status": 1, "last_success": time.time()}, wal=st)
        self.assertIn("taktovia_wal_archive_lag_seconds", text); self.assertIn("taktovia_backup_last_status 1", text)

    def test_metriken_bei_ausgefallener_archivierung(self):
        c2 = pitr.PgCluster(self.base / "defekt", free_port(), self.base / "sock-defekt")
        self.clusters.append(c2)
        c2.initdb(); c2.configure(c2.base_settings() | {"archive_mode": "on", "archive_command": "false", "archive_timeout": "1s"})
        c2.start()
        with c2.connect() as c:
            c.execute("CREATE TABLE x(a int)")
            c.execute("SELECT pg_switch_wal()")
            wait_for(lambda: c.execute("SELECT failed_count FROM pg_stat_archiver").fetchone()[0] > 0, what="fehlgeschlagene Archivierung gezählt")
            c.execute("INSERT INTO x SELECT generate_series(1, 1000)"); c.execute("SELECT pg_switch_wal()")
            st = metrics.wal_archive_stats(c, now=time.time() + 1000)       # eine Archivierung ist nie gelungen: Alter ab Serverstart
        self.assertGreater(st["failed"], 0); self.assertGreater(st["lag_seconds"], 900, "Verzug wächst, solange nichts archiviert wird")
        self.assertIn("taktovia_wal_archive_failed_total", metrics.render(wal=st))

    @staticmethod
    def feed(store, text, t0, t1, step=15.0):
        for t in range(int(t0), int(t1) + 1, int(step)):
            for line in text.splitlines():
                if line and not line.startswith("#"):
                    name, value = line.rsplit(" ", 1)
                    store.add(name, {"job": "postgres"}, float(t), float(value))

    def test_echte_metriken_loesen_die_echten_alarmregeln_aus(self):
        """Kette Cluster -> Metrik -> Alarmregel: die Metriken kommen aus echten Clustern (gesund bzw. mit ausgefallener Archivierung), die Regeln sind die echten aus alert-rules.d (Auswertung
        in der PromQL-Nachbildung; Prometheus selbst läuft hier nicht)."""
        import yaml
        sys.path.insert(0, str(REPO / "infra" / "alerting"))
        import promql_mini as pq
        groups = yaml.safe_load((REPO / "infra" / "observability" / "alert-rules.d" / "kritische-szenarien.yml").read_text(encoding="utf-8"))["groups"]

        def firing(series):
            store = pq.Store(); series(store)
            ev = pq.RuleEvaluator(groups, store)
            for t in range(0, 1201, 15):
                ev.step(float(t))
            return {fp.split("|")[0] for _t, event, fp in ev.log if event == "firing"}
        # gesunder Cluster: kein Backup-/Archivalarm
        with self.src.connect() as c:
            c.execute("INSERT INTO belege(marke) VALUES ('gesund')")
            ok_stats = metrics.wal_archive_stats(c)
        ok_text = metrics.render(backup_state={"last_status": 1, "last_success": 1000.0 - 3600}, wal=ok_stats, standby_expected=True, standby_streaming=1)
        gesund = firing(lambda st: self.feed(st, ok_text, 0, 1200))
        self.assertFalse(gesund & {"BackupFehlgeschlagen", "BackupZuAlt", "WalArchivierungsverzug", "WalArchivierungFehlgeschlagen", "StandbyGetrennt"}, gesund)
        # Cluster mit ausgefallener Archivierung
        c2 = pitr.PgCluster(self.base / "defekt2", free_port(), self.base / "sock-defekt2")
        self.clusters.append(c2)
        c2.initdb(); c2.configure(c2.base_settings() | {"archive_mode": "on", "archive_command": "false", "archive_timeout": "1s"})
        c2.start()
        with c2.connect() as c:
            before = metrics.wal_archive_stats(c)
            c.execute("CREATE TABLE x(a int)"); c.execute("SELECT pg_switch_wal()")
            wait_for(lambda: c.execute("SELECT failed_count FROM pg_stat_archiver").fetchone()[0] > 0, what="fehlgeschlagene Archivierung gezählt")
            c.execute("INSERT INTO x SELECT generate_series(1, 1000)"); c.execute("SELECT pg_switch_wal()")
            after = metrics.wal_archive_stats(c, now=time.time() + 1000)
        t_before = metrics.render(backup_state={"last_status": 0, "last_success": 1000.0 - 3600}, wal=before, standby_expected=True, standby_streaming=0)
        t_after = metrics.render(backup_state={"last_status": 0, "last_success": 1000.0 - 3600}, wal=after, standby_expected=True, standby_streaming=0)

        def defekt(st):
            self.feed(st, t_before, 0, 135); self.feed(st, t_after, 150, 1200)
        fired = firing(defekt)
        self.assertTrue({"BackupFehlgeschlagen", "WalArchivierungsverzug", "WalArchivierungFehlgeschlagen", "StandbyGetrennt"} <= fired, fired)

    def test_standby_metrik_nur_bei_erwartetem_standby(self):
        self.assertNotIn("taktovia_standby_connected", metrics.render())
        with self.src.connect() as c:
            n = metrics.standby_connected(c)
        self.assertEqual(n, 0)
        self.assertIn("taktovia_standby_connected 0", metrics.render(standby_expected=True, standby_streaming=n))
        self.assertIn("taktovia_standby_connected 1", metrics.render(standby_expected=True, standby_streaming=1))


class BackupStatusMetrics(unittest.TestCase):
    @unittest.skipIf(SKIP_REASON is not None, SKIP_REASON or "")
    def test_fehlgeschlagenes_backup_ueberschreibt_den_letzten_erfolg_nicht(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "state.json"
            s1 = metrics.record_backup_result(f, True, now=1000.0)
            self.assertEqual((s1["last_status"], s1["last_success"]), (1, 1000.0))
            s2 = metrics.record_backup_result(f, False, now=2000.0)
            self.assertEqual((s2["last_status"], s2["last_success"], s2["last_run"]), (0, 1000.0, 2000.0))
            text = metrics.render(backup_state=s2)
            self.assertIn("taktovia_backup_last_status 0", text); self.assertIn("taktovia_backup_last_success_timestamp_seconds 1000", text)


if __name__ == "__main__":
    unittest.main()
