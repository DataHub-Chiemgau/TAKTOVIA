"""WP-M4-03 / LH-6.23-SCH-002: `staff_shift` mit Betrieb, Depot, Funktion, Qualifikation, Personalbedarf und Pausenplan unter der echten Runtime-Rolle: Betriebstag aus dem lokalen Beginn (Datum in der Zone des Aggregats),
Mitternachtsschicht, Mandantenbezug von Depot und Betrieb, offene Schichten, Revision je Änderung und Verträglichkeit mit den Altzeilen aus M2/M3."""
import unittest
from contextlib import contextmanager
import psycopg
from helpers import admin, tx as _tx, uid, seed_tenant


@contextmanager
def tx(tenant):
    """Wie helpers.tx, mit Planerrecht für Entwurfszeilen (WP-M4-04: `app.roster_draft_access`; die Schichten dieses Tests sind Entwürfe)."""
    with _tx(tenant) as c:
        c.execute("SELECT set_config('app.roster_draft_access', 'on', true)")
        yield c

A, B = uid(), uid()
LE = {A: uid(), B: uid()}; ORG = {A: uid(), B: uid()}; DEPOT = {A: uid(), B: uid()}; REGION = {A: uid(), B: uid()}
EMPTY_PLAN = '{"schema_version": "1.0", "breaks": []}'


def setup(tenant):
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'LE','DE')", (tenant, LE[tenant]))
        c.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,'Betrieb',%s,'{TRANSPORT_COMPANY}')", (tenant, ORG[tenant], LE[tenant], f'f-{ORG[tenant]}'))
        c.execute("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (%s,%s,'R','user:t')", (tenant, REGION[tenant]))
        c.execute("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (%s,%s,%s,'D','user:t')", (tenant, DEPOT[tenant], REGION[tenant]))


def shift(c, tenant, start='2026-12-01T21:00:00Z', end='2026-12-02T05:00:00Z', tz='Europe/Berlin', depot=None, operator=None, driver=None, vehicle=None, state='DRAFT', need=2, plan=EMPTY_PLAN):
    sid = uid()
    c.execute("""INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state,operator_id,depot_id,shift_function,required_qualification,staffing_need,break_plan,created_by)
                 VALUES (%s,%s,%s,%s,%s,%s,%s,0,%s,%s,%s,'Fahrdienst','PERSONENBEFOERDERUNG',%s,%s::jsonb,'user:planer')""",
              (tenant, sid, driver, vehicle, start, end, tz, state, operator or ORG[tenant], depot or DEPOT[tenant], need, plan))
    return sid


class Schichtmodell(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm403-a'); seed_tenant(B, 'm403-b'); setup(A); setup(B)

    def test_mitternachtsschicht_betriebstag_ist_lokaler_tag_des_beginns_und_dauer_8h(self):
        with tx(A) as c:
            sid = shift(c, A)   # 22:00 Berlin (MEZ) bis 06:00 am Folgetag
            r = c.execute("""SELECT operating_day, (starts_at AT TIME ZONE timezone)::time, (ends_at AT TIME ZONE timezone)::time, (ends_at AT TIME ZONE timezone)::date, ends_at - starts_at
                             FROM taktovia.staff_shift WHERE id=%s""", (sid,)).fetchone()
        self.assertEqual(str(r[0]), '2026-12-01'); self.assertEqual((str(r[1]), str(r[2]), str(r[3])), ('22:00:00', '06:00:00', '2026-12-02')); self.assertEqual(r[4].total_seconds(), 8 * 3600)

    def test_dst_schicht_hat_reale_dauer_und_lokale_zeiten(self):
        with tx(A) as c:
            autumn = shift(c, A, '2026-10-24T20:00:00Z', '2026-10-25T05:00:00Z')   # 22:00 MESZ bis 06:00 MEZ = 9 h real
            spring = shift(c, A, '2027-03-27T21:00:00Z', '2027-03-28T04:00:00Z')   # 22:00 MEZ bis 06:00 MESZ = 7 h real
            for sid, hours in ((autumn, 9), (spring, 7)):
                r = c.execute("SELECT (ends_at - starts_at), (starts_at AT TIME ZONE timezone)::time, (ends_at AT TIME ZONE timezone)::time FROM taktovia.staff_shift WHERE id=%s", (sid,)).fetchone()
                self.assertEqual(r[0].total_seconds(), hours * 3600); self.assertEqual((str(r[1]), str(r[2])), ('22:00:00', '06:00:00'))

    def test_ende_vor_beginn_und_ueber_24h_werden_abgewiesen(self):
        for start, end in (('2026-12-02T05:00:00Z', '2026-12-01T21:00:00Z'), ('2026-12-01T05:00:00Z', '2026-12-01T05:00:00Z'), ('2026-12-01T05:00:00Z', '2026-12-02T05:00:01Z')):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=f'{start} {end}'): shift(c, A, start, end)

    def test_fremdes_depot_nicht_referenzierbar(self):  # auch per DB-FK nicht
        for kw in ({'depot': DEPOT[B]}, {'operator': ORG[B]}):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.ForeignKeyViolation, msg=str(kw)): shift(c, A, **kw)
        with tx(A) as c: shift(c, A)   # eigene Referenzen gehen

    def test_offene_schicht_ohne_fahrer_und_fahrzeug(self):
        with tx(A) as c:
            sid = shift(c, A, driver=None, vehicle=None)
            self.assertEqual(c.execute("SELECT driver_ref IS NULL AND vehicle_id IS NULL FROM taktovia.staff_shift WHERE id=%s", (sid,)).fetchone()[0], True)

    def test_pausenplan_form_und_personalbedarf_werden_geprueft(self):
        for plan in ('{"schema_version": "2.0", "breaks": []}', '{"schema_version": "1.0", "breaks": "x"}', '[]', '{"schema_version": "1.0", "breaks": [1,2,3,4,5,6,7]}'):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=plan): shift(c, A, plan=plan)
        for need in (0, 51):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=str(need)): shift(c, A, need=need)
        with tx(A) as c: shift(c, A, plan='{"schema_version": "1.0", "breaks": [{"offset_min": 240, "duration_min": 30, "paid": false}]}')

    def test_revision_je_aenderung_und_abgeleitete_spalten_nicht_ueberschreibbar(self):
        with tx(A) as c:
            sid = shift(c, A)
            self.assertEqual(c.execute("SELECT shift_revision FROM taktovia.staff_shift WHERE id=%s", (sid,)).fetchone()[0], 1)
            c.execute("UPDATE taktovia.staff_shift SET shift_function='Leitstelle' WHERE id=%s", (sid,))
            c.execute("UPDATE taktovia.staff_shift SET shift_function='Leitstelle' WHERE id=%s", (sid,))   # ohne inhaltliche Änderung keine neue Revision
            self.assertEqual(c.execute("SELECT shift_revision FROM taktovia.staff_shift WHERE id=%s", (sid,)).fetchone()[0], 2)
            c.execute("UPDATE taktovia.staff_shift SET operating_day='2000-01-01', shift_revision=99 WHERE id=%s", (sid,))
            r = c.execute("SELECT operating_day, shift_revision FROM taktovia.staff_shift WHERE id=%s", (sid,)).fetchone()
            self.assertEqual((str(r[0]), r[1]), ('2026-12-01', 2), 'Ableitung und Revision gehören der Datenbank')
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.staff_shift SET created_by='user:anderer' WHERE id=%s", (sid,))

    def test_dienstplanzuordnung_ist_endgueltig(self):
        r1, r2 = uid(), uid()
        with tx(A) as c:
            # WP-M4-04: die Zuordnung verweist auf eine Dienstplanrevision (Fremdschlüssel); die zweite Kennung gibt es ebenfalls
            for r in (r1, r2):
                c.execute("INSERT INTO taktovia.roster_revision(tenant_id,id,roster_id,depot_id,week_start,revision,author) VALUES (%s,%s,%s,%s,%s,1,'user:planer')",
                          (A, uid(), r, DEPOT[A], '2026-11-30' if r == r1 else '2026-12-07'))
            sid = shift(c, A)
            c.execute("UPDATE taktovia.staff_shift SET roster_id=%s, roster_revision=1 WHERE id=%s", (r1, sid))   # erstes Setzen
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.staff_shift SET roster_id=%s, roster_revision=1 WHERE id=%s", (r2, sid))

    def test_rls_fremder_mandant_sieht_nichts(self):
        with tx(A) as c: sid = shift(c, A)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.staff_shift WHERE id=%s", (sid,)).fetchone()[0], 0)
        with tx(None) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.staff_shift").fetchone()[0], 0)

    def test_m3_altzeilen_bleiben_lesbar_und_resource_release_gueltig(self):
        """Eine Zeile in der Form der M2-/M3-Fixtures (nur Spalten des Kernschemas) bleibt gültig: neue Spalten sind befüllt (abgeleitet, Standardwerte) oder leer."""
        sid, vid, rel = uid(), uid(), uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (%s,%s,'x','{}',false,1)", (A, vid))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,'2026-12-01T04:00:00Z','2026-12-01T14:00:00Z','Europe/Berlin',0,'PUBLISHED')", (A, sid, uid(), vid))
            c.execute("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (%s,%s,%s,%s,1,%s,1,'2026-12-01T14:00:00Z')", (A, rel, vid, sid, uid()))
            r = c.execute("""SELECT s.state, s.roster_revision, s.shift_revision, s.staffing_need, s.break_plan::text, s.operating_day, s.depot_id, s.operator_id, s.roster_id,
                                    (SELECT count(*) FROM taktovia.resource_release rr WHERE rr.shift_id = s.id) FROM taktovia.staff_shift s WHERE s.id=%s""", (sid,)).fetchone()
        self.assertEqual((r[0], r[1], r[2], r[3], r[9]), ('PUBLISHED', 0, 1, 1, 1))
        self.assertEqual(str(r[5]), '2026-12-01'); self.assertEqual((r[6], r[7], r[8]), (None, None, None)); self.assertIn('"breaks": []', r[4])


if __name__ == '__main__':
    unittest.main()
