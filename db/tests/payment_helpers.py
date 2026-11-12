"""Testhilfen des Clusters Zahlung (WP-M2-30/-31): Journey mit Quote und Zahlung (`payment_intent`) unter der Runtime-Rolle anlegen."""
from helpers import uid
from geo_helpers import make_service
from tarif_helpers import add_revision


def make_journey(c, tenant, amount=1000):
    """Versuch, Konfigurationsrevision, Tarifrevision, Quote, Fahrgast und Journey; liefert (journey_id, quote_id)."""
    qa, jid, cfg, q, pid = uid(), uid(), uid(), uid(), uid()
    c.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel) VALUES (%s,%s,now(),'WEB')", (tenant, qa))
    svc = make_service(c, tenant)
    cfg = c.execute("SELECT config_revision_id FROM taktovia.service WHERE id=%s", (svc,)).fetchone()[0]
    trev = add_revision(c, tenant, svc, state='VEROEFFENTLICHT')
    c.execute("""INSERT INTO taktovia.fare_quote(tenant_id,id,attempt_id,currency,pay_at_platform_minor,external_known_minor,external_unknown_leg_ids,
                 expires_at,tariff_revision_id,config_revision_id,snapshot,content_hash)
                 VALUES (%s,%s,%s,'EUR',%s,0,'{}',now(),%s,%s,'{}',%s)""", (tenant, q, qa, amount, trev, cfg, 'a' * 64))
    c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,now())", (tenant, pid, str(uid())))
    c.execute("INSERT INTO taktovia.journey(tenant_id,id,passenger_id,quote_id,state,created_at,currency) VALUES (%s,%s,%s,%s,'HELD',now(),'EUR')", (tenant, jid, pid, q))
    return jid, q


def make_payment(c, tenant, state='CREATED', authorized=0, captured=0, refunded=0, requested=1000, provider='simpsp', with_ref=False):
    """Zahlung im gewünschten Ausgangszustand (direkt eingefügt: nur Testaufbau); liefert (payment_id, journey_id, quote_id)."""
    jid, q = make_journey(c, tenant, requested)
    pay = uid()
    c.execute("""INSERT INTO taktovia.payment_intent(tenant_id,id,journey_id,provider,provider_ref,state,currency,authorized_minor,captured_minor,refunded_minor,
                 quote_id,requested_minor,merchant_ref) VALUES (%s,%s,%s,%s,%s,%s,'EUR',%s,%s,%s,%s,%s,%s)""",
              (tenant, pay, jid, provider, f"psp_{pay}" if (with_ref or state not in ('CREATED', 'AUTHORIZATION_FAILED')) else None, state, authorized, captured, refunded, q, requested, f"m-{pay}"))
    return pay, jid, q
