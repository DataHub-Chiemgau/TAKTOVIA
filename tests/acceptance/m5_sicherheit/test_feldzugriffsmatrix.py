"""WP-M5-17 (Feldzugriffsprüfung): Rolle × Feldklasse (PII, Unterstützungsmerkmale, HR, Finanzen).

Die Matrix `feldzugriffsmatrix.csv` wird aus Rechtematrix, Feldrechten und Systemkatalog erzeugt (`feldzugriffsmatrix.py`); der Test erzeugt sie neu und vergleicht (Abweichung = jemand hat Rechte
geändert, ohne die Matrix zu erneuern und zu prüfen). Dazu feste Invarianten aus Kap. 01 §1.7 und dem Rollenvertrag und echte Zugriffsversuche unter den Datenbankrollen.
Das Verhalten je Operation und Rolle (403 bei „N“) prüft `RechtematrixApiTest` (Backend, gegen die echte Anwendung); die Feldausgabe `FieldProtectionTest` (backend/platform).
Voraussetzung: DB-Umgebung aus db/db.py (ohne sie übersprungen, mit TAKTOVIA_REQUIRE_DB=true Fehlschlag).
"""
import os
import sys
import unittest
import uuid
from pathlib import Path

import psycopg

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(HERE)); sys.path.insert(0, str(REPO / "db"))
import feldzugriffsmatrix as fm  # noqa: E402

HAS_DB = bool(os.environ.get("TAKTOVIA_DB_RUNTIME_PASSWORD") and os.environ.get("TAKTOVIA_DB_ADMIN_PASSWORD"))
if not HAS_DB and os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
    raise AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)")
if HAS_DB:
    import db as dbtool


def admin():
    return dbtool.admin(os.environ.get("TAKTOVIA_DB_NAME", "taktovia"))


@unittest.skipUnless(HAS_DB, "Datenbankzugang nicht gesetzt: nicht ausgeführt")
class Feldzugriff(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with admin() as c:
            cls.erzeugt = fm.alle_zeilen(c)
        cls.fest = fm.lies()

    def zeilen(self, ebene):
        return [z for z in self.erzeugt if z[0] == ebene]

    @staticmethod
    def muster(zeilen):
        """Zugriffsmuster je Rolle und Feldklasse: keiner, teilweise, alle. Die Zahlen der CSV sind Dokumentation und wachsen mit neuen Operationen/Spalten; das Muster nicht."""
        return [(e, r, k, 0 if n == 0 else 2 if n == g else 1) for e, r, k, n, g in zeilen]

    def test_matrix_entspricht_den_fuehrenden_quellen(self):
        self.assertEqual(self.muster(self.erzeugt), self.muster(self.fest),
                         "Zugriffsmuster weicht von feldzugriffsmatrix.csv ab: `python tests/acceptance/m5_sicherheit/feldzugriffsmatrix.py` und die Änderung prüfen")

    def test_administrator_hat_auf_keine_feldklasse_zugriff(self):
        for ebene, rolle, klasse, erlaubt, _ in self.erzeugt:
            if rolle == "tenant_admin":
                self.assertEqual(erlaubt, 0, f"{ebene}/{klasse}: Administrator darf nie Zugriff haben (Kap. 01 §1.7)")
        klassen = {z[2] for z in self.zeilen("API") if z[1] == "tenant_admin"}
        self.assertEqual(klassen, {"PII", "HR", "FINANZ"}, "die Matrix führt die Administratorzeile für jede Datenklasse")

    def test_finanzdaten_nur_fachrollen_und_hr_nur_mit_betrieblichem_bezug(self):
        finanz = {z[1] for z in self.zeilen("API") if z[2] == "FINANZ" and z[3] > 0}
        self.assertEqual(finanz, {"driver", "finance", "workshop"}, "Finanzen: Fahrer und Werkstatt nur für den eigenen Beleg-/Kostenweg, vollständig nur die Finanzrolle")
        fin = next(z for z in self.zeilen("API") if z[1] == "finance" and z[2] == "FINANZ")
        self.assertEqual(fin[3], fin[4])
        self.assertTrue(all(z[3] < z[4] for z in self.zeilen("API") if z[2] == "FINANZ" and z[1] in ("driver", "workshop")), "Fahrer/Werkstatt nie vollständig")
        pii = {z[1] for z in self.zeilen("API") if z[2] == "PII" and z[3] > 0}
        self.assertTrue({"callcenter", "support", "dispatcher"} <= pii)
        self.assertNotIn("finance", pii); self.assertNotIn("tenant_admin", pii); self.assertNotIn("staff_planning", pii)
        self.assertNotIn("driver", pii)

    def test_unterstuetzungsmerkmale_und_kontakt_nur_ueber_ausdrueckliche_feldrechte(self):
        supp = {z[1] for z in self.zeilen("FELDRECHT") if z[2] == "SUPPORT" and z[3] > 0}
        self.assertEqual(supp, {"callcenter", "support", "dispatcher"})
        pii = {z[1] for z in self.zeilen("FELDRECHT") if z[2] == "PII" and z[3] > 0}
        self.assertEqual(pii, {"callcenter", "support"})
        for rolle in ("passenger", "driver", "tenant_admin", "finance"):
            self.assertEqual([z for z in self.zeilen("FELDRECHT") if z[1] == rolle and z[2] in ("SUPPORT", "PII") and z[3] > 0], [], rolle)

    def test_ciphertextspalten_sind_nur_fuer_den_feldmodulzugang_lesbar(self):
        with admin() as c:
            cols = [(t, col) for t, col in c.execute("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema='taktovia' AND column_name LIKE '%ciphertext%'").fetchall()]
            self.assertTrue(cols)
            for t, col in cols:
                for rolle in fm.DB_ROLLEN:
                    darf = c.execute("SELECT has_column_privilege(%s, %s::regclass, %s, 'SELECT')", (rolle, f"taktovia.{t}", col)).fetchone()[0]
                    self.assertEqual(darf, rolle == "taktovia_fields", f"{rolle} auf {t}.{col}")

    def test_zugriffsversuche_unter_den_datenbankrollen_ohne_feldrecht_scheitern(self):
        tenant = uuid.uuid4()
        with admin() as c:
            c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,%s,'ACTIVE')", (tenant, "m5-17-feld"))
        verbindungen = {"taktovia_runtime": dbtool.conn("taktovia_runtime", dbtool.env("TAKTOVIA_DB_RUNTIME_PASSWORD"), autocommit=False)}
        for rolle in ("taktovia_mod_stafftime", "taktovia_mod_docsfin", "taktovia_mod_analytics"):
            verbindungen[rolle] = dbtool.conn(rolle, dbtool.module_password(rolle, True), autocommit=False)
        try:
            for rolle, c in verbindungen.items():
                for spalte in ("support_ciphertext", "contact_ciphertext"):
                    c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))
                    with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=f"{rolle} liest {spalte}"):
                        c.execute(f"SELECT {spalte} FROM taktovia.passenger_private")  # sql-lint: ok feste Spaltennamen der Prüfliste
                    c.rollback()
                    with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=f"{rolle} schreibt {spalte}"):
                        c.execute(f"UPDATE taktovia.passenger_private SET {spalte} = %s", (b"x",))  # sql-lint: ok feste Spaltennamen der Prüfliste
                    c.rollback()
        finally:
            for c in verbindungen.values():
                c.close()
            with admin() as c:
                c.execute("DELETE FROM taktovia.tenant WHERE id=%s", (tenant,))

    def test_selbsttest_geaenderte_rechte_werden_von_der_matrix_erkannt(self):
        """Würde jemand dem Administrator ein Recht auf eine PII-Operation geben, müsste der Vergleich mit der festen Matrix und die Administratorinvariante scheitern."""
        manipuliert = [list(z) for z in self.erzeugt]
        for z in manipuliert:
            if z[:3] == ["API", "tenant_admin", "PII"]:
                z[3] = 1
        self.assertNotEqual(self.muster(manipuliert), self.muster(self.fest))
        self.assertTrue(any(z[1] == "tenant_admin" and z[3] != 0 for z in manipuliert))


if __name__ == "__main__":
    unittest.main()
