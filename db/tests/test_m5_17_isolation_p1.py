"""WP-M5-17 (Sicherheitsabnahme, AK 1 und 2): Isolationssuite über ALLE Mandantentabellen des P1-Umfangs unter der echten Runtime-Rolle.

Anders als `test_isolation_alle_tabellen.py` (Strukturprüfung plus Leseversuch auf überwiegend leeren Tabellen) belegt diese Suite das Verhalten an **Zeilen**: für zwei Mandanten
werden in jeder Mandantentabelle, in die sich eine gültige Zeile erzeugen lässt, Datensätze angelegt (Betriebsschritt mit Administratorzugang; Fremdschlüssel- und Zustandstrigger
sind dafür mit `session_replication_role = replica` ausgesetzt, die CHECK-Regeln gelten weiter). Danach laufen alle Angriffe ausschließlich unter der Runtime-Rolle:

- Leseisolation je Tabelle: eigener Bestand vollständig, kein Fremdbestand, ohne Kontext nichts, mit unbrauchbarem Kontext nichts oder Fehler (nie alles);
- Schreibisolation: UPDATE und DELETE fremder Zeilen ändern nichts, INSERT mit fremder `tenant_id` wird abgewiesen;
- Pool-Wiederverwendung: dieselbe Verbindung wechselt Mandanten und Kontext (auch leer) ohne Rest; parallele Anfragen zweier Mandanten über einen gemeinsamen Verbindungspool;
- Wächter „Tabelle ohne Policy“ über das gesamte Schema mit Selbsttest (eine absichtlich undichte Tabelle wird erkannt).

Tabellen, in die sich keine Zeile erzeugen ließ, werden **ausdrücklich ausgewiesen** (`UNBEFUELLT`) und nur strukturell geprüft; ihre Zahl ist nach oben begrenzt, damit die
Abdeckung nicht unbemerkt sinkt. Nicht geprüft wird hier: Verhalten der Anwendungsschicht (siehe `backend/app/.../SicherheitsabnahmeApiTest.kt`, `CrossTenantIsolationTest.kt`).
"""
import queue
import re
import sys
import threading
import unittest
import uuid
from pathlib import Path

from helpers import admin, runtime, seed_tenant, set_tenant, uid

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'infra' / 'rollout'))
from taktovia_rollout.datenbank import tabellen_ohne_policy  # noqa: E402
from test_m2_48_rls_abdeckung import abdeckung  # noqa: E402

A, B = uid(), uid()
# globale Katalogtabellen ohne Mandantenbezug haben keine tenant_id und sind hier nicht Gegenstand (der Wächter prüft sie auf Schreibrechte)
MAX_UNBEFUELLT = 60          # Obergrenze der nur strukturell geprüften Tabellen (Stand 15.11.2026: siehe Nachweis); sinkt mit besseren Generatoren
MIN_BEFUELLT = 115           # Untergrenze der an Zeilen geprüften Tabellen

# restriktive Policy `draft_visibility` (Entwürfe nur mit `app.roster_draft_access`, V40040) und Subject-Index (Lesepolicy über `app.subject`, V1060), Kontaktkanal nur zu einem sichtbaren, nicht archivierten Fahrgastkonto (V10330): der eigene Bestand ist ohne den Schalter leer
BEDINGT_SICHTBAR = {'roster_shift_change', 'membership_subject_index', 'passenger_contact'}


def mandantentabellen(c):
    return [r[0] for r in c.execute("""SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t
                                       ON t.table_schema=c.table_schema AND t.table_name=c.table_name
                                       WHERE c.table_schema='taktovia' AND c.column_name='tenant_id' AND t.table_type='BASE TABLE' ORDER BY 1""").fetchall()]


def zeile(c, t, tenant, ueberschreibung):
    """Gültigkeitsorientierte Spaltenwerte einer synthetischen Zeile (Typen, Enums, einfache CHECK-Muster)."""
    d = {}
    for n, dt, udt, nul, dflt, gen, ident in c.execute("""SELECT column_name,data_type,udt_name,is_nullable,column_default,is_generated,is_identity
                                                          FROM information_schema.columns WHERE table_schema='taktovia' AND table_name=%s ORDER BY ordinal_position""", (t,)).fetchall():
        if gen == 'ALWAYS' or ident == 'YES':
            continue
        if n == 'tenant_id':
            d[n] = tenant
            continue
        if n in ueberschreibung:
            d[n] = ueberschreibung[n]
            continue
        if dflt is not None or nul == 'YES':
            continue
        if dt == 'uuid':
            v = uuid.uuid4()
        elif dt in ('integer', 'bigint', 'smallint', 'numeric'):
            v = 1
        elif dt == 'boolean':
            v = False
        elif dt.startswith('timestamp'):
            v = '2026-02-01T00:00:00Z' if re.search(r'(ends|_to$|until|expires|_end)', n) else '2026-01-01T00:00:00Z'
        elif dt in ('jsonb', 'json', 'ARRAY'):
            v = '{}'
        elif dt == 'date':
            v = '2026-01-01'
        elif dt == 'USER-DEFINED':
            r = c.execute("SELECT e.enumlabel FROM pg_enum e JOIN pg_type ty ON ty.oid=e.enumtypid WHERE ty.typname=%s ORDER BY e.enumsortorder LIMIT 1", (udt,)).fetchone()
            v = r[0] if r else ('SRID=4326;MULTIPOLYGON(((13 51,14 51,14 52,13 51)))' if udt == 'geometry' else 'X')
        else:
            v = 'user:probe' if re.search(r'(_by$|^actor|author)', n) else 'X'
        d[n] = v
    return d


def muster_wert(df):
    """Ein Wert, der das in einer CHECK-Definition erkennbare Muster erfüllt (nur die in diesem Schema vorkommenden Formen), sonst None."""
    regeln = [('{64}', 'a' * 64), ('payment\\.', 'payment.test'), ('SPE-', 'SPE-2026-000001'), ('LK_', 'LK_PII'), ('(UTC|', 'Europe/Berlin'),
              ('[A-Z][A-Z0-9_]', 'TEST_CODE'), ('(\\.[a-z', 'a.b'), ('[a-z][a-z0-9_]{0', 'abc'), ('[a-z0-9][a-z0-9._-]', 'v1'),
              ('length(', 'abcdefgh'), ('case_year', 2026), ('number_year', 2026), ('[A-Za-z0-9._:-]{8,128}', 'command-ref-1')]
    for kennung, wert in regeln:
        if kennung in df:
            return wert
    return None


def einfuegen(c, t, tenant):
    """Legt eine Zeile an (Administratorverbindung, Trigger ausgesetzt). Rückgabe True bei Erfolg; CHECK-Verstöße werden bis zu sechsmal mit passenden Literalen wiederholt."""
    ueber = {}
    for _ in range(6):
        d = zeile(c, t, tenant, ueber)
        try:
            with c.transaction():
                c.execute(f"INSERT INTO taktovia.{t}({','.join(d)}) VALUES ({','.join(['%s'] * len(d))})", list(d.values()))  # sql-lint: ok Tabellen- und Spaltennamen aus dem Systemkatalog
            return True
        except Exception as e:
            m = re.search(r'check constraint "([^"]+)"', str(e))
            if not m:
                return False
            df = c.execute("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=%s AND conrelid=%s::regclass", (m.group(1), f'taktovia.{t}')).fetchone()[0]
            cols = [x for x in re.findall(r'\b([a-z_]+)\b', df) if x in d and x != 'tenant_id' and x not in ueber]
            lit = re.findall(r"'([^']+)'::text", df)
            if not cols:
                return False
            col = cols[0]
            wert = muster_wert(df)
            if wert is not None:
                ueber[col] = wert
            elif lit and '~' not in df:
                ueber[col] = lit[0]
            elif re.search(r'(user|service|system|partner)', df):
                ueber[col] = 'user:probe'
            else:
                return False
    return False


def zaehle_admin(c, t, tenant):
    return c.execute(f'SELECT count(*) FROM taktovia.{t} WHERE tenant_id=%s', (tenant,)).fetchone()[0]  # sql-lint: ok Tabellenname aus dem Systemkatalog


def kann_lesen(c, t):
    return c.execute("SELECT has_table_privilege('taktovia_runtime', %s::regclass, 'SELECT')", (f'taktovia.{t}',)).fetchone()[0]


def lecks(conn, t, eigener, fremder, erwartet_eigen):
    """Alle Lese- und Schreibangriffe auf eine Tabelle unter der Runtime-Verbindung `conn`; Rückgabe: Liste der Befunde (leer = dicht)."""
    befunde = []
    set_tenant(conn, eigener)
    try:
        fremd = conn.execute(f'SELECT count(*) FROM taktovia.{t} WHERE tenant_id <> %s', (eigener,)).fetchone()[0]  # sql-lint: ok Tabellenname aus dem Systemkatalog
        eigen = conn.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0]  # sql-lint: ok Tabellenname aus dem Systemkatalog
        if fremd:
            befunde.append(f'{t}: {fremd} Fremdzeilen sichtbar')
        if t in BEDINGT_SICHTBAR:   # eigener Bestand nur mit Zusatzschalter oder Subject-Schlüssel sichtbar: hier gilt nur „nie mehr als eigen“
            if eigen > erwartet_eigen:
                befunde.append(f'{t}: eigener Bestand {eigen} über erwartet {erwartet_eigen}')
        elif eigen != erwartet_eigen:
            befunde.append(f'{t}: eigener Bestand {eigen}, erwartet {erwartet_eigen}')
    finally:
        conn.rollback()
    return befunde


class IsolationP1(unittest.TestCase):
    tabellen = []
    befuellt = {}      # Tabelle -> (Zeilen A, Zeilen B)
    unbefuellt = []

    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm5-17-a'); seed_tenant(B, 'm5-17-b')
        with admin() as c:
            cls.tabellen = [t for t in mandantentabellen(c)]
            c.execute('SET session_replication_role = replica')
            for t in cls.tabellen:
                na = einfuegen(c, t, A) and einfuegen(c, t, B)
                (cls.befuellt.__setitem__(t, (zaehle_admin(c, t, A), zaehle_admin(c, t, B))) if na else cls.unbefuellt.append(t))
            c.commit()

    @classmethod
    def tearDownClass(cls):
        with admin() as c:
            c.execute('SET session_replication_role = replica')
            for t in cls.tabellen:
                c.execute(f'DELETE FROM taktovia.{t} WHERE tenant_id IN (%s,%s)', (A, B))  # sql-lint: ok Tabellenname aus dem Systemkatalog
            c.execute('DELETE FROM taktovia.tenant WHERE id IN (%s,%s)', (A, B))
            c.commit()

    # --- Abdeckung -------------------------------------------------------------------------------------------------------------------------------------

    def test_abdeckung_wird_ausgewiesen_und_faellt_nicht_unbemerkt(self):
        print(f'\nIsolationssuite P1: {len(self.befuellt)} Tabellen an Zeilen geprüft, {len(self.unbefuellt)} nur strukturell (UNBEFUELLT): {", ".join(sorted(self.unbefuellt))}')
        self.assertGreaterEqual(len(self.befuellt), MIN_BEFUELLT)
        self.assertLessEqual(len(self.unbefuellt), MAX_UNBEFUELLT)
        self.assertEqual(len(self.befuellt) + len(self.unbefuellt), len(self.tabellen))
        for must in ('booking', 'vehicle', 'membership', 'config_revision', 'passenger_private', 'staff_shift', 'defect_case', 'time_event', 'expense_document'):
            self.assertIn(must, self.tabellen, f'{must}: Mandantentabelle fehlt im Schema')

    # --- Wächter ---------------------------------------------------------------------------------------------------------------------------------------

    def test_waechter_tabelle_ohne_policy_meldet_nichts(self):
        with admin() as c:
            self.assertEqual(tabellen_ohne_policy(c), [])

    def test_jede_mandantentabelle_erfuellt_die_konvention_mit_using_und_with_check(self):
        with admin() as c:
            ausnahmen = {'membership_subject_index'}   # Lesepolicy über den Subject-Schlüssel statt tenant_id (V1060, Anmeldung vor Mandantenkontext), im Wächter begründet
            bad = {t: p for t in self.tabellen if t not in ausnahmen and (p := abdeckung(c, t))}
            self.assertEqual(bad, {})

    def test_waechter_erkennt_absichtlich_undichte_tabellen(self):
        with admin() as c:
            c.execute('DROP TABLE IF EXISTS taktovia.zz_m5_17_offen, taktovia.zz_m5_17_alles')
            try:
                c.execute('CREATE TABLE taktovia.zz_m5_17_offen (tenant_id uuid NOT NULL, id uuid, PRIMARY KEY (tenant_id, id))')   # RLS fehlt
                c.execute('CREATE TABLE taktovia.zz_m5_17_alles (tenant_id uuid NOT NULL, id uuid, PRIMARY KEY (tenant_id, id))')
                c.execute('ALTER TABLE taktovia.zz_m5_17_alles ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY')
                c.execute('CREATE POLICY alles ON taktovia.zz_m5_17_alles FOR ALL USING (true) WITH CHECK (true)')   # Policy ohne tenant_id
                befunde = tabellen_ohne_policy(c)
                self.assertTrue(any(b.startswith('zz_m5_17_offen: RLS nicht aktiviert') for b in befunde), befunde)
                self.assertTrue(any(b.startswith('zz_m5_17_alles: keine Policy auf tenant_id') for b in befunde), befunde)
                self.assertIn('keine Policy auf tenant_id', abdeckung(c, 'zz_m5_17_alles'))
            finally:
                c.execute('DROP TABLE IF EXISTS taktovia.zz_m5_17_offen, taktovia.zz_m5_17_alles')

    def test_leckpruefung_erkennt_eine_tabelle_ohne_rls_an_zeilen(self):
        """Die Verhaltensprüfung selbst muss ein Leck finden können: Tabelle ohne RLS mit Zeilen beider Mandanten, Leserecht für die Runtime-Rolle."""
        with admin() as c:
            c.execute('DROP TABLE IF EXISTS taktovia.zz_m5_17_leck')
            try:
                c.execute('CREATE TABLE taktovia.zz_m5_17_leck (tenant_id uuid NOT NULL, id uuid PRIMARY KEY)')
                c.execute('GRANT SELECT ON taktovia.zz_m5_17_leck TO taktovia_runtime')
                c.execute('INSERT INTO taktovia.zz_m5_17_leck VALUES (%s,%s),(%s,%s)', (A, uid(), B, uid()))
                conn = runtime()
                try:
                    befunde = lecks(conn, 'zz_m5_17_leck', A, B, 1)
                finally:
                    conn.close()
                self.assertTrue(any('Fremdzeilen sichtbar' in b for b in befunde), befunde)
            finally:
                c.execute('DROP TABLE IF EXISTS taktovia.zz_m5_17_leck')

    # --- Leseisolation ---------------------------------------------------------------------------------------------------------------------------------

    def test_leseisolation_jeder_befuellten_tabelle_unter_der_runtime_rolle(self):
        with admin() as c:
            lesbar = [t for t in self.befuellt if kann_lesen(c, t)]
        self.assertGreaterEqual(len(lesbar), 60, 'zu wenige lesbare befüllte Tabellen')
        conn = runtime()
        try:
            befunde = []
            for t in lesbar:
                na, nb = self.befuellt[t]
                befunde += lecks(conn, t, A, B, na) + lecks(conn, t, B, A, nb)
            self.assertEqual(befunde, [])
            for t in lesbar:   # ohne Kontext: nichts
                set_tenant(conn, None)
                self.assertEqual(conn.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0], 0, f'{t}: ohne Mandantenkontext sichtbar')  # sql-lint: ok Tabellenname aus dem Systemkatalog
                conn.rollback()
        finally:
            conn.close()

    def test_unbrauchbarer_kontext_liefert_nichts_oder_fehler_nie_alles(self):
        with admin() as c:
            lesbar = [t for t in self.befuellt if kann_lesen(c, t)][:40]
        conn = runtime()
        try:
            for t in lesbar:
                for wert in ('kein-uuid', '00000000-0000-0000-0000-000000000000', f'{A},{B}', f"{A}' OR '1'='1"):
                    try:
                        conn.execute("SELECT set_config('app.tenant_id', %s, true)", (wert,))
                        n = conn.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0]  # sql-lint: ok Tabellenname aus dem Systemkatalog
                        self.assertEqual(n, 0, f'{t}: Kontext {wert!r} zeigt Zeilen')
                    except Exception as e:   # Fehler (ungültiges UUID-Literal) ist zulässig; es darf nur nie etwas geliefert werden
                        self.assertNotIsInstance(e, AssertionError)
                    finally:
                        conn.rollback()
        finally:
            conn.close()

    # --- Schreibisolation ------------------------------------------------------------------------------------------------------------------------------

    def test_fremde_zeilen_lassen_sich_weder_aendern_noch_loeschen_noch_einschleusen(self):
        with admin() as c:
            kandidaten = [t for t in self.befuellt if kann_lesen(c, t)]
        conn = runtime()
        angriffe = {'UPDATE': 0, 'DELETE': 0, 'INSERT': 0}
        abgewiesen_rls = 0
        try:
            for t in kandidaten:
                for verb, sql in (('UPDATE', f'UPDATE taktovia.{t} SET tenant_id = tenant_id WHERE tenant_id = %s'),  # sql-lint: ok Tabellenname aus dem Systemkatalog
                                  ('DELETE', f'DELETE FROM taktovia.{t} WHERE tenant_id = %s')):  # sql-lint: ok Tabellenname aus dem Systemkatalog
                    set_tenant(conn, A)
                    try:
                        n = conn.execute(sql, (B,)).rowcount
                        self.assertEqual(n, 0, f'{t}: {verb} fremder Zeilen betraf {n}')
                        angriffe[verb] += 1
                    except AssertionError:
                        raise
                    except Exception:
                        pass   # fehlendes Recht oder Trigger: ebenfalls abgewehrt
                    finally:
                        conn.rollback()
                # Einschleusen: gültige Zeile mit fremder tenant_id unter dem Kontext von A
                with admin() as c:
                    d = zeile(c, t, B, {})
                set_tenant(conn, A)
                try:
                    conn.execute(f"INSERT INTO taktovia.{t}({','.join(d)}) VALUES ({','.join(['%s'] * len(d))})", list(d.values()))  # sql-lint: ok Tabellen- und Spaltennamen aus dem Systemkatalog
                    self.fail(f'{t}: INSERT mit fremder tenant_id wurde angenommen')
                except AssertionError:
                    raise
                except Exception as e:
                    angriffe['INSERT'] += 1
                    abgewiesen_rls += 'row-level security' in str(e)
                finally:
                    conn.rollback()
        finally:
            conn.close()
        with admin() as c:   # Fremdbestand unverändert
            for t in kandidaten:
                self.assertEqual(zaehle_admin(c, t, B), self.befuellt[t][1], f'{t}: Bestand von B verändert')
        print(f'\nSchreibangriffe abgewehrt: {angriffe}, davon INSERT durch RLS-Policy: {abgewiesen_rls}')
        self.assertGreater(abgewiesen_rls, 20, 'zu wenige INSERT-Angriffe wurden von der RLS-Policy selbst abgewiesen')

    # --- Pool ------------------------------------------------------------------------------------------------------------------------------------------

    def test_eine_verbindung_wechselt_mandanten_und_leeren_kontext_ohne_rest(self):
        with admin() as c:
            tabs = [t for t in self.befuellt if kann_lesen(c, t)][:30]
        conn = runtime()
        try:
            for tenant in (A, B, None, A, None, B, A):
                for t in tabs:
                    set_tenant(conn, tenant)
                    n = conn.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0]  # sql-lint: ok Tabellenname aus dem Systemkatalog
                    erwartet = 0 if tenant is None else self.befuellt[t][0 if tenant == A else 1]
                    self.assertEqual(n, erwartet, f'{t}: Kontext {tenant}')
                    conn.commit()
                rest = conn.execute("SELECT coalesce(nullif(current_setting('app.tenant_id', true), ''), '-')").fetchone()[0]
                self.assertEqual(rest, '-', 'nach COMMIT bleibt kein Mandantenkontext')
                conn.commit()
        finally:
            conn.close()

    def test_parallele_anfragen_zweier_mandanten_ueber_einen_gemeinsamen_verbindungspool(self):
        """Drei Verbindungen, zwölf Threads (je sechs je Mandant): jede Transaktion holt eine beliebige Verbindung, setzt ihren Kontext und liest; nie Fremdbestand, nie Rest eines früheren Kontexts."""
        with admin() as c:
            tabs = [t for t in self.befuellt if kann_lesen(c, t)][:12]
        pool = queue.Queue()
        verbindungen = [runtime() for _ in range(3)]
        for v in verbindungen:
            pool.put(v)
        fehler = []

        def lauf(eigener, fremder, idx):
            for i in range(60):
                conn = pool.get()
                try:
                    rest = conn.execute("SELECT coalesce(nullif(current_setting('app.tenant_id', true), ''), '-')").fetchone()[0]
                    if rest != '-':
                        fehler.append(('Kontextrest', rest))
                    conn.rollback()
                    set_tenant(conn, eigener)
                    t = tabs[(i + idx) % len(tabs)]
                    fremd = conn.execute(f'SELECT count(*) FROM taktovia.{t} WHERE tenant_id = %s', (fremder,)).fetchone()[0]  # sql-lint: ok Tabellenname aus dem Systemkatalog
                    eigen = conn.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0]  # sql-lint: ok Tabellenname aus dem Systemkatalog
                    if fremd or eigen != self.befuellt[t][0 if eigener == A else 1]:
                        fehler.append((t, eigener, fremd, eigen))
                    conn.commit()
                except Exception as e:  # noqa: BLE001
                    fehler.append(('Ausnahme', repr(e)))
                    conn.rollback()
                finally:
                    pool.put(conn)

        ts = [threading.Thread(target=lauf, args=((A, B) if i % 2 == 0 else (B, A)) + (i,)) for i in range(12)]
        [t.start() for t in ts]; [t.join(120) for t in ts]
        [v.close() for v in verbindungen]
        self.assertEqual(fehler, [])


if __name__ == '__main__':
    unittest.main()
