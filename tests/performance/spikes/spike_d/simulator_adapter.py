"""Adapter vom PSP-Port auf den PSP-Simulator (HTTP). Ergebnisse darüber sind SIMULATOR-Ergebnisse, kein PSP-Nachweis."""
from taktovia_testkit.jsonhttp import NoResponse, http_call

from .psp_port import PaymentView, PspPort, Rejected, Unknown


class SimulatorAdapter(PspPort):
    name = "psp-simulator"

    def __init__(self, base_url, timeout=3.0):
        self.base, self.timeout = base_url.rstrip("/"), timeout

    def _call(self, method, path, body=None, key=None, write=True):
        try:
            status, data, headers, _ = http_call(method, self.base + path, body, {"Idempotency-Key": key} if key else {}, self.timeout)
        except NoResponse as e:
            if write:
                raise Unknown(str(e)) from e
            raise Unknown(str(e)) from e
        if status >= 500:
            raise Unknown(f"HTTP {status}")
        if status >= 400:
            raise Rejected((data or {}).get("code", f"HTTP_{status}"), status, (data or {}).get("message", ""))
        return self._view(data, headers.get("idempotent-replayed") == "true")

    @staticmethod
    def _view(d, replayed):
        return PaymentView(d["payment_id"], d["provider_ref"], d["merchant_ref"], d["state"], d["currency"], d["authorized_minor"], d["captured_minor"],
                           d["refunded_minor"], d.get("dispute_state"), d.get("operations", []), replayed, d)

    def authorize(self, merchant_ref, amount_minor, currency, payment_method, idempotency_key, operation_ref=None):
        body = {"merchant_ref": merchant_ref, "amount_minor": amount_minor, "currency": currency, "payment_method": payment_method}
        if operation_ref:
            body["operation_ref"] = operation_ref
        return self._call("POST", "/psp/v1/payments", body, idempotency_key)

    def capture(self, payment_id, amount_minor, idempotency_key, operation_ref=None):
        body = {"amount_minor": amount_minor}
        if operation_ref:
            body["operation_ref"] = operation_ref
        return self._call("POST", f"/psp/v1/payments/{payment_id}/capture", body, idempotency_key)

    def void(self, payment_id, idempotency_key, operation_ref=None):
        return self._call("POST", f"/psp/v1/payments/{payment_id}/void", {"operation_ref": operation_ref} if operation_ref else {}, idempotency_key)

    def refund(self, payment_id, amount_minor, idempotency_key, operation_ref=None):
        body = {"amount_minor": amount_minor}
        if operation_ref:
            body["operation_ref"] = operation_ref
        return self._call("POST", f"/psp/v1/payments/{payment_id}/refund", body, idempotency_key)

    def status_by_merchant_ref(self, merchant_ref):
        return self._call("GET", f"/psp/v1/payments?merchant_ref={merchant_ref}", write=False)

    def status(self, payment_id):
        return self._call("GET", f"/psp/v1/payments/{payment_id}", write=False)
