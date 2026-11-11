"""Schnittstellen-Platzhalter für OCR, Malware-Scan und KoSIT-Validator (M4).

Es gibt bewusst keine Implementierung und keinen Datenbankzugriff. Die Aufrufschnittstelle
Kern <-> Worker (Queue über RabbitMQ oder synchroner Aufruf) ist offen, siehe README.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class OcrResult:
    text: str
    confidence: float


@dataclass(frozen=True, slots=True)
class ScanVerdict:
    clean: bool
    detail: str = ""


@dataclass(frozen=True, slots=True)
class ValidationReport:
    valid: bool
    messages: tuple[str, ...] = ()


class OcrAdapter(Protocol):
    def extract(self, content: bytes, media_type: str) -> OcrResult: ...


class MalwareScanner(Protocol):
    def scan(self, content: bytes) -> ScanVerdict: ...


class KositValidator(Protocol):
    def validate(self, xml: bytes) -> ValidationReport: ...


class NotConfigured:
    """Platzhalter für alle drei Ports: bricht laut ab statt Ergebnisse vorzutäuschen."""

    def extract(self, content: bytes, media_type: str) -> OcrResult:
        raise NotImplementedError("OCR-Adapter erst in M4")

    def scan(self, content: bytes) -> ScanVerdict:
        raise NotImplementedError("Malware-Scan erst in M4")

    def validate(self, xml: bytes) -> ValidationReport:
        raise NotImplementedError("KoSIT-Validator erst in M4")
