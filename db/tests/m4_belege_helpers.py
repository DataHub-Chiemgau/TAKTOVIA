"""Gemeinsame Testhilfen für die Beleg-Datenbanktests (WP-M4-22 bis -27): Rechtsträger, Ticket und Beleg unter der Runtime-Rolle."""
from helpers import admin, tx, uid

SHA = 'a' * 64


def legal_entity(tenant):
    le = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'Test GmbH','DE')", (tenant, le))
    return le


def ticket(c, tenant, le, channel=None, kind=None, source_ref=None, sender_verified=None, state='ISSUED', tid=None):
    tid = tid or uid()
    cols, vals = '', []
    for col, v in (('source_channel', channel), ('document_kind', kind), ('source_ref', source_ref), ('sender_verified', sender_verified)):
        if v is not None:
            cols += f',{col}'; vals.append(v)
    c.execute(f"INSERT INTO taktovia.upload_ticket(tenant_id,id,legal_entity_id,purpose,declared_mime,declared_size,declared_sha256,quarantine_key,state,issued_by,expires_at{cols}) "
              f"VALUES (%s,%s,%s,'RECEIPT','image/jpeg',1000,%s,%s,%s,'u-1',now() + interval '15 minutes'{',%s' * len(vals)})", (tenant, tid, le, SHA, f'receipts/quarantine/{tid}', state, *vals))
    return tid


def document(c, tenant, le, tid, state='QUARANTINED', **extra):
    """Beleg zu einem Ticket; `extra`: weitere Spalten (z. B. source_channel, integrity_state)."""
    did = uid()
    cols = ''.join(f',{k}' for k in extra)
    c.execute(f"INSERT INTO taktovia.expense_document(tenant_id,id,legal_entity_id,object_key,original_sha256,mime,state,revision,created_by,original_received_at,upload_ticket_id,size_bytes{cols}) "
              f"VALUES (%s,%s,%s,%s,%s,'image/jpeg',%s,1,'u-1',now(),%s,1000{',%s' * len(extra)})", (tenant, did, le, f'receipts/originals/{did}', SHA, state, tid, *extra.values()))
    return did


def stored_document(tenant, le, **extra):
    """Beleg im Zustand STORED (Quarantäne bestanden) samt Ticket; liefert die Beleg-ID."""
    with tx(tenant) as c:
        t = ticket(c, tenant, le, **{k: extra.pop(k) for k in ('channel', 'kind', 'source_ref', 'sender_verified') if k in extra})
        d = document(c, tenant, le, t, **extra)
    with tx(tenant) as c:
        c.execute("UPDATE taktovia.expense_document SET state='STORED', scan_engine='clamd', scanned_at=now() WHERE id=%s", (d,))
    return d
