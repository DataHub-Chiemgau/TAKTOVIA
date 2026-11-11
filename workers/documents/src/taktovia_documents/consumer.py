"""Queue-Consumer-Skelett (WP-M0-07): nimmt Nachrichten entgegen, verarbeitet noch nichts.

Der Transport (RabbitMQ-Queue oder synchroner Aufruf) ist ein offener Vertragspunkt (M4).
Das Skelett definiert nur die Form eines Handlers und lehnt unbekannte Aufgaben begründet ab.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Task:
    task_id: str
    kind: str  # "ocr" | "malware_scan" | "kosit_validate" (M4)
    payload: bytes


Handler = Callable[[Task], None]


class Consumer:
    def __init__(self) -> None:
        self._handlers: dict[str, Handler] = {}

    def register(self, kind: str, handler: Handler) -> None:
        self._handlers[kind] = handler

    def handle(self, task: Task) -> None:
        handler = self._handlers.get(task.kind)
        if handler is None:
            raise LookupError(f"kein Handler für Aufgabenart {task.kind!r}")
        handler(task)
