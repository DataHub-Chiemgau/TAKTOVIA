"""WP-M1-11 / LH-I.4-MUL-007, LH-6.1-SYS-001: die je Mandant getrennten Bestände (Konfigurationsrevisionen, Mitgliedschaften/Rollen, Stammdaten) sind
mandantenlokal: `tenant_id` NOT NULL im Primärschlüssel, zusammengesetzte Fremdschlüssel, RLS erzwungen, und Werte (Benachrichtigungsregeln) liegen je Mandant
getrennt. Geprüft unter der echten Runtime-Rolle. Abgrenzung: Marken-/Brandingwerte sind im Pflichtenheft nicht spezifiziert (OP-09) und stehen nicht im
Parameterkatalog; sie sind hier nicht nachweisbar. Tarifnachweis (`tariff_revision`) folgt mit M2."""
import json
import unittest

from helpers import admin, seed_tenant, tx, uid

A, B = uid(), uid()
# Mandantenlokale Bestände aus WP-M1-05/-09/-20 und die Konfigurations-/Berichtstabellen
BESTAENDE = ['config_revision', 'impact_report', 'membership', 'organization', 'legal_entity', 'service', 'cost_object', 'region', 'depot']


class Struktur(unittest.TestCase):
    def test_config_revision_membership_stammdaten_haben_tenant_id_im_pk_und_rls(self):
        with admin() as c:
            for t in BESTAENDE:
                nullable = c.execute("SELECT is_nullable FROM information_schema.columns WHERE table_schema='taktovia' AND table_name=%s AND column_name='tenant_id'", (t,)).fetchone()
                self.assertEqual(nullable and nullable[0], 'NO', f'{t}.tenant_id NOT NULL')
                pk = [r[0] for r in c.execute("""SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum = ANY(i.indkey)
                                                 WHERE i.indrelid=%s::regclass AND i.indisprimary""", (f'taktovia.{t}',)).fetchall()]
                self.assertIn('tenant_id', pk, f'{t}: tenant_id im Primärschlüssel')
                rls = c.execute("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid=%s::regclass", (f'taktovia.{t}',)).fetchone()
                self.assertEqual(tuple(rls), (True, True), f'{t}: RLS aktiv und erzwungen')
                pol = c.execute("SELECT count(*) FROM pg_policies WHERE schemaname='taktovia' AND tablename=%s", (t,)).fetchone()[0]
                self.assertGreaterEqual(pol, 1, f'{t}: Policy vorhanden')
            # Fremdschlüssel zwischen mandantenlokalen Tabellen sind zusammengesetzt und enthalten tenant_id
            rows = c.execute("""SELECT conrelid::regclass::text, conname, array_length(conkey,1), (SELECT bool_or(a.attname='tenant_id') FROM pg_attribute a WHERE a.attrelid=conrelid AND a.attnum=ANY(conkey))
                                FROM pg_constraint WHERE contype='f' AND connamespace='taktovia'::regnamespace AND conrelid::regclass::text = ANY(%s)
                                  AND confrelid::regclass::text <> ALL(ARRAY['taktovia.tenant','taktovia.currency','taktovia.policy_schema_version'])""",
                             ([f'taktovia.{t}' for t in BESTAENDE],)).fetchall()
            self.assertTrue(rows)
            for table, name, n, has_tenant in rows:
                self.assertTrue(has_tenant and n >= 2, f'{table}.{name}: zusammengesetzter Fremdschlüssel mit tenant_id')

    def test_runtime_rolle_ist_kein_eigentuemer_und_umgeht_rls_nicht(self):
        with admin() as c:
            r = c.execute("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname='taktovia_runtime'").fetchone()
            self.assertEqual(tuple(r), (False, False))
            owners = {x[0] for x in c.execute("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname='taktovia'").fetchall()}
            self.assertNotIn('taktovia_runtime', owners)


class Werte(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'best-a'); seed_tenant(B, 'best-b')

    def revision(self, tenant, values, revision=1):
        rid = uid()
        with tx(tenant) as c:
            c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason)
                         VALUES (%s,%s,'TENANT',%s,%s,'1',%s::jsonb,'2030-01-01T00:00:00Z','user:alice','Benachrichtigungsregeln')""",
                      (tenant, rid, tenant, revision, json.dumps(values)))
        return rid

    def test_benachrichtigungswerte_und_rollen_je_mandant_getrennt(self):
        ra = self.revision(A, {'notification_quiet_hours': {'from': '22:00', 'to': '06:00'}, 'reporting_timezone': 'Europe/Berlin', 'customer_notify_before_pickup_min': 10})
        rb = self.revision(B, {'notification_quiet_hours': {'from': '23:30', 'to': '05:00'}, 'reporting_timezone': 'Europe/Vienna', 'customer_notify_before_pickup_min': 20})
        with tx(A) as c:
            v = c.execute("SELECT id, values_json FROM taktovia.config_revision WHERE scope_kind='TENANT' AND values_json ? 'notification_quiet_hours'").fetchall()
            self.assertEqual([(x[0], x[1]['reporting_timezone']) for x in v], [(ra, 'Europe/Berlin')])
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_revision WHERE id=%s", (rb,)).fetchone()[0], 0)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT values_json->'notification_quiet_hours'->>'from' FROM taktovia.config_revision WHERE id=%s", (rb,)).fetchone()[0], '23:30')
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_revision WHERE id=%s", (ra,)).fetchone()[0], 0)
        # Rollenzuweisungen je Mandant
        for t, role in ((A, 'planner'), (B, 'dispatcher')):
            with tx(t) as c:
                c.execute("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,'https://idp.test','sub-' || %s::text,%s)", (t, uid(), t, role))
        with tx(A) as c:
            self.assertEqual({r[0] for r in c.execute("SELECT role_code FROM taktovia.membership").fetchall()}, {'planner'})
        with tx(B) as c:
            self.assertEqual({r[0] for r in c.execute("SELECT role_code FROM taktovia.membership").fetchall()}, {'dispatcher'})

    def test_aenderung_in_a_erzeugt_keine_zeile_in_b(self):
        with tx(B) as c: before = c.execute("SELECT count(*) FROM taktovia.config_revision").fetchone()[0]
        self.revision(A, {'reporting_timezone': 'Europe/Berlin'}, revision=50)
        with tx(B) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_revision").fetchone()[0], before)


if __name__ == '__main__':
    unittest.main()
