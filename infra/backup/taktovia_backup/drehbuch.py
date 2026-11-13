"""Ausführung des Wiederanlaufdrehbuchs mit Protokoll und Freigabeschranke (WP-M5-14; Beschreibung: tests/performance/wiederanlauf-drehbuch-m5.md).

Die Schritte laufen in fester Reihenfolge; jeder Schritt wird mit Zeitpunkt und Ergebnis protokolliert (Struktur der Vorlage `tests/performance/templates/restore-drehbuch-vorlage.json`).
Messdefinitionen (Kap. 08 §8.6, Detailvertrag LH-6.19-NFR-007):
  RPO-Messpunkt = Zeitpunkt des letzten wiederherstellbaren Commits; gemessenes RPO = Ausfallzeitpunkt minus dieser Zeitpunkt.
  RTO-Ende      = Zeitpunkt der funktionsfähigen End-to-End-Probe; gemessenes RTO = dieser Zeitpunkt minus Ausfallzeitpunkt.
Freigabeschranke: Schritt 7 (Worker und Ereignisstrom aktivieren, d. h. die Umgebung freigeben) wird verweigert (`ReleaseBlocked`), solange (a) die Konsistenzprüfungen einschließlich der
Wirkung des Lösch-/Sperrjournals nicht ohne blockierenden Befund bestanden sind oder (b) der Abgleich externer Vorgänge nicht bestätigt ist. Die Schranke ist Code, keine Absichtserklärung.
Zuständigkeit und Freigabeberechtigte stehen im Drehbuch (Rollen aus .github/CODEOWNERS und docs/betrieb; Personen nach Beschluss D-12).
"""
import json
from datetime import datetime, timezone
from pathlib import Path

RPO_ZIEL_MIN = 15.0
RTO_ZIEL_H = 2.0

STEPS = {
    1: "Ausfall feststellen und Zeitpunkt festhalten",
    2: "Schreibzugriffe stoppen, Ereignisstrom und Worker anhalten",
    3: "Datenbank aus Basissicherung und WAL auf den Zeitpunkt wiederherstellen; letzten wiederherstellbaren Commit bestimmen",
    4: "Migrationsstand und Rollen/RLS prüfen",
    5: "Lösch-/Sperrjournal wieder einspielen und Konsistenzprüfungen ausführen (blockiert die Freigabe)",
    6: "Externe Buchungen und Zahlungen mit Providerreferenzen abgleichen, bevor unsichere Schritte wiederholt werden",
    7: "Freigabe: Worker und Ereignisstrom wieder aktivieren",
    8: "Funktionsfähige End-to-End-Probe (Buchungs- und Operatorprobe)",
}


class ReleaseBlocked(Exception):
    """Die Freigabe ist gesperrt (Konsistenzbefund, Journal nicht wirksam oder Abgleich nicht bestätigt)."""


class OutOfOrder(Exception):
    pass


def _iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).isoformat()


class Drehbuch:
    def __init__(self, environment: str, executed_by: str, clock=lambda: datetime.now(timezone.utc)):
        self.clock = clock
        self.next_no = 1
        self.gate_consistency = False
        self.gate_reconciled = False
        self.p = {
            "drehbuch_version": "m5-1.0", "status": "LAUF", "umgebung": {"name": environment, "verantwortlich": executed_by},
            "ausfallzeitpunkt": None, "letzter_wiederherstellbarer_commit": {"zeitpunkt": None, "referenz": None}, "zeitpunkt_e2e_probe": None,
            "abgleich_externe_vorgaenge": [], "referenzziele": {"rpo_min": RPO_ZIEL_MIN, "rto_h": RTO_ZIEL_H, "provisional": True},
            "schritte": [{"nr": n, "schritt": s, "zeitpunkt": None, "ergebnis": None} for n, s in STEPS.items()],
            "konsistenz": None, "bewertung": {"rpo_min_gemessen": None, "rto_h_gemessen": None, "rpo_erfuellt": None, "rto_erfuellt": None},
        }

    def _row(self, nr):
        return self.p["schritte"][nr - 1]

    def _begin(self, nr):
        if nr != self.next_no:
            raise OutOfOrder(f"Schritt {nr} vor Schritt {self.next_no}: die Reihenfolge des Drehbuchs ist verbindlich")

    def _done(self, nr, result):
        row = self._row(nr)
        row["zeitpunkt"] = _iso(self.clock()); row["ergebnis"] = result
        self.next_no = nr + 1

    def _fail(self, nr, e):
        row = self._row(nr)
        row["zeitpunkt"] = _iso(self.clock()); row["ergebnis"] = f"FEHLGESCHLAGEN: {type(e).__name__}: {str(e)[:200]}"

    def ausfall(self, at: datetime | None = None, note: str = "Primärdatenbank nicht verfügbar"):
        self._begin(1)
        self.p["ausfallzeitpunkt"] = _iso(at or self.clock())
        self._done(1, note)

    def stoppen(self, fn=lambda: "gestoppt"):
        self._begin(2)
        self._done(2, str(fn()))

    def wiederherstellen(self, fn):
        """`fn()` stellt wieder her und liefert {"last_commit": datetime, "referenz": str}."""
        self._begin(3)
        try:
            r = fn()
        except Exception as e:
            self._fail(3, e); raise
        self.p["letzter_wiederherstellbarer_commit"] = {"zeitpunkt": _iso(r["last_commit"]), "referenz": r.get("referenz")}
        self._done(3, f"wiederhergestellt bis {_iso(r['last_commit'])}")

    def struktur_pruefen(self, fn):
        self._begin(4)
        try:
            ok, detail = fn()
        except Exception as e:
            self._fail(4, e); raise
        if not ok:
            self._fail(4, RuntimeError(detail)); raise ReleaseBlocked(f"Struktur-/Migrationsprüfung: {detail}")
        self._done(4, detail)

    def konsistenz(self, report):
        """`report` ist ein consistency.Report; blockierende Befunde oder fehlende Journalwirkung sperren die Freigabe, protokolliert wird in jedem Fall."""
        self._begin(5)
        self.p["konsistenz"] = report.as_dict()
        if not report.release_allowed:
            self._fail(5, RuntimeError("blockierende Befunde: " + ", ".join(report.as_dict()["violations"] or ["Journalwirkung nicht belegt"])))
            self.p["status"] = "FREIGABE_GESPERRT"
            raise ReleaseBlocked("Konsistenzprüfung nicht bestanden: " + ", ".join(report.as_dict()["violations"]))
        self.gate_consistency = True
        self._done(5, f"{len(report.checks)} Prüfungen, keine blockierenden Befunde")

    def abgleich(self, fn):
        """`fn()` liefert {"done": bool, "by": str, "vorgaenge": [ {...} ]}; nur `done=True` öffnet die Freigabe."""
        self._begin(6)
        try:
            r = fn()
        except Exception as e:
            self._fail(6, e); raise
        self.p["abgleich_externe_vorgaenge"] = r.get("vorgaenge", [])
        if not r.get("done"):
            self._fail(6, RuntimeError("Abgleich nicht bestätigt")); raise ReleaseBlocked("Abgleich externer Vorgänge nicht bestätigt")
        self.gate_reconciled = True
        self._done(6, f"{len(r.get('vorgaenge', []))} Vorgänge abgeglichen ({r.get('by', '?')})")

    def freigeben(self, fn=lambda: "Worker und Ereignisstrom aktiv"):
        self._begin(7)
        if not (self.gate_consistency and self.gate_reconciled):
            self._fail(7, ReleaseBlocked("Freigabe gesperrt")); self.p["status"] = "FREIGABE_GESPERRT"
            raise ReleaseBlocked("Freigabe gesperrt: Konsistenzprüfung und Abgleich müssen bestanden sein")
        self._done(7, str(fn()))

    def e2e_probe(self, fn):
        self._begin(8)
        try:
            ok = fn()
        except Exception as e:
            self._fail(8, e); raise
        if not ok:
            self._fail(8, RuntimeError("Probe fehlgeschlagen")); raise RuntimeError("End-to-End-Probe fehlgeschlagen")
        self.p["zeitpunkt_e2e_probe"] = _iso(self.clock())
        self._done(8, "Probe erfolgreich")
        self.p["status"] = "ABGESCHLOSSEN"
        self._bewerten()

    def _bewerten(self):
        t_fail = datetime.fromisoformat(self.p["ausfallzeitpunkt"])
        t_commit = datetime.fromisoformat(self.p["letzter_wiederherstellbarer_commit"]["zeitpunkt"])
        t_probe = datetime.fromisoformat(self.p["zeitpunkt_e2e_probe"])
        rpo = max(0.0, (t_fail - t_commit).total_seconds() / 60.0)
        rto = (t_probe - t_fail).total_seconds() / 3600.0
        self.p["bewertung"] = {"rpo_min_gemessen": round(rpo, 3), "rto_h_gemessen": round(rto, 5), "rpo_erfuellt": rpo <= RPO_ZIEL_MIN, "rto_erfuellt": rto <= RTO_ZIEL_H}

    def save(self, path: Path) -> None:
        Path(path).write_text(json.dumps(self.p, indent=1, sort_keys=True, ensure_ascii=False))
