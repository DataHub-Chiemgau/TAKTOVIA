"""Konsistenzprüfungen nach einem Restore (WP-M5-14, Kap. 04 §4.5, Kap. 01 §1.7) und die Freigabeschranke des Wiederanlaufdrehbuchs.

Alle Prüfungen lesen nur (Administratorverbindung, umgeht RLS) und liefern ein Protokoll: je Prüfung `ok`, Schwere (`block` verhindert die Freigabe, `info` ist Befund für den
nächsten Schritt) und Zähler. `Report.release_allowed` ist nur wahr, wenn keine blockierende Prüfung verletzt ist UND das Lösch-/Sperrjournal gegen den Bestand geprüft wurde.
Was NICHT geprüft wird (siehe docs/umsetzungsplan/vertraege/LH-6.19-NFR-007.md): eine Hashkette in `audit_event` gibt es in der Datenbank nicht (die HMAC-Kette gehört zum Export,
LH-6.18-SEC-006); geprüft wird der Schutz der Tabelle (Trigger `audit_immutable` aktiv) und die Zeitordnung. Fahrzeugsperren und andere Änderungen im Verlustfenster
(RPO) kann keine Datenbankprüfung wiederherstellen; sie sind Gegenstand des Abgleichberichts (WP-M5-15).
"""
from dataclasses import dataclass, field
from pathlib import Path

CONFIRMED_BOOKING_STATES = ("CONFIRMED", "PARTIAL", "CANCEL_PENDING", "COMPLETED")
UNCERTAIN_PAYMENT_STATES = ("CREATED", "REQUIRES_ACTION", "CAPTURE_PENDING", "CAPTURE_UNKNOWN", "RECONCILING", "VOID_PENDING", "REFUND_PENDING")


@dataclass
class Check:
    id: str
    ok: bool
    severity: str          # "block" | "info"
    detail: str = ""
    count: int = 0


@dataclass
class Report:
    checks: list = field(default_factory=list)

    def add(self, id_, ok, severity="block", detail="", count=0):
        self.checks.append(Check(id_, bool(ok), severity, detail, int(count)))

    @property
    def violations(self):
        return [c for c in self.checks if c.severity == "block" and not c.ok]

    @property
    def release_allowed(self) -> bool:
        return not self.violations and any(c.id == "journal_wirksam" and c.ok for c in self.checks)

    def as_dict(self) -> dict:
        return {"release_allowed": self.release_allowed, "violations": [c.id for c in self.violations],
                "checks": [{"id": c.id, "ok": c.ok, "severity": c.severity, "count": c.count, "detail": c.detail} for c in self.checks]}


def _n(conn, query, *args) -> int:
    return conn.execute(query, args).fetchone()[0]


def check_outbox_inbox(conn, r: Report) -> None:
    r.add("outbox_zeitfolge", _n(conn, "SELECT count(*) FROM taktovia.outbox WHERE published_at < occurred_at") == 0, detail="veröffentlicht vor Eintritt des Ereignisses",
          count=_n(conn, "SELECT count(*) FROM taktovia.outbox WHERE published_at < occurred_at"))
    fut = _n(conn, "SELECT count(*) FROM taktovia.outbox WHERE published_at > clock_timestamp() + interval '1 minute' OR occurred_at > clock_timestamp() + interval '1 minute'")
    r.add("outbox_nicht_aus_der_zukunft", fut == 0, detail="Ereignis aus der Zukunft des wiederhergestellten Stands", count=fut)
    bad_inbox = _n(conn, "SELECT count(*) FROM taktovia.inbox WHERE received_at > clock_timestamp() + interval '1 minute' OR payload_sha256 !~ '^[0-9a-f]{64}$'")
    r.add("inbox_zeitfolge_und_pruefsumme", bad_inbox == 0, detail="Eingangszeile aus der Zukunft oder mit ungültiger Prüfsumme", count=bad_inbox)
    open_ = _n(conn, "SELECT count(*) FROM taktovia.outbox WHERE published_at IS NULL")
    r.add("outbox_unveroeffentlicht", True, "info", detail="werden nach dem Wiederanlauf mindestens einmal veröffentlicht; Empfänger deduplizieren über die Inbox", count=open_)
    dup = _n(conn, "SELECT count(*) FROM (SELECT tenant_id, aggregate_id, aggregate_revision, event_type FROM taktovia.outbox GROUP BY 1,2,3,4 HAVING count(*) > 1) d")
    r.add("outbox_eindeutigkeit", dup == 0, detail="doppelte Ereignisse je Aggregatrevision", count=dup)


def check_idempotency(conn, r: Report) -> None:
    orphan = _n(conn, """SELECT count(*) FROM taktovia.idempotency_record i WHERE i.operation_id IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM taktovia.operation o WHERE o.tenant_id = i.tenant_id AND o.id = i.operation_id)""")
    r.add("idempotenz_operation_vorhanden", orphan == 0, detail="Idempotenzeintrag verweist auf eine fehlende Operation", count=orphan)
    inflight = _n(conn, "SELECT count(*) FROM taktovia.idempotency_record WHERE state = 'IN_PROGRESS' AND expires_at > clock_timestamp()")
    r.add("idempotenz_in_bearbeitung", True, "info", detail="Wiederholung durch den Client führt zur ursprünglichen oder zu einer neuen Bearbeitung, nie zu zwei Wirkungen (Eindeutigkeit des Schlüssels)", count=inflight)
    future = _n(conn, "SELECT count(*) FROM taktovia.idempotency_record WHERE expires_at > clock_timestamp() + interval '400 days'")
    r.add("idempotenz_ablauf_plausibel", future == 0, "info", detail="Ablaufzeitpunkt weit in der Zukunft", count=future)


def check_capacity(conn, r: Report) -> None:
    over = _n(conn, """
        WITH active AS (SELECT * FROM taktovia.capacity_hold WHERE state = 'CONSUMED' OR (state = 'HELD' AND expires_at > clock_timestamp())),
             seg AS (SELECT h.tenant_id, h.run_id, s.seg, sum(coalesce((h.capacity_vector->>'seats')::int, 0)) AS seats, sum(coalesce((h.capacity_vector->>'wheelchair')::int, 0)) AS wc
                       FROM active h CROSS JOIN LATERAL generate_series(h.interval_from, h.interval_to - 1) AS s(seg) GROUP BY 1, 2, 3)
        SELECT count(*) FROM seg
          JOIN taktovia.vehicle_run vr ON vr.tenant_id = seg.tenant_id AND vr.id = seg.run_id
          JOIN taktovia.vehicle v ON v.tenant_id = vr.tenant_id AND v.id = vr.vehicle_id
         WHERE (v.capacity_config ? 'seats' AND seg.seats > (v.capacity_config->>'seats')::int) OR (v.capacity_config ? 'wheelchair' AND seg.wc > (v.capacity_config->>'wheelchair')::int)""")
    r.add("kapazitaet_nicht_ueberbucht", over == 0, detail="Segmente über der Fahrzeugkapazität (Sitze, Rollstuhlplätze)", count=over)
    nocap = _n(conn, """SELECT count(*) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id = b.tenant_id AND h.id = b.hold_id
                         WHERE b.state = ANY(%s) AND h.state <> 'CONSUMED'""", list(CONFIRMED_BOOKING_STATES))
    r.add("keine_buchung_ohne_kapazitaet", nocap == 0, detail="bestätigte Buchung ohne verbrauchte Kapazität", count=nocap)
    leak = _n(conn, """SELECT count(*) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id = b.tenant_id AND h.id = b.hold_id
                        WHERE b.state IN ('CANCELED', 'FAILED') AND h.state = 'CONSUMED'""")
    r.add("kapazitaet_stornierter_buchungen_frei", leak == 0, "info", detail="stornierte Buchung hält noch Kapazität", count=leak)


def check_audit(conn, r: Report) -> None:
    trg = conn.execute("""SELECT t.tgenabled FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                           WHERE n.nspname = 'taktovia' AND c.relname = 'audit_event' AND t.tgname = 'audit_immutable'""").fetchone()
    r.add("audit_unveraenderlich_aktiv", bool(trg) and trg[0] in ("O", "A"), detail="Trigger audit_immutable fehlt oder ist abgeschaltet")
    fut = _n(conn, "SELECT count(*) FROM taktovia.audit_event WHERE occurred_at > clock_timestamp() + interval '1 minute'")
    r.add("audit_zeitfolge", fut == 0, detail="Auditereignis aus der Zukunft des wiederhergestellten Stands", count=fut)


def check_migrations(conn, r: Report, repo_migrations: Path | None) -> None:
    if repo_migrations is None:
        return
    applied = {row[0] for row in conn.execute("SELECT script FROM taktovia_meta.schema_history").fetchall()}
    wanted = {p.name for p in repo_migrations.glob("V*.sql")}
    missing, extra = sorted(wanted - applied), sorted(a for a in applied - wanted if a.startswith("V"))
    r.add("migrationsstand", not missing and not extra, detail=f"fehlend: {missing[:5]}; unbekannt: {extra[:5]}", count=len(missing) + len(extra))


def check_journal(conn, r: Report, journal_entries: list | None) -> None:
    """Wirkung des außerhalb der Datenbank geführten Lösch-/Sperrjournals: jeder Löschvorgang ist im wiederhergestellten Bestand wirksam (kein Fahrgast mit Personendaten, der gelöscht
    wurde). Ohne Journal (None) ist die Prüfung nicht erbracht und die Freigabe gesperrt."""
    if journal_entries is None:
        r.add("journal_wirksam", False, detail="Lösch-/Sperrjournal nicht übergeben: Freigabe gesperrt")
        return
    resurrected = 0
    unarchived = 0
    for e in journal_entries:
        kind, _, ident = e["object_ref"].partition(":")
        if kind != "passenger":
            r.add("journal_wirksam", False, detail=f"unbekannter Objekttyp im Journal: {kind}")
            return
        t = e["tenant_id"]
        if e["action"] in ("DELETE", "ANONYMIZE"):
            row = conn.execute("SELECT erased_at IS NOT NULL, contact_ciphertext IS NULL AND support_ciphertext IS NULL FROM taktovia.passenger_private WHERE tenant_id = %s AND id = %s", (t, ident)).fetchone()
            leftovers = _n(conn, """SELECT (SELECT count(*) FROM taktovia.passenger_contact WHERE tenant_id = %s AND passenger_id = %s)
                                        + (SELECT count(*) FROM taktovia.passenger_preference WHERE tenant_id = %s AND passenger_id = %s)
                                        + (SELECT count(*) FROM taktovia.contact_verification WHERE tenant_id = %s AND passenger_id = %s)""", t, ident, t, ident, t, ident)
            if row is not None and (not row[0] or not row[1]) or leftovers:
                resurrected += 1
        elif e["action"] == "ARCHIVE":
            row = conn.execute("SELECT erased_at IS NOT NULL OR archived_at IS NOT NULL FROM taktovia.passenger_private WHERE tenant_id = %s AND id = %s", (t, ident)).fetchone()
            if row is not None and not row[0]:
                unarchived += 1
    r.add("journal_wirksam", resurrected == 0 and unarchived == 0, detail="gelöschte Datensätze mit Personendaten bzw. aufgehobene Archivsperre im wiederhergestellten Bestand", count=resurrected + unarchived)


def payments_to_reconcile(conn, r: Report) -> None:
    rows = conn.execute("SELECT state, count(*) FROM taktovia.payment_intent WHERE state = ANY(%s) GROUP BY 1 ORDER BY 1", (list(UNCERTAIN_PAYMENT_STATES),)).fetchall()
    total = sum(n for _, n in rows)
    r.add("zahlungen_abzugleichen", True, "info", detail=", ".join(f"{s}={n}" for s, n in rows) or "keine", count=total)
    ops = _n(conn, "SELECT count(*) FROM taktovia.operation WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'MANUAL_REVIEW')")
    r.add("operationen_offen", True, "info", detail="offene Operationen werden von den Workern mit Lease und Fencing fortgesetzt; PSP-Aufrufe wiederholen nur mit derselben Kommandoreferenz", count=ops)


def run_all(conn, journal_entries: list | None, repo_migrations: Path | None = None) -> Report:
    r = Report()
    check_migrations(conn, r, repo_migrations)
    check_outbox_inbox(conn, r)
    check_idempotency(conn, r)
    check_capacity(conn, r)
    check_audit(conn, r)
    check_journal(conn, r, journal_entries)
    payments_to_reconcile(conn, r)
    return r
