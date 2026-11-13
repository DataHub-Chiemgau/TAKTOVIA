"""WP-M5-07 (LH-6.19-NFR-012): Geldspalten sind ganzzahlig (bigint, kleinste Einheit, Kap. 03 §2.1), Zeitspalten tragen eine Zeitzone (timestamptz).

Die Prüfung `verstoesse` ist eine reine Funktion über einen Schemanamen; der Negativtest wendet sie auf ein Wegwerfschema mit absichtlich
falschen Spalten an und zeigt, dass sie dort anschlägt (kein wirkungsloser Test).
"""
import unittest
import psycopg
from helpers import admin

KERN = ('fare_quote', 'payment_intent', 'economic_event', 'allocation_line', 'journal_line')
GELD = r'(_minor$|^amount$|^price$)'
ZEIT = r'(_at$|_from$|_to$|_by$|^expires|^valid_)'



def verstoesse(c, schema, tabellen=None):
    """Liste (Tabelle, Spalte, Typ, Grund) aller Verstöße in `schema`; `tabellen=None` prüft das ganze Schema."""
    sql = """SELECT table_name, column_name, data_type FROM information_schema.columns c
             WHERE table_schema=%s AND table_name IN (SELECT table_name FROM information_schema.tables WHERE table_schema=%s AND table_type='BASE TABLE')"""
    params = [schema, schema]
    if tabellen:
        sql += " AND table_name = ANY(%s)"; params.append(list(tabellen))
    out = []
    import re
    for t, col, typ in c.execute(sql, params).fetchall():
        if re.search(GELD, col) and typ != 'bigint' and typ != 'ARRAY':
            out.append((t, col, typ, 'Geldspalte nicht bigint'))
        if typ == 'timestamp without time zone':
            out.append((t, col, typ, 'Zeitspalte ohne Zone'))
        if typ in ('time without time zone', 'time with time zone') and re.search(ZEIT, col):
            out.append((t, col, typ, 'Zeitpunktspalte ohne timestamptz'))   # `date` bleibt erlaubt: Kalendertag/Fiskalperiode ist kein Zeitpunkt
        if typ in ('real', 'double precision', 'money'):
            out.append((t, col, typ, 'Gleitkomma/money-Typ'))
    return out


class GeldZeitSpalten(unittest.TestCase):
    def test_geldspalten_bigint_zeitspalten_timestamptz(self):
        with admin() as c:
            # die fünf im Vertrag genannten Tabellen haben die erwarteten Spalten: bigint für Geld, timestamptz für Zeit
            for t in KERN:
                cols = dict(c.execute("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema='taktovia' AND table_name=%s", (t,)).fetchall())
                self.assertTrue(cols, f'{t} fehlt')
                minor = [k for k in cols if k.endswith('_minor')]
                self.assertTrue(minor, f'{t}: keine Geldspalte gefunden')
                for k in minor:
                    self.assertEqual(cols[k], 'bigint', f'{t}.{k}')
            zeit = {'fare_quote': ['expires_at'], 'payment_intent': ['coupled_at', 'authorize_by', 'created_at', 'updated_at'],
                    'economic_event': ['occurred_at', 'booked_at', 'service_from', 'service_to']}
            for t, ks in zeit.items():
                for k in ks:
                    typ = c.execute("SELECT data_type FROM information_schema.columns WHERE table_schema='taktovia' AND table_name=%s AND column_name=%s", (t, k)).fetchone()
                    self.assertEqual(typ[0], 'timestamp with time zone', f'{t}.{k}')
            # Währung: ISO-4217-Code (char(3)), der Exponent steht im Währungskatalog
            self.assertEqual(c.execute("SELECT data_type FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='currency' AND column_name='exponent'").fetchone()[0], 'smallint')
            # keine Verstöße in diesen Tabellen und im gesamten Schema (jede künftige Tabelle fällt sofort auf)
            self.assertEqual(verstoesse(c, 'taktovia', KERN), [])
            self.assertEqual(verstoesse(c, 'taktovia'), [])

    def test_pruefung_schlaegt_bei_nicht_ganzzahliger_geldspalte_und_zeit_ohne_zone_an(self):
        with admin() as c:
            c.execute('DROP SCHEMA IF EXISTS zz_geldzeit_probe CASCADE')
            try:
                c.execute('CREATE SCHEMA zz_geldzeit_probe')
                c.execute("""CREATE TABLE zz_geldzeit_probe.schlecht (id int PRIMARY KEY, price_minor numeric(12,2), tax_minor double precision,
                              booked_at timestamp, service_from timestamp without time zone, gut_minor bigint, gut_at timestamptz)""")
                got = verstoesse(c, 'zz_geldzeit_probe')
                self.assertEqual(sorted((col, grund) for _, col, _, grund in got),
                                 [('booked_at', 'Zeitspalte ohne Zone'), ('price_minor', 'Geldspalte nicht bigint'), ('service_from', 'Zeitspalte ohne Zone'),
                                  ('tax_minor', 'Geldspalte nicht bigint'), ('tax_minor', 'Gleitkomma/money-Typ')])
            finally:
                c.execute('DROP SCHEMA IF EXISTS zz_geldzeit_probe CASCADE')


if __name__ == '__main__':
    unittest.main()
