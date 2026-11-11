"""Mandantengebundener, versionsgeprüfter Cache für Routingergebnisse (WP-M2-10).

Der Schlüssel enthält Mandant, Start, Ziel, Profil und Kartendatenversion. Zusätzlich prüft ``get``
die im Eintrag gespeicherte Version: ein Eintrag mit anderer
Version als der aktuellen wird verworfen und nie geliefert (auch wenn ein anderer Speicher den
Schlüssel ohne Version abbildet). Der Worker hält nur diesen
flüchtigen Zwischenspeicher; der führende Cache liegt im Kern (PostgreSQL ``route_matrix_cache``,
Redis nur flüchtig).
"""

from __future__ import annotations

import re
import threading
from dataclasses import dataclass
from typing import Protocol

from .port import Cell

_PART = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


@dataclass(frozen=True)
class CacheKey:
    tenant_id: str
    origin: str
    destination: str
    profile: str
    map_version: str

    def __post_init__(self) -> None:
        for name in ("tenant_id", "origin", "destination", "profile", "map_version"):
            if not _PART.match(getattr(self, name)):
                raise ValueError(f"Cache-Schlüsselanteil {name} ungültig oder leer")


@dataclass(frozen=True)
class CacheEntry:
    cell: Cell
    map_version: str  # Version, mit der der Wert berechnet wurde


class RouteCache(Protocol):
    def get(self, key: CacheKey) -> CacheEntry | None: ...

    def put(self, key: CacheKey, entry: CacheEntry) -> None: ...


class InMemoryRouteCache:
    """Thread-sicherer Zwischenspeicher je Prozess."""

    def __init__(self) -> None:
        self._data: dict[CacheKey, CacheEntry] = {}
        self._lock = threading.Lock()
        self.discarded = 0

    def get(self, key: CacheKey) -> CacheEntry | None:
        with self._lock:
            return self._data.get(key)

    def put(self, key: CacheKey, entry: CacheEntry) -> None:
        with self._lock:
            self._data[key] = entry

    def drop(self, key: CacheKey) -> None:
        with self._lock:
            self._data.pop(key, None)
            self.discarded += 1

    def invalidate_other_versions(self, tenant_id: str, map_version: str) -> int:
        """Neuer Kartenstand: alle Einträge des Mandanten mit anderer Version entfernen."""
        with self._lock:
            stale = [
                k for k in self._data if k.tenant_id == tenant_id and k.map_version != map_version
            ]
            for k in stale:
                del self._data[k]
            return len(stale)
