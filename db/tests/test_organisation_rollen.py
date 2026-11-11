"""WP-M1-05 / LH-I.4-MUL-003: Rechtsträger, Organisationen und die sechs vertraglichen Rollen unter der echten Runtime-Rolle
(CHECK der Rollenwerte, Revision, Urheber, Mandantentrennung per zusammengesetztem Schlüssel, Mandantenanlage und Statuswechsel)."""
import threading, unittest
import psycopg
from helpers import admin, runtime, set_tenant, tx, uid, seed_tenant

A, B = uid(), uid()
LE_A, LE_B = uid(), uid()
SIX = ['PLATFORM_OPERATOR', 'TRANSPORT_COMPANY', 'TASK_AUTHORITY', 'CLIENT', 'BROKER', 'ACTUAL_CARRIER']


def org(c, tenant, le, roles, ref=None, org_id=None):
    oid = org_id or uid()
    c.execute('INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,%s,%s,%s)',
              (tenant, oid, le, 'Org', ref or f'fed-{uid()}', roles))
    return oid


class OrganisationRollen(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm105-a'); seed_tenant(B, 'm105-b')
        for t, le in ((A, LE_A), (B, LE_B)):
            with tx(t) as c:
                c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'LE','DE')", (t, le))

    def test_roles_check_lehnt_unbekannte_werte_ab(self):
        for bad in (['OPERATOR'], ['transport_company'], [], ['CLIENT', 'CLIENT'], ['CLIENT', None], ['CLIENT', 'X']):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=str(bad)):
                with tx(A) as c: org(c, A, LE_A, bad)

    def test_alle_sechs_rollen_getrennt_speicherbar_und_kombinierbar(self):
        with tx(A) as c:
            ids = {r: org(c, A, LE_A, [r]) for r in SIX}
            multi = org(c, A, LE_A, ['BROKER', 'CLIENT'])
            for r, oid in ids.items():
                self.assertEqual(c.execute('SELECT roles FROM taktovia.organization WHERE id=%s', (oid,)).fetchone()[0], [r])
            self.assertEqual(sorted(c.execute('SELECT roles FROM taktovia.organization WHERE id=%s', (multi,)).fetchone()[0]), ['BROKER', 'CLIENT'])

    def test_rechtstraeger_eines_fremden_mandanten_wird_abgewiesen(self):
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(A) as c: org(c, A, LE_B, ['CLIENT'])
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):  # fremder Mandant im Schreibkontext: RLS WITH CHECK
            with tx(A) as c: org(c, B, LE_B, ['CLIENT'])

    def test_organisation_des_anderen_mandanten_ist_unsichtbar(self):
        with tx(B) as c: oid = org(c, B, LE_B, ['CLIENT'])
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.organization WHERE id=%s', (oid,)).fetchone()[0], 0)
            self.assertEqual(c.execute('UPDATE taktovia.organization SET name=%s WHERE id=%s', ('x', oid)).rowcount, 0)

    def test_doppelte_federation_ref_und_paralleles_anlegen_genau_ein_erfolg(self):
        ref = f'dup-{uid()}'
        with tx(A) as c: org(c, A, LE_A, ['CLIENT'], ref)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: org(c, A, LE_A, ['CLIENT'], ref)
        ref2, results, start = f'par-{uid()}', [], threading.Barrier(6)

        def run():
            try:
                with tx(A) as c:
                    start.wait(); org(c, A, LE_A, ['BROKER'], ref2); results.append('ok')
            except psycopg.errors.UniqueViolation:
                results.append('dup')
        ts = [threading.Thread(target=run) for _ in range(6)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual((results.count('ok'), results.count('dup')), (1, 5), results)

    def test_revision_steigt_und_urheber_bleibt_unveraenderlich(self):
        with tx(A) as c:
            oid = org(c, A, LE_A, ['CLIENT'])
            self.assertEqual(c.execute('SELECT revision, created_by FROM taktovia.organization WHERE id=%s', (oid,)).fetchone(), (1, 'system:unattributed'))
            c.execute("UPDATE taktovia.organization SET roles=%s WHERE id=%s", (['CLIENT', 'BROKER'], oid))
            self.assertEqual(c.execute('SELECT revision FROM taktovia.organization WHERE id=%s', (oid,)).fetchone()[0], 2)
        for col, val in (('created_by', "'user:x'"), ('created_at', "now() - interval '1 day'"), ('id', 'gen_random_uuid()')):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=col):
                with tx(A) as c: c.execute(f'UPDATE taktovia.organization SET {col}={val} WHERE id=%s', (oid,))
        with self.assertRaises(psycopg.errors.CheckViolation):  # Urheberformat
            with tx(A) as c: c.execute("UPDATE taktovia.legal_entity SET name='n' WHERE id=%s", (LE_A,)); c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country,created_by) VALUES (%s,%s,'x','DE','Max Mustermann')", (A, uid()))
        with tx(A) as c:
            c.execute("UPDATE taktovia.legal_entity SET name='LE2' WHERE id=%s", (LE_A,))
            self.assertGreaterEqual(c.execute('SELECT revision FROM taktovia.legal_entity WHERE id=%s', (LE_A,)).fetchone()[0], 2)

    def test_rollengueltigkeit_halboffen(self):
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles,roles_valid_from,roles_valid_to) "
                          "VALUES (%s,%s,%s,'o',%s,%s,now(),now() - interval '1 day')", (A, uid(), LE_A, f'g-{uid()}', ['CLIENT']))


class Mandantenanlage(unittest.TestCase):
    def test_anlage_nur_im_kontext_der_eigenen_neuen_id(self):
        new = uid()
        with tx(new) as c:  # Plattformverwaltung: Kontext = neue Mandanten-ID, dann genau diese Zeile
            c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'neu','ACTIVE')", (new,))
            c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'LE','DE')", (new, uid()))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):  # fremde ID im Kontext eines anderen Mandanten
            with tx(A) as c: c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'fremd','ACTIVE')", (uid(),))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):  # kein Kontext
            with tx(None) as c: c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'ohne','ACTIVE')", (uid(),))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(uid()) as c: c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (current_setting('app.tenant_id')::uuid,'x','GELOESCHT')")

    def test_statuswechsel_nur_nach_uebergangsregeln_und_closed_ist_endgueltig(self):
        t = uid()
        with tx(t) as c: c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'st','ACTIVE')", (t,))
        for to in ('SUSPENDED', 'ACTIVE', 'CLOSED'):
            with tx(t) as c: self.assertEqual(c.execute('UPDATE taktovia.tenant SET status=%s WHERE id=%s', (to, t)).rowcount, 1)
        for to in ('ACTIVE', 'SUSPENDED'):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=to):
                with tx(t) as c: c.execute('UPDATE taktovia.tenant SET status=%s WHERE id=%s', (to, t))

    def test_betreiberkonten_nur_mit_subject_schluessel_lesbar(self):
        iss, sub = 'https://idp.test/realms/m105', f'op-{uid()}'
        with admin() as a: a.execute('INSERT INTO taktovia.platform_operator_account(issuer,subject) VALUES (%s,%s)', (iss, sub))
        c = runtime()
        try:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.platform_operator_account').fetchone()[0], 0)  # kein Aufzählen
            c.execute("SELECT set_config('app.subject_key', %s, true)", (iss + '\n' + sub,))
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.platform_operator_account').fetchone()[0], 1)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute('INSERT INTO taktovia.platform_operator_account(issuer,subject) VALUES (%s,%s)', (iss, 'selbst'))
        finally:
            c.rollback(); c.close()


if __name__ == '__main__':
    unittest.main()
