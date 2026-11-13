"""Sättigungsmessung während eines Laufs: CPU und Speicher des Anwendungsprozesses (aus /proc), CPU der Maschine, Datenbankverbindungen, Sperrwartende, Warteschlangentiefe des Outbox-Verteilers."""
from __future__ import annotations

import os
import threading
import time


def _proc_cpu_ticks(pid: int) -> int:
    with open(f"/proc/{pid}/stat") as f:
        parts = f.read().rsplit(")", 1)[1].split()
    return int(parts[11]) + int(parts[12])          # utime + stime


def _machine_cpu() -> tuple[int, int]:
    with open("/proc/stat") as f:
        v = [int(x) for x in f.readline().split()[1:]]
    return sum(v) - v[3] - (v[4] if len(v) > 4 else 0), sum(v)     # busy (ohne idle/iowait), total


class Sampler:
    def __init__(self, pid: int, admin_factory, db_name: str, tenants: list[str], interval: float = 2.0) -> None:
        self.pid, self.admin_factory, self.db_name, self.tenants, self.interval = pid, admin_factory, db_name, tenants, interval
        self.samples: list[dict] = []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self.hz = os.sysconf("SC_CLK_TCK")
        self.cpus = os.cpu_count() or 1

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> list[dict]:
        self._stop.set(); self._thread.join(10)
        return self.samples

    def _run(self) -> None:
        admin = self.admin_factory()
        last_t, last_p, (last_b, last_tot) = time.monotonic(), _proc_cpu_ticks(self.pid), _machine_cpu()
        while not self._stop.wait(self.interval):
            try:
                now = time.monotonic(); p = _proc_cpu_ticks(self.pid); b, tot = _machine_cpu()
                rss = int(next(l for l in open(f"/proc/{self.pid}/status") if l.startswith("VmRSS")).split()[1]) // 1024
                conns, waiting = admin.execute("SELECT count(*) FILTER (WHERE usename = 'taktovia_runtime'), count(*) FILTER (WHERE wait_event_type = 'Lock') FROM pg_stat_activity WHERE datname = %s", (self.db_name,)).fetchone()
                outbox = admin.execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ANY(%s::uuid[]) AND published_at IS NULL AND dead_lettered_at IS NULL", (self.tenants,)).fetchone()[0]
                self.samples.append({"t": round(now, 2), "app_cpu_pct_of_one_core": round(100.0 * (p - last_p) / self.hz / (now - last_t), 1), "machine_cpu_pct": round(100.0 * (b - last_b) / max(1, tot - last_tot), 1),
                                     "app_rss_mb": rss, "db_runtime_connections": conns, "db_lock_waiting": waiting, "outbox_pending": outbox})
                last_t, last_p, last_b, last_tot = now, p, b, tot
            except Exception as e:  # noqa: BLE001 - Sampler darf den Lauf nie stören
                self.samples.append({"t": round(time.monotonic(), 2), "error": f"{type(e).__name__}: {e}"})
        admin.close()
