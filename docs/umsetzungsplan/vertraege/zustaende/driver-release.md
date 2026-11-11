# Zustandsautomat Fahrerfreigabe (WP-M1-35, LH-I.4-MUL-005)

Der Zustand wird **nicht gespeichert**, sondern bei jedem Zugriff aus Zeit und `revoked_at` abgeleitet (Zeitquelle: Datenbankuhr `clock_timestamp()`). Das Intervall ist links geschlossen, rechts offen.

| Zustand | Bedingung |
|---|---|
| `PLANNED` (geplant) | `revoked_at IS NULL` und jetzt < `valid_from` |
| `ACTIVE` (aktiv) | `revoked_at IS NULL` und `valid_from` <= jetzt < `valid_to` |
| `EXPIRED` (abgelaufen) | `revoked_at IS NULL` und jetzt >= `valid_to` |
| `REVOKED` (widerrufen) | `revoked_at IS NOT NULL` (gilt vor allen anderen) |

Übergänge: `PLANNED -> ACTIVE -> EXPIRED` allein durch Zeitablauf; `PLANNED | ACTIVE -> REVOKED` durch den Widerruf des Eigentümers (`DELETE /v1/driver-releases/{id}`). Ein abgelaufener oder bereits widerrufener Eintrag lässt keinen Widerruf mehr zu (409). `REVOKED` ist endgültig.

Zugriff des Empfängers (`GET /v1/driver-releases/{id}/projection`): nur im Zustand `ACTIVE` und nur für einen freigegebenen Dienst; sonst gleichartig 404 (unbekannt, fremder Mandant, geplant, abgelaufen, widerrufen, anderer Dienst). Durchgesetzt in der Datenbank durch die Policy `recipient_read` der Tabelle `driver_release_projection`; der Widerruf sperrt die Projektion in derselben Transaktion (Trigger `driver_release_revoke_projection`).
