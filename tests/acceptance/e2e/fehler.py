"""Hilfen der Fault-Injection (WP-M5-16): Eingriffe in Datenbank, Anwendungsprozess und PSP-Simulator sowie eine leichte Grundlast. Alle Eingriffe wirken nur auf die eigene Testdatenbank und die
eigenen Prozesse dieser Welt; die Datenbank anderer Cluster wird nie berührt."""
import threading
import time

import db as dbtool

from .referenzfall import Http


class Datenbankfehler:
    def __init__(self, welt):
        self.welt = welt
        self.name = welt.dbname

    def _admin(self):
        return dbtool.admin("postgres")

    def verbindungen_kappen(self):
        """Verbindungsabriss: alle Sitzungen der Runtime-Rolle auf dieser Datenbank werden beendet (die Datenbank selbst bleibt erreichbar)."""
        with self._admin() as c:
            return c.execute("SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE datname = %s AND usename = 'taktovia_runtime'", (self.name,)).fetchone()[0]

    def ausfall(self):
        """Ausfall: keine neuen Verbindungen zur Datenbank, bestehende werden beendet."""
        with self._admin() as c:
            c.execute(f'ALTER DATABASE "{self.name}" ALLOW_CONNECTIONS false')   # sql-lint: ok Name der eigenen Testdatenbank, nur Testbetrieb
        self.verbindungen_kappen()

    def rueckkehr(self):
        with self._admin() as c:
            c.execute(f'ALTER DATABASE "{self.name}" ALLOW_CONNECTIONS true')   # sql-lint: ok Name der eigenen Testdatenbank, nur Testbetrieb


class Grundlast:
    """Leichte Hintergrundlast während eines Szenarios: ein Fahrgast fragt wiederholt Angebote an und liest seine Buchungen. Sammelt (Methode, Pfad, Status, Zeit)."""

    def __init__(self, welt, fahrgast, intervall=0.25):
        self.welt, self.fahrgast, self.intervall = welt, fahrgast, intervall
        self.ergebnisse = []
        self._stop = threading.Event()
        self._t = threading.Thread(target=self._lauf, daemon=True)

    def _lauf(self):
        http = Http(self.welt)
        n = 0
        while not self._stop.is_set():
            n += 1
            if n % 2:
                r = http("POST", "/v1/ride-offers", self.fahrgast.token, self.fahrgast.anfrage_koerper(tage=3))
                self.ergebnisse.append(("POST", "/v1/ride-offers", r.status, time.time()))
            else:
                r = http("GET", "/v1/bookings", self.fahrgast.token)
                self.ergebnisse.append(("GET", "/v1/bookings", r.status, time.time()))
            self._stop.wait(self.intervall)

    def __enter__(self):
        self._t.start()
        return self

    def __exit__(self, *a):
        self._stop.set()
        self._t.join(30)

    def statuscodes(self, seit=0.0):
        return sorted({r[2] for r in self.ergebnisse if r[3] >= seit})
