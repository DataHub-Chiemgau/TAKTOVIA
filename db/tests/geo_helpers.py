"""Testhilfen des Clusters Dienste/Geo (WP-M2-03 bis -06): Dienst anlegen, Haltepunkt- und Gebietszeilen einfügen."""
import json
from helpers import uid


def make_service(c, tenant, tz='Europe/Berlin'):
    """Rechtsträger, Betreiber, Konfigurationsrevision und Dienst im Mandanten; liefert die Dienst-ID."""
    le, op, svc, cfg = uid(), uid(), uid(), uid()
    c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'LE','DE')", (tenant, le))
    c.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,'Betreiber',%s,'{TRANSPORT_COMPANY}')",
              (tenant, op, le, f'f-{op}'))
    c.execute("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) "
              "VALUES (%s,%s,'SERVICE',%s,1,'1','{}','2026-01-01','user:test','Test')", (tenant, cfg, svc))
    c.execute("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (%s,%s,%s,'Rufbus',%s,%s,'user:test')",
              (tenant, svc, op, tz, cfg))
    return svc


def square(x0, y0, size=0.1):
    """Quadrat als WKT-MultiPolygon (lon/lat)."""
    x1, y1 = x0 + size, y0 + size
    return f'MULTIPOLYGON((({x0} {y0},{x1} {y0},{x1} {y1},{x0} {y1},{x0} {y0})))'
