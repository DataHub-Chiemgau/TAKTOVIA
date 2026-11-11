"""WP-M1-35 / LH-I.4-MUL-005: zeitlich begrenzte Fahrerfreigabe mit minimierter Projektion unter der echten Runtime-Rolle.

Der Eigentümer (A) legt Freigabe und Projektion an (die Projektion liegt unter dem Mandanten des Empfängers); der Empfänger (B, C) liest nur die Projektion,
nur im Zeitraum und nur vor dem Widerruf. Fahrer-, Qualifikations- und Freigabetabellen des Eigentümers sind für den Empfänger unsichtbar (RLS)."""
import json, threading, time, unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B, C, D = uid(), uid(), uid(), uid()
FIELDS = ['tenant_id', 'id', 'owner_tenant_id', 'service_ids', 'valid_from', 'valid_to', 'revoked_at', 'qualifications', 'contact_channel', 'contact_value', 'revision', 'created_at']
SVC_B, SVC_C = uid(), uid()


def new_driver(name='Erika Muster'):
    d = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value) VALUES (%s,%s,%s,'PHONE','+491701234567')", (A, d, name))
        c.execute("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,valid_to,evidence_ref) "
                  "VALUES (%s,%s,%s,'PBEF',1,now()-interval '1 year',NULL,'geheim-akte-4711')", (A, uid(), d))
    return d


def release(c, driver, recipient, services, vf, vt, rid=None, project=True):
    """Freigabe und Projektion beim Eigentümer (Kontext A) in einer Transaktion; vf/vt sind SQL-Zeitausdrücke (z. B. "now() - interval '1 hour'")."""
    rid = rid or uid()
    c.execute(f"INSERT INTO taktovia.driver_release(tenant_id,id,driver_id,recipient_tenant_id,service_ids,valid_from,valid_to,created_by) "
              f"VALUES (%s,%s,%s,%s,%s,{vf},{vt},'user:test')", (A, rid, driver, recipient, services))
    if project:
        c.execute(f"INSERT INTO taktovia.driver_release_projection(tenant_id,id,owner_tenant_id,service_ids,valid_from,valid_to,qualifications,contact_channel,contact_value) "
                  f"SELECT %s, r.id, r.tenant_id, r.service_ids, r.valid_from, r.valid_to, "
                  f"(SELECT coalesce(jsonb_agg(jsonb_build_object('code',q.qualification_code,'valid_from',q.valid_from,'valid_to',q.valid_to)),'[]'::jsonb) "
                  f"FROM taktovia.qualification_revision q WHERE q.driver_id=r.driver_id), d.contact_channel, d.contact_value "
                  f"FROM taktovia.driver_release r JOIN taktovia.driver d ON d.tenant_id=r.tenant_id AND d.id=r.driver_id WHERE r.id=%s", (recipient, rid))
    return rid


def visible(tenant, rid):
    with tx(tenant) as c:
        return c.execute("SELECT count(*) FROM taktovia.driver_release_projection WHERE id=%s", (rid,)).fetchone()[0]


class Fahrerfreigabe(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for t, n in ((A, 'a'), (B, 'b'), (C, 'c'), (D, 'd')): seed_tenant(t, f'm135-{n}')

    def test_empfaenger_sieht_keine_personalakte(self):  # AK 1, 2
        d = new_driver(); B, C, D = uid(), uid(), uid()   # eigene Empfänger, damit keine Freigaben anderer Fälle mitzählen
        for t, n in ((B, 'b2'), (C, 'c2'), (D, 'd2')): seed_tenant(t, f'm135-{n}')
        with tx(A) as c:
            rb = release(c, d, B, [SVC_B], "now() - interval '1 hour'", "now() + interval '1 hour'")
            rc = release(c, d, C, [SVC_C], "now() - interval '1 hour'", "now() + interval '1 hour'")   # derselbe Fahrer, zweiter Empfänger („mehrerer Mandanten“)
        with tx(B) as c:
            row = c.execute("SELECT * FROM taktovia.driver_release_projection").fetchall()
            cols = [x.name for x in c.execute("SELECT * FROM taktovia.driver_release_projection LIMIT 0").description]
            self.assertEqual(len(row), 1); self.assertEqual(cols, FIELDS)
            r = dict(zip(cols, row[0]))
            self.assertEqual(r['id'], rb); self.assertEqual(r['qualifications'][0]['code'], 'PBEF'); self.assertEqual(set(r['qualifications'][0]), {'code', 'valid_from', 'valid_to'})
            self.assertEqual(r['contact_value'], '+491701234567')
            blob = json.dumps({k: str(v) for k, v in r.items()})
            self.assertNotIn('Erika', blob); self.assertNotIn('geheim-akte', blob); self.assertNotIn(str(d), blob)   # kein Name, kein Nachweis, keine Fahrer-ID des Eigentümers
            for t in ('driver', 'qualification_revision', 'driver_release'):   # per SQL unter der Runtime-Rolle von B: keine Zeile des Eigentümers
                self.assertEqual(c.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0], 0, t)
                self.assertEqual(c.execute(f'SELECT count(*) FROM taktovia.{t} WHERE tenant_id=%s', (A,)).fetchone()[0], 0, t)
        with tx(C) as c:   # jeder sieht nur die eigene Projektion
            self.assertEqual([r[0] for r in c.execute('SELECT id FROM taktovia.driver_release_projection')], [rc])
        self.assertEqual(visible(D, rb), 0); self.assertEqual(visible(D, rc), 0)   # unbeteiligter Dritter

    def test_projektionsspalten_ohne_personalakte_arbeitszeit_abwesenheit_gehalt(self):
        with admin() as c:
            cols = [r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='driver_release_projection'")]
            self.assertEqual(sorted(cols), sorted(FIELDS))
            self.assertEqual(c.execute("SELECT count(*) FROM pg_views WHERE schemaname='taktovia'").fetchone()[0], 0)
            for bad in ('salary', 'gehalt', 'absence', 'abwesen', 'working_time', 'arbeitszeit', 'personnel', 'personal_file'):
                self.assertEqual(c.execute("SELECT count(*) FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='driver_release_projection' AND column_name ILIKE %s", (f'%{bad}%',)).fetchone()[0], 0, bad)

    def test_projektion_nicht_lesbar_ausserhalb_zeitraum_und_nach_widerruf(self):  # AK 3
        d = new_driver()
        with tx(A) as c:
            vorher = release(c, d, B, [SVC_B], "now() + interval '1 hour'", "now() + interval '2 hours'")
            abgelaufen = release(c, d, B, [SVC_B], "now() - interval '2 hours'", "now() - interval '1 hour'")
            aktiv = release(c, d, B, [SVC_B], "now() - interval '1 hour'", "now() + interval '1 hour'")
            kurz = release(c, d, B, [SVC_B], "now() - interval '1 hour'", "now() + interval '2 seconds'")
        self.assertEqual((visible(B, vorher), visible(B, abgelaufen), visible(B, aktiv), visible(B, kurz)), (0, 0, 1, 1))
        time.sleep(2.2)   # Ablauf wird bei jedem Zugriff geprüft, kein Cache: ohne weitere Änderung ist die Projektion jetzt unsichtbar
        self.assertEqual(visible(B, kurz), 0)
        with tx(A) as c:   # Widerruf: gleiche Transaktion sperrt die Projektion
            self.assertEqual(c.execute("UPDATE taktovia.driver_release SET revoked_at=clock_timestamp(), revoked_by='user:test' WHERE id=%s", (aktiv,)).rowcount, 1)
            rows = c.execute("SELECT revoked_at IS NOT NULL FROM taktovia.driver_release_projection WHERE id=%s", (aktiv,)).fetchall()
            self.assertEqual(rows, [(True,)])
        self.assertEqual(visible(B, aktiv), 0)
        with admin() as c:
            self.assertEqual(c.execute("SELECT revision FROM taktovia.driver_release WHERE id=%s", (aktiv,)).fetchone()[0], 2)

    def test_widerruf_parallel_zu_lesezugriffen_nach_commit_kein_zugriff(self):  # konkurrierend
        d = new_driver()
        with tx(A) as c:
            rid = release(c, d, B, [SVC_B], "now() - interval '1 hour'", "now() + interval '1 hour'")
        committed = threading.Event(); stop = threading.Event(); bad, seen_before, after = [], [0], [0]

        def reader():
            while not stop.is_set():
                was_committed = committed.is_set()   # Zustand VOR dem Statement: war der Widerruf schon committet, muss es leer sein
                n = visible(B, rid)
                if was_committed:
                    after[0] += 1
                    if n != 0: bad.append(n)
                elif n: seen_before[0] += 1

        ts = [threading.Thread(target=reader) for _ in range(4)]
        [t.start() for t in ts]; time.sleep(0.5)
        with tx(A) as c:
            c.execute("UPDATE taktovia.driver_release SET revoked_at=clock_timestamp(), revoked_by='user:test' WHERE id=%s", (rid,))
        committed.set(); time.sleep(0.7); stop.set(); [t.join() for t in ts]
        self.assertEqual(bad, [], 'nach dem Commit des Widerrufs darf kein Lesezugriff mehr treffen')
        self.assertGreater(seen_before[0], 0, 'vor dem Widerruf war die Projektion lesbar'); self.assertGreater(after[0], 0)

    def test_ablauf_parallel_zu_lesezugriff(self):  # konkurrierend: valid_to läuft während der Lesezugriffe ab
        d = new_driver()
        with tx(A) as c:
            rid = release(c, d, B, [SVC_B], "now() - interval '1 hour'", "now() + interval '1500 milliseconds'")
            expiry = c.execute("SELECT valid_to FROM taktovia.driver_release WHERE id=%s", (rid,)).fetchone()[0]
        results, stop = [], threading.Event()

        def reader():
            while not stop.is_set():
                with tx(B) as c:
                    n, now = c.execute("SELECT (SELECT count(*) FROM taktovia.driver_release_projection WHERE id=%s), clock_timestamp()", (rid,)).fetchone()
                results.append((n, now))
        ts = [threading.Thread(target=reader) for _ in range(3)]
        [t.start() for t in ts]; time.sleep(2.2); stop.set(); [t.join() for t in ts]
        self.assertTrue(any(n == 1 for n, _ in results)); self.assertTrue(any(n == 0 for n, _ in results))
        self.assertEqual([r for r in results if r[1] >= expiry and r[0] != 0 and (r[1] - expiry).total_seconds() > 0.2], [], 'nach valid_to kein Treffer mehr')

    def test_empfaenger_kann_projektion_nicht_aendern_loeschen_oder_anlegen(self):
        d = new_driver()
        with tx(A) as c:
            rid = release(c, d, B, [SVC_B], "now() - interval '1 hour'", "now() + interval '1 hour'")
        with tx(B) as c:
            self.assertEqual(c.execute("UPDATE taktovia.driver_release_projection SET revoked_at=NULL WHERE id=%s", (rid,)).rowcount, 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(B) as c: c.execute("DELETE FROM taktovia.driver_release_projection WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(B) as c: c.execute("UPDATE taktovia.driver_release_projection SET contact_value='x' WHERE id=%s", (rid,))
        # B kann sich keine Projektion für C „freigeben“: ohne eigene Freigabe weist der Trigger ab; mit owner_tenant_id = A greift die RLS-Prüfung
        for owner in (B, A):
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)):
                with tx(B) as c:
                    c.execute("INSERT INTO taktovia.driver_release_projection(tenant_id,id,owner_tenant_id,service_ids,valid_from,valid_to,qualifications,contact_channel,contact_value) "
                              "VALUES (%s,%s,%s,%s,now(),now()+interval '1 hour','[]','PHONE','x')", (C, uid(), owner, [SVC_C]))

    def test_eigentuemer_darf_nur_fuer_eigene_freigabe_projizieren_und_nur_feste_feldliste(self):
        d = new_driver()
        with self.assertRaises(psycopg.errors.CheckViolation):   # Projektion ohne Freigabe
            with tx(A) as c: release(c, d, B, [SVC_B], "now()", "now() + interval '1 hour'", project=False) ; c.execute(
                "INSERT INTO taktovia.driver_release_projection(tenant_id,id,owner_tenant_id,service_ids,valid_from,valid_to,qualifications,contact_channel,contact_value) "
                "VALUES (%s,%s,%s,%s,now(),now()+interval '1 hour','[]','PHONE','x')", (B, uid(), A, [SVC_B]))
        with tx(A) as c:
            rid = release(c, d, B, [SVC_B], "now()", "now() + interval '1 hour'", project=False)
        for extra in ({'code': 'PBEF', 'salary': 1}, {'code': 'PBEF', 'absence': 'krank'}):   # zusätzliches Feld in der Qualifikation
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(A) as c:
                    c.execute("INSERT INTO taktovia.driver_release_projection(tenant_id,id,owner_tenant_id,service_ids,valid_from,valid_to,qualifications,contact_channel,contact_value) "
                              "SELECT recipient_tenant_id,id,tenant_id,service_ids,valid_from,valid_to,%s::jsonb,'PHONE','x' FROM taktovia.driver_release WHERE id=%s", (json.dumps([extra]), rid))
        with self.assertRaises(psycopg.errors.CheckViolation):   # abweichende Dienste
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.driver_release_projection(tenant_id,id,owner_tenant_id,service_ids,valid_from,valid_to,qualifications,contact_channel,contact_value) "
                          "SELECT recipient_tenant_id,id,tenant_id,%s,valid_from,valid_to,'[]','PHONE','x' FROM taktovia.driver_release WHERE id=%s", ([SVC_B, uid()], rid))
        self.assertEqual(visible(B, rid), 0)

    def test_freigabe_unveraenderlich_ausser_einmaligem_widerruf(self):
        d = new_driver()
        with tx(A) as c:
            rid = release(c, d, B, [SVC_B], "now()", "now() + interval '1 hour'")
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # Spaltenrecht: nur revoked_at/revoked_by
            with tx(A) as c: c.execute("UPDATE taktovia.driver_release SET valid_to = valid_to + interval '1 day' WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("DELETE FROM taktovia.driver_release WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.CheckViolation):   # auch der Administrator kann die Freigabe nicht ändern oder löschen (Trigger)
            with admin() as c: c.execute("UPDATE taktovia.driver_release SET valid_to = valid_to + interval '1 day' WHERE id=%s", (rid,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.driver_release SET revoked_at=clock_timestamp(), revoked_by='user:test' WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.CheckViolation):   # zweiter Widerruf
            with tx(A) as c: c.execute("UPDATE taktovia.driver_release SET revoked_at=clock_timestamp(), revoked_by='user:test' WHERE id=%s", (rid,))

    def test_freigabe_pruefungen(self):
        d = new_driver()
        for sql, args, exc in (
            ("INSERT INTO taktovia.driver_release(tenant_id,id,driver_id,recipient_tenant_id,service_ids,valid_from,valid_to,created_by) VALUES (%s,%s,%s,%s,%s,now(),now(),'user:t')", (A, uid(), d, B, [SVC_B]), psycopg.errors.CheckViolation),
            ("INSERT INTO taktovia.driver_release(tenant_id,id,driver_id,recipient_tenant_id,service_ids,valid_from,valid_to,created_by) VALUES (%s,%s,%s,%s,%s,now(),now()+interval '1 hour','user:t')", (A, uid(), d, A, [SVC_B]), psycopg.errors.CheckViolation),
            ("INSERT INTO taktovia.driver_release(tenant_id,id,driver_id,recipient_tenant_id,service_ids,valid_from,valid_to,created_by) VALUES (%s,%s,%s,%s,%s,now(),now()+interval '1 hour','user:t')", (A, uid(), d, B, []), psycopg.errors.CheckViolation),
            ("INSERT INTO taktovia.driver_release(tenant_id,id,driver_id,recipient_tenant_id,service_ids,valid_from,valid_to,created_by) VALUES (%s,%s,%s,%s,%s,now(),now()+interval '1 hour','user:t')", (A, uid(), uid(), B, [SVC_B]), psycopg.errors.ForeignKeyViolation),
            ("INSERT INTO taktovia.driver_release(tenant_id,id,driver_id,recipient_tenant_id,service_ids,valid_from,valid_to,created_by) VALUES (%s,%s,%s,%s,%s,now(),now()+interval '1 hour','user:t')", (A, uid(), d, uid(), [SVC_B]), psycopg.errors.ForeignKeyViolation),
        ):
            with self.assertRaises(exc, msg=sql[:60]):
                with tx(A) as c: c.execute(sql, args)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # fremder Mandant schreibt nicht für A
            with tx(B) as c:
                c.execute("INSERT INTO taktovia.driver_release(tenant_id,id,driver_id,recipient_tenant_id,service_ids,valid_from,valid_to,created_by) VALUES (%s,%s,%s,%s,%s,now(),now()+interval '1 hour','user:t')", (A, uid(), d, B, [SVC_B]))


if __name__ == '__main__':
    unittest.main()
