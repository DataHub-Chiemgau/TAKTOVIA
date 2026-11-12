"""WP-M2-21 / LH-6.4-BKG-002: `ride_offer` unter der echten Runtime-Rolle: Pflichtbindung an Quote, Versuch und Dienst desselben Mandanten, Inhalt unveränderlich (auch für die
Administration), Zustandsautomat gehärtet, Revision nur +1, Hold-Bezug höchstens einmal, RLS (fremder Mandant sieht und referenziert nichts), Runtime ohne DELETE."""
import json
import unittest

import psycopg
from helpers import tx, uid, seed_tenant, admin
from geo_helpers import make_service
from tarif_helpers import add_revision
from test_fare_quote import make_quote
from test_route_stop import make_run

A, B = uid(), uid()
VEC = json.dumps({'schema_version': '1.0', 'seats': 2, 'wheelchair': 0, 'equipment': []})


def make_offer(c, tenant, svc, quote, **over):
    row = c.execute("SELECT attempt_id FROM taktovia.fare_quote WHERE id=%s", (quote,)).fetchone()
    attempt = over.pop('attempt_id', row[0] if row else uid())
    oid = uid()
    cols = dict(attempt_id=attempt, quote_id=quote, service_id=svc, actor_ref='user:t', booking_kind='ADVANCE', time_kind='DEPARTURE', pickup_earliest='2026-12-01T09:00:00Z',
                pickup_latest='2026-12-01T09:15:00Z', arrival_earliest='2026-12-01T09:10:00Z', arrival_latest='2026-12-01T09:25:00Z', arrival_grace_min=5, direct_seconds=600,
                expires_at='2026-12-01T08:55:00Z', created_at='2026-12-01T08:50:00Z', cancellation_policy_ref='std-24h', demand=VEC)
    cols.update(over)
    names = ','.join(cols)
    c.execute(f"INSERT INTO taktovia.ride_offer(tenant_id,id,{names}) VALUES (%s,%s,{','.join('%s' for _ in cols)})", (tenant, oid, *cols.values()))
    return oid


class RideOffer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm221-a'); seed_tenant(B, 'm221-b')

    def setUp(self):
        with tx(A) as c:
            self.svc = make_service(c, A)
            self.quote = make_quote(c, A, add_revision(c, A, self.svc, state='VEROEFFENTLICHT'))
            self.offer = make_offer(c, A, self.svc, self.quote)

    def test_angebot_inhalt_unveraendert_und_zustandsautomat(self):
        # Inhalt: weder Runtime (Spaltenrecht) noch Administration (Trigger ride_offer_guard)
        for sql in ("UPDATE taktovia.ride_offer SET expires_at = expires_at + interval '1 hour' WHERE id=%s", "UPDATE taktovia.ride_offer SET demand = '{\"seats\": 1}'::jsonb WHERE id=%s",
                    "UPDATE taktovia.ride_offer SET quote_id = quote_id WHERE id=%s"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql):
                with tx(A) as c: c.execute(sql, (self.offer,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with admin() as a: a.execute("UPDATE taktovia.ride_offer SET expires_at = expires_at + interval '1 hour' WHERE id=%s", (self.offer,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):                                 # kein DELETE
            with tx(A) as c: c.execute("DELETE FROM taktovia.ride_offer WHERE id=%s", (self.offer,))
        # Zustand: OFFERED -> HELD nur mit Revision +1 und Hold-Bezug; Sprünge und Rückwege sind unzulässig
        with self.assertRaises(psycopg.errors.CheckViolation):                                         # Revision muss genau um 1 steigen
            with tx(A) as c: c.execute("UPDATE taktovia.ride_offer SET state='DECLINED', revision=revision+2 WHERE id=%s", (self.offer,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.ride_offer SET state='EXPIRED', revision=revision+1 WHERE id=%s", (self.offer,))
        for target in ('OFFERED', 'HELD', 'DECLINED'):                                                 # EXPIRED ist endgültig
            with self.assertRaises(psycopg.errors.CheckViolation, msg=target):
                with tx(A) as c: c.execute("UPDATE taktovia.ride_offer SET state=%s, revision=revision+1 WHERE id=%s", (target, self.offer))
        with self.assertRaises(psycopg.errors.CheckViolation):                                         # unbekannter Zustand
            with tx(A) as c: c.execute("UPDATE taktovia.ride_offer SET state='DONE', revision=revision+1 WHERE id=%s", (make_offer_ok(self),))

    def test_hold_bezug_einmalig_und_nur_mit_zustand(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A)
            h1, h2 = uid(), uid()
            for h in (h1, h2):
                c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
                          "VALUES (%s,%s,%s,1,%s,%s::jsonb,0,1,clock_timestamp()+interval '3 minutes','HELD')", (A, h, run, f"ref-{h}", VEC))
        scope = 'a' * 64
        with self.assertRaises(psycopg.errors.CheckViolation):                                         # Hold-Bezug im Zustand OFFERED (und ohne scope) ist unzulässig
            with tx(A) as c: c.execute("UPDATE taktovia.ride_offer SET hold_id=%s, revision=revision+1 WHERE id=%s", (h1, self.offer))
        with tx(A) as c:
            c.execute("UPDATE taktovia.ride_offer SET state='HELD', hold_id=%s, hold_scope=%s, revision=revision+1 WHERE id=%s", (h1, scope, self.offer))
        with self.assertRaises(psycopg.errors.CheckViolation):                                         # nur einmal setzen
            with tx(A) as c: c.execute("UPDATE taktovia.ride_offer SET hold_id=%s, hold_scope=%s, revision=revision+1 WHERE id=%s", (h2, scope, self.offer))
        with self.assertRaises(psycopg.errors.CheckViolation):                                         # Format des Scope
            with tx(A) as c: c.execute("UPDATE taktovia.ride_offer SET hold_scope='x', revision=revision+1 WHERE id=%s", (make_offer_ok(self),))

    def test_bindungen_pflicht_und_mandantentrennung(self):
        with tx(A) as c:
            # ohne Quote kein Angebot (Hold und Bestätigung sind ohne Preisstand nicht möglich)
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                with c.transaction(): make_offer(c, A, self.svc, uid())
        with self.assertRaises(psycopg.errors.UniqueViolation):                                        # ein Angebot je Quote
            with tx(A) as c: make_offer(c, A, self.svc, self.quote)
        with tx(B) as c:                                                                               # fremder Mandant: unsichtbar und nicht referenzierbar
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.ride_offer WHERE id=%s", (self.offer,)).fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(B) as c: make_offer(c, A, self.svc, self.quote)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(B) as c: make_offer(c, B, self.svc, self.quote)

    def test_formpruefungen(self):
        cases = [dict(booking_kind='SOFORT'), dict(arrival_latest='2026-12-01T09:00:00Z'), dict(expires_at='2026-12-01T08:50:00Z'), dict(demand=json.dumps({'seats': 2, 'foo': 1})),
                 dict(demand=json.dumps({'seats': 100})), dict(run_id=uid()), dict(arrival_grace_min=241), dict(direct_seconds=0), dict(cancellation_policy_ref='')]
        for over in cases:
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.ForeignKeyViolation), msg=str(over)):
                with tx(A) as c:
                    svc, q = fresh_quote(c)
                    make_offer(c, A, svc, q, **over)


def fresh_quote(c):
    """Eigener Dienst mit veröffentlichter Tarifrevision und Quote (Tarifrevisionen überlappen sich je Dienst nicht)."""
    svc = make_service(c, A)
    return svc, make_quote(c, A, add_revision(c, A, svc, state='VEROEFFENTLICHT'))


def make_offer_ok(t):
    with tx(A) as c:
        svc, q = fresh_quote(c)
        return make_offer(c, A, svc, q)


if __name__ == '__main__':
    unittest.main()
