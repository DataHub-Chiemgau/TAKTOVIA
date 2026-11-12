"""WP-M4-61/-62 / LH-I.3-ETA-001, LH-I.3-ETA-002: `eta_model_revision` (Revision des fahrerneutralen Referenzmodells) in der Datenbank.
Geprüft werden Unveränderlichkeit nach Freigabe, Mandantentrennung unter der echten Runtime-Rolle, fehlende Schreibrechte der Runtime-Rolle, die feste
Personalisierung `false`, die Puffertabelle und die Systemgrenze für `stop_dwell_sec`."""
import contextlib
import json
import unittest

import psycopg
from helpers import admin, migrator, runtime, seed_tenant, tx, uid

A, B = uid(), uid()


@contextlib.contextmanager
def mt(tenant):
    """Migrator-Verbindung (autocommit) mit einer Transaktion im Mandanten: Anlage und Freigabe der Revisionen gehören in P1 dem Betrieb."""
    with migrator() as m:
        with m.transaction():
            m.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))
            yield m


def draft(c, tenant, version=1, params=None, state='DRAFT'):
    rid = uid()
    approved = ", clock_timestamp(), 'user:freigeber'" if state == 'APPROVED' else ", NULL, NULL"
    c.execute(f"""INSERT INTO taktovia.eta_model_revision(tenant_id,id,version,parameters,state,created_by,approved_at,approved_by)
                  VALUES (%s,%s,%s,%s::jsonb,%s,'user:betrieb'{approved})""", (tenant, rid, version, json.dumps(params or {}), state))
    return rid


class EtaModelRevision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'eta-a'); seed_tenant(B, 'eta-b')

    def test_freigegebene_revision_unveraenderlich_und_mandantengetrennt(self):
        with mt(A) as m:
            rid = draft(m, A, 1, {'default_stop_dwell_sec': 30})
            m.execute("UPDATE taktovia.eta_model_revision SET parameters = '{\"default_stop_dwell_sec\": 45}'::jsonb WHERE tenant_id=%s AND id=%s", (A, rid))  # Entwurf änderbar
            m.execute("UPDATE taktovia.eta_model_revision SET state='APPROVED', approved_at=now(), approved_by='user:freigeber' WHERE tenant_id=%s AND id=%s", (A, rid))
        # Nach Freigabe: kein UPDATE, kein DELETE, auch nicht für den Migrator
        for sql in ("UPDATE taktovia.eta_model_revision SET parameters = '{}'::jsonb WHERE tenant_id=%s AND id=%s",
                    "UPDATE taktovia.eta_model_revision SET road_data_version = 'x' WHERE tenant_id=%s AND id=%s",
                    "UPDATE taktovia.eta_model_revision SET buffer_pct_by_quantile = '{\"p50\":0,\"p75\":0,\"p85\":0,\"p90\":0}'::jsonb WHERE tenant_id=%s AND id=%s",
                    "UPDATE taktovia.eta_model_revision SET min_buffer_sec = 0 WHERE tenant_id=%s AND id=%s",
                    "UPDATE taktovia.eta_model_revision SET state = 'DRAFT', approved_at = NULL, approved_by = NULL WHERE tenant_id=%s AND id=%s",
                    "DELETE FROM taktovia.eta_model_revision WHERE tenant_id=%s AND id=%s"):
            with self.subTest(sql=sql), self.assertRaises(psycopg.errors.CheckViolation), mt(A) as m:
                m.execute(sql, (A, rid))
        # Mandantentrennung unter der Runtime-Rolle: B sieht A nicht, A sieht die Revision
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.eta_model_revision WHERE id=%s", (rid,)).fetchone()[0], 1)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.eta_model_revision WHERE id=%s", (rid,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.eta_model_revision").fetchone()[0], 0)
        with mt(B) as m:   # eine Zeile für B (anderer Mandant) beeinflusst A nicht; gleiche Versionsnummer je Mandant erlaubt
            rid_b = draft(m, B, 1)
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.eta_model_revision WHERE id=%s", (rid_b,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.eta_model_revision WHERE tenant_id=%s", (B,)).fetchone()[0], 0)
        # Fremden Mandanten beschreiben: RLS (WITH CHECK) weist ab
        with self.assertRaises(psycopg.errors.InsufficientPrivilege), mt(A) as m:
            draft(m, B, 2)

    def test_runtime_rolle_hat_nur_leserecht(self):
        with tx(A) as c:
            for sql in ("INSERT INTO taktovia.eta_model_revision(tenant_id,id,version,created_by) VALUES (taktovia.current_tenant(), gen_random_uuid(), 99, 'user:x')",
                        "UPDATE taktovia.eta_model_revision SET min_buffer_sec = 1",
                        "DELETE FROM taktovia.eta_model_revision"):
                with self.subTest(sql=sql):
                    with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                        c.execute(sql)
                    c.rollback()
                    c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(A),))

    def test_entwurf_loeschbar_version_eindeutig_je_mandant(self):
        with mt(A) as m:
            draft(m, A, 50)
        with self.assertRaises(psycopg.errors.UniqueViolation), mt(A) as m:
            draft(m, A, 50)
        with mt(A) as m:
            rid = draft(m, A, 51)
            m.execute("DELETE FROM taktovia.eta_model_revision WHERE tenant_id=%s AND id=%s", (A, rid))
            self.assertEqual(m.execute("SELECT count(*) FROM taktovia.eta_model_revision WHERE id=%s", (rid,)).fetchone()[0], 0)

    def test_personalisierung_in_der_revision_nicht_aktivierbar(self):
        with mt(A) as m:
            draft(m, A, 60, {'eta_personalization_enabled': False})   # explizites false ist zulässig
        with self.assertRaises(psycopg.errors.CheckViolation), mt(A) as m:
            draft(m, A, 61, {'eta_personalization_enabled': True})

    def test_puffertabelle_und_freigabepaar_werden_geprueft(self):
        bad_tables = ['{"p50":5,"p75":10,"p85":15}', '{"p50":20,"p75":10,"p85":15,"p90":20}', '{"p50":-1,"p75":10,"p85":15,"p90":20}',
                      '{"p50":5,"p75":10,"p85":15,"p90":101}', '{"p50":"5","p75":10,"p85":15,"p90":20}']
        for i, table in enumerate(bad_tables):
            with self.subTest(table=table), self.assertRaises(psycopg.errors.CheckViolation), mt(A) as m:
                m.execute("INSERT INTO taktovia.eta_model_revision(tenant_id,id,version,created_by,buffer_pct_by_quantile) VALUES (%s,%s,%s,'user:x',%s::jsonb)", (A, uid(), 200 + i, table))
        with self.assertRaises(psycopg.errors.CheckViolation), mt(A) as m:   # APPROVED ohne Freigabedaten ist unzulässig
            m.execute("INSERT INTO taktovia.eta_model_revision(tenant_id,id,version,created_by,state) VALUES (%s,%s,300,'user:x','APPROVED')", (A, uid()))

    def test_stop_dwell_sec_unter_systemgrenze_wird_in_der_datenbank_abgewiesen(self):
        with admin() as c:
            floor = dict((r[0], r[1]) for r in c.execute("SELECT parameter, minimum FROM taktovia.policy_system_floor()"))
            self.assertEqual(floor['stop_dwell_sec'], 10)
            below = c.execute("SELECT pointer, code FROM taktovia.config_value_violations('TENANT', '1', '{\"stop_dwell_sec\": 9}'::jsonb)").fetchall()
            self.assertEqual(below, [('/stop_dwell_sec', 'BELOW_MINIMUM')])
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_value_violations('TENANT', '1', '{\"stop_dwell_sec\": 10}'::jsonb)").fetchone()[0], 0)

    def test_personalisierungsparameter_ist_fest_false(self):
        with admin() as c:
            self.assertEqual(c.execute("SELECT pointer, code FROM taktovia.config_value_violations('TENANT', '1', '{\"eta_personalization_enabled\": true}'::jsonb)").fetchall(),
                             [('/eta_personalization_enabled', 'SAFETY_INVARIANT_VIOLATED')])
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_value_violations('TENANT', '1', '{\"eta_personalization_enabled\": false}'::jsonb)").fetchone()[0], 0)
            row = c.execute("SELECT security_class, const_value FROM taktovia.policy_schema WHERE schema_version='1' AND parameter='eta_personalization_enabled'").fetchone()
            self.assertEqual((row[0], row[1]), ('SAFETY_INVARIANT', False))


if __name__ == '__main__':
    unittest.main()
