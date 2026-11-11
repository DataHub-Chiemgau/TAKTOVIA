# Zustandsautomat Hold (`capacity_hold.state`)

- **Arbeitspaket:** WP-M2-01 (Vertrag), Umsetzung WP-M2-22; Datenbank: `capacity_hold.state CHECK IN ('HELD','CONSUMED','RELEASED','EXPIRED')` (V1), Härtung über `harden_state_column`.
- **Quelle:** Kap. 01 §1.4 Schritte 5 und 6, Kap. 02 §7.2. Standarddauer 180 s (Planungsdefault, kein Katalogparameter, M2 OP-20); keine Verlängerung in P1 (M2 OP-47, Annahme).
- **Regeln:** Alle Übergänge laufen unter demselben Kapazitätslock (Zeile `vehicle_run`, stabile Reihenfolge). Am Grenzzeitpunkt gilt Serverzeit `now >= expires_at` als abgelaufen; der Ablauf wird beim Zugriff geprüft, der Expiry-Job ist nur Beschleunigung. Belegung zählt `CONSUMED` sowie `HELD` mit `now < expires_at`. Ein Hold wird genau einmal konsumiert.

Zustände: `HELD`, `CONSUMED`, `RELEASED`, `EXPIRED`
Anfangszustand: `HELD`
Endzustände: `CONSUMED`, `RELEASED`, `EXPIRED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `HELD` | `CONSUMED` | Confirm vor `expires_at`, Planrevision unverändert | Fahrgast, System | 409 `HOLD_EXPIRED` (abgelaufen), 409 `INVALID_STATE_TRANSITION` (sonst) |
| `HELD` | `RELEASED` | Freigabe durch den Client, Abbruch, Kompensation | Fahrgast, System | 409 `INVALID_STATE_TRANSITION` |
| `HELD` | `EXPIRED` | `now >= expires_at` (Zugriffsprüfung oder Expiry-Job) | System | 409 `INVALID_STATE_TRANSITION` |
