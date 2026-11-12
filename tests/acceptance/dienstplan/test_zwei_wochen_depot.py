"""WP-M4-18 / LH-6.23-SCH-001: Datenbasis des Planer-Dashboards gegen die echte Datenbank unter der Runtime-Rolle.

Originalabnahme: „Planer kann verfügbare Einsätze eines Depots für zwei aufeinanderfolgende Wochen sehen.“ Geprüft wird die Datengrundlage des Lesemodells
(`roster_revision` und `staff_shift`, wie `GET /v1/rosters` und `GET /v1/rosters/shifts` sie lesen): zwei aufeinanderfolgende Wochen eines Depots mit zugeordneten und offenen Schichten,
Entwurf und veröffentlichte Revision getrennt, ein anderes Depot und ein anderer Mandant erscheinen nicht, der Entwurf ist ohne Planerrecht (`app.roster_draft_access`) unsichtbar.
GRENZE: Das ist KEIN Browser-E2E. Die Darstellung (Raster, Zeilen, Zähler, Tabellenalternative) prüfen `web/operator/src/dienstplan/DienstplanRaster.test.tsx` (Komponententests mit
Server-Ersatz) und die Backend-Tests der Rosters-API; ein Lauf der Oberfläche im echten Browser gegen den echten Server ist in dieser Umgebung nicht ausgeführt.
Aufruf: python -m unittest discover -s tests/acceptance -p 'test_zwei_wochen_depot.py' (DB-Umgebung wie db/db.py; legt die Datenbank taktovia_at_dienstplan an).
"""
import sys
import unittest
import uuid
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from taktovia_at import db as atdb  # noqa: E402
from taktovia_at import m4_referenz as rr  # noqa: E402

DB = "taktovia_at_dienstplan"
A, B = rr.TENANTS["a"], rr.TENANTS["b"]
W1, W2 = date(2026, 11, 30), date(2026, 12, 7)  # zwei aufeinanderfolgende Kalenderwochen (Montage)


def _id(label, key):
    return rr.fixture_id(label, key)


def _legen_dienstplan_an(c, tenant, label, depot_key, week, roster_key, shifts, veroeffentlichen):
    """Dienstplan als Entwurf (Revision 1); bei `veroeffentlichen` wird Revision 1 veröffentlicht und Revision 2 ein neuer Entwurf mit denselben Schichten."""
    roster = _id(label, roster_key)
    depot = _id(label, depot_key)
    operator = _id(label, "org-y")
    c.execute("SELECT set_config('app.roster_draft_access', 'on', true)")

    def revision(rev, state):
        rid = uuid.uuid5(roster, f"rev-{rev}")
        c.execute("INSERT INTO roster_revision(tenant_id, id, roster_id, depot_id, week_start, revision, state, author) VALUES (%s,%s,%s,%s,%s,%s,'DRAFT','user:planer')",
                  (tenant, rid, roster, depot, week, rev))
        return rid

    def schichten(rev, state):
        for n, (tag, start, ende, fahrer) in enumerate(shifts):
            beginn = f"{week + timedelta(days=tag)}T{start}+01:00"
            schluss = f"{week + timedelta(days=tag)}T{ende}+01:00"
            c.execute("INSERT INTO staff_shift(tenant_id, id, driver_ref, vehicle_id, starts_at, ends_at, timezone, roster_revision, state, roster_id, operator_id, depot_id, shift_function, created_by)"
                      " VALUES (%s,%s,%s,NULL,%s,%s,'Europe/Berlin',%s,%s,%s,%s,%s,'DRIVER','user:planer')",
                      (tenant, uuid.uuid5(roster, f"shift-{rev}-{n}"), _id(label, fahrer) if fahrer else None, beginn, schluss, rev, state, roster, operator, depot))

    revision(1, "DRAFT")
    if veroeffentlichen:
        schichten(1, "PUBLISHED")
        c.execute("UPDATE roster_revision SET state='PUBLISHED', published_at=now(), published_by='user:planer' WHERE tenant_id=%s AND roster_id=%s AND revision=1", (tenant, roster))
        revision(2, "DRAFT")
        schichten(2, "DRAFT")
    else:
        schichten(1, "DRAFT")


class DienstplanZweiWochen(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        atdb.reset_database(DB)
        rr.load(DB)
        c = atdb.migrator_conn(DB)
        try:
            c.execute("SET search_path = taktovia, public")
            for tenant, label in ((A, "a"), (B, "b")):
                atdb.set_tenant(c, tenant)
                # Depot 1: Woche 1 veröffentlicht plus Entwurf, Woche 2 nur Entwurf, mit zugeordneten und offenen Schichten
                _legen_dienstplan_an(c, tenant, label, "dep-1", W1, "roster-w1", [(0, "06:00:00", "14:00:00", "drv-1"), (1, "06:00:00", "14:00:00", None), (2, "14:00:00", "22:00:00", "drv-2")], True)
                _legen_dienstplan_an(c, tenant, label, "dep-1", W2, "roster-w2", [(0, "06:00:00", "14:00:00", "drv-2"), (3, "06:00:00", "14:00:00", None)], False)
                # Depot 2: Woche 1 (darf im Dienstplan von Depot 1 nicht erscheinen)
                _legen_dienstplan_an(c, tenant, label, "dep-2", W1, "roster-d2", [(0, "08:00:00", "16:00:00", "drv-1")], False)
            c.commit()
        finally:
            c.close()

    @classmethod
    def tearDownClass(cls):
        atdb.drop_database(DB)

    def _lies(self, tenant, planer, sql, params=()):
        c = atdb.runtime_conn(DB)
        try:
            c.execute("SET search_path = taktovia, public")
            atdb.set_tenant(c, tenant)
            if planer:
                c.execute("SELECT set_config('app.roster_draft_access', 'on', true)")
            return c.execute(sql, params).fetchall()
        finally:
            c.rollback()
            c.close()

    def test_planer_sieht_zwei_wochen_eines_depots(self):
        depot = _id("a", "dep-1")
        wochen = self._lies(A, True, "SELECT week_start, revision, state FROM roster_revision WHERE depot_id=%s AND week_start BETWEEN %s AND %s ORDER BY week_start, revision", (depot, W1, W2))
        self.assertEqual([(w[0], w[1], w[2]) for w in wochen], [(W1, 1, "PUBLISHED"), (W1, 2, "DRAFT"), (W2, 1, "DRAFT")])
        # Arbeitsstand (Entwurf, sonst veröffentlicht) je Woche; Einträge mit Person und offene Dienste getrennt zählbar
        schichten = self._lies(A, True,
            "SELECT s.operating_day, s.driver_ref IS NULL AS offen, r.state FROM staff_shift s JOIN roster_revision r"
            " ON r.tenant_id=s.tenant_id AND r.roster_id=s.roster_id AND r.revision=s.roster_revision"
            " WHERE s.depot_id=%s AND s.operating_day BETWEEN %s AND %s AND r.state IN ('DRAFT') ORDER BY s.operating_day, s.starts_at", (depot, W1, W2 + timedelta(days=6)))
        self.assertEqual([(s[0], s[1]) for s in schichten], [(W1, False), (W1 + timedelta(days=1), True), (W1 + timedelta(days=2), False), (W2, False), (W2 + timedelta(days=3), True)])
        self.assertEqual(sum(1 for s in schichten if s[1]), 2)  # zwei offene Dienste in beiden Wochen
        # Depot 2 erscheint nicht in der Sicht auf Depot 1
        d2 = self._lies(A, True, "SELECT DISTINCT depot_id FROM staff_shift WHERE roster_id IS NOT NULL AND operating_day BETWEEN %s AND %s", (W1, W2 + timedelta(days=6)))
        self.assertEqual({x[0] for x in d2}, {depot, _id("a", "dep-2")})

    def test_entwurf_nur_mit_planerrecht_und_mandanten_getrennt(self):
        depot = _id("a", "dep-1")
        ohne = self._lies(A, False, "SELECT state FROM roster_revision WHERE depot_id=%s AND week_start BETWEEN %s AND %s", (depot, W1, W2))
        self.assertEqual([x[0] for x in ohne], ["PUBLISHED"])  # ohne Planerrecht nur die veröffentlichte Revision
        ohne_schichten = self._lies(A, False, "SELECT DISTINCT state FROM staff_shift WHERE depot_id=%s AND roster_id IS NOT NULL", (depot,))
        self.assertEqual([x[0] for x in ohne_schichten], ["PUBLISHED"])
        # Mandant B sieht das Depot und die Dienstpläne von Mandant A nicht
        fremd = self._lies(B, True, "SELECT count(*) FROM roster_revision WHERE depot_id=%s", (depot,))
        self.assertEqual(fremd[0][0], 0)
        eigene_b = self._lies(B, True, "SELECT count(*) FROM roster_revision WHERE depot_id=%s", (_id("b", "dep-1"),))
        self.assertEqual(eigene_b[0][0], 3)


if __name__ == "__main__":
    unittest.main()
