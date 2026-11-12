"""WP-M0-20: Trigger und Constraints unter der Runtime-Rolle, einschließlich konkurrierender Transaktionen."""
import threading, unittest
import psycopg
from helpers import runtime, set_tenant, tx, uid, seed_tenant
from geo_helpers import make_service
from tarif_helpers import add_revision

T = uid()
LE = uid()


def event(c, eid, net=10000, tax=1900, recoverable=1900, ref=None):
    c.execute("""INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,
                 category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor,tax_rule_revision,occurred_at,booked_at)
                 VALUES (%s,%s,%s,'test',%s,'COST','ACTUAL','FUEL','EUR',%s,%s,%s,%s,'t1',now(),now())""",
              (T, eid, LE, ref or str(eid), net, tax, net + tax, recoverable))


def period(c, pid, closed=False, start='2026-10-01', end='2026-11-01'):
    c.execute("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on,closed_at) VALUES (%s,%s,%s,%s,%s,%s)",
              (T, pid, LE, start, end, 'now' if closed else None))


def journal(c, jid, eid, pid, date='2026-10-15'):
    c.execute("""INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state)
                 VALUES (%s,%s,%s,%s,'EUR',%s,%s,'DRAFT')""", (T, jid, eid, LE, pid, date))


def line(c, jid, side, amount):
    c.execute("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor) VALUES (%s,%s,%s,'4000',%s,%s)",
              (T, uid(), jid, side, amount))


def post(c, jid, by='u1'):
    c.execute("UPDATE taktovia.journal SET state='POSTED', posted_by=%s WHERE tenant_id=%s AND id=%s", (by, T, jid))


class Trigger(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(T, 'finanz')
        with tx(T) as c:
            c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'LE','DE')", (T, LE))
            cls.P = uid(); period(c, cls.P)
            cls.PC = uid(); period(c, cls.PC, closed=True, start='2026-09-01', end='2026-10-01')

    def draft(self, c, balanced=True):
        e, j = uid(), uid()
        event(c, e); journal(c, j, e, self.P)
        line(c, j, 'D', 11900); line(c, j, 'C', 11900 if balanced else 11800)
        return e, j

    # --- Unveränderlichkeit (Gegenereignis statt Änderung) ---
    def test_economic_event_unveraenderlich_auch_fuer_besitzer_trigger(self):
        with tx(T) as c:
            e = uid(); event(c, e)
        # Runtime hat kein UPDATE/DELETE-Recht; der Trigger greift zusätzlich für jede andere Rolle (Migrator/Admin)
        from helpers import admin
        with admin() as a:
            a.execute("SELECT set_config('app.tenant_id', %s, false)", (str(T),))
            for stmt, params in (('UPDATE taktovia.economic_event SET category=%s WHERE id=%s', ('X', e)),
                                 ('DELETE FROM taktovia.economic_event WHERE id=%s', (e,))):
                with self.assertRaises(psycopg.errors.CheckViolation):
                    a.execute(stmt, params)

    def test_summenconstraints_economic_event(self):
        for kw in ({'net': 100, 'tax': 19, 'recoverable': 20}, {'net': 100, 'tax': 19, 'recoverable': -1}):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=str(kw)):
                with tx(T) as c: event(c, uid(), net=kw['net'], tax=kw['tax'], recoverable=kw['recoverable'])
        with self.assertRaises(psycopg.errors.CheckViolation):  # net+tax != gross
            with tx(T) as c:
                c.execute("""INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,
                  currency,net_minor,tax_minor,gross_minor,tax_rule_revision,occurred_at,booked_at)
                  VALUES (%s,%s,%s,'t','r','COST','ACTUAL','F','EUR',100,19,120,'t1',now(),now())""", (T, uid(), LE))

    def test_kostenbasis_ist_berechnet(self):
        with tx(T) as c:
            e = uid(); event(c, e)
            self.assertEqual(c.execute('SELECT cost_basis_minor FROM taktovia.economic_event WHERE id=%s', (e,)).fetchone()[0], 10000)

    def test_einmalige_kostenwirkung_unique_key(self):
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(T) as c:
                event(c, uid(), ref='dup'); event(c, uid(), ref='dup')

    # --- Journal ---
    def test_journal_ausgeglichen_wird_gebucht(self):
        with tx(T) as c:
            e, j = self.draft(c); post(c, j)
            self.assertEqual(c.execute('SELECT state FROM taktovia.journal WHERE id=%s', (j,)).fetchone()[0], 'POSTED')

    def test_unausgeglichenes_journal_wird_abgewiesen(self):
        with self.assertRaises(psycopg.errors.RaiseException):
            with tx(T) as c:
                e, j = self.draft(c, balanced=False); post(c, j)

    def test_weniger_als_zwei_zeilen_und_fehlende_identitaet(self):
        with self.assertRaises(psycopg.errors.RaiseException):
            with tx(T) as c:
                e, j = uid(), uid(); event(c, e); journal(c, j, e, self.P); line(c, j, 'D', 5); post(c, j)
        with self.assertRaises(psycopg.errors.RaiseException):
            with tx(T) as c:
                e, j = self.draft(c); c.execute("UPDATE taktovia.journal SET state='POSTED' WHERE id=%s", (j,))

    def test_direkt_als_gebucht_anlegen_verboten(self):
        with self.assertRaises(psycopg.errors.RaiseException):
            with tx(T) as c:
                e, j = uid(), uid(); event(c, e)
                c.execute("""INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state,posted_by)
                             VALUES (%s,%s,%s,%s,'EUR',%s,'2026-10-15','POSTED','u')""", (T, j, e, LE, self.P))

    def test_gebuchtes_journal_und_zeilen_unveraenderlich(self):
        with tx(T) as c:
            e, j = self.draft(c); post(c, j)
        for stmt in ("UPDATE taktovia.journal SET accounting_date='2026-10-16' WHERE id=%s", 'DELETE FROM taktovia.journal WHERE id=%s',
                     'DELETE FROM taktovia.journal_line WHERE journal_id=%s', 'UPDATE taktovia.journal_line SET amount_minor=1 WHERE journal_id=%s'):
            with self.assertRaises(psycopg.errors.RaiseException, msg=stmt):
                with tx(T) as c: c.execute(stmt, (j,))
        with self.assertRaises(psycopg.errors.RaiseException):  # Zeile nachträglich hinzufügen
            with tx(T) as c: line(c, j, 'D', 1)

    def test_geschlossene_periode_und_datum_ausserhalb(self):
        with self.assertRaises(psycopg.errors.RaiseException):
            with tx(T) as c:
                e, j = uid(), uid(); event(c, e); journal(c, j, e, self.PC, date='2026-09-15')
                line(c, j, 'D', 5); line(c, j, 'C', 5); post(c, j)
        with self.assertRaises(psycopg.errors.RaiseException):  # Enddatum ist exklusiv (rechts offen)
            with tx(T) as c:
                e, j = uid(), uid(); event(c, e); journal(c, j, e, self.P, date='2026-11-01')
                line(c, j, 'D', 5); line(c, j, 'C', 5); post(c, j)

    def test_zeile_kann_nicht_in_anderes_journal_umgehaengt_werden(self):
        with self.assertRaises(psycopg.errors.RaiseException):
            with tx(T) as c:
                e1, j1 = self.draft(c); e2, j2 = self.draft(c)
                c.execute('UPDATE taktovia.journal_line SET journal_id=%s WHERE journal_id=%s', (j2, j1))

    def test_ein_journal_je_ereignis(self):
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(T) as c:
                e = uid(); event(c, e); journal(c, uid(), e, self.P); journal(c, uid(), e, self.P)

    def test_zeilenbetrag_muss_positiv_sein(self):
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(T) as c:
                e, j = uid(), uid(); event(c, e); journal(c, j, e, self.P); line(c, j, 'D', 0)

    # --- Konkurrenz: gleichzeitiges Buchen und Zeileneinfügen ---
    def test_konkurrierend_buchen_und_zeile_einfuegen(self):
        with tx(T) as c:
            e, j = self.draft(c)
        res = {}
        c1, c2 = runtime(), runtime()
        try:
            set_tenant(c1, T); set_tenant(c2, T)
            post(c1, j)  # c1 hält die Sperre auf Kopf und Periode bis COMMIT
            def other():
                try:
                    line(c2, j, 'D', 1)  # wartet auf die Kopfsperre, danach: posted journal immutable
                    c2.commit(); res['c2'] = 'ok'
                except psycopg.errors.RaiseException:
                    c2.rollback(); res['c2'] = 'abgewiesen'
            th = threading.Thread(target=other); th.start()
            th.join(timeout=1.0); self.assertTrue(th.is_alive(), 'Zeileneinfügen muss auf die Kopfsperre warten')
            c1.commit(); th.join(timeout=10)
            self.assertEqual(res.get('c2'), 'abgewiesen')
        finally:
            c1.close(); c2.close()
        with tx(T) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.journal_line WHERE journal_id=%s', (j,)).fetchone()[0], 2)

    def test_konkurrierend_zwei_journale_gleiche_periode_schliessen(self):
        """Zwei parallele Buchungen in derselben Periode serialisieren über FOR UPDATE auf fiscal_period."""
        with tx(T) as c:
            _, j1 = self.draft(c); _, j2 = self.draft(c)
        errs = []
        def go(j):
            try:
                with tx(T) as c: post(c, j)
            except Exception as e: errs.append(e)
        ts = [threading.Thread(target=go, args=(j,)) for j in (j1, j2)]
        [t.start() for t in ts]; [t.join(timeout=20) for t in ts]
        self.assertEqual(errs, [])

    # --- Weitere Tabellen-Constraints aus dem Kernschema ---
    def test_zahlungsintervall_und_betragsgrenzen(self):
        # Rückerstattung > Einzug, Einzug > Autorisierung sind per CHECK ausgeschlossen
        for cap, ref in ((11, 0), (5, 6)):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(T) as c:
                    qa, jid = uid(), uid()
                    c.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel) VALUES (%s,%s,now(),'WEB')", (T, qa))
                    cfg = uid()
                    c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason)
                                 VALUES (%s,%s,'TENANT',%s,1,'1','{}',now(),'user:u','Testdaten')""", (T, cfg, T))
                    q = uid(); trev = add_revision(c, T, make_service(c, T), state='VEROEFFENTLICHT')   # Preisbindung: Tarifrevision (WP-M2-07)
                    c.execute("""INSERT INTO taktovia.fare_quote(tenant_id,id,attempt_id,currency,pay_at_platform_minor,external_known_minor,external_unknown_leg_ids,
                                 expires_at,tariff_revision_id,config_revision_id,snapshot,content_hash)
                                 VALUES (%s,%s,%s,'EUR',1000,0,'{}',now(),%s,%s,'{}',%s)""", (T, q, qa, trev, cfg, 'a' * 64))
                    pid = uid()
                    c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,now())", (T, pid, str(uid())))
                    c.execute("INSERT INTO taktovia.journey(tenant_id,id,passenger_id,quote_id,state,created_at,currency) VALUES (%s,%s,%s,%s,'HELD',now(),'EUR')", (T, jid, pid, q))
                    c.execute("""INSERT INTO taktovia.payment_intent(tenant_id,id,journey_id,provider,provider_ref,state,currency,authorized_minor,captured_minor,refunded_minor)
                                 VALUES (%s,%s,%s,'psp',%s,'AUTHORIZED','EUR',10,%s,%s)""", (T, uid(), jid, str(uid()), cap, ref))

    def test_fare_quote_unveraenderlich(self):
        from helpers import admin
        with tx(T) as c:
            qa = uid(); cfg = uid(); q = uid()
            trev = add_revision(c, T, make_service(c, T), state='VEROEFFENTLICHT')   # Preisbindung: Tarifrevision (WP-M2-07)
            c.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel) VALUES (%s,%s,now(),'WEB')", (T, qa))
            c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason)
                         VALUES (%s,%s,'TENANT',%s,2,'1','{}',now(),'user:u','Testdaten')""", (T, cfg, T))
            c.execute("""INSERT INTO taktovia.fare_quote(tenant_id,id,attempt_id,currency,pay_at_platform_minor,external_known_minor,external_unknown_leg_ids,
                         expires_at,tariff_revision_id,config_revision_id,snapshot,content_hash)
                         VALUES (%s,%s,%s,'EUR',1000,0,'{}',now(),%s,%s,'{}',%s)""", (T, q, qa, trev, cfg, 'b' * 64))
        with admin() as a:
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute('UPDATE taktovia.fare_quote SET pay_at_platform_minor=1 WHERE id=%s', (q,))

    def test_idempotenzschluessel_eindeutig_je_mandant_akteur_scope(self):
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(T) as c:
                for _ in range(2):
                    c.execute("""INSERT INTO taktovia.idempotency_record(tenant_id,principal_ref,command_scope,key,request_sha256,expires_at)
                                 VALUES (%s,'p','confirm','k1',%s,now())""", (T, 'c' * 64))
        with tx(T) as c:  # anderer Akteur oder anderer Operationstyp kollidiert nicht
            for p, s in (('p1', 'confirm'), ('p2', 'confirm'), ('p1', 'cancel')):
                c.execute("""INSERT INTO taktovia.idempotency_record(tenant_id,principal_ref,command_scope,key,request_sha256,expires_at)
                             VALUES (%s,%s,%s,'k2',%s,now())""", (T, p, s, 'c' * 64))

    def test_idempotenz_zustand_lease_und_ergebnis_konsistent(self):  # WP-M1-13
        with tx(T) as c:
            c.execute("""INSERT INTO taktovia.idempotency_record(tenant_id,principal_ref,command_scope,key,request_sha256,expires_at)
                         VALUES (%s,'p','confirm','k-state-default',%s,now())""", (T, 'd' * 64))
            self.assertEqual(c.execute("SELECT state FROM taktovia.idempotency_record WHERE key='k-state-default'").fetchone()[0], 'IN_PROGRESS')
        with self.assertRaises(psycopg.errors.CheckViolation):   # COMPLETED ohne gespeicherten Status
            with tx(T) as c:
                c.execute("""UPDATE taktovia.idempotency_record SET state='COMPLETED' WHERE key='k-state-default'""")
        with self.assertRaises(psycopg.errors.CheckViolation):   # nur angenommene (2xx) Ergebnisse werden gespeichert
            with tx(T) as c:
                c.execute("""UPDATE taktovia.idempotency_record SET state='COMPLETED', http_status=500, completed_at=now() WHERE key='k-state-default'""")
        with self.assertRaises(psycopg.errors.CheckViolation):   # Fingerprint ist ein SHA-256-Hex
            with tx(T) as c:
                c.execute("""INSERT INTO taktovia.idempotency_record(tenant_id,principal_ref,command_scope,key,request_sha256,expires_at)
                             VALUES (%s,'p','confirm','k-bad-sha',%s,now())""", (T, 'XYZ'))
        with tx(T) as c:
            c.execute("""UPDATE taktovia.idempotency_record SET state='COMPLETED', http_status=201, completed_at=now(), response_json='{"id":1}'::jsonb
                         WHERE key='k-state-default'""")

    def test_idempotenz_fremder_mandant_sieht_keinen_eintrag(self):  # WP-M1-13, RLS
        other = uid(); seed_tenant(other, 'idem-fremd')
        with tx(T) as c:
            c.execute("""INSERT INTO taktovia.idempotency_record(tenant_id,principal_ref,command_scope,key,request_sha256,expires_at)
                         VALUES (%s,'p','confirm','k-rls-sichtbar',%s,now())""", (T, 'e' * 64))
        with tx(other) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.idempotency_record WHERE key='k-rls-sichtbar'").fetchone()[0], 0)

    def test_geometrie_ungueltig_wird_abgewiesen(self):
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(T) as c:
                cfg, svc, org = uid(), uid(), uid()
                c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason)
                             VALUES (%s,%s,'SERVICE',%s,1,'1','{}',now(),'user:u','Testdaten')""", (T, cfg, svc))
                c.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,'o',%s,'{TRANSPORT_COMPANY}')", (T, org, LE, str(uid())))
                c.execute("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id) VALUES (%s,%s,%s,'s','Europe/Berlin',%s)", (T, svc, org, cfg))
                bowtie = 'SRID=4326;MULTIPOLYGON(((0 0,1 1,1 0,0 1,0 0)))'  # selbstschneidend
                c.execute("INSERT INTO taktovia.service_area(tenant_id,id,service_id,revision,area,valid_from) VALUES (%s,%s,%s,1,%s,now())", (T, uid(), svc, bowtie))


if __name__ == '__main__':
    unittest.main()
