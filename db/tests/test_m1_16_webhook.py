"""WP-M1-16: Webhook-Tabellen unter der Runtime-Rolle (Secret nur als Referenz, RLS, Zustellzustand, Rechte)."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()


def sub(c, tenant, url='https://hook.example.org/x', secret='secret://taktovia-local-secrets/webhook_abc', types=('membership.granted',)):
    sid = uid()
    c.execute("""INSERT INTO taktovia.webhook_subscription(tenant_id,id,url,event_types,secret_ref,created_by)
                 VALUES (%s,%s,%s,%s,%s,'user:u1')""", (tenant, sid, url, list(types), secret))
    return sid


class WebhookDb(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'wh-a'); seed_tenant(B, 'wh-b')

    def test_secret_nur_als_referenz_nie_als_wert(self):
        for bad in ('geheimerwert123', 'https://token@example.org', 'secret://', 'secret://x/y'):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=bad):
                with tx(A) as c:
                    sub(c, A, secret=bad)
        with admin() as c:   # es gibt keine Spalte für einen Secretwert
            cols = [r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='webhook_subscription'")]
            self.assertIn('secret_ref', cols)
            self.assertFalse([x for x in cols if any(k in x for k in ('secret_value', 'password', 'token', 'hmac_key', 'signing_key'))])

    def test_nur_https_und_ereignistypen_begrenzt(self):
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                sub(c, A, url='http://hook.example.org/x')
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                sub(c, A, types=())
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                sub(c, A, types=[f't.e{i}' for i in range(51)])

    def test_abonnement_ist_mandantengetrennt(self):
        with tx(A) as c:
            sid = sub(c, A)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.webhook_subscription WHERE id=%s", (sid,)).fetchone()[0], 0)
            self.assertEqual(c.execute("UPDATE taktovia.webhook_subscription SET active=false, deactivated_at=now() WHERE id=%s", (sid,)).rowcount, 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # Löschen gibt es nicht: Deaktivieren per UPDATE
            with tx(A) as c:
                c.execute("DELETE FROM taktovia.webhook_subscription WHERE id=%s", (sid,))
        with self.assertRaises(psycopg.errors.Error):   # Abonnement eines Mandanten kann nicht auf anderen Mandanten verweisen/umgehängt werden
            with tx(A) as c:
                c.execute("UPDATE taktovia.webhook_subscription SET tenant_id=%s WHERE id=%s", (B, sid))

    def test_deaktivierung_ist_konsistent(self):
        with tx(A) as c:
            sid = sub(c, A)
        with self.assertRaises(psycopg.errors.CheckViolation):   # inaktiv ohne Zeitpunkt
            with tx(A) as c:
                c.execute("UPDATE taktovia.webhook_subscription SET active=false WHERE id=%s", (sid,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.webhook_subscription SET active=false, deactivated_at=now(), revision=revision+1 WHERE id=%s", (sid,))

    def test_zustellung_einmalig_je_abonnement_und_ereignis(self):
        with tx(A) as c:
            sid = sub(c, A); ev = uid()
            c.execute("INSERT INTO taktovia.webhook_delivery(tenant_id,id,subscription_id,event_id,event_type,body) VALUES (%s,%s,%s,%s,'membership.granted','{}')", (A, uid(), sid, ev))
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.webhook_delivery(tenant_id,id,subscription_id,event_id,event_type,body) VALUES (%s,%s,%s,%s,'membership.granted','{}')", (A, uid(), sid, ev))
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):   # fremdes Abonnement
            with tx(B) as c:
                c.execute("INSERT INTO taktovia.webhook_delivery(tenant_id,id,subscription_id,event_id,event_type,body) VALUES (%s,%s,%s,%s,'membership.granted','{}')", (B, uid(), sid, uid()))

    def test_zustellzustand_konsistent_mit_zeitstempeln_und_fehlercode_ohne_freitext(self):
        with tx(A) as c:
            sid = sub(c, A); did = uid()
            c.execute("INSERT INTO taktovia.webhook_delivery(tenant_id,id,subscription_id,event_id,event_type,body) VALUES (%s,%s,%s,%s,'membership.granted','{}')", (A, did, sid, uid()))
        for sql in ("UPDATE taktovia.webhook_delivery SET state='DELIVERED' WHERE id=%s", "UPDATE taktovia.webhook_delivery SET state='DEAD' WHERE id=%s",
                    "UPDATE taktovia.webhook_delivery SET last_error='Verbindung zu 10.0.0.1 abgelehnt' WHERE id=%s", "UPDATE taktovia.webhook_delivery SET state='WEIRD' WHERE id=%s"):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with tx(A) as c:
                    c.execute(sql, (did,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.webhook_delivery SET state='DELIVERED', delivered_at=now(), last_status=200 WHERE id=%s", (did,))

    def test_quarantaene_nur_anhaengen_und_nur_hashes(self):
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.webhook_quarantine(tenant_id,id,source,event_id,payload_sha256,original_sha256,reason) VALUES (%s,%s,'partner-x',%s,%s,%s,'payload_hash_mismatch')",
                      (A, uid(), uid(), 'a' * 64, 'b' * 64))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c:
                c.execute("UPDATE taktovia.webhook_quarantine SET reason='x' WHERE tenant_id=%s", (A,))
        with admin() as c:
            cols = [r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='webhook_quarantine'")]
            self.assertNotIn('payload', cols); self.assertNotIn('body', cols)


if __name__ == '__main__':
    unittest.main()
