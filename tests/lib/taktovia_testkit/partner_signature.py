"""Referenzprüfer für Partnerereignisse nach Kap. 05 §5.6 (Testbibliothek, KEIN Produktionscode).

Dient dazu nachzuweisen, dass die von der Sandbox erzeugten Fehlermodi von einem korrekten Empfänger abgelehnt werden
müssen, und dass gültige Ereignisse (auch während der Schlüsselrotation) angenommen werden.
"""
import hashlib
import hmac
import json

MAX_BODY_BYTES = 65536   # Planungswert der Sandbox; die produktive Grenze legt der Detailvertrag fest
TOLERANCE_S = 300


def sign(secret: str, timestamp, raw_body: bytes) -> str:
    return hmac.new(secret.encode(), str(timestamp).encode() + b"." + raw_body, hashlib.sha256).hexdigest()


def verify(headers: dict, raw_body: bytes, now_epoch: int, keys: dict, binding: dict, max_body=MAX_BODY_BYTES):
    """-> (http_status, grund). headers: Kleinschreibung. binding: {partner_id, tenant_id, allowed_types}."""
    if len(raw_body) > max_body:
        return 413, "BODY_TOO_LARGE"
    key_id = headers.get("x-partner-key-id")
    if key_id not in keys:
        return 401, "UNKNOWN_KEY_ID"
    try:
        ts = int(headers.get("x-partner-timestamp", ""))
    except ValueError:
        return 401, "MALFORMED_TIMESTAMP"
    sig = headers.get("x-partner-signature", "")
    if not sig.startswith("v1="):
        return 401, "MALFORMED_SIGNATURE"
    if abs(now_epoch - ts) > TOLERANCE_S:
        return 401, "TIMESTAMP_OUT_OF_WINDOW"
    if not hmac.compare_digest(sig[3:], sign(keys[key_id], ts, raw_body)):
        return 401, "BAD_SIGNATURE"
    try:
        ev = json.loads(raw_body)
    except ValueError:
        return 400, "MALFORMED_BODY"
    if ev.get("event_type") not in binding["allowed_types"]:
        return 422, "EVENT_TYPE_NOT_ALLOWED"
    if ev.get("tenant_id") != binding["tenant_id"] or ev.get("partner_id") != binding["partner_id"]:
        return 403, "TENANT_BINDING_MISMATCH"
    return 200, "VERIFIED"


class Inbox:
    """Inbox-Deduplikation nach Kap. 05 §5.6: Schlüssel (partner_id,event_id) plus Payloadhash."""

    def __init__(self):
        self.seen = {}
        self.versions = {}
        self.effects = 0

    def accept(self, ev: dict, raw_body: bytes):
        k = (ev["partner_id"], ev["event_id"])
        h = hashlib.sha256(raw_body).hexdigest()
        if k in self.seen:
            return (200, "ALREADY_RECEIVED") if self.seen[k] == h else (409, "PAYLOAD_CHANGED_QUARANTINE")
        self.seen[k] = h
        agg, ver = ev["aggregate_ref"], ev["aggregate_version"]
        last = self.versions.get(agg, 0)
        if ver <= last:
            return 202, "STALE_IGNORED"          # älteres Ereignis überschreibt keinen neueren Zustand
        self.versions[agg] = ver
        self.effects += 1
        return 202, "ACCEPTED_VERSION_GAP_RECONCILE" if ver > last + 1 else "ACCEPTED"
