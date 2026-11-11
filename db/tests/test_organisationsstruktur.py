"""WP-M1-09 / LH-I.4-MUL-002: Region, Betriebshof, Dienst und Kostenstelle unter der echten Runtime-Rolle (zusammengesetzte Schlüssel,
RLS, Revision, Eindeutigkeit der Kostenstelle, Mandantentrennung, parallele Anlage)."""
import threading, unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
SYS = 'user:test'


def legal_entity(c, tenant):
    le = uid()
    c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'LE','DE')", (tenant, le))
    return le


def organization(c, tenant, le):
    o = uid()
    c.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,'Org',%s,%s)",
              (tenant, o, le, f'f-{o}', ['TRANSPORT_COMPANY']))
    return o


def region(c, tenant, name='Region'):
    r = uid()
    c.execute("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (%s,%s,%s,%s)", (tenant, r, name, SYS))
    return r


def revision(c, tenant, scope_id, kind='SERVICE'):
    r = uid()
    c.execute("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) "
              "VALUES (%s,%s,%s,%s,1,'1','{}','2026-01-01','user:test','Test')", (tenant, r, kind, scope_id))
    return r


def service(c, tenant, org, region_id=None, tz='Europe/Berlin', name='Dienst'):
    s = uid()
    rev = revision(c, tenant, s)
    c.execute("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,region_id,created_by) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
              (tenant, s, org, name, tz, rev, region_id, SYS))
    return s


def cost_center(c, tenant, le, ref, name='KST', region_id=None, service_id=None):
    k = uid()
    c.execute("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref,name,region_id,service_id,created_by) VALUES (%s,%s,%s,'COST_CENTER',%s,%s,%s,%s,%s)",
              (tenant, k, le, ref, name, region_id, service_id, SYS))
    return k


class Organisationsstruktur(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm109-a'); seed_tenant(B, 'm109-b')
        cls.le, cls.org, cls.leB, cls.orgB = {}, {}, None, None
        with tx(A) as c:
            cls.leA = legal_entity(c, A); cls.orgA = organization(c, A, cls.leA)
        with tx(B) as c:
            cls.leB = legal_entity(c, B); cls.orgB = organization(c, B, cls.leB)

    def test_region_betriebshof_tabellen_haben_tenant_pk_und_rls(self):
        with admin() as c:
            for t in ('region', 'depot'):
                pk = c.execute("SELECT array_agg(a.attname ORDER BY k.ord) FROM pg_index i "
                               "JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum, ord) ON true "
                               "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum "
                               "WHERE i.indrelid = %s::regclass AND i.indisprimary", (f'taktovia.{t}',)).fetchone()[0]
                self.assertEqual(pk, ['tenant_id', 'id'], t)
                rls = c.execute("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = %s::regclass", (f'taktovia.{t}',)).fetchone()
                self.assertEqual(rls, (True, True), t)
                pol = c.execute("SELECT count(*) FROM pg_policies WHERE schemaname='taktovia' AND tablename=%s AND policyname='tenant_isolation'", (t,)).fetchone()[0]
                self.assertEqual(pol, 1, t)
                self.assertTrue(c.execute("SELECT count(*) FROM pg_attribute WHERE attrelid=%s::regclass AND attname='revision' AND NOT attisdropped", (f'taktovia.{t}',)).fetchone()[0])

    def test_zwei_regionen_je_ein_dienst_und_mehrere_betriebshoefe_kostenstellen(self):
        with tx(A) as c:
            r1, r2 = region(c, A, 'Nord'), region(c, A, 'Süd')
            s1, s2 = service(c, A, self.orgA, r1, name='Rufbus Nord'), service(c, A, self.orgA, r2, name='Rufbus Süd')
            d = [uid(), uid(), uid()]
            for i, rid in zip(d, (r1, r1, r2)):
                c.execute("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (%s,%s,%s,'Hof',%s)", (A, i, rid, SYS))
            cost_center(c, A, self.leA, 'KST-1', region_id=r1); cost_center(c, A, self.leA, 'KST-2', service_id=s2); cost_center(c, A, self.leA, 'KST-3')
            self.assertEqual(c.execute("SELECT region_id FROM taktovia.service WHERE id=%s", (s1,)).fetchone()[0], r1)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.depot WHERE region_id=%s", (r1,)).fetchone()[0], 2)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.cost_object WHERE kind='COST_CENTER' AND tenant_id=%s", (A,)).fetchone()[0] >= 3, True)

    def test_fremder_mandant_ist_nicht_referenzierbar(self):
        with tx(B) as c: rB = region(c, B)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):  # Dienst -> Region eines fremden Mandanten
            with tx(A) as c: service(c, A, self.orgA, rB)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):  # Betriebshof -> Region eines fremden Mandanten
            with tx(A) as c: c.execute("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (%s,%s,%s,'H',%s)", (A, uid(), rB, SYS))
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):  # Kostenstelle -> Region eines fremden Mandanten
            with tx(A) as c: cost_center(c, A, self.leA, 'X-' + str(uid()), region_id=rB)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):  # Dienst mit Betreiber (Organisation) eines fremden Mandanten
            with tx(A) as c: service(c, A, self.orgB)
        with tx(A) as c:  # RLS: B-Region ist unsichtbar und unveränderbar
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.region WHERE id=%s", (rB,)).fetchone()[0], 0)
            self.assertEqual(c.execute("UPDATE taktovia.region SET name='x' WHERE id=%s", (rB,)).rowcount, 0)

    def test_doppelte_business_ref_je_kind_und_gleichnamige_in_a_und_b(self):
        ref = 'KST-' + str(uid())
        with tx(A) as c: cost_center(c, A, self.leA, ref)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: cost_center(c, A, self.leA, ref)
        with tx(B) as c: cost_center(c, B, self.leB, ref)   # in Mandant B ein anderes, getrenntes Objekt
        with tx(A) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.cost_object WHERE business_ref=%s", (ref,)).fetchone()[0], 1)
        with tx(B) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.cost_object WHERE business_ref=%s", (ref,)).fetchone()[0], 1)

    def test_paralleles_anlegen_identischer_kostenstelle_genau_ein_erfolg(self):
        ref, results, barrier = 'PAR-' + str(uid()), [], threading.Barrier(6)

        def run():
            try:
                barrier.wait()
                with tx(A) as c: cost_center(c, A, self.leA, ref)
                results.append('ok')
            except psycopg.errors.UniqueViolation:
                results.append('dup')
        ts = [threading.Thread(target=run) for _ in range(6)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual((results.count('ok'), results.count('dup')), (1, 5), results)

    def test_ungueltige_iana_zone_und_namen_abgewiesen(self):
        for tz in ('Mars/Olympus', 'CET', 'Etc/GMT+1', 'Europe/Berlin '):
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InvalidParameterValue), msg=tz):
                with tx(A) as c: service(c, A, self.orgA, tz=tz)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: region(c, A, name='')
        with self.assertRaises(psycopg.errors.CheckViolation):  # Kennung länger als 40 Zeichen
            with tx(A) as c: cost_center(c, A, self.leA, 'K' * 41)

    def test_revision_steigt_je_aenderung_und_urheber_ist_unveraenderlich(self):
        with tx(A) as c:
            r = region(c, A, 'Alt')
            self.assertEqual(c.execute("SELECT revision FROM taktovia.region WHERE id=%s", (r,)).fetchone()[0], 1)
            c.execute("UPDATE taktovia.region SET name='Neu' WHERE id=%s", (r,))
            self.assertEqual(c.execute("SELECT revision FROM taktovia.region WHERE id=%s", (r,)).fetchone()[0], 2)
            c.execute("UPDATE taktovia.region SET name='Neu' WHERE id=%s", (r,))   # ohne Wirkung: keine neue Revision
            self.assertEqual(c.execute("SELECT revision FROM taktovia.region WHERE id=%s", (r,)).fetchone()[0], 2)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.region SET created_by='user:andere' WHERE id=%s", (r,))
        with tx(A) as c:
            k = cost_center(c, A, self.leA, 'R-' + str(uid()))
            c.execute("UPDATE taktovia.cost_object SET name='Neu' WHERE id=%s", (k,))
            self.assertEqual(c.execute("SELECT revision FROM taktovia.cost_object WHERE id=%s", (k,)).fetchone()[0], 2)


if __name__ == '__main__':
    unittest.main()
