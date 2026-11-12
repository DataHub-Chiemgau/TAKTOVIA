"""WP-M3-07 / LH-6.8-FLT-003: eindeutige Schichtgrenzen unter der echten Runtime-Rolle. Überlappung je Fahrer und je Fahrzeug ist verboten, aneinandergrenzende Schichten
(Ende = Beginn, halboffenes Intervall) sind erlaubt, abgebrochene Schichten blockieren nichts, die dokumentierte Ausnahme (Genehmiger, Grund, Zeitpunkt) hebt das Verbot
für genau diese Schicht auf, Pflichtfelder und Intervallprüfung gelten auch für direktes SQL, und zwei parallele Transaktionen legen höchstens eine von zwei
überlappenden Schichten an."""
import threading, unittest
import psycopg
from helpers import admin, runtime, set_tenant, tx, uid, seed_tenant

A, B = uid(), uid()
INS = ("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state%s) VALUES (%%s,%%s,%%s,%%s,%%s,%%s,'Europe/Berlin',0,%%s%s)")


def vehicle(tenant):
    v = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (tenant, v))
    return v


def shift(c, tenant, driver, veh, start, end, state='PLANNED', exception=None):
    sid = uid()
    if exception:
        c.execute(INS % (',overlap_exception_by,overlap_exception_reason,overlap_exception_at', ',%s,%s,%s'), (tenant, sid, driver, veh, start, end, state, *exception))
    else:
        c.execute(INS % ('', ''), (tenant, sid, driver, veh, start, end, state))
    return sid


class Schichtgrenzen(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm307-a'); seed_tenant(B, 'm307-b')

    def test_ueberlappung_fahrer_und_fahrzeug_verboten(self):
        d1, d2, v1, v2 = uid(), uid(), vehicle(A), vehicle(A)
        with tx(A) as c: shift(c, A, d1, v1, '2026-12-01T06:00Z', '2026-12-01T14:00Z')
        # derselbe Fahrer, anderes Fahrzeug: Konflikt Fahrer
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ExclusionViolation) as e: shift(c, A, d1, v2, '2026-12-01T13:00Z', '2026-12-01T20:00Z')
        self.assertEqual(e.exception.diag.constraint_name, 'staff_shift_driver_no_overlap'); self.assertTrue(e.exception.diag.message_detail.startswith('DRIVER:'))
        # anderer Fahrer, dasselbe Fahrzeug: Konflikt Fahrzeug
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ExclusionViolation) as e: shift(c, A, d2, v1, '2026-12-01T05:00Z', '2026-12-01T06:01Z')
        self.assertEqual(e.exception.diag.constraint_name, 'staff_shift_vehicle_no_overlap'); self.assertTrue(e.exception.diag.message_detail.startswith('VEHICLE:'))
        # vollständig enthaltene Schicht ebenfalls
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ExclusionViolation): shift(c, A, d1, v2, '2026-12-01T08:00Z', '2026-12-01T09:00Z')
        # Änderung in die Überlappung hinein: auch der UPDATE-Pfad ist gesperrt, die Schicht blockiert sich nicht selbst
        with tx(A) as c: s = shift(c, A, d2, v2, '2026-12-02T06:00Z', '2026-12-02T10:00Z')
        with tx(A) as c:
            c.execute("UPDATE taktovia.staff_shift SET ends_at='2026-12-02T12:00Z' WHERE id=%s", (s,))      # Verlängerung ohne Fremdberührung
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ExclusionViolation):
                c.execute("UPDATE taktovia.staff_shift SET driver_ref=%s, starts_at='2026-12-01T07:00Z', ends_at='2026-12-01T08:00Z' WHERE id=%s", (d1, s))

    def test_aneinandergrenzende_schichten_erlaubt(self):
        d, v = uid(), vehicle(A)
        with tx(A) as c:
            shift(c, A, d, v, '2026-12-03T06:00Z', '2026-12-03T14:00Z')
            shift(c, A, d, v, '2026-12-03T14:00Z', '2026-12-03T22:00Z')           # Ende = Beginn
            shift(c, A, d, v, '2026-12-03T00:00Z', '2026-12-03T06:00Z')           # direkt davor
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.staff_shift WHERE driver_ref=%s', (d,)).fetchone()[0], 3)

    def test_abgebrochene_schicht_blockiert_nichts_und_wiederaufnahme_wird_geprueft(self):
        d, v = uid(), vehicle(A)
        with tx(A) as c: s = shift(c, A, d, v, '2026-12-04T06:00Z', '2026-12-04T14:00Z', state='CANCELLED')
        with tx(A) as c: shift(c, A, d, v, '2026-12-04T08:00Z', '2026-12-04T12:00Z')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ExclusionViolation):
                c.execute("UPDATE taktovia.staff_shift SET state='PLANNED' WHERE id=%s", (s,))

    def test_dst_ende_25_10_2026_rechnet_mit_absoluten_zeitpunkten(self):
        d, v = uid(), vehicle(A)
        # 25.10.2026 (Europe/Berlin): 03:00 CEST -> 02:00 CET. 01:30+02:00 bis 02:30+01:00 sind zwei Stunden; die Wandzeit „02:30“ kommt zweimal vor.
        with tx(A) as c:
            shift(c, A, d, v, '2026-10-25T01:30:00+02:00', '2026-10-25T02:30:00+01:00')
            self.assertEqual(c.execute("SELECT extract(epoch from ends_at-starts_at)::int FROM taktovia.staff_shift WHERE driver_ref=%s", (d,)).fetchone()[0], 2 * 3600)
        # Beginn „02:30“ nach dem ersten Durchlauf (+02:00) liegt vor dem Ende „02:30“ (+01:00): nach Wandzeit angrenzend, absolut überlappend
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ExclusionViolation): shift(c, A, d, v, '2026-10-25T02:30:00+02:00', '2026-10-25T08:00:00+01:00')
        # die zweite „02:30“ (+01:00) grenzt dagegen exakt an
        with tx(A) as c: shift(c, A, d, v, '2026-10-25T02:30:00+01:00', '2026-10-25T08:00:00+01:00')

    def test_ausnahme_hebt_das_verbot_nur_fuer_diese_schicht_auf(self):
        d, v1, v2 = uid(), vehicle(A), vehicle(A)
        with tx(A) as c: shift(c, A, d, v1, '2026-12-05T06:00Z', '2026-12-05T14:00Z')
        with tx(A) as c:
            e = shift(c, A, d, v2, '2026-12-05T12:00Z', '2026-12-05T18:00Z', exception=('user:dispo-1', 'Krankheitsvertretung', '2026-12-04T10:00Z'))
            self.assertEqual(c.execute('SELECT overlap_exception_by FROM taktovia.staff_shift WHERE id=%s', (e,)).fetchone()[0], 'user:dispo-1')
        # eine weitere reguläre Schicht, die die Ausnahmezeile berührt, bleibt ein Konflikt
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ExclusionViolation): shift(c, A, d, vehicle(A), '2026-12-05T15:00Z', '2026-12-05T16:00Z')

    def test_ausnahme_nur_vollstaendig(self):
        d, v = uid(), vehicle(A)
        for sql in ("overlap_exception_by='user:x'", "overlap_exception_reason='Grund'", "overlap_exception_at=now()", "overlap_exception_by='user:x', overlap_exception_reason='Grund'"):
            with tx(A) as c: s = shift(c, A, uid(), vehicle(A), '2026-12-06T06:00Z', '2026-12-06T07:00Z')
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): c.execute(f"UPDATE taktovia.staff_shift SET {sql} WHERE id=%s", (s,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): shift(c, A, d, v, '2026-12-06T06:00Z', '2026-12-06T07:00Z', exception=('user:x', '  ', '2026-12-04T10:00Z'))

    def test_pflichtfelder_und_intervall_auch_per_sql(self):
        d, v = uid(), vehicle(A)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): shift(c, A, d, v, '2026-12-07T08:00Z', '2026-12-07T08:00Z')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): shift(c, A, d, v, '2026-12-07T09:00Z', '2026-12-07T08:00Z')
        for col in ('starts_at', 'ends_at'):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.NotNullViolation):
                    c.execute(f"INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,{'ends_at' if col == 'starts_at' else 'starts_at'},timezone,roster_revision,state) "
                              "VALUES (%s,%s,%s,%s,'2026-12-07T08:00Z','Europe/Berlin',0,'PLANNED')", (A, uid(), d, v))

    def test_fremder_mandant_ist_nicht_referenzierbar_und_blockiert_nicht(self):
        d = uid(); va, vb = vehicle(A), vehicle(B)
        with tx(B) as c: shift(c, B, d, vb, '2026-12-08T06:00Z', '2026-12-08T14:00Z')
        with tx(A) as c: shift(c, A, d, va, '2026-12-08T06:00Z', '2026-12-08T14:00Z')        # gleiche Fahrerkennung in anderem Mandanten: kein Konflikt
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): shift(c, A, uid(), vb, '2026-12-09T06:00Z', '2026-12-09T14:00Z')   # Fahrzeug des Mandanten B

    def test_revision_steigt_bei_inhaltlicher_aenderung(self):
        d, v = uid(), vehicle(A)
        with tx(A) as c:
            s = shift(c, A, d, v, '2026-12-10T06:00Z', '2026-12-10T14:00Z')
            self.assertEqual(c.execute('SELECT revision FROM taktovia.staff_shift WHERE id=%s', (s,)).fetchone()[0], 1)
            c.execute("UPDATE taktovia.staff_shift SET ends_at='2026-12-10T15:00Z' WHERE id=%s", (s,))
            self.assertEqual(c.execute('SELECT revision FROM taktovia.staff_shift WHERE id=%s', (s,)).fetchone()[0], 2)
            c.execute("UPDATE taktovia.staff_shift SET ends_at='2026-12-10T15:00Z' WHERE id=%s", (s,))
            self.assertEqual(c.execute('SELECT revision FROM taktovia.staff_shift WHERE id=%s', (s,)).fetchone()[0], 2, 'unverändert: keine neue Revision')

    def test_parallele_ueberlappende_schichten_hoechstens_eine(self):
        """Zwei Transaktionen legen überlappende Schichten desselben Fahrers an (A) bzw. desselben Fahrzeugs mit verschiedenen Fahrern (B): jeweils genau eine gewinnt."""
        for kind in ('driver', 'vehicle'):
            for round_ in range(5):
                d1, d2 = uid(), uid(); v1, v2 = vehicle(A), vehicle(A)
                day = f'2027-01-{10 + round_:02d}'
                specs = [(d1, v1), (d1, v2)] if kind == 'driver' else [(d1, v1), (d2, v1)]
                barrier = threading.Barrier(2); results = []

                def worker(driver, veh):
                    c = runtime()
                    try:
                        set_tenant(c, A)
                        barrier.wait()
                        shift(c, A, driver, veh, f'{day}T06:00Z', f'{day}T14:00Z')
                        import time; time.sleep(0.15)       # Transaktion offen halten: der Gegner muss auf den Lock warten
                        c.commit(); results.append('ok')
                    except psycopg.errors.ExclusionViolation:
                        c.rollback(); results.append('conflict')
                    except Exception as e:      # z. B. erschöpfte Verbindungen der gemeinsam genutzten Testdatenbank: sichtbar machen statt zu verschwinden
                        results.append('error: ' + repr(e))
                    finally:
                        c.close()
                ts = [threading.Thread(target=worker, args=s) for s in specs]
                [t.start() for t in ts]; [t.join(30) for t in ts]
                self.assertEqual(sorted(results), ['conflict', 'ok'], f'{kind} Runde {round_}: {results}')
                key, val = ('driver_ref', d1) if kind == 'driver' else ('vehicle_id', v1)
                with tx(A) as c:
                    self.assertEqual(c.execute(f"SELECT count(*) FROM taktovia.staff_shift WHERE {key}=%s AND starts_at='{day}T06:00Z'", (val,)).fetchone()[0], 1)


if __name__ == '__main__':
    unittest.main()
