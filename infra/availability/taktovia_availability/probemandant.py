"""Probemandant für die synthetischen Proben (WP-M5-05): eigener Mandant (`tenant.synthetic = true`, V50050), eigener Betreiber, Dienst, Probefahrzeug und Umlauf.

Die Probeschicht ist auf 23 Stunden begrenzt (Regel „höchstens 24 Stunden je Schicht“); die tägliche Erneuerung gehört zum Probenbetrieb (WP-M5-28).
Wird vom Plattformbetrieb mit Administratorverbindung angelegt (die Runtime-Rolle sieht und schreibt nur den eigenen Mandanten). Nichts hier kann Kapazität eines Echtmandanten berühren:
alle Zeilen tragen `tenant_id` des Probemandanten, Fremdschlüssel sind mandantengebunden, RLS trennt. Die Zugangsdaten des Probekontos entstehen nicht hier (Identitätsdienst, Secret-Speicher).
OP-28 (Probemandant in Produktion zulässig?) ist offen: das Anlegen in Produktion ist Gegenstand von WP-M5-28, nicht dieses Pakets.
"""
import json
import uuid
from datetime import datetime, timedelta, timezone


def anlegen(conn, name: str = "synthetic-probe", sitze: int = 4, tag: datetime | None = None) -> dict:
    """Legt den Probemandanten samt Probefahrzeug an (Administratorverbindung, eigene Transaktion des Aufrufers). Liefert alle Kennungen."""
    t = uuid.uuid4()
    le, org, cfg, svc, veh, shift, run = (uuid.uuid4() for _ in range(7))
    beginn = (tag or datetime.now(timezone.utc)).replace(microsecond=0)
    conn.execute("INSERT INTO taktovia.currency(code,exponent) VALUES ('EUR',2) ON CONFLICT DO NOTHING")
    conn.execute("INSERT INTO taktovia.tenant(id,name,status,synthetic) VALUES (%s,%s,'ACTIVE',true)", (t, name))
    conn.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'Probe-Rechtstraeger','DE')", (t, le))
    conn.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,'Probe-Betreiber',%s,'{TRANSPORT_COMPANY}')", (t, org, le, f"probe-{t}"))
    conn.execute("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) "
                 "VALUES (%s,%s,'SERVICE',%s,1,'1','{}'::jsonb,%s,'system:probemandant','Probemandant')", (t, cfg, svc, beginn - timedelta(days=1)))
    conn.execute("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (%s,%s,%s,'Probedienst','Europe/Berlin',%s,'system:probemandant')", (t, svc, org, cfg))
    conn.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (%s,%s,'probe',%s::jsonb,'PROBE-1')", (t, veh, json.dumps({"seats": sitze, "wheelchair": 0})))
    conn.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,'Europe/Berlin',1,'PLANNED')", (t, shift, beginn, beginn + timedelta(hours=23)))
    conn.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'ACTIVE')", (t, run, veh, shift))
    return {"tenant": t, "legal_entity": le, "operator": org, "service": svc, "vehicle": veh, "shift": shift, "run": run}
