"""Entwurf des internen PSP-Ports (Kap. 03 §4.2; künftig backend/adapters/psp). Der PSP-Simulator (WP-M0-23) und jeder Kandidatenadapter
implementieren dieselbe Schnittstelle. Wegwerf-Entwurf der Spike-Arbeit, nicht Produktivcode.

Regeln des Ports (aus Kap. 03 §4.2 und den Leitlinien 3, 5, 6):
  * Beträge sind Ganzzahlen in kleinster Einheit.
  * Jede schreibende Operation trägt einen vom Aufrufer vergebenen Idempotenzschlüssel; die Wiederholung mit gleichem Schlüssel erzeugt
    keine zweite Wirkung.
  * Ein Timeout oder abgebrochener Aufruf ist UNKNOWN (kein Misserfolg). Aufgelöst wird nur über die Statusabfrage mit der ursprünglichen
    Referenz (resolve_unknown); ein zweiter Charge auf Verdacht ist verboten.
  * Aufrufe gehören nie in eine offene SQL-Transaktion (Leitlinie 3); das ist Sache des Aufrufers, der Port kennt keine Datenbank.
"""
from dataclasses import dataclass, field


class Unknown(Exception):
    """Ergebnis einer schreibenden Operation unbestimmt (Timeout, Verbindungsabbruch, 5xx). Entspricht CAPTURE_UNKNOWN/RECONCILING beim Aufrufer."""


class Rejected(Exception):
    """Definitive Ablehnung durch den Anbieter oder den Port (mit Grundcode)."""

    def __init__(self, code, http_status=None, message=""):
        super().__init__(f"{code}: {message}")
        self.code, self.http_status, self.message = code, http_status, message


@dataclass
class PaymentView:
    payment_id: str
    provider_ref: str
    merchant_ref: str
    state: str                      # Zustand des Anbieters, auf den Automaten aus Kap. 03 §4.2 abgebildet
    currency: str
    authorized_minor: int
    captured_minor: int
    refunded_minor: int
    dispute_state: str = None
    operations: list = field(default_factory=list)
    replayed: bool = False          # Antwort stammt aus einer Idempotenz-Wiederholung
    raw: dict = field(default_factory=dict)


class PspPort:
    """Schnittstelle. Implementierungen: SimulatorAdapter (simulator_adapter.py); Kandidaten: candidate_adapter_template.py."""
    name = "abstract"

    def authorize(self, merchant_ref, amount_minor, currency, payment_method, idempotency_key, operation_ref=None) -> PaymentView: raise NotImplementedError
    def capture(self, payment_id, amount_minor, idempotency_key, operation_ref=None) -> PaymentView: raise NotImplementedError
    def void(self, payment_id, idempotency_key, operation_ref=None) -> PaymentView: raise NotImplementedError
    def refund(self, payment_id, amount_minor, idempotency_key, operation_ref=None) -> PaymentView: raise NotImplementedError
    def status_by_merchant_ref(self, merchant_ref) -> PaymentView: raise NotImplementedError
    def status(self, payment_id) -> PaymentView: raise NotImplementedError


def resolve_unknown(port: PspPort, merchant_ref, expect_op, expect_operation_ref=None, retries=3):
    """Löst ein UNKNOWN über die Statusabfrage mit der ursprünglichen Referenz auf.
    -> (True, PaymentView) wenn die Operation beim Anbieter wirksam wurde, (False, PaymentView|None) wenn nicht, sonst Unknown (Statusabfrage nicht möglich)."""
    last = None
    for _ in range(retries):
        try:
            view = port.status_by_merchant_ref(merchant_ref)
        except Rejected as e:
            if e.code == "UNKNOWN_REFERENCE" and expect_op == "authorize":
                return False, None
            raise
        except Unknown as e:
            last = e
            continue
        done = [o for o in view.operations if o["kind"] == expect_op and (expect_operation_ref is None or o.get("operation_ref") == expect_operation_ref)]
        return bool(done), view
    raise Unknown("Statusabfrage nicht verfügbar; manueller Klärfall (Kap. 03 §4.2)") from last
