"""WP-M2-33 / LH-6.12-COM-002: channel_template_revision unter der echten Runtime-Rolle (Unveränderlichkeit, NULL-Verkehrsprodukt in der Unique-Regel,
Pflichtplatzhalter Buchungsreferenz, RLS, Bindung der Vorlagenrevision am Auftrag)."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
PA = uid()
BOOKING_TYPES = ['booking.confirmed', 'eta.changed', 'vehicle.arrived', 'booking.cancelled_by_passenger', 'booking.cancelled_by_operator']


def template(c, t, event_type='booking.confirmed', language='de', service=None, channel='SMS', revision=1, body='Buchung {{booking_reference}}', vf='2026-01-01', vt=None):
    tid = uid()
    c.execute("INSERT INTO taktovia.channel_template_revision(tenant_id,id,event_type,language,service_id,channel,revision,body_template,valid_from,valid_to,created_by) "
              "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'user:test')", (t, tid, event_type, language, service, channel, revision, body, vf, vt))
    return tid


class Vorlagenrevision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm233-a'); seed_tenant(B, 'm233-b')
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,now() + interval '1 year')", (A, PA, f'tok-{PA}'))

    def test_revision_unveraenderlich_und_rls(self):
        with tx(A) as c: t = template(c, A, event_type='eta.changed')
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):    # Runtime hat kein UPDATE/DELETE-Recht
            with tx(A) as c: c.execute("UPDATE taktovia.channel_template_revision SET body_template='x {{booking_reference}}' WHERE id=%s", (t,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("DELETE FROM taktovia.channel_template_revision WHERE id=%s", (t,))
        for sql in ("UPDATE taktovia.channel_template_revision SET valid_to='2030-01-01' WHERE id=%s", "DELETE FROM taktovia.channel_template_revision WHERE id=%s"):
            with self.assertRaises(psycopg.errors.CheckViolation):       # auch der Eigentümer (Trigger)
                with admin() as c: c.execute(sql, (t,))
        # RLS: Lesen fremder Revisionen leer, Schreiben mit fremdem Mandanten abgewiesen
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.channel_template_revision WHERE id=%s', (t,)).fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(B) as c: template(c, A)

    def test_unique_behandelt_null_verkehrsprodukt_als_gleich(self):
        with tx(A) as c: template(c, A, language='fr', revision=1)
        with self.assertRaises(psycopg.errors.UniqueViolation):          # zweite Revision 1 ohne Verkehrsprodukt
            with tx(A) as c: template(c, A, language='fr', revision=1)
        with tx(A) as c: template(c, A, language='fr', revision=2)       # nächste Revision zulässig
        with tx(A) as c: template(c, A, language='it', channel='EMAIL')  # anderer Kanal/Sprache zulässig

    def test_pflichtplatzhalter_buchungsreferenz_in_der_datenbank(self):
        for et in BOOKING_TYPES:
            with self.assertRaises(psycopg.errors.CheckViolation, msg=et):
                with tx(A) as c: template(c, A, event_type=et, body='Ihre Fahrt wurde bestaetigt', language='xx')
            with tx(A) as c: template(c, A, event_type=et, body='Ref {{ booking_reference }}', language='xx')   # Leerzeichen in den Klammern sind zulässig
        with tx(A) as c: template(c, A, event_type='other.type', body='ohne Referenz', language='xx')          # nicht buchungsbezogener Typ

    def test_gueltigkeit_und_sprache_geprueft(self):
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: template(c, A, language='yy', vf='2026-05-01', vt='2026-05-01')
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: template(c, A, language='DE_de')

    def test_auftrag_bindet_revision_unveraenderlich(self):
        with tx(A) as c:
            r1, r2 = template(c, A, language='zz'), template(c, A, language='zz', revision=2)
            n = uid()
            c.execute("INSERT INTO taktovia.notification(tenant_id,id,event_id,recipient_ref,channel,event_type) VALUES (%s,%s,%s,%s,'SMS','booking.confirmed')", (A, n, uid(), PA))
            c.execute("UPDATE taktovia.notification SET template_revision_id=%s, language='zz' WHERE id=%s", (r1, n))
        with self.assertRaises(psycopg.errors.CheckViolation):           # einmal gebunden, nie ersetzt
            with tx(A) as c: c.execute("UPDATE taktovia.notification SET template_revision_id=%s WHERE id=%s", (r2, n))
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):      # nur existierende Revisionen
            with tx(A) as c:
                m = uid()
                c.execute("INSERT INTO taktovia.notification(tenant_id,id,event_id,recipient_ref,channel,event_type,template_revision_id) VALUES (%s,%s,%s,%s,'SMS','booking.confirmed',%s)", (A, m, uid(), PA, uid()))


if __name__ == '__main__':
    unittest.main()
