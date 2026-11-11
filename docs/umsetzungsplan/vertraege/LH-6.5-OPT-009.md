# LH-6.5-OPT-009 – Hold: Kapazitätssperre mit Revisionsabgleich

- **Verantwortlicher:** Rolle Disposition (Modul `booking-dispatch`, Hold-Befehl)
- **Arbeitspaket:** WP-M2-22
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Zuweisungen sind atomar mit aktueller Kapazitäts- und Routenrevision abzugleichen.

**Originalabnahme (unverändert):** Konkurrenztests führen nicht zu Doppelbelegung.

## Vertrag

- **Tabellen / DDL:** Tabellen `capacity_hold` (`run_id`, `run_revision`, `federation_ref`, `capacity_vector`, `interval_from`, `interval_to`, `expires_at`, `state` in `HELD|CONSUMED|RELEASED|EXPIRED`, V1; `UNIQUE(tenant_id, federation_ref)`, `CHECK(interval_to > interval_from)`), `vehicle_run` (`revision`, `state`), `route_stop` (neu, WP-M2-16). Invariante Kap. 04 §4.5: Run-Zeilen in stabiler Reihenfolge sperren, Revisionen vergleichen; kein Redis-Lock. Hold, Planrevision und Outbox-Ereignis in einer Transaktion. Ablauf: Prüfung bei jedem Zugriff mit Serverzeit plus Expiry-Job, der denselben Kapazitätslock benutzt (Kap. 02 §7.1). Alle Tabellen mit `tenant_id` und RLS.
- **API:** Hold-Befehl `POST /v1/ride-offers/{id}/hold` (Pfadvorschlag, OP-21) mit `Idempotency-Key` und `If-Match`; Antwort mit `hold_id`, `expires_at`; `409 CAPACITY_UNAVAILABLE` bei erschöpfter Kapazität oder konkurrierender Revision, `412` bei veralteter Revision, 404 bei fremdem Hold. Freigabe eines eigenen Holds durch den Client idempotent (Pfad im Bau festzulegen, OP-21); bereits `CONSUMED` Hold bleibt unverändert.
- **Zustandsautomat:** `capacity_hold.state`: `HELD -> CONSUMED | RELEASED | EXPIRED` (Zustandsdatei `vertraege/zustaende/capacity_hold.md` ist mit dem Bau anzulegen, das Verzeichnis existiert noch nicht).
- **JSON-Schema / Ereignisse:** Ereignisse Hold gesetzt, freigegeben, abgelaufen über `outbox` (V1), Schemas unter `api/events/`.
- **Parameter und Kennzahlen:** `min_capacity_reserve` (Plätze). Hold-Dauer 180 s als Planungsdefault (Kap. 02 §7.2), kein Katalogparameter (OP-20). Keine Verlängerung in P1 (OP-47).
- **Oberfläche:** Keine; Contract-Fall abgelaufene Holds (Kap. 05 §5.8) betrifft den Client im Checkout.
- **Entscheidungen:** D-05 (Lastprofil Peaklast; blockiert den Bau nicht). Spike-Bericht WP-M0-26 (Hold/Confirm unter 100 parallelen Transaktionen) liefert die Lock-Reihenfolge.
- **Offene Punkte:** OP-20 (Hold-Dauer ohne Parameter), OP-21 (Pfade Hold und Freigabe), OP-47 (Verlängerung nur nach erneuter Kapazitätsprüfung, in P1 keine Verlängerung angenommen). Sperre aus Prüfung/Schaden im selben Lock erst mit WP-M3-08/-09 (PH-AT-020); in M2 über Leseport-Fixture. Blockiert den Bau nicht.

## Tests

- **Positiv:** Hold auf freien Platz liefert `HELD` mit `hold_id` und `expires_at`; Hold und Outbox-Ereignis entstehen gemeinsam; Freigabe eines eigenen Holds setzt `RELEASED` und gibt Kapazität frei; abgelaufener Hold gibt Kapazität frei.
- **Negativ:** Kapazität erschöpft, Revision veraltet, Angebot abgelaufen, über Leseport gesperrtes Fahrzeug: je `409`/definierter Fachgrund ohne Hold; Freigabe eines fremden Holds ergibt 404; Freigabe eines `CONSUMED` Holds ändert ihn nicht; Fehler nach Hold-Insert, vor Outbox-Insert: beides zurückgerollt.
- **Konkurrierend:** 100 parallele Holds auf den letzten Sitz eines überlappenden Abschnitts: höchstens ein `HELD`; gleiches mit belegtem Rollstuhlplatz; gegenläufige Abschnittsreihenfolge als Deadlock-Provokation: jede Transaktion endet mit Erfolg oder definiertem Fachgrund; Freigabe parallel zum Confirm desselben Holds: genau ein Ausgang; am Grenzzeitpunkt `now >= expires_at` (Serverzeit, steuerbare Uhr) gilt der Hold als abgelaufen; Hold-Ablauf parallel zum Confirm: nie abgelaufen und belegt; kein Abschnitt über Kapazität in irgendeiner Dimension.
- **Referenztests:** AT-03, PH-AT-004, Contract-Fall abgelaufene Holds.
- Testfälle: AT-03; PH-AT-004; geplant:db/tests/test_capacity_hold_concurrency.py::test_100_parallele_holds_hoechstens_einer; geplant:db/tests/test_capacity_hold_concurrency.py::test_belegter_rollstuhlplatz_und_gegenlaeufige_abschnittsreihenfolge; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/HoldExpiryTest.kt::ablauf_gibt_kapazitaet_frei_und_nie_abgelaufen_und_belegt; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/HoldCommandTest.kt::hold_gegen_veraltete_run_revision_wird_abgewiesen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/HoldCommandTest.kt::freigabe_parallel_zu_confirm_genau_ein_ausgang

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Kapazität).
