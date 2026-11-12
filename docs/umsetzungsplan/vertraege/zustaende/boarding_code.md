# Zustände des Boarding-Codes (WP-M3-30, `LH-6.6-DRV-006`)

- **Arbeitspaket:** WP-M3-30; Tabelle `boarding_code` (Besitzer `staff-time`, V3020, erweitert in V30300), Prüfung `JdbcBoardingCodes`, Wächter `boarding_code_guard`.
- **Status:** Planungsannahme (OP-18): der Zustand ist abgeleitet, es gibt keine Zustandsspalte.

| Zustand | Erkennung | Folge |
|---|---|---|
| ausgestellt | Zeile vorhanden, `used_at` und `locked_at` leer, `valid_until` in der Zukunft, `failed_attempts < 5` | Check-in mit richtigem Code wirkt |
| entwertet | `used_at` und `used_event_id` gesetzt (nur zusammen, endgültig) | weiterer Check-in: online 422 `BOARDING_CODE_USED`, offline Klärfall `CODE_ALREADY_USED` |
| gesperrt | `locked_at` gesetzt (endgültig, ab dem fünften Fehlversuch) | 422 `BOARDING_CODE_LOCKED` bzw. Klärfall `CODE_LOCKED`; Hinweisereignis `driver.boarding_code_locked` an die Leitstelle |
| ungültig | `valid_until` überschritten, oder die Buchung ist storniert, nicht mehr im Plan oder nicht mehr `CONFIRMED` | abgewiesen (`CODE_INVALID` bzw. kein Check-in über die Wirkung auf die Buchung) |

Übergänge: ausgestellt -> entwertet (beim wirksamen Check-in, im selben Zeitpunkt wie die Wirkung auf Buchung und Fahrt); ausgestellt -> gesperrt (Fehlversuchsgrenze). Der Fehlversuchszähler steigt nur; Hash, Buchung und Gültigkeit sind unveränderlich, die Zeile ist nicht löschbar (Datenbankwächter, SQLSTATE 23514). Ein falscher Code erhöht den Zähler nur, wenn die Buchung im Plan der eigenen Schicht steht (sonst 404).
