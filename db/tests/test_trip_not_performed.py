"""WP-M4-55 (LH-I.6-KPI-009): nicht durchgeführte Fahrten in der Datenbank unter der echten Runtime-Rolle. Ursachenkatalog (global, nur lesbar), Ableitung aus dem Buchungsfakt (Fahrzeugausfall und No-Show
verschieden, Konflikt, Ursache offen sichtbar), Korrekturvermerke unveränderlich (auch für die Administration), Mandantentrennung."""
import unittest

import psycopg

from helpers import admin, seed_tenant, tx, uid

A, B = uid(), uid()
BASE = ("INSERT INTO taktovia.fact_booking(tenant_id,booking_id,service_id,confirmed_at,pickup_earliest,cancelled_at,cancel_reason,cause_class,no_show_at,state) "
        "VALUES (%s,%s,%s,'2026-12-01T05:00Z','2026-12-01T06:00Z',%s,%s,%s,%s,%s)")


def fact(c, tenant, state, reason=None, cause=None, cancelled=None, no_show=None):
    bid = uid()
    c.execute(BASE, (tenant, bid, uid(), cancelled, reason, cause, no_show, state))
    c.execute("SELECT taktovia.not_performed_refresh(%s)", (bid,))
    return bid


def row(c, bid):
    return c.execute("SELECT cause_state, cause, category, derived_cause, revision FROM taktovia.not_performed_trip WHERE booking_id=%s", (bid,)).fetchone()


class NotPerformed(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm455-a'); seed_tenant(B, 'm455-b')

    def test_ursachenkatalog_global_nur_lesbar_mit_kategorien(self):
        with tx(A) as c:
            cat = dict(c.execute("SELECT code, category FROM taktovia.not_performed_cause").fetchall())
        self.assertEqual(cat['VEHICLE_OUTAGE'], 'OPERATOR'); self.assertEqual(cat['PASSENGER_NO_SHOW'], 'PASSENGER'); self.assertEqual(cat['EXTERNAL_EVENT'], 'EXTERNAL')
        self.assertNotEqual(cat['VEHICLE_OUTAGE'], cat['PASSENGER_NO_SHOW'])
        for stmt in ("UPDATE taktovia.not_performed_cause SET category='EXTERNAL' WHERE code='VEHICLE_OUTAGE'", "DELETE FROM taktovia.not_performed_cause", "INSERT INTO taktovia.not_performed_cause(code,category,label,derived_from) VALUES ('NEU','OPERATOR','x','y')"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=stmt), tx(A) as c:
                c.execute(stmt)
        with self.assertRaises(psycopg.errors.CheckViolation), admin() as c:
            c.execute("UPDATE taktovia.not_performed_cause SET category='EXTERNAL' WHERE code='VEHICLE_OUTAGE'")

    def test_ableitung_fahrzeugausfall_no_show_konflikt_und_ursache_offen(self):
        with tx(A) as c:
            outage = fact(c, A, 'CANCELED', 'VEHICLE_OUTAGE', 'FAHRZEUGAUSFALL', '2026-12-01T05:30Z')
            noshow = fact(c, A, 'NO_SHOW', None, None, None, '2026-12-01T06:20Z')
            conflict = fact(c, A, 'NO_SHOW', 'VEHICLE_OUTAGE', 'FAHRZEUGAUSFALL', '2026-12-01T05:30Z', '2026-12-01T06:20Z')
            open_ = fact(c, A, 'CANCELED', 'HOLD_EXPIRED', 'FAHRGAST', '2026-12-01T05:30Z')
            done = fact(c, A, 'COMPLETED')
            self.assertEqual(row(c, outage)[:3], ('SET', 'VEHICLE_OUTAGE', 'OPERATOR'))
            self.assertEqual(row(c, noshow)[:3], ('SET', 'PASSENGER_NO_SHOW', 'PASSENGER'))
            self.assertEqual(row(c, conflict)[:3], ('CONFLICT', None, None))          # Konfliktmarkierung statt stiller Wahl
            self.assertEqual(row(c, open_)[:3], ('OPEN', None, None))                 # Ursache offen bleibt sichtbar
            self.assertIsNone(row(c, done))                                           # durchgeführt: keine nicht durchgeführte Fahrt
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.not_performed_trip").fetchone()[0], 4)
            # Wiederholung der Ableitung ändert die Revision nicht
            c.execute("SELECT taktovia.not_performed_refresh(%s)", (outage,))
            self.assertEqual(row(c, outage)[4], 1)

    def test_korrekturvermerk_unveraenderlich_und_ursache_offen_sichtbar(self):
        with tx(A) as c:
            bid = fact(c, A, 'CANCELED', 'HOLD_EXPIRED', 'FAHRGAST', '2026-12-01T05:30Z')
            self.assertEqual(row(c, bid)[0], 'OPEN')
            c.execute("INSERT INTO taktovia.not_performed_correction(tenant_id,id,booking_id,correction_no,old_cause,old_category,new_cause,new_category,reason,actor) "
                      "VALUES (%s,%s,%s,1,NULL,NULL,'EXTERNAL_EVENT','EXTERNAL','Unwetter','user:disp')", (A, uid(), bid))
            c.execute("SELECT taktovia.not_performed_refresh(%s)", (bid,))      # die Ableitung nach dem Vermerk: wirksam ist die Korrektur, die abgeleitete Ursache bleibt (offen) sichtbar
            self.assertEqual(row(c, bid)[:4], ('CORRECTED', 'EXTERNAL_EVENT', 'EXTERNAL', None))
        # Vermerke: nur anhängen; kein UPDATE/DELETE für die Runtime-Rolle, Trigger für die Administration
        for stmt in ("UPDATE taktovia.not_performed_correction SET reason='anders' WHERE booking_id=%s", "DELETE FROM taktovia.not_performed_correction WHERE booking_id=%s"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=stmt), tx(A) as c:
                c.execute(stmt, (bid,))
            with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt), admin() as c:
                c.execute(stmt, (bid,))
        # Pflichtangaben: Grund und Akteur, fortlaufende Nummer je Fahrt (eindeutig), alter und neuer Wert zusammen
        bad = ("INSERT INTO taktovia.not_performed_correction(tenant_id,id,booking_id,correction_no,old_cause,old_category,new_cause,new_category,reason,actor) VALUES (%s,%s,%s,%s,%s,%s,'EXTERNAL_EVENT','EXTERNAL',%s,%s)")
        for args in ((A, uid(), bid, 2, None, None, '  ', 'user:x'), (A, uid(), bid, 2, None, None, 'Grund ok', 'Peter'), (A, uid(), bid, 1, None, None, 'Grund ok', 'user:x'), (A, uid(), bid, 2, 'VEHICLE_OUTAGE', None, 'Grund ok', 'user:x')):
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.UniqueViolation)), tx(A) as c:
                c.execute(bad, args)
        # eine Ursache ohne Kategorie (oder umgekehrt) ist unmöglich: genau eine wirksame Ursache oder keine
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:
            c.execute("UPDATE taktovia.not_performed_trip SET category=NULL WHERE booking_id=%s", (bid,))
        # Mandantentrennung
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.not_performed_trip").fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.not_performed_correction").fetchone()[0], 0)
        with self.assertRaises(psycopg.Error), tx(B) as c:
            c.execute("INSERT INTO taktovia.not_performed_correction(tenant_id,id,booking_id,correction_no,new_cause,new_category,reason,actor) VALUES (%s,%s,%s,9,'EXTERNAL_EVENT','EXTERNAL','Grund ok','user:x')", (A, uid(), bid))


if __name__ == '__main__':
    unittest.main()
