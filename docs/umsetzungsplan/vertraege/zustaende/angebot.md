# Zustandsautomat Angebot (`ride_offer.state`)

- **Arbeitspaket:** WP-M2-01 (Entscheidung OP-19), Umsetzung WP-M2-21/-22; Datenbank: Maschine `ride_offer` in `state_transition_rule` (Migration V20210), CHECK der Wertemenge und Trigger `ride_offer_state_transition`; Inhaltswächter `ride_offer_guard`.
- **Quelle:** Kap. 01 §1.4 Schritt 4 und 5, Detailvertrag [LH-6.4-BKG-002](../LH-6.4-BKG-002.md). Ein Angebot verspricht keine Kapazität; erst der Hold (`capacity_hold`) sperrt sie.
- **Regeln:** Der Inhalt des Angebots (Preisstand, Zeitfenster, Haltepunkte, Dienst, Planbindung, Ablauf) ist nach dem Anlegen unveränderlich; geändert werden nur `state`, `hold_id`, `hold_scope`, `revision` (jeweils genau +1, starker ETag) und `updated_at`. Ablauf: `now >= expires_at` (Serverzeit) gilt als abgelaufen und wird bei jedem Zugriff bestimmt (die Sicht liefert `EXPIRED`, auch bevor der Zustand fortgeschrieben ist). Der Zustand `HELD` bleibt bestehen, wenn der Hold in eine Buchung übergeht (`capacity_hold.state = CONSUMED`, WP-M2-23). Eine Freigabe des Holds macht das Angebot zu `DECLINED` (neue Suche nötig), der Ablauf des Holds zu `EXPIRED`.

Zustände: `OFFERED`, `HELD`, `EXPIRED`, `DECLINED`
Anfangszustand: `OFFERED`
Endzustände: `EXPIRED`, `DECLINED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `OFFERED` | `HELD` | `POST /v1/ride-offers/{id}/hold` mit `If-Match`, vor `expires_at`, Preis unverändert, Kapazität frei | Fahrgast (Urheber) | 409 `OFFER_EXPIRED`, 409 `REQUOTE_REQUIRED`, 409 `CAPACITY_UNAVAILABLE`, 412 `REVISION_MISMATCH`, 428 |
| `OFFERED` | `EXPIRED` | `now >= expires_at` (Zugriffsprüfung) | System | 409 `INVALID_STATE_TRANSITION` |
| `OFFERED` | `DECLINED` | Verwerfen durch den Client (Folgearbeit M2-37) | Fahrgast (Urheber) | 409 `INVALID_STATE_TRANSITION` |
| `HELD` | `DECLINED` | `POST /v1/ride-offers/{id}/hold/release` mit `If-Match` | Fahrgast (Urheber) | 409 `INVALID_STATE_TRANSITION` (Hold bereits `CONSUMED`), 412, 428 |
| `HELD` | `EXPIRED` | Hold abgelaufen (`capacity_hold.state = EXPIRED`, Zugriffsprüfung oder Expiry-Job) | System | 409 `INVALID_STATE_TRANSITION` |
