"""WP-M3-54 (LH-6.22-DMG-010): Störungsablauf in der Datenbank unter der echten Runtime-Rolle. Zustandsautomat der betroffenen Buchung per Trigger (auch für die Administration), Entscheidung
vollständig und unveränderlich, Revision +1 je Zustandsänderung, kein DELETE, höchstens ein offener Störungsfall je Buchung, Vorschläge nur anhängbar, Mandantentrennung (RLS)."""
import unittest

import psycopg
from geo_helpers import make_service
from helpers import admin, seed_tenant, tx, uid
from test_booking_status_history import numbered_booking
from test_route_stop import make_run

A, B = uid(), uid()


def incident(c, tenant, vehicle):
    iid = uid()
    c.execute("INSERT INTO taktovia.incident(tenant_id,id,kind,vehicle_id,dedupe_key,created_by) VALUES (%s,%s,'VEHICLE_OUTAGE',%s,%s,'system:workflow')", (tenant, iid, vehicle, f'VEHICLE_OUTAGE:{iid}'))
    return iid


def case(c, tenant, number):
    """Incident, Umlauf, Buchung und die Zeile des Störungsfalls (Zustand OPEN)."""
    run, veh, _ = make_run(c, tenant); svc = make_service(c, tenant)
    bid, _ = numbered_booking(c, tenant, run, number, svc)
    inc = incident(c, tenant, veh)
    oid = uid()
    c.execute("INSERT INTO taktovia.outage_booking(tenant_id,id,incident_id,booking_id,run_id,vehicle_id) VALUES (%s,%s,%s,%s,%s,%s)", (tenant, oid, inc, bid, run, veh))
    return oid, inc, bid, run, veh


def move(c, oid, state, revision, extra=''):
    c.execute(f"UPDATE taktovia.outage_booking SET state=%s, revision=%s{extra} WHERE id=%s", (state, revision, oid))


DECIDE = ", decision='CANCEL', decision_reason='VEHICLE_OUTAGE', decided_by='user:disp', decided_at=now()"


class Stoerungsablauf(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm354-a'); seed_tenant(B, 'm354-b')

    def test_zustandsautomat_erlaubt_nur_definierte_uebergaenge(self):
        with tx(A) as c:
            oid, *_ = case(c, A, 'BK-2026-000701')
        with tx(A) as c:
            move(c, oid, 'PROPOSED', 2)
        with tx(A) as c:
            move(c, oid, 'OPEN_ISSUE', 3, ", issue_code='NO_REPLACEMENT'")
        with tx(A) as c:
            move(c, oid, 'PROPOSED', 4, ", issue_code=NULL")
        # Umbuchung: Entscheidung vollständig und Ersatzumlauf gesetzt
        with tx(A) as c:
            run2, _, _ = make_run(c, A)
            move(c, oid, 'REBOOKED', 5, f", decision='REBOOK', decision_reason='VEHICLE_OUTAGE', decided_by='user:disp', decided_at=now(), replacement_run_id='{run2}'")
        # Endzustand: kein Übergang mehr (Runtime-Rolle und Administration)
        for ctx in (tx(A), admin()):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with ctx as c: move(c, oid, 'OPEN', 6)
        for to in ('CANCELLED', 'PROPOSED', 'BOARDED'):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=to), tx(A) as c:
                move(c, oid, to, 6)

    def test_revision_steigt_je_zustandsaenderung_um_eins(self):
        with tx(A) as c:
            oid, *_ = case(c, A, 'BK-2026-000702')
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:
            move(c, oid, 'PROPOSED', 5)                       # Sprung
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:
            move(c, oid, 'PROPOSED', 1)                       # Zustandswechsel ohne Revision
        with tx(A) as c:
            c.execute("UPDATE taktovia.outage_booking SET notice_revision = 1 WHERE id=%s", (oid,))   # ohne Zustandswechsel bleibt die Revision

    def test_entscheidung_vollstaendig_und_unveraenderlich(self):
        with tx(A) as c:
            oid, *_ = case(c, A, 'BK-2026-000703')
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:     # Entscheidungszustand ohne Entscheidung
            move(c, oid, 'CANCELLED', 2)
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:     # Entscheidung ohne Akteur
            move(c, oid, 'CANCELLED', 2, ", decision='CANCEL', decision_reason='VEHICLE_OUTAGE'")
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:     # Umbuchung ohne Ersatzumlauf
            move(c, oid, 'REBOOKED', 2, ", decision='REBOOK', decision_reason='VEHICLE_OUTAGE', decided_by='user:disp', decided_at=now()")
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:     # Freitext statt Code
            move(c, oid, 'CANCELLED', 2, ", decision='CANCEL', decision_reason='weil kaputt', decided_by='user:disp', decided_at=now()")
        with tx(A) as c:
            move(c, oid, 'CANCELLED', 2, DECIDE)
        for ctx in (tx(A), admin()):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with ctx as c: c.execute("UPDATE taktovia.outage_booking SET decided_by='user:andere' WHERE id=%s", (oid,))

    def test_kein_delete_und_identitaet_unveraenderlich(self):
        with tx(A) as c:
            oid, inc, bid, run, veh = case(c, A, 'BK-2026-000704')
        with self.assertRaises(psycopg.errors.InsufficientPrivilege), tx(A) as c:    # Runtime-Rolle: kein DELETE-Recht
            c.execute("DELETE FROM taktovia.outage_booking WHERE id=%s", (oid,))
        with self.assertRaises(psycopg.errors.CheckViolation), admin() as c:          # Administration: Trigger
            c.execute("DELETE FROM taktovia.outage_booking WHERE id=%s", (oid,))
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:
            c.execute("UPDATE taktovia.outage_booking SET booking_id=%s WHERE id=%s", (uid(), oid))
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:
            c.execute("UPDATE taktovia.outage_booking SET incident_id=%s WHERE id=%s", (incident(c, A, veh), oid))

    def test_hoechstens_ein_offener_stoerungsfall_je_buchung(self):
        with tx(A) as c:
            oid, inc, bid, run, veh = case(c, A, 'BK-2026-000705')
        with self.assertRaises(psycopg.errors.UniqueViolation), tx(A) as c:
            c.execute("INSERT INTO taktovia.outage_booking(tenant_id,id,incident_id,booking_id,run_id,vehicle_id) VALUES (%s,%s,%s,%s,%s,%s)", (A, uid(), incident(c, A, veh), bid, run, veh))
        # nach der Entscheidung ist die Buchung frei für einen späteren Störungsfall
        with tx(A) as c:
            move(c, oid, 'CANCELLED', 2, DECIDE)
            c.execute("INSERT INTO taktovia.outage_booking(tenant_id,id,incident_id,booking_id,run_id,vehicle_id) VALUES (%s,%s,%s,%s,%s,%s)", (A, uid(), incident(c, A, veh), bid, run, veh))

    def test_vorschlaege_nur_anhaengen_und_mandantentrennung(self):
        with tx(A) as c:
            oid, inc, bid, run, veh = case(c, A, 'BK-2026-000706')
            target, tveh, _ = make_run(c, A)
            pid = uid()
            c.execute("INSERT INTO taktovia.outage_proposal(tenant_id,id,outage_booking_id,generation,vehicle_id,run_id,run_revision,pickup_at,dropoff_at,window_effect) "
                      "VALUES (%s,%s,%s,1,%s,%s,1,'2026-12-01 09:00+00','2026-12-01 09:20+00','IN_WINDOW')", (A, pid, oid, tveh, target))
        for sql in ("UPDATE taktovia.outage_proposal SET generation=2 WHERE id=%s", "DELETE FROM taktovia.outage_proposal WHERE id=%s"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql), tx(A) as c:
                c.execute(sql, (pid,))
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql), admin() as c:
                c.execute(sql, (pid,))
        # Fenster-Wirkung und Verletzungsliste gehören zusammen
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:
            c.execute("INSERT INTO taktovia.outage_proposal(tenant_id,id,outage_booking_id,generation,vehicle_id,run_id,run_revision,pickup_at,dropoff_at,window_effect) "
                      "VALUES (%s,%s,%s,2,%s,%s,1,'2026-12-01 09:00+00','2026-12-01 09:20+00','OUTSIDE_WINDOW')", (A, uid(), oid, tveh, target))
        # RLS: Mandant B sieht weder Zeile noch Vorschlag und kann keine anlegen
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.outage_booking").fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.outage_proposal").fetchone()[0], 0)
        with self.assertRaises(psycopg.Error), tx(B) as c:
            c.execute("INSERT INTO taktovia.outage_booking(tenant_id,id,incident_id,booking_id,run_id,vehicle_id) VALUES (%s,%s,%s,%s,%s,%s)", (A, uid(), inc, bid, run, veh))


if __name__ == '__main__':
    unittest.main()
