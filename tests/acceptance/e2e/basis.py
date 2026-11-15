"""Gemeinsame Modulfixtur der E2E-Testmodule: Welt und Wächter je Testmodul (eine Anwendungsinstanz je Modul, Start dauert etwa eine halbe Minute)."""
import os
import unittest

from .referenzwelt import Referenzwelt, jar_vorhanden, JAR
from .waechter import Waechter


class Modul:
    welt: Referenzwelt = None
    waechter: Waechter = None


def hochfahren(dbname=None, **kw):
    if not jar_vorhanden():
        if os.environ.get("TAKTOVIA_REQUIRE_APP_JAR") == "true":
            raise AssertionError(f"{JAR} fehlt (TAKTOVIA_REQUIRE_APP_JAR=true): cd backend && ./gradlew :app:bootJar")
        raise unittest.SkipTest(f"{JAR} fehlt: cd backend && ./gradlew :app:bootJar (nicht ausgeführt)")
    if dbname:
        kw["dbname"] = dbname
    Modul.welt = Referenzwelt(**kw).starten()
    Modul.waechter = Waechter(Modul.welt.welt)
    Modul.waechter.installieren()
    return Modul


def herunterfahren():
    if Modul.waechter:
        Modul.waechter.entfernen()
    if Modul.welt:
        Modul.welt.stoppen()
    Modul.welt = Modul.waechter = None
