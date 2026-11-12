"""WP-M4-09 / LH-6.24-TIM-004: Trennung von Schicht-Soll (staff_shift) und Arbeitszeit-Ist (time_event) unter der echten Runtime-Rolle.
Die Datenbank trägt die Trennung: keine Schreibrechte außer INSERT auf time_event, keine Fremdschlüssel- oder Triggerwirkung von staff_shift auf time_event,
eine Plan- oder Dispositionsänderung lässt vorhandene Ist-Zeilen unverändert."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A = uid()
T0 = '2026-12-01T06:00:00Z'


def shift(c, tenant, driver):
    veh, s = uid(), uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (tenant, veh))
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
              "VALUES (%s,%s,%s,%s,%s,%s,'Europe/Berlin',0,'PLANNED')", (tenant, s, driver, veh, T0, '2026-12-01T14:00:00Z'))
    return s, veh


def event(c, tenant, shift_id, kind='WORK_START', at=T0):
    e = uid()
    c.execute("INSERT INTO taktovia.time_event(tenant_id,id,person_ref,kind,occurred_at,device_recorded_at,source,recorded_by,shift_id,payload_sha256) "
              "VALUES (%s,%s,'emp-1',%s,%s,%s,'APP','emp-1',%s,%s)", (tenant, e, kind, at, at, shift_id, 'a' * 64))
    return e


class SollIstTrennung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm409-a')

    def test_runtime_rolle_ohne_update_delete_auf_time_event(self):
        with tx(A) as c:
            s, _ = shift(c, A, uid()); e = event(c, A, s)
        for sql in ("UPDATE taktovia.time_event SET kind='WORK_END' WHERE id=%s", "UPDATE taktovia.time_event SET shift_id=NULL WHERE id=%s", "DELETE FROM taktovia.time_event WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql):   # Recht fehlt (nicht erst der Trigger)
                    c.execute(sql, (e,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute("TRUNCATE taktovia.time_event")
        with admin() as a:   # Rechte laut Katalog: genau SELECT und INSERT
            privs = {r[0] for r in a.execute("SELECT privilege_type FROM information_schema.role_table_grants WHERE grantee='taktovia_runtime' AND table_schema='taktovia' AND table_name='time_event'")}
        self.assertEqual(privs, {'SELECT', 'INSERT'})

    def test_keine_datenbankwirkung_von_staff_shift_auf_time_event(self):
        with admin() as a:
            # kein Trigger auf staff_shift, der time_event erwähnt; der Fremdschlüssel time_event -> staff_shift löscht oder ändert nichts kaskadierend (NO ACTION)
            trig = a.execute("SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_proc p ON p.oid=t.tgfoid "
                             "WHERE c.relname='staff_shift' AND NOT t.tgisinternal AND p.prosrc ILIKE '%time_event%'").fetchall()
            self.assertEqual(trig, [])
            fk = a.execute("SELECT confdeltype, confupdtype FROM pg_constraint WHERE conrelid='taktovia.time_event'::regclass AND contype='f' AND confrelid='taktovia.staff_shift'::regclass").fetchall()
            self.assertEqual(fk, [('a', 'a')])
            # umgekehrt: staff_shift hat keinen Fremdschlüssel auf time_event
            self.assertEqual(a.execute("SELECT count(*) FROM pg_constraint WHERE conrelid='taktovia.staff_shift'::regclass AND confrelid='taktovia.time_event'::regclass").fetchone()[0], 0)

    def test_plan_und_dispositionsaenderung_lassen_ist_zeilen_unveraendert(self):
        with tx(A) as c:
            s, veh = shift(c, A, uid()); event(c, A, s); event(c, A, s, kind='WORK_END', at='2026-12-01T14:00:00Z')
            c.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'PLANNED')", (A, uid(), veh, s))
        snap = "SELECT string_agg(t::text, ';' ORDER BY id) FROM taktovia.time_event t WHERE shift_id=%s"
        with tx(A) as c: before = c.execute(snap, (s,)).fetchone()[0]
        with tx(A) as c:   # Neuveröffentlichung der Schicht (Revision, Zeiten) und Planrevision der Disposition
            c.execute("UPDATE taktovia.staff_shift SET roster_revision=roster_revision+1, starts_at=starts_at+interval '1 hour', ends_at=ends_at+interval '1 hour', shift_revision=shift_revision+1 WHERE id=%s", (s,))
            c.execute("UPDATE taktovia.vehicle_run SET revision=revision+1 WHERE shift_id=%s", (s,))
        with tx(A) as c: self.assertEqual(c.execute(snap, (s,)).fetchone()[0], before)
        with tx(A) as c:   # eine Schicht mit Ist-Zeilen lässt sich nicht löschen (kein stilles Mitlöschen des Ist)
            with self.assertRaises((psycopg.errors.ForeignKeyViolation, psycopg.errors.InsufficientPrivilege)):
                c.execute("DELETE FROM taktovia.staff_shift WHERE id=%s", (s,))
