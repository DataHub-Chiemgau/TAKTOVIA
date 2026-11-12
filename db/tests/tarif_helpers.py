"""Testhilfen des Clusters Tarif (WP-M2-07/-08): Tarifrevision mit Regeln anlegen, prüfen, veröffentlichen (unter der Runtime-Rolle)."""
from helpers import uid


def add_revision(c, tenant, svc, tariff_class='STANDARD', revision_no=1, valid_from='2026-01-01Z', valid_to=None, created_by='user:ersteller', state='ENTWURF'):
    """Legt einen Entwurf mit einer Festpreisregel an und führt ihn bis [state] fort (Prüfer user:pruefer, Veröffentlichender user:pruefer)."""
    rid = uid()
    c.execute("INSERT INTO taktovia.tariff_revision(tenant_id,id,service_id,tariff_class,revision_no,valid_from,valid_to,currency,reason,created_by) "
              "VALUES (%s,%s,%s,%s,%s,%s,%s,'EUR','Test',%s)", (tenant, rid, svc, tariff_class, revision_no, valid_from, valid_to, created_by))
    add_rule(c, tenant, rid, 1)
    if state in ('GEPRUEFT', 'VEROEFFENTLICHT'):
        c.execute("UPDATE taktovia.tariff_revision SET state='GEPRUEFT', reviewed_by='user:pruefer', reviewed_at=clock_timestamp() WHERE tenant_id=%s AND id=%s", (tenant, rid))
    if state == 'VEROEFFENTLICHT':
        publish(c, tenant, rid)
    return rid


def add_rule(c, tenant, revision_id, rule_no, rule_type='FESTPREIS', amount=500, zone_from=None, zone_to=None, rate=None):
    rid = uid()
    c.execute("INSERT INTO taktovia.tariff_rule(tenant_id,id,revision_id,rule_no,rule_type,amount_minor,zone_from,zone_to,rate_per_km,tax_rule_revision) "
              "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,'DE-2026-1')", (tenant, rid, revision_id, rule_no, rule_type, amount, zone_from, zone_to, rate))
    return rid


def publish(c, tenant, revision_id, by='user:pruefer'):
    c.execute("UPDATE taktovia.tariff_revision SET state='VEROEFFENTLICHT', published_by=%s, published_at=clock_timestamp() WHERE tenant_id=%s AND id=%s", (by, tenant, revision_id))
