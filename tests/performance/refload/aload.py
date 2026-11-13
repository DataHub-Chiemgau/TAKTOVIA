"""Asynchroner Lastgenerator (nur Standardbibliothek): HTTP/1.1 mit Keep-Alive je Sitzung, Messung der Antwortzeit je Anfrage ab Senden bis zum vollständig gelesenen Antwortkörper
(serverseitige Zeit plus lokale Schleife; der Generator läuft auf derselben Maschine, die Netzzeit ist vernachlässigbar, die CPU-Konkurrenz nicht, siehe Berichtsgrenzen).
Geschlossene Sitzungen (Think-Time) und offene Ankunftsrate für Buchungssitzungen. Perzentile nach Nearest-Rank wie `perfkit.stats`."""
from __future__ import annotations

import asyncio
import json
import random
import time
from collections import defaultdict
from dataclasses import dataclass, field


@dataclass
class Rec:
    t: float            # Sekunden seit Laufbeginn (Sendezeitpunkt)
    endpoint: str
    tenant: str
    status: int         # 0 = Transportfehler/Zeitüberschreitung
    ms: float
    retry_after: str | None = None
    tag: str = ""


class Recorder:
    def __init__(self) -> None:
        self.recs: list[Rec] = []
        self.t0 = time.monotonic()
        self.warmup_s = 0.0

    def add(self, r: Rec) -> None:
        self.recs.append(r)

    def measured(self) -> list[Rec]:
        return [r for r in self.recs if r.t >= self.warmup_s]


class Conn:
    """Eine Keep-Alive-Verbindung (eine Sitzung)."""

    def __init__(self, host: str, port: int, timeout: float = 30.0) -> None:
        self.host, self.port, self.timeout = host, port, timeout
        self.r: asyncio.StreamReader | None = None
        self.w: asyncio.StreamWriter | None = None

    async def _open(self) -> None:
        self.r, self.w = await asyncio.wait_for(asyncio.open_connection(self.host, self.port), self.timeout)

    async def close(self) -> None:
        if self.w is not None:
            self.w.close()
            try:
                await self.w.wait_closed()
            except Exception:  # noqa: BLE001
                pass
        self.r = self.w = None

    async def request(self, method: str, path: str, headers: dict, body: bytes | None = None) -> tuple[int, dict, bytes]:
        if self.w is None:
            await self._open()
        h = {"Host": f"{self.host}:{self.port}", "Connection": "keep-alive", **headers}
        if body is not None:
            h["Content-Length"] = str(len(body))
        head = f"{method} {path} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in h.items()) + "\r\n"
        try:
            self.w.write(head.encode() + (body or b""))
            await self.w.drain()
            return await asyncio.wait_for(self._read(), self.timeout)
        except BaseException:
            await self.close()
            raise

    async def _read(self) -> tuple[int, dict, bytes]:
        line = await self.r.readline()
        if not line:
            raise ConnectionError("Verbindung geschlossen")
        status = int(line.split()[1])
        headers = {}
        while True:
            l = await self.r.readline()
            if l in (b"\r\n", b"\n", b""):
                break
            k, _, v = l.decode("latin-1").partition(":")
            headers[k.strip().lower()] = v.strip()
        if headers.get("transfer-encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size = int((await self.r.readline()).split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    await self.r.readline()
                    break
                chunks.append(await self.r.readexactly(size))
                await self.r.readline()
            body = b"".join(chunks)
        else:
            n = int(headers.get("content-length", "0"))
            body = await self.r.readexactly(n) if n else b""
        if headers.get("connection", "").lower() == "close":
            await self.close()
        return status, headers, body


class Client:
    """Sitzungsclient mit Messung: jede Anfrage erzeugt genau einen Messsatz."""

    def __init__(self, rec: Recorder, host: str, port: int, tenant: str, token_fn, timeout: float = 30.0) -> None:
        self.rec, self.tenant, self.token_fn = rec, tenant, token_fn
        self.conn = Conn(host, port, timeout)
        self._tok, self._tok_at = "", 0.0

    async def close(self) -> None:
        await self.conn.close()

    def token(self) -> str:
        if time.monotonic() - self._tok_at > 200:          # Tokens leben 300 s
            self._tok, self._tok_at = self.token_fn(), time.monotonic()
        return self._tok

    async def call(self, endpoint: str, method: str, path: str, body=None, if_match: str | None = None, idem: bool = True, tag: str = "") -> tuple[int, dict, dict | list | None]:
        h = {"Authorization": f"Bearer {self.token()}", "Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
        if method != "GET" and idem:
            h["Idempotency-Key"] = f"l-{random.getrandbits(64):016x}{random.getrandbits(64):016x}"
        if if_match is not None:
            h["If-Match"] = if_match if if_match.startswith('"') else f'"{if_match}"'
        t = time.monotonic()
        try:
            status, hd, raw = await self.conn.request(method, path, h, data)
        except Exception:  # noqa: BLE001 - Transportfehler zählt als Fehler (status 0)
            self.rec.add(Rec(t - self.rec.t0, endpoint, self.tenant, 0, (time.monotonic() - t) * 1000, tag=tag))
            return 0, {}, None
        ms = (time.monotonic() - t) * 1000
        self.rec.add(Rec(t - self.rec.t0, endpoint, self.tenant, status, ms, hd.get("retry-after"), tag))
        try:
            js = json.loads(raw) if raw else None
        except ValueError:
            js = None
        return status, hd, js


# --- Auswertung --------------------------------------------------------------------------------------------------------------------------------

def percentile(sorted_ms: list[float], p: float) -> float:
    import math
    return sorted_ms[max(0, math.ceil(p / 100 * len(sorted_ms)) - 1)]


def summarize(recs: list[Rec], ok=lambda r: 200 <= r.status < 300) -> dict:
    """Kennzahlen einer Messmenge. `errors` = Transportfehler und 5xx; `shed_429` = kontrollierte Abweisung (429); 4xx ohne 429 zählen als erwartete fachliche Antworten nur, wenn der Aufrufer sie als ok wertet."""
    if not recs:
        return {"n": 0}
    lat = sorted(r.ms for r in recs)
    errs = [r for r in recs if r.status == 0 or r.status >= 500]
    shed = [r for r in recs if r.status == 429]
    dur = max(r.t for r in recs) - min(r.t for r in recs) or 1e-9
    return {"n": len(recs), "min_ms": round(lat[0], 3), "mean_ms": round(sum(lat) / len(lat), 3), "p50_ms": round(percentile(lat, 50), 3), "p90_ms": round(percentile(lat, 90), 3),
            "p95_ms": round(percentile(lat, 95), 3), "p99_ms": round(percentile(lat, 99), 3), "max_ms": round(lat[-1], 3), "error_count": len(errs), "shed_429": len(shed),
            "error_rate": round(len(errs) / len(recs), 5), "rps": round(len(recs) / dur, 2)}


def group(recs: list[Rec], key) -> dict[str, list[Rec]]:
    out: dict[str, list[Rec]] = defaultdict(list)
    for r in recs:
        out[key(r)].append(r)
    return dict(out)
