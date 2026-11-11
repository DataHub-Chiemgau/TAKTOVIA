"""WP-M2-10 / LH-6.5-OPT-007: route_matrix_cache unter der echten Runtime-Rolle. Cache-Key aus Mandant, Start, Ziel, Profil und Kartendatenversion;
Mandantentrennung (RLS, WITH CHECK), kein UPDATE, Version bei Abfrage; parallele Zugriffe zweier Mandanten auf dieselbe Relation."""
import threading
import unittest

import psycopg

from helpers import runtime, seed_tenant, set_tenant, tx, uid

A, B = uid(), uid()
S1, S2 = uid(), uid()

INSERT = ("INSERT INTO taktovia.route_matrix_cache(tenant_id,id,origin_stop,dest_stop,profile,map_version,time_s,distance_m,provider) "
          "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,'grid')")
SELECT = "SELECT time_s, distance_m FROM taktovia.route_matrix_cache WHERE origin_stop=%s AND dest_stop=%s AND profile=%s AND map_version=%s"


class RoutingCache(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm210-a'); seed_tenant(B, 'm210-b')

    def test_cache_mandantengebunden(self):
        # dieselbe Relation, identisches Profil und identische Version: je Mandant ein eigener Eintrag mit eigenen Werten
        with tx(A) as c: c.execute(INSERT, (A, uid(), S1, S2, 'car', 'karte-1', 180, 1500))
        with tx(B) as c: c.execute(INSERT, (B, uid(), S1, S2, 'car', 'karte-1', 999, 9999))
        with tx(A) as c: self.assertEqual(c.execute(SELECT, (S1, S2, 'car', 'karte-1')).fetchall(), [(180, 1500)])
        with tx(B) as c: self.assertEqual(c.execute(SELECT, (S1, S2, 'car', 'karte-1')).fetchall(), [(999, 9999)])
        # ohne Mandantenkontext nichts sichtbar
        with tx(None) as c: self.assertEqual(c.execute(SELECT, (S1, S2, 'car', 'karte-1')).fetchall(), [])
        # einen Eintrag für einen fremden Mandanten anlegen scheitert (WITH CHECK)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute(INSERT, (B, uid(), uid(), uid(), 'car', 'karte-1', 1, 1))
        # Mandantenwechsel einer Zeile unmöglich (kein UPDATE-Recht)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("UPDATE taktovia.route_matrix_cache SET tenant_id=%s", (B,))

    def test_profil_und_kartenversion_sind_teil_des_schluessels(self):
        o, d = uid(), uid()
        with tx(A) as c:
            c.execute(INSERT, (A, uid(), o, d, 'car', 'karte-1', 100, 1000))
            c.execute(INSERT, (A, uid(), o, d, 'van_high', 'karte-1', 200, 2000))
            c.execute(INSERT, (A, uid(), o, d, 'car', 'karte-2', 90, 900))
        with tx(A) as c:
            self.assertEqual(c.execute(SELECT, (o, d, 'car', 'karte-1')).fetchall(), [(100, 1000)])
            self.assertEqual(c.execute(SELECT, (o, d, 'van_high', 'karte-1')).fetchall(), [(200, 2000)])
            self.assertEqual(c.execute(SELECT, (o, d, 'car', 'karte-2')).fetchall(), [(90, 900)])
            self.assertEqual(c.execute(SELECT, (o, d, 'car', 'karte-3')).fetchall(), [], 'andere Kartenversion: kein Treffer')
        with self.assertRaises(psycopg.errors.UniqueViolation):   # derselbe Schlüssel nur einmal je Mandant
            with tx(A) as c:
                c.execute(INSERT, (A, uid(), o, d, 'car', 'karte-1', 1, 1))

    def test_neuer_kartenstand_loescht_nur_alte_versionen_des_eigenen_mandanten(self):
        o, d = uid(), uid()
        with tx(A) as c: c.execute(INSERT, (A, uid(), o, d, 'car', 'karte-alt', 1, 1)); c.execute(INSERT, (A, uid(), o, d, 'car', 'karte-neu', 2, 2))
        with tx(B) as c: c.execute(INSERT, (B, uid(), o, d, 'car', 'karte-alt', 3, 3))
        with tx(A) as c:
            n = c.execute("DELETE FROM taktovia.route_matrix_cache WHERE map_version <> %s", ('karte-neu',)).rowcount
            self.assertGreaterEqual(n, 1)
        with tx(A) as c: self.assertEqual(c.execute(SELECT, (o, d, 'car', 'karte-alt')).fetchall(), [])
        with tx(A) as c: self.assertEqual(c.execute(SELECT, (o, d, 'car', 'karte-neu')).fetchall(), [(2, 2)])
        with tx(B) as c: self.assertEqual(c.execute(SELECT, (o, d, 'car', 'karte-alt')).fetchall(), [(3, 3)], 'Löschen wirkt nur im eigenen Mandanten')

    def test_ungueltige_werte_werden_abgewiesen(self):
        for bad in (('Car', 'v1', 1, 1), ('car', 'v 1', 1, 1), ('car', 'v1', -1, 1), ('car', 'v1', 1, -1), ('car', '', 1, 1)):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=str(bad)):
                with tx(A) as c: c.execute(INSERT, (A, uid(), uid(), uid(), bad[0], bad[1], bad[2], bad[3]))

    def test_paralleler_zugriff_zweier_mandanten_liefert_nie_fremde_werte(self):
        o, d = uid(), uid()
        errors, stop = [], threading.Event()

        def writer(tenant, base):
            for i in range(60):
                try:
                    with tx(tenant) as c: c.execute(INSERT, (tenant, uid(), o, d, 'car', f'k-{i}', base + i, base + i))
                except Exception as e:  # noqa: BLE001
                    errors.append(e)

        def reader(tenant, base):
            while not stop.is_set():
                with tx(tenant) as c:
                    for t, dist in c.execute("SELECT time_s, distance_m FROM taktovia.route_matrix_cache WHERE origin_stop=%s AND dest_stop=%s", (o, d)).fetchall():
                        if not (base <= t < base + 1000): errors.append(AssertionError(f'fremder Wert {t} für Mandant {tenant}'))

        readers = [threading.Thread(target=reader, args=(A, 1000)), threading.Thread(target=reader, args=(B, 5000))]
        writers = [threading.Thread(target=writer, args=(A, 1000)), threading.Thread(target=writer, args=(B, 5000))]
        for t in readers + writers: t.start()
        for t in writers: t.join()
        stop.set()
        for t in readers: t.join()
        self.assertEqual(errors, [])
        for tenant, n in ((A, 60), (B, 60)):
            with tx(tenant) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.route_matrix_cache WHERE origin_stop=%s AND dest_stop=%s", (o, d)).fetchone()[0], n)


if __name__ == '__main__':
    unittest.main()
