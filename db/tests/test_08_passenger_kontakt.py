"""WP-M1-28 / LH-6.2-IAM-001: Kontaktkanal, Verifikationsaufgabe und Konto-Gerüst unter der echten Runtime-Rolle (RLS, Trigger, Rechte)."""
import unittest

import psycopg

from helpers import admin, fields, runtime, seed_tenant, set_tenant, uid

A, B = uid(), uid()

def hex64(ch):
    return ch * 64


def mk_passenger(tenant, passenger, subject_token):
    c = runtime()
    try:
        set_tenant(c, tenant)
        c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,now()+interval '1 year')",
                  (tenant, passenger, subject_token))
        c.commit()
    finally:
        c.close()


def mk_contact(tenant, passenger, channel, tok, status='UNVERIFIED'):
    c = runtime()
    try:
        set_tenant(c, tenant)
        c.execute("INSERT INTO taktovia.passenger_contact(tenant_id,id,passenger_id,channel,contact_token,status,verified_at) VALUES (%s,%s,%s,%s,%s,%s,%s)",
                  (tenant, uid(), passenger, channel, tok, status, None if status == 'UNVERIFIED' else __import__('datetime').datetime.now(__import__('datetime').timezone.utc)))
        c.commit()
    finally:
        c.close()


class PassengerKontakt(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'kontakt-a-' + str(A)[:8]); seed_tenant(B, 'kontakt-b-' + str(B)[:8])
        cls.pa, cls.pb = uid(), uid()
        mk_passenger(A, cls.pa, 'tok-' + str(cls.pa)); mk_passenger(B, cls.pb, 'tok-' + str(cls.pb))
        mk_contact(A, cls.pa, 'EMAIL', hex64('a'), 'VERIFIED'); mk_contact(B, cls.pb, 'EMAIL', hex64('b'), 'VERIFIED')
        # Ciphertext schreibt nur der Modulzugang
        for t, p in ((A, cls.pa), (B, cls.pb)):
            f = fields()
            try:
                set_tenant(f, t)
                f.execute("UPDATE taktovia.passenger_private SET contact_ciphertext=%s WHERE id=%s", (b'\x01nicht-klartext', p))
                f.commit()
            finally:
                f.close()

    def test_fremder_mandant_liest_keinen_kontakt(self):
        c = runtime()
        try:
            set_tenant(c, A)
            seen = {r[0] for r in c.execute('SELECT passenger_id FROM taktovia.passenger_contact')}
            self.assertIn(self.pa, seen)
            self.assertNotIn(self.pb, seen)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id=%s', (self.pb,)).fetchone()[0], 0)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.passenger_private WHERE id=%s', (self.pb,)).fetchone()[0], 0)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.contact_verification').fetchone()[0], 0)
            # der Ciphertext des fremden Mandanten ist auch über den Modulzugang unsichtbar
            f = fields()
            try:
                set_tenant(f, A)
                self.assertEqual(f.execute('SELECT count(*) FROM taktovia.passenger_private WHERE id=%s', (self.pb,)).fetchone()[0], 0)
                self.assertEqual(f.execute('UPDATE taktovia.passenger_private SET contact_ciphertext=%s WHERE id=%s', (b'x', self.pb)).rowcount, 0)
            finally:
                f.rollback(); f.close()
            # Schreiben für den fremden Mandanten scheitert an WITH CHECK, Fremdschlüssel auf den fremden Fahrgast am zusammengesetzten FK
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute("INSERT INTO taktovia.passenger_contact(tenant_id,id,passenger_id,channel,contact_token) VALUES (%s,%s,%s,'PHONE',%s)", (B, uid(), self.pb, hex64('c')))
            c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                c.execute("INSERT INTO taktovia.passenger_contact(tenant_id,id,passenger_id,channel,contact_token) VALUES (%s,%s,%s,'PHONE',%s)", (A, uid(), self.pb, hex64('d')))
        finally:
            c.rollback(); c.close()

    def test_kontaktaenderung_setzt_kanal_zurueck_und_ist_unverifiziert(self):
        p = uid(); mk_passenger(A, p, 'tok-' + str(p)); mk_contact(A, p, 'EMAIL', hex64('e'), 'VERIFIED')
        c = runtime()
        try:
            set_tenant(c, A)
            self.assertEqual(c.execute("SELECT status FROM taktovia.passenger_contact WHERE passenger_id=%s", (p,)).fetchone()[0], 'VERIFIED')
            c.execute("UPDATE taktovia.passenger_contact SET contact_token=%s WHERE passenger_id=%s", (hex64('f'), p))
            st, vat = c.execute("SELECT status, verified_at FROM taktovia.passenger_contact WHERE passenger_id=%s", (p,)).fetchone()
            self.assertEqual((st, vat), ('UNVERIFIED', None))
            c.commit()
            set_tenant(c, A)
            # Kanal und Fahrgast sind unveränderlich
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.passenger_contact SET channel='PHONE' WHERE passenger_id=%s", (p,))
        finally:
            c.rollback(); c.close()

    def test_ein_kontakt_ein_konto_und_kein_klartext(self):
        p1, p2 = uid(), uid(); mk_passenger(A, p1, 'tok-' + str(p1)); mk_passenger(A, p2, 'tok-' + str(p2))
        mk_contact(A, p1, 'EMAIL', hex64('1'))
        with self.assertRaises(psycopg.errors.UniqueViolation):
            mk_contact(A, p2, 'EMAIL', hex64('1'))
        with self.assertRaises(psycopg.errors.CheckViolation):   # nur 64 Hexzeichen (HMAC), kein Klartext wie "a@b.de"
            mk_contact(A, p2, 'PHONE', 'anna@example.org')
        # gleiche Kontakt-Token in verschiedenen Mandanten sind unabhängig
        mk_contact(B, self.pb, 'PHONE', hex64('1'))

    def test_verifikationsaufgabe_einmalig_und_unveraenderlich(self):
        p = uid(); mk_passenger(A, p, 'tok-' + str(p))
        c = runtime()
        try:
            set_tenant(c, A)
            vid = uid()
            c.execute("INSERT INTO taktovia.contact_verification(tenant_id,id,passenger_id,channel,contact_token,code_mac,expires_at) VALUES (%s,%s,%s,'EMAIL',%s,%s,now()+interval '10 minutes')",
                      (A, vid, p, hex64('a'), hex64('c')))
            with self.assertRaises(psycopg.errors.UniqueViolation):   # höchstens eine offene Aufgabe je Fahrgast und Kanal
                c.execute("INSERT INTO taktovia.contact_verification(tenant_id,id,passenger_id,channel,contact_token,code_mac,expires_at) VALUES (%s,%s,%s,'EMAIL',%s,%s,now()+interval '10 minutes')",
                          (A, uid(), p, hex64('a'), hex64('d')))
            c.rollback(); set_tenant(c, A)
            c.execute("INSERT INTO taktovia.contact_verification(tenant_id,id,passenger_id,channel,contact_token,code_mac,expires_at) VALUES (%s,%s,%s,'EMAIL',%s,%s,now()+interval '10 minutes')",
                      (A, vid, p, hex64('a'), hex64('c')))
            with self.assertRaises(psycopg.errors.CheckViolation):   # Code-MAC ist unveränderlich
                c.execute("UPDATE taktovia.contact_verification SET code_mac=%s WHERE id=%s", (hex64('e'), vid))
            c.rollback(); set_tenant(c, A)
            c.execute("INSERT INTO taktovia.contact_verification(tenant_id,id,passenger_id,channel,contact_token,code_mac,expires_at) VALUES (%s,%s,%s,'EMAIL',%s,%s,now()+interval '10 minutes')",
                      (A, vid, p, hex64('a'), hex64('c')))
            c.execute("UPDATE taktovia.contact_verification SET state='CONSUMED', closed_at=now() WHERE id=%s", (vid,))
            with self.assertRaises(psycopg.errors.CheckViolation):   # geschlossen ist endgültig: ein Code wirkt höchstens einmal
                c.execute("UPDATE taktovia.contact_verification SET state='PENDING', closed_at=NULL WHERE id=%s", (vid,))
            c.rollback(); set_tenant(c, A)
            c.execute("INSERT INTO taktovia.contact_verification(tenant_id,id,passenger_id,channel,contact_token,code_mac,expires_at) VALUES (%s,%s,%s,'EMAIL',%s,%s,now()+interval '10 minutes')",
                      (A, uid(), p, hex64('a'), hex64('c')))
            with self.assertRaises(psycopg.errors.CheckViolation):   # DELETE nur für gelöschte Fahrgäste (Löschlauf WP-M1-33, Trigger)
                c.execute("DELETE FROM taktovia.contact_verification")
        finally:
            c.rollback(); c.close()

    def test_runtime_sieht_den_klartext_nie_und_kein_codeklartext_spalte(self):
        with admin() as c:
            cols = {r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name IN ('contact_verification','passenger_contact')")}
        self.assertFalse({'code', 'email', 'phone', 'contact', 'contact_value', 'value'} & cols, cols)


if __name__ == '__main__':
    unittest.main()
