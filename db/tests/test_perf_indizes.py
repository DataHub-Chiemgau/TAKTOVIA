"""WP-M5-11 / LH-6.19-NFR-002: nachgezogene Leistungsindizes (V50110) laufen ohne Sperre, die Buchungen blockiert, sind frisch migrierbar und mit der RLS-Policy planverträglich.

* Die Migration `V50110__perf_indizes.sql` trägt den Marker `taktovia:no-transaction` und nutzt ausschliesslich `CREATE INDEX CONCURRENTLY IF NOT EXISTS` (kein gewöhnlicher Indexaufbau auf `booking`).
* Ein Schreibzugriff auf `booking` (ROW EXCLUSIVE, wie jede Buchung) wird vom nebenläufigen Indexaufbau **nicht** blockiert, der Aufbau läuft während dessen; eine Negativkontrolle zeigt, dass derselbe
  Test einen gewöhnlichen Indexaufbau in einer offenen Transaktion als blockierend erkennt (sonst bewiese der Test nichts).
* Der Index ist gültig, die Migration idempotent (erneutes Anwenden ändert nichts), und unter der Runtime-Rolle mit Tenantkontext liest der Plan `booking` in Schlüsselreihenfolge über den Index mit
  Mandantenbedingung der RLS-Policy als Indexbedingung (kein Sortieren, kein Filter je Zeile)."""
import re
import threading
import time
import unittest
from pathlib import Path

import psycopg

from helpers import admin, migrator, runtime, set_tenant, uid

MIG = Path(__file__).resolve().parents[1] / 'migrations' / 'V50110__perf_indizes.sql'
INDEX = 'booking_by_created'


def statements():
    """Die Anweisungen der Migration ohne Kommentarzeilen (so führt `db.py` sie aus: eine Anweisung je Ausführung)."""
    out = []
    for x in re.split(r';[ \t]*\n', MIG.read_text()):
        body = '\n'.join(l for l in x.splitlines() if l.strip() and not l.lstrip().startswith('--')).strip()
        if body:
            out.append(body)
    return out


def index_state(c):
    return c.execute("SELECT i.indisvalid, i.indisready FROM pg_index i WHERE i.indexrelid = to_regclass('taktovia.booking_by_created')").fetchone()


class PerfIndizes(unittest.TestCase):
    def test_index_migration_ohne_buchungssperre_und_frisch_migrierbar(self):
        text = MIG.read_text()
        self.assertRegex(text, r'(?m)^-- taktovia:no-transaction\s*$')
        for stmt in statements():
            self.assertRegex(stmt, r'^CREATE INDEX CONCURRENTLY IF NOT EXISTS ', 'nur nebenläufiger, idempotenter Indexaufbau')
        self.assertEqual(len(statements()), 1)
        m = migrator()
        try:
            # frisch migriert: Index vorhanden und gültig; erneutes Anwenden ist ein No-op
            self.assertEqual(index_state(m), (True, True))
            for stmt in statements():
                m.execute(stmt)
            self.assertEqual(index_state(m), (True, True))
            # Zustand „vor der Migration“ (freigegebener Vorstand ohne Index): Aufbau auf der gefüllten Tabelle mit nebenläufigem Schreiber
            m.execute('DROP INDEX CONCURRENTLY taktovia.booking_by_created')
            self.assertIsNone(index_state(m))
            self._build_with_concurrent_writer(m)
            self.assertEqual(index_state(m), (True, True))
        finally:
            for stmt in statements():
                m.execute(stmt)                       # Zustand wiederherstellen (idempotent)
            m.close()

    def _writer(self, timeout_ms):
        """Ein Schreibzugriff auf `booking` (ROW EXCLUSIVE, keine Zeile geändert); misst die Wartezeit, bricht nach [timeout_ms] ab."""
        c = runtime()
        try:
            set_tenant(c, uid())
            c.execute(f"SET LOCAL lock_timeout = '{int(timeout_ms)}ms'")
            t = time.monotonic()
            try:
                c.execute('UPDATE taktovia.booking SET revision = revision WHERE false')
                c.commit()
                return time.monotonic() - t, None
            except psycopg.errors.LockNotAvailable as e:
                c.rollback()
                return time.monotonic() - t, e
        finally:
            c.close()

    def _build_with_concurrent_writer(self, m):
        # eine offene schreibende Transaktion (ROW EXCLUSIVE, wie eine laufende Buchung) hält den nebenläufigen Aufbau in der Wartephase: der Aufbau ist nachweislich „im Gange“, während der Schreiber läuft
        holder = runtime()
        set_tenant(holder, uid())
        holder.execute('UPDATE taktovia.booking SET revision = revision WHERE false')
        built = {}

        def build():
            mm = migrator()
            try:
                for stmt in statements():
                    mm.execute(stmt)
                built['done'] = time.monotonic()
            finally:
                mm.close()
        th = threading.Thread(target=build)
        th.start()
        time.sleep(1.0)
        self.assertNotIn('done', built, 'der Aufbau muss laufen (wartet auf die offene Transaktion), sonst prüft der Test nichts')
        waited, err = self._writer(timeout_ms=3000)
        self.assertIsNone(err, 'Schreibzugriff auf booking wurde vom Indexaufbau blockiert')
        self.assertLess(waited, 1.0)
        self.assertNotIn('done', built)
        holder.commit(); holder.close()
        th.join(60)
        self.assertIn('done', built)

    def test_negativkontrolle_gewoehnlicher_indexaufbau_blockiert_buchungsschreiber(self):
        m = migrator()
        m.autocommit = False
        try:
            m.execute('CREATE INDEX booking_perf_probe ON taktovia.booking (tenant_id, created_at, id) WHERE booking_no IS NOT NULL')   # SHARE-Sperre bis zum Ende der Transaktion
            waited, err = self._writer(timeout_ms=700)
            self.assertIsNotNone(err, 'ein gewöhnlicher Indexaufbau muss Schreibzugriffe auf booking blockieren (sonst ist der Test blind)')
            self.assertGreaterEqual(waited, 0.6)
        finally:
            m.rollback(); m.close()
        a = admin()
        try:
            self.assertIsNone(a.execute("SELECT to_regclass('taktovia.booking_perf_probe')").fetchone()[0])
        finally:
            a.close()

    def test_plan_liest_buchungen_in_schluesselreihenfolge_mit_rls_bedingung_als_indexbedingung(self):
        t = uid()
        c = runtime()
        try:
            set_tenant(c, t)
            c.execute('SET LOCAL enable_seqscan = off')
            c.execute('SET LOCAL enable_bitmapscan = off')
            plan = '\n'.join(r[0] for r in c.execute('EXPLAIN SELECT id FROM taktovia.booking WHERE booking_no IS NOT NULL ORDER BY created_at, id LIMIT 101').fetchall())
            self.assertRegex(plan, rf'Index (Only )?Scan using {INDEX}')
            self.assertIn('Index Cond: (tenant_id =', plan, 'die RLS-Bedingung (tenant_id = current_setting) muss Indexbedingung sein')
            self.assertNotIn('Sort', plan, 'Sortierung entfällt (Schlüsselreihenfolge)')
        finally:
            c.rollback(); c.close()

    def test_index_ist_mandantengefuehrt_und_teilindex_auf_nummerierte_buchungen(self):
        a = admin()
        try:
            d = a.execute("SELECT indexdef FROM pg_indexes WHERE schemaname = 'taktovia' AND indexname = 'booking_by_created'").fetchone()[0]
        finally:
            a.close()
        self.assertIn('(tenant_id, created_at, id)', d)
        self.assertIn('WHERE (booking_no IS NOT NULL)', d)


if __name__ == '__main__':
    unittest.main()
