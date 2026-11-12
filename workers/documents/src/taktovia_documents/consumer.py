"""Queue-Consumer-Skelett (WP-M0-07): nimmt Nachrichten entgegen, verarbeitet noch nichts.

Der Transport (RabbitMQ-Queue oder synchroner Aufruf) ist ein offener Vertragspunkt (M4).
Das Skelett definiert nur die Form eines Handlers und lehnt unbekannte Aufgaben begründet ab.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass

from .diagnose import Telemetry, describe_exception


@dataclass(frozen=True, slots=True)
class Task:
    task_id: str
    kind: str  # "ocr" | "malware_scan" | "kosit_validate" (M4)
    payload: bytes
    traceparent: str | None = None  # W3C-Kontext des auslösenden Kernaufrufs (WP-M5-03)


Handler = Callable[[Task], None]


class Consumer:
    def __init__(self, telemetry: Telemetry | None = None) -> None:
        self._handlers: dict[str, Handler] = {}
        self.telemetry = telemetry

    def register(self, kind: str, handler: Handler) -> None:
        self._handlers[kind] = handler

    def handle(self, task: Task) -> None:
        handler = self._handlers.get(task.kind)
        if handler is None:
            if self.telemetry:
                self.telemetry.metrics.inc(
                    "taktovia_documents_tasks_total", {"kind": "unknown", "result": "rejected"}
                )
            raise LookupError(f"kein Handler für Aufgabenart {task.kind!r}")
        tel = self.telemetry
        if tel is None:
            handler(task)
            return
        kind = task.kind if task.kind.isidentifier() and len(task.kind) <= 32 else "unknown"
        t0, result = time.monotonic(), "ok"
        try:
            with tel.span(
                f"documents {kind}",
                task.traceparent,
                {"kind": kind, "task_id": task.task_id, "size_bytes": len(task.payload)},
            ):
                handler(task)
        except Exception as e:
            result = "error"
            tel.log.error(
                "Aufgabe fehlgeschlagen: %s",
                describe_exception(e),
                extra={"kind": kind, "task_id": task.task_id},
            )
            raise
        finally:
            tel.log.info(
                "Aufgabe bearbeitet",
                extra={
                    "kind": kind,
                    "task_id": task.task_id,
                    "result": result,
                    "size_bytes": len(task.payload),
                },
            )
            tel.metrics.inc("taktovia_documents_tasks_total", {"kind": kind, "result": result})
            tel.metrics.observe(
                "taktovia_documents_task_seconds",
                time.monotonic() - t0,
                {"kind": kind, "result": result},
            )
