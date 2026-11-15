"""Aufräumprüfung nach jedem Negativszenario (WP-M5-24, LH-6.20-QAT-004): prüft für einen Mandanten, dass keine Ressource hängt. Reine Leseabfragen über den Administratorzugang,
unabhängig von der Anwendung.

Befunde (jeder muss leer sein):
  - verwaiste `capacity_hold`: `HELD` mit abgelaufener Frist (nach Karenz des Ablauf-Zeitgebers), oder ein `HELD`/`CONSUMED` Hold einer stornierten oder nicht erschienenen Buchung;
  - `operation` mit überfälliger Lease: `RUNNING` und `lease_until` in der Vergangenheit (nach Karenz);
  - `payment_intent` in unbestimmtem Zustand (`CAPTURE_UNKNOWN`, `RECONCILING`) ohne offene Abgleichsoperation;
  - unzugestellte `outbox`-Einträge (weder veröffentlicht noch Dead-Letter) nach Karenz;
  - Fahrtenplan mit Stopps stornierter Buchungen in der aktuellen Planrevision.
Klärfälle (unbestimmte Zahlungen mit Abgleichsauftrag, `MANUAL_REVIEW`-Operationen, `RECONCILING`-Buchungen) sind KEIN Befund, werden aber getrennt ausgewiesen: sie bleiben sichtbar und werden nicht als Erfolg
oder Fehler verkürzt."""
import time

KARENZ_S = 20


def pruefen(welt, tenant_id, karenz_s=KARENZ_S, warten_s=100):
    """Wiederholt die Prüfung bis zum Ablauf von `warten_s` (die Aufräumprozesse laufen als Zeitgeber der Anwendung; ein beim Neustart beanspruchter Outbox-Eintrag wird nach der Lease des Relays von 60 s
    erneut zugestellt, deshalb 100 s); liefert den letzten Bericht."""
    ende = time.time() + warten_s
    while True:
        bericht = _einmal(welt, tenant_id, karenz_s)
        if bericht["ok"] or time.time() >= ende:
            return bericht
        time.sleep(2)


def _einmal(welt, t, karenz_s):
    q = lambda c, sql, *a: c.execute(sql, a).fetchall()   # noqa: E731
    with welt.admin() as c:
        b = {
            "verwaiste_holds": q(c, """SELECT h.id::text, h.state FROM taktovia.capacity_hold h WHERE h.tenant_id = %s AND (
                   (h.state = 'HELD' AND h.expires_at < now() - make_interval(secs => %s))
                OR (h.state IN ('HELD','CONSUMED') AND EXISTS (SELECT 1 FROM taktovia.booking b WHERE b.tenant_id = h.tenant_id AND b.hold_id = h.id AND b.state IN ('CANCELED','FAILED'))))""", t, karenz_s),
            "ueberfaellige_leases": q(c, "SELECT id::text, kind FROM taktovia.operation WHERE tenant_id = %s AND status = 'RUNNING' AND lease_until < now() - make_interval(secs => %s)", t, karenz_s),
            "unbestimmte_zahlung_ohne_abgleich": q(c, """SELECT p.id::text, p.state FROM taktovia.payment_intent p WHERE p.tenant_id = %s AND p.state IN ('CAPTURE_UNKNOWN','RECONCILING') AND NOT EXISTS (
                   SELECT 1 FROM taktovia.payment_operation po JOIN taktovia.operation o ON o.tenant_id = po.tenant_id AND o.id = po.operation_id
                   WHERE po.tenant_id = p.tenant_id AND po.payment_id = p.id AND o.status IN ('QUEUED','RUNNING','RECONCILING','MANUAL_REVIEW'))""", t),
            "unzugestellte_outbox": q(c, "SELECT event_id::text, event_type FROM taktovia.outbox WHERE tenant_id = %s AND published_at IS NULL AND dead_lettered_at IS NULL AND recorded_at < now() - make_interval(secs => %s)", t, karenz_s),
            "stopps_stornierter_buchungen": q(c, """SELECT b.id::text, r.id::text FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id = b.tenant_id AND h.id = b.hold_id
                   JOIN taktovia.vehicle_run r ON r.tenant_id = h.tenant_id AND r.id = h.run_id
                   JOIN taktovia.route_stop s ON s.tenant_id = r.tenant_id AND s.run_id = r.id AND s.run_revision = r.revision
                  WHERE b.tenant_id = %s AND b.state IN ('CANCELED','FAILED') AND (s.boardings @> jsonb_build_array(jsonb_build_object('booking_id', b.id::text)) OR s.alightings @> jsonb_build_array(jsonb_build_object('booking_id', b.id::text)))""", t),
        }
        klaer = {
            "zahlungen_im_abgleich": q(c, "SELECT id::text, state FROM taktovia.payment_intent WHERE tenant_id = %s AND state IN ('CAPTURE_UNKNOWN','RECONCILING')", t),
            "operationen_manuelle_pruefung": q(c, "SELECT id::text, kind FROM taktovia.operation WHERE tenant_id = %s AND status = 'MANUAL_REVIEW'", t),
            "buchungen_im_abgleich": q(c, "SELECT id::text FROM taktovia.booking WHERE tenant_id = %s AND state = 'RECONCILING'", t),
        }
    return {"ok": not any(b.values()), "befunde": {k: v for k, v in b.items() if v}, "klaerfaelle": {k: v for k, v in klaer.items() if v}}
