"""WP-M5-08 / LH-6.19-NFR-005: position_sample trägt Messzeit, Empfangszeit und Qualitätsflag als Pflichtangaben (NOT NULL, Wertebereich per CHECK), unter der echten Runtime-Rolle.
Eine Position ohne eine dieser Angaben oder mit unbekanntem Qualitätswert wird von der Datenbank abgewiesen; die Mandantentrennung gilt auch für die Qualitätsspalte; die
Abfrage der jüngsten verwendbaren Position je Fahrzeug nutzt den Index (Mengenprobe mit 250 Fahrzeugen)."""
import unittest
import psycopg
from helpers import runtime, set_tenant, tx, uid, seed_tenant
from test_m3_34_position import fixture

A, B = uid(), uid()
COLS = "tenant_id,id,vehicle_id,shift_id,position,accuracy_m,measured_at,received_at,quality,created_by"
INS = (f"INSERT INTO taktovia.position_sample({COLS}) VALUES (%s,%s,%s,%s,ST_GeogFromText('POINT(13.74 51.05)'),5,%s,%s,%s,'user:t')")


class PositionPflichtangaben(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm508-a'); seed_tenant(B, 'm508-b')
        cls.va, cls.sa = fixture(A); cls.vb, cls.sb = fixture(B)

    def _ins(self, tenant, v, s, measured, received, quality, at='2026-12-05T06:00:00Z'):
        with tx(tenant) as c:
            c.execute(INS, (tenant, uid(), v, s, measured, received, quality))

    def test_position_ohne_zeit_oder_qualitaet_wird_abgewiesen(self):
        ok = '2026-12-05T06:00:00Z'
        # vollständige Meldung wird angenommen
        self._ins(A, self.va, self.sa, ok, ok, 'GOOD')
        for name, args in {
            'ohne Messzeit': (None, ok, 'GOOD'),
            'ohne Empfangszeit': (ok, None, 'GOOD'),
            'ohne Qualitätsflag': (ok, ok, None),
        }.items():
            with self.subTest(name), self.assertRaises(psycopg.errors.NotNullViolation):
                self._ins(A, self.va, self.sa, *args)
        # das Qualitätsflag hat keinen Standardwert: eine Zeile ohne die Spalte wird abgewiesen
        with self.assertRaises(psycopg.errors.NotNullViolation), tx(A) as c:
            c.execute("INSERT INTO taktovia.position_sample(tenant_id,id,vehicle_id,shift_id,position,accuracy_m,measured_at,received_at,created_by) "
                      "VALUES (%s,%s,%s,%s,ST_GeogFromText('POINT(13.74 51.05)'),5,%s,%s,'user:t')", (A, uid(), self.va, self.sa, ok, ok))

    def test_qualitaetswert_ausserhalb_des_wertebereichs_wird_abgewiesen(self):
        for bad in ('', 'good', 'FRESH', 'UNKNOWN'):
            with self.subTest(bad), self.assertRaises(psycopg.errors.CheckViolation):
                self._ins(A, self.va, self.sa, '2026-12-06T06:00:00Z', '2026-12-06T06:00:01Z', bad)
        for good in ('GOOD', 'LOW_ACCURACY', 'UNUSABLE'):
            self._ins(A, self.va, self.sa, f'2026-12-07T06:00:0{len(good) % 10}Z', '2026-12-07T06:00:30Z', good)

    def test_qualitaet_ist_unveraenderlich_und_mandantengetrennt(self):
        self._ins(A, self.va, self.sa, '2026-12-08T06:00:00Z', '2026-12-08T06:00:01Z', 'UNUSABLE')
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.position_sample WHERE quality='UNUSABLE' AND vehicle_id=%s", (self.va,)).fetchone()[0], 0)
        with self.assertRaises(psycopg.Error), tx(A) as c:      # kein Umdeuten einer gespeicherten Position (Trigger, kein UPDATE-Recht)
            c.execute("UPDATE taktovia.position_sample SET quality='GOOD' WHERE vehicle_id=%s", (self.va,))

    def test_bestand_mit_250_fahrzeugen_jüngste_verwendbare_position_nutzt_den_index(self):
        """Mengenprobe: 250 Fahrzeuge je Mandant, je 40 Positionen; die Abfrage „jüngste verwendbare Position je Fahrzeug“ liest über den Index, nicht über die ganze Tabelle."""
        t = uid(); seed_tenant(t, 'm508-last')
        vehicles = []
        with tx(t) as c:
            sh = uid(); v0 = uid()
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) SELECT %s, gen_random_uuid(), 'x', '{}' FROM generate_series(1,250)", (t,))
            vehicles = [r[0] for r in c.execute("SELECT id FROM taktovia.vehicle WHERE tenant_id=%s", (t,))]
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                      "SELECT %s, gen_random_uuid(), gen_random_uuid(), id, now()-interval '2 hours', now()+interval '10 hours', 'Europe/Berlin', 0, 'STARTED' FROM taktovia.vehicle WHERE tenant_id=%s", (t, t))
            c.execute(f"INSERT INTO taktovia.position_sample({COLS}) "
                      "SELECT %s, gen_random_uuid(), s.vehicle_id, s.id, ST_GeogFromText('POINT(13.74 51.05)'), 5, now() - (g * interval '15 seconds'), now() - (g * interval '15 seconds'), "
                      "CASE WHEN g = 0 AND random() < 0.05 THEN 'UNUSABLE' ELSE 'GOOD' END, 'user:t' FROM taktovia.staff_shift s, generate_series(0,39) g WHERE s.tenant_id=%s", (t, t))
            c.execute("ANALYZE taktovia.position_sample")
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=%s", (t,)).fetchone()[0], 250 * 40)
            c.execute("SET LOCAL enable_seqscan = off")        # kleine Tabelle: erzwingt nur, dass der Planer den Index überhaupt verwenden KANN (Nachweis der Indexeignung, nicht der Planerwahl)
            plan = "\n".join(r[0] for r in c.execute(
                "EXPLAIN SELECT p.measured_at FROM taktovia.position_sample p WHERE p.tenant_id=%s AND p.vehicle_id=%s AND p.quality <> 'UNUSABLE' ORDER BY p.measured_at DESC LIMIT 1", (t, vehicles[0])))
            self.assertIn('position_sample_latest', plan, plan)
            self.assertNotIn('Seq Scan', plan, plan)
            last = c.execute("SELECT count(*) FROM (SELECT DISTINCT ON (vehicle_id) vehicle_id FROM taktovia.position_sample WHERE tenant_id=%s AND quality <> 'UNUSABLE' "
                             "ORDER BY vehicle_id, measured_at DESC) x", (t,)).fetchone()[0]
            self.assertEqual(last, 250)


if __name__ == '__main__':
    unittest.main()
