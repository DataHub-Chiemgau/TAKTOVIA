"""WP-M1-29 / LH-6.2-IAM-006: Präferenzen unter der echten Runtime-Rolle (RLS, Form, Haltestellenreferenz)."""
import unittest

import psycopg

from helpers import admin, runtime, seed_tenant, set_tenant, uid

A, B = uid(), uid()


def stop(tenant):
    sid = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from) "
                  "VALUES (%s,%s,%s,1,ST_SetSRID(ST_MakePoint(9.0,48.0),4326),'Fixture',%s,'test',now())", (tenant, uid(), sid, '{}'))
    return sid


def passenger(tenant):
    p = uid()
    c = runtime()
    try:
        set_tenant(c, tenant)
        c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,now()+interval '1 year')", (tenant, p, 'tok-' + str(p)))
        c.commit()
    finally:
        c.close()
    return p


class PassengerPraeferenz(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'pref-a-' + str(A)[:8]); seed_tenant(B, 'pref-b-' + str(B)[:8])
        cls.pa, cls.pb = passenger(A), passenger(B)
        cls.sa, cls.sb = stop(A), stop(B)
        for t, p, s in ((A, cls.pa, cls.sa), (B, cls.pb, cls.sb)):
            c = runtime()
            try:
                set_tenant(c, t)
                c.execute("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,stop_id) VALUES (%s,%s,%s,'PREFERRED_STOP',0,%s)", (t, uid(), p, s))
                c.execute("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,channel) VALUES (%s,%s,%s,'NOTIFICATION_CHANNEL',0,'PUSH')", (t, uid(), p))
                c.commit()
            finally:
                c.close()

    def test_rls_fremder_mandant_liest_keine_praeferenz(self):
        c = runtime()
        try:
            set_tenant(c, A)
            self.assertEqual({r[0] for r in c.execute('SELECT passenger_id FROM taktovia.passenger_preference')}, {self.pa})
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.passenger_preference WHERE passenger_id=%s', (self.pb,)).fetchone()[0], 0)
            self.assertEqual(c.execute('DELETE FROM taktovia.passenger_preference WHERE passenger_id=%s', (self.pb,)).rowcount, 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # WITH CHECK: nicht für den fremden Mandanten schreiben
                c.execute("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,channel) VALUES (%s,%s,%s,'NOTIFICATION_CHANNEL',1,'SMS')", (B, uid(), self.pb))
            c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):   # zusammengesetzter FK: fremder Fahrgast im eigenen Mandanten
                c.execute("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,channel) VALUES (%s,%s,%s,'NOTIFICATION_CHANNEL',1,'SMS')", (A, uid(), self.pb))
        finally:
            c.rollback(); c.close()

    def test_unbekannte_und_fremde_haltestelle_wird_abgewiesen(self):
        c = runtime()
        try:
            set_tenant(c, A)
            for s in (uid(), self.sb):   # nicht existierend bzw. Haltestelle eines anderen Mandanten
                with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                    c.execute("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,stop_id) VALUES (%s,%s,%s,'PREFERRED_STOP',5,%s)", (A, uid(), self.pa, s))
                c.rollback(); set_tenant(c, A)
        finally:
            c.rollback(); c.close()

    def test_form_je_art_und_eindeutigkeit(self):
        c = runtime()
        try:
            set_tenant(c, A)
            with self.assertRaises(psycopg.errors.CheckViolation):   # Favorit braucht Bezeichnung und Haltestelle
                c.execute("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,stop_id) VALUES (%s,%s,%s,'FAVORITE',0,%s)", (A, uid(), self.pa, self.sa))
            c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.UniqueViolation):   # Kanal höchstens einmal
                c.execute("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,channel) VALUES (%s,%s,%s,'NOTIFICATION_CHANNEL',3,'PUSH')", (A, uid(), self.pa))
            c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.CheckViolation):   # Kanal nur aus dem Katalog
                c.execute("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,channel) VALUES (%s,%s,%s,'NOTIFICATION_CHANNEL',3,'FAX')", (A, uid(), self.pa))
        finally:
            c.rollback(); c.close()


if __name__ == '__main__':
    unittest.main()
