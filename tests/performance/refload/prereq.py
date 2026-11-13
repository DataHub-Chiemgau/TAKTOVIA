"""Voraussetzungen der Lasttests gegen die echte Anwendung: Opt-in `TAKTOVIA_PERF_RUN=1`, Jar, Java, Datenbank. Fehlen sie, ist der Test **übersprungen** (mit Grund) – mit `TAKTOVIA_REQUIRE_DB=true` (CI) **fehlgeschlagen**,
damit ein Lauf ohne Voraussetzungen nie als grün gilt."""
from __future__ import annotations

import os
import unittest

from .appproc import available


def require_app() -> None:
    if os.environ.get("TAKTOVIA_PERF_RUN") != "1":
        raise unittest.SkipTest("Lasttest gegen die echte Anwendung nur mit TAKTOVIA_PERF_RUN=1 (dauert Minuten, braucht das bootJar und die Datenbankumgebung)")
    reason = available()
    if reason is None and not os.environ.get("TAKTOVIA_DB_ADMIN_PASSWORD"):
        reason = "TAKTOVIA_DB_ADMIN_PASSWORD nicht gesetzt (Administratorzugang für den Testaufbau)"
    if reason:
        if os.environ.get("TAKTOVIA_REQUIRE_DB", "").lower() == "true":
            raise AssertionError(f"Voraussetzung fehlt (TAKTOVIA_REQUIRE_DB=true): {reason}")
        raise unittest.SkipTest(reason)
