"""WP-M3-15 / LH-6.21-CHK-014: Prüfentwurf und versionierte Korrektureinträge in der Datenbank unter der echten Runtime-Rolle. Originalabnahme: nachträgliche Bearbeitung überschreibt
niemals die ursprünglichen Antworten. Entwurfsantworten sind bis zum Abschluss änderbar, danach nur noch append-only Nachträge mit Pflichtgrund, Version je Originalantwort
(UNIQUE) und Bezug auf die Originalantwort desselben Laufs und Schritts."""
import unittest

import psycopg

from helpers import admin, tx, uid, seed_tenant
import test_inspection_immutability as base

A = uid()
B = uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'


def run_fixture(c):
    return base.run_fixture(c, A)


def answer(c, run, code, step_id=None, status='OK'):
    return base.answer(c, run, code, step_id, status, tenant=A)


def complete(c, run):
    base.complete(c, run)


def amendment(c, run, aid, step='S_ONE', version=1, reason='Tippfehler', kind='CORRECTION', tenant=A):
    c.execute("INSERT INTO taktovia.inspection_amendment(tenant_id,id,inspection_id,step_code,answer_id,version,kind,new_answer,new_note,amended_by,reason) VALUES (%s,%s,%s,%s,%s,%s,%s,'DEFECT','Kratzer',%s,%s)",
              (tenant, uid(), run, step, aid, version, kind, ACTOR, reason))


class Nachtraege(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm315-a')
        seed_tenant(B, 'm315-b')

    def test_original_antwort_nach_abschluss_nicht_aenderbar(self):
        with tx(A) as c:
            run, tpl, ids = run_fixture(c)
            aid = answer(c, run, 'S_ONE', ids['S_ONE'])
            c.execute("UPDATE taktovia.inspection_answer SET answer='DEFECT', note='Entwurf' WHERE id=%s", (aid,))   # Entwurf bis zum Abschluss änderbar
            complete(c, run)
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("UPDATE taktovia.inspection_answer SET answer='OK' WHERE id=%s", (aid,))
            amendment(c, run, aid)   # der Nachtrag ist der Weg; er ändert die Originalzeile nicht
            self.assertEqual(c.execute("SELECT answer, note FROM taktovia.inspection_answer WHERE id=%s", (aid,)).fetchone(), ('DEFECT', 'Entwurf'))
            # Nachträge selbst sind append-only
            for sql in ("UPDATE taktovia.inspection_amendment SET reason='anders' WHERE answer_id=%s", "DELETE FROM taktovia.inspection_amendment WHERE answer_id=%s"):
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)):
                    with c.transaction():
                        c.execute(sql, (aid,))
        with admin() as a:
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("UPDATE taktovia.inspection_amendment SET reason='anders' WHERE answer_id=%s", (aid,))

    def test_nachtrag_nur_zu_abgeschlossenem_lauf_und_zur_antwort_desselben_laufs(self):
        with tx(A) as c:
            run, tpl, ids = run_fixture(c)
            aid = answer(c, run, 'S_ONE', ids['S_ONE'])
            with self.assertRaises(psycopg.errors.CheckViolation):   # Lauf noch offen
                with c.transaction():
                    amendment(c, run, aid)
            complete(c, run)
            other_run, _, other_ids = run_fixture(c)
            other_answer = answer(c, other_run, 'S_ONE', other_ids['S_ONE'])
            with self.assertRaises(psycopg.errors.CheckViolation):   # Originalantwort eines anderen Laufs
                with c.transaction():
                    amendment(c, run, other_answer)
            with self.assertRaises(psycopg.errors.CheckViolation):   # Schritt passt nicht zur Originalantwort
                with c.transaction():
                    amendment(c, run, aid, step='S_TWO')

    def test_nachtrag_grund_not_null_und_version_eindeutig(self):
        with tx(A) as c:
            run, tpl, ids = run_fixture(c)
            aid = answer(c, run, 'S_ONE', ids['S_ONE']); complete(c, run)
            amendment(c, run, aid, version=1); amendment(c, run, aid, version=2, kind='SUPPLEMENT')
            with self.assertRaises(psycopg.errors.UniqueViolation):   # Doppelvergabe der Version je Originalantwort
                with c.transaction():
                    amendment(c, run, aid, version=2)
            with self.assertRaises(psycopg.errors.CheckViolation):   # Version >= 1
                with c.transaction():
                    amendment(c, run, aid, version=0)
            for reason in (None, '', '   '):   # Grund ist Pflicht
                with self.assertRaises((psycopg.errors.NotNullViolation, psycopg.errors.CheckViolation), msg=repr(reason)):
                    with c.transaction():
                        amendment(c, run, aid, version=3, reason=reason)
            with self.assertRaises(psycopg.errors.CheckViolation):   # Art
                with c.transaction():
                    amendment(c, run, aid, version=3, kind='OVERWRITE')
            self.assertEqual(c.execute("SELECT array_agg(version ORDER BY version) FROM taktovia.inspection_amendment WHERE answer_id=%s", (aid,)).fetchone()[0], [1, 2])

    def test_fremder_mandant_sieht_nachtraege_nicht(self):
        with tx(A) as c:
            run, tpl, ids = run_fixture(c)
            aid = answer(c, run, 'S_ONE', ids['S_ONE']); complete(c, run); amendment(c, run, aid)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.inspection_amendment WHERE inspection_id=%s", (run,)).fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
