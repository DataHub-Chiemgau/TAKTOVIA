"""Abgleichbericht nach einem Restore (WP-M5-15): Buchungen, Kapazitätsholds, Zahlungen, Operationen und Sperren im Verlustfenster.

Grundsatz (Kap. 01 §1.7, Leitlinie „Unbestimmtes bleibt unbestimmt“): Zuerst wird mit dem Zahlungsdienstleister über die **Händler- bzw. Providerreferenz** abgeglichen; erst danach darf ein unsicherer
Schritt wiederholt oder ein Kunde als ungebucht gelten. Der Abgleich löst nie eine neue Autorisierung oder einen neuen Einzug aus (keine zweite Belastung, keine neue Referenz): er liest den Stand
beim Anbieter und übernimmt ihn **höchstens einmal** (Vergleich-und-Setzen auf den erwarteten lokalen Zustand; nur der Gewinner schreibt das Ereignis).
Quellen des Verlustfensters außerhalb der wiederhergestellten Datenbank: der Ereignisstrom (Messaging; in der Übung eine Ersatzdatei, `umgebung.Ereignisstrom`) und der Zahlungsanbieter selbst.

Dies ist das Werkzeug der Übung. Der Abgleichlauf des Backends (`Payments.reconcileAfterRestoreIn`, Kotlin, Operationen mit Lease und Fencing) ist der betriebliche Weg und mit
`PaymentRestoreReconcileTest` getestet; dieses Werkzeug prüft dieselben Invarianten gegen die wirklich wiederhergestellte Datenbank und weist sie je Vorgang aus.
"""
import json
import uuid
from datetime import datetime, timezone

# Reihenfolge der Zustände entlang des Automaten (nur vorwärts)
RANG = {"CREATED": 0, "REQUIRES_ACTION": 1, "AUTHORIZED": 2, "CAPTURE_PENDING": 3, "CAPTURE_UNKNOWN": 3, "RECONCILING": 3, "CAPTURED": 4}

K = "KONSISTENT"
UEBERNOMMEN = "UEBERNOMMEN"
BEIM_PSP_NICHT_VORHANDEN = "UNVERAENDERT_OFFEN_BEIM_PSP_NICHT_VORHANDEN"
LOKAL_VERLOREN = "LOKAL_VERLOREN_PSP_VORHANDEN_MANUELLE_KLAERUNG"
ANOMALIE = "ANOMALIE_LOKAL_VOR_PSP"
BUCHUNG_VERLOREN_MIT_ZAHLUNG = "BUCHUNG_VERLOREN_ZAHLUNG_VORHANDEN_KLAERUNG"
BUCHUNG_VERLOREN_OHNE_ZAHLUNG = "BUCHUNG_VERLOREN_KEINE_ZAHLUNG_KUNDE_INFORMIEREN"
SPERRE_WIEDERHERGESTELLT = "SPERRE_AUS_EREIGNISSTROM_WIEDERHERGESTELLT"
BLOCKIEREND = {ANOMALIE}


class SimPsp:
    """Lesender Zugang zum PSP-Simulator über die Händlerreferenz (Statusabfrage, Port aus WP-M2-29)."""

    def __init__(self, sim):
        self.sim = sim

    def status(self, merchant_ref: str):
        resp, _ = self.sim.execute("status", "", {"_query": {"merchant_ref": merchant_ref}}, None)
        return resp.body if resp.status == 200 else None


def adopt_provider_state(conn, tenant, merchant_ref: str, psp: dict) -> bool:
    """Übernimmt den verifizierten Stand des Anbieters in `payment_intent`: höchstens einmal (Vergleich-und-Setzen auf den gelesenen lokalen Zustand). Gibt True nur dem Gewinner.
    Nur Vorwärtsübergänge entlang des Automaten; nie ein neuer PSP-Aufruf. `conn` ist eine Verbindung unter der Runtime-Rolle; die Transaktion führt der Aufrufer."""
    conn.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))
    row = conn.execute("SELECT id, state, revision, captured_minor FROM taktovia.payment_intent WHERE tenant_id=%s AND merchant_ref=%s FOR UPDATE", (tenant, merchant_ref)).fetchone()
    if row is None:
        return False
    pid, state, rev, _ = row
    target = psp["state"]
    if target != "CAPTURED" or RANG.get(state, 99) >= RANG["CAPTURED"] or state not in ("AUTHORIZED", "CAPTURE_PENDING", "CAPTURE_UNKNOWN", "RECONCILING"):
        return False          # in diesem Stand der Übung übernimmt der Abgleich nur den Einzug (CAPTURED); andere Zustände bleiben unverändert und werden gemeldet
    if state == "AUTHORIZED":
        conn.execute("UPDATE taktovia.payment_intent SET state='CAPTURE_PENDING', revision=revision+1, updated_at=clock_timestamp() WHERE tenant_id=%s AND id=%s AND revision=%s", (tenant, pid, rev))
        rev += 1
    n = conn.execute("UPDATE taktovia.payment_intent SET state='CAPTURED', captured_minor=%s, revision=revision+1, updated_at=clock_timestamp() "
                     "WHERE tenant_id=%s AND id=%s AND revision=%s AND state IN ('CAPTURE_PENDING', 'CAPTURE_UNKNOWN', 'RECONCILING')", (psp["captured_minor"], tenant, pid, rev)).rowcount
    if n != 1:
        return False
    conn.execute("INSERT INTO taktovia.outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload,recipient_tenant_id) "
                 "VALUES (%s,%s,%s,%s,'payment.captured','1.0',clock_timestamp(),%s::jsonb,%s)", (tenant, uuid.uuid4(), pid, rev + 1, json.dumps({"merchant_ref": merchant_ref, "via": "abgleich"}), tenant))
    return True


def _isoparse(s):
    return datetime.fromisoformat(s)


def abgleichbericht(admin, runtime_conn_factory, psp: SimPsp, ereignisse: list[dict], rpo_punkt: datetime, ausfall: datetime, anwenden: bool = True) -> dict:
    """Erzeugt den Abgleichbericht für das Verlustfenster [rpo_punkt, ausfall] und wendet (anwenden=True) die sicheren Korrekturen an: Übernahme des Anbieterstandes (Einzug) und
    Wiederherstellung von Fahrzeugsperren aus dem Ereignisstrom. Alles andere wird ausgewiesen und nie stillschweigend verkürzt.

    admin: Administratorverbindung (lesen, Sperren wiederherstellen); runtime_conn_factory(): neue Verbindung unter der Runtime-Rolle (Zahlungsübernahme); ereignisse: Ereignisstrom außerhalb der Datenbank.
    Sicherheitsabstand: Ereignisse ab `rpo_punkt` minus 1 s zählen zum Fenster (Zeitgenauigkeit von Uhren)."""
    vorgaenge = []

    def add(typ, ident, ergebnis, detail="", tenant=None):
        vorgaenge.append({"typ": typ, "id": str(ident), "mandant": str(tenant) if tenant else None, "ergebnis": ergebnis, "detail": detail})

    fenster_von = rpo_punkt.timestamp() - 1.0
    im_fenster = [e for e in ereignisse if _isoparse(e["zeitpunkt"]).timestamp() >= fenster_von]
    # --- Zahlungen: lokal bekannte (nahe dem Fenster geändert oder in einem Ereignis genannt) und nur im Ereignisstrom bekannte Händlerreferenzen
    refs = {}
    for e in im_fenster:
        if e["typ"].startswith("payment.") and e.get("merchant_ref"):
            refs[e["merchant_ref"]] = e["tenant"]
    # zusätzlich jede lokal noch nicht eingezogene Zahlung (unabhängig vom Änderungszeitpunkt): ein Einzug beim Anbieter kann im Verlustfenster liegen, ohne dass die wiederhergestellte Zeile oder der Strom ihn zeigt
    for tenant, mref in admin.execute("SELECT tenant_id, merchant_ref FROM taktovia.payment_intent WHERE merchant_ref IS NOT NULL AND (updated_at >= to_timestamp(%s) OR state <> 'CAPTURED')", (fenster_von,)).fetchall():
        refs.setdefault(mref, str(tenant))
    for mref, tenant in sorted(refs.items()):
        lokal = admin.execute("SELECT state, captured_minor, booking_id FROM taktovia.payment_intent WHERE tenant_id=%s AND merchant_ref=%s", (tenant, mref)).fetchone()
        remote = psp.status(mref)
        if lokal is None and remote is None:
            add("zahlung", mref, BEIM_PSP_NICHT_VORHANDEN, "weder lokal noch beim Anbieter: nie vollzogen", tenant)
        elif lokal is None:
            add("zahlung", mref, LOKAL_VERLOREN, f"beim Anbieter {remote['state']} (erfasst {remote['captured_minor']}), lokal keine Zahlung: kein zweiter Einzug, Klärung Erstattung oder Neubuchung", tenant)
        elif remote is None:
            add("zahlung", mref, BEIM_PSP_NICHT_VORHANDEN, f"lokal {lokal[0]}, beim Anbieter keine Spur: Schritt darf nur mit derselben Referenz wiederholt werden", tenant)
        elif lokal[0] == remote["state"]:
            add("zahlung", mref, K, f"{lokal[0]}", tenant)
        elif RANG.get(lokal[0], 99) < RANG.get(remote["state"], -1):
            ok = False
            if anwenden:
                c = runtime_conn_factory()
                try:
                    ok = adopt_provider_state(c, tenant, mref, remote)
                    c.commit()
                finally:
                    c.close()
            add("zahlung", mref, UEBERNOMMEN if ok or not anwenden else K, f"lokal {lokal[0]} -> Anbieter {remote['state']} (erfasst {remote['captured_minor']})", tenant)
        else:
            add("zahlung", mref, ANOMALIE, f"lokal {lokal[0]} weiter als Anbieter {remote['state']}", tenant)
    # --- Buchungen: bestätigte Buchungen aus dem Ereignisstrom, die im wiederhergestellten Bestand fehlen
    for e in im_fenster:
        if e["typ"] != "booking.confirmed":
            continue
        tenant, bid = e["tenant"], e["booking_id"]
        row = admin.execute("SELECT b.state, h.state FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id=b.tenant_id AND h.id=b.hold_id WHERE b.tenant_id=%s AND b.id=%s", (tenant, bid)).fetchone()
        if row is not None:
            add("buchung", bid, K, f"Buchung {row[0]}, Kapazität {row[1]}", tenant)
            continue
        remote = psp.status(e["merchant_ref"]) if e.get("merchant_ref") else None
        if remote and remote["state"] in ("AUTHORIZED", "CAPTURE_PENDING", "CAPTURED"):
            add("buchung", bid, BUCHUNG_VERLOREN_MIT_ZAHLUNG, f"Buchung und Hold fehlen lokal, Zahlung beim Anbieter {remote['state']}: Kunde nicht als ungebucht markieren, bevor Erstattung oder Neubuchung entschieden ist", tenant)
        else:
            add("buchung", bid, BUCHUNG_VERLOREN_OHNE_ZAHLUNG, "Buchung und Hold fehlen lokal, keine Zahlung beim Anbieter: Kapazität ist frei, Kunde informieren", tenant)
    # --- Fahrzeugsperren aus dem Ereignisstrom (sicherheitsrelevant: eine verlorene Sperre würde das Fahrzeug wieder freigeben)
    for e in im_fenster:
        if e["typ"] != "vehicle.blocked":
            continue
        tenant, block_id = e["tenant"], e["block_id"]
        lokal = admin.execute("SELECT status FROM taktovia.vehicle_block WHERE tenant_id=%s AND id=%s", (tenant, block_id)).fetchone()
        if lokal is not None:
            add("sperre", block_id, K, f"Sperre {lokal[0]}", tenant)
            continue
        if anwenden and admin.execute("SELECT 1 FROM taktovia.vehicle WHERE tenant_id=%s AND id=%s", (tenant, e["vehicle_id"])).fetchone():
            admin.execute("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,set_by,set_at) VALUES (%s,%s,%s,'SAFETY',%s,%s,%s)",
                          (tenant, block_id, e["vehicle_id"], e.get("reason_code", "MANUAL"), "system:abgleich-nach-restore", e["zeitpunkt"]))
            add("sperre", block_id, SPERRE_WIEDERHERGESTELLT, "Sperre lag im Verlustfenster; aus dem Ereignisstrom vor der Freigabe wieder gesetzt", tenant)
        else:
            add("sperre", block_id, "SPERRE_FEHLT_FAHRZEUG_NICHT_WIEDERHERSTELLBAR", "Fahrzeug fehlt im wiederhergestellten Bestand", tenant)
    # --- Operationen: nicht abgeschlossene Operationen werden von den Workern mit derselben Kommandoreferenz fortgesetzt
    for tenant, oid, status in admin.execute("SELECT tenant_id, id, status FROM taktovia.operation WHERE status NOT IN ('SUCCEEDED','FAILED','MANUAL_REVIEW')").fetchall():
        add("operation", oid, "FORTSETZEN_MIT_GLEICHER_KOMMANDOREFERENZ", f"Status {status}", tenant)
    # --- Holds: im Verlustfenster abgelaufene oder verlorene Holds blockieren nichts; nur Bestand HELD ohne Ablauf wird gezählt
    n_held = admin.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE state='HELD'").fetchone()[0]
    je_ergebnis, je_typ = {}, {}
    for v in vorgaenge:
        je_ergebnis[v["ergebnis"]] = je_ergebnis.get(v["ergebnis"], 0) + 1
        je_typ[v["typ"]] = je_typ.get(v["typ"], 0) + 1
    blockierend = [v for v in vorgaenge if v["ergebnis"] in BLOCKIEREND]
    ausserhalb = sum(1 for v in vorgaenge if v["ergebnis"] not in (K,))
    return {"fenster": {"von": rpo_punkt.astimezone(timezone.utc).isoformat(), "bis": ausfall.astimezone(timezone.utc).isoformat(), "sekunden": round((ausfall - rpo_punkt).total_seconds(), 3)},
            "anzahl_vorgaenge": len(vorgaenge), "je_typ": je_typ, "je_ergebnis": je_ergebnis, "betroffen_nicht_konsistent": ausserhalb, "offene_holds_im_bestand": n_held,
            "blockierend": [v["id"] for v in blockierend], "abgleich_abgeschlossen": not blockierend, "vorgaenge": vorgaenge}
