"""WP-M2-36: Favoriten mit Koordinate unter der echten Runtime-Rolle (Form, Wertebereich, nie 0/0, Haltepunkt optional)."""
import unittest

import psycopg

from helpers import runtime, seed_tenant, set_tenant, uid
from test_10_passenger_preference import passenger

T = uid()
INS = ("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,label,point) "
       "VALUES (%s,%s,%s,'FAVORITE',%s,%s,ST_SetSRID(ST_MakePoint(%s,%s),4326))")


class FavoritKoordinate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(T, 'fav-' + str(T)[:8])
        cls.p = passenger(T)

    def run_sql(self, sql, args, exc=None):
        c = runtime()
        try:
            set_tenant(c, T)
            if exc:
                with self.assertRaises(exc):
                    c.execute(sql, args)
            else:
                c.execute(sql, args)
                c.commit()
        finally:
            c.rollback(); c.close()

    def test_favorit_ohne_haltepunkt_mit_koordinate_ist_gueltig(self):
        self.run_sql(INS, (T, uid(), self.p, 0, 'Zuhause', 9.18, 48.78))

    def test_koordinate_ausserhalb_wertebereich_und_nullkoordinate_abgewiesen(self):
        for i, (lon, lat) in enumerate(((9.0, 91.0), (181.0, 48.0), (0.0, 0.0)), start=1):
            self.run_sql(INS, (T, uid(), self.p, 10 + i, 'X', lon, lat), psycopg.errors.CheckViolation)

    def test_favorit_ohne_koordinate_abgewiesen_und_praeferenz_ohne_koordinate(self):
        self.run_sql("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,label) VALUES (%s,%s,%s,'FAVORITE',20,'ohne')",
                     (T, uid(), self.p), psycopg.errors.CheckViolation)
        # bevorzugte Haltestellen und Kanäle tragen keine Koordinate
        self.run_sql("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,position,channel,point) VALUES (%s,%s,%s,'NOTIFICATION_CHANNEL',21,'PUSH',ST_SetSRID(ST_MakePoint(9,48),4326))",
                     (T, uid(), self.p), psycopg.errors.CheckViolation)


if __name__ == '__main__':
    unittest.main()
