# Zustandsautomat Fahrzeugsperre (`vehicle_block.status`, je Quelle)

- **Arbeitspaket:** WP-M3-01 (Vertrag, Entscheidung OP-01), Umsetzung WP-M3-08 (Kern), WP-M3-41 (operative Sperre), WP-M3-16/-46 (Mangel, Schaden).
- **Status:** Entscheidung dieses Plans zu OP-01: Der bisherige Boolean `vehicle.active_block` genügt nicht; jeder Grund ist eine Zeile `vehicle_block` (Quelle `OPERATIONS` oder `SAFETY`, Grundcode, gesetzt/aufgehoben mit Akteur). `vehicle.active_block` bleibt als abgeleiteter Wert bestehen (wahr, solange mindestens eine Zeile `ACTIVE` ist; Default `false` für neue Fahrzeuge mit Stammdatenstatus, Bedeutung des V1-Defaults `true` ist damit „nicht freigegeben“, siehe Datenmodell-Entwurf). Jede Änderung erhöht `vehicle.safety_revision` im selben Commit.
- **Rollen:** `OPERATIONS`-Sperre setzen/aufheben: Disposition (`dispatcher`). `SAFETY`-Sperre setzen: ausschließlich der Server (kritischer Mangel, sicherheitskritischer Schaden); aufheben: nur über `createVehicleRelease` durch Werkstatt/technische Freigabe (`workshop`) mit Vieraugenpflicht (Betriebsleitung `tenant_admin`).
- **Regel:** Fahrzeug ist zuweisbar nur, wenn keine Zeile `ACTIVE` besteht, die Einsatzfreigabe gültig ist und `safety_revision` seit der Freigabe unverändert ist oder neu bestätigt wurde.

Zustände: `ACTIVE`, `LIFTED`
Anfangszustand: `ACTIVE`
Endzustände: `LIFTED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `ACTIVE` | `LIFTED` | `liftVehicleBlock` (Quelle `OPERATIONS`) | Disposition | 409 `INVALID_STATE_TRANSITION` |
| `ACTIVE` | `LIFTED` | `createVehicleRelease` mit Reparaturnachweis, Folgeprüfung und Vieraugenentscheidung (Quelle `SAFETY`) | Werkstatt, Betriebsleitung | 409 `INVALID_STATE_TRANSITION` |

## Negativbeispiele

- `liftVehicleBlock` auf eine Sperre der Quelle `SAFETY`: abgewiesen, 409 `INVALID_STATE_TRANSITION`; die Sperre bleibt, `safety_revision` bleibt unverändert (OPS-005, DMG-006).
- `LIFTED` -> `ACTIVE` (Wiederaufnahme derselben Zeile): abgewiesen; eine neue Sperre ist eine neue Zeile (append-only), 409 `IMMUTABLE_RECORD`.
- Aufhebung der Sicherheitssperre durch dieselbe Person, die die Erstfreigabe erteilt hat (Vieraugenpflicht): 403 `FORBIDDEN`.
