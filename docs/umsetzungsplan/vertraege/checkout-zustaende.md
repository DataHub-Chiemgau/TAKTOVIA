# Detailvertrag UI-03 Checkout: Zustandsdiagramm, Texte, Idempotenzschlüssel (WP-M2-42, Enabler)

- **Code:** `web/passenger/src/booking/Checkout.tsx` (Ablauf), `checkoutAblauf.ts` (Auswertung des Serverstands), `Countdown.tsx`, `PspComponent.tsx` (Schnittstelle zur PSP-gehosteten Komponente, Simulator), Route `/checkout/:offerId` (`CheckoutScreen.tsx`).
- **Tests:** `web/passenger/src/booking/Checkout.test.tsx` (16 Fälle gegen den Ersatzserver `buchungsWelt`), Übergabe Liste zu Checkout in `OfferList.test.tsx`. Das echte Serververhalten (Hold, Confirm, Zahlung) belegen `RideOfferHoldHttpApiTest`, `BookingHttpApiTest`, `PaymentCheckoutHttpTest` im Backend.

## Ablauf

`Prüfen` (Zusammenfassung, Preisaufschlüsselung aus `fare_components`, Stornoregel, PSP-Zahlungsauswahl, Pflichtzustimmung getrennt von jeder Marketingeinwilligung) -> Schaltfläche „Zahlungspflichtig buchen – Betrag“ (gesperrt ohne Zahlungsmittel und Zustimmung) -> `Hold` („Plätze werden reserviert“) -> `Bestätigung` (`POST /v1/bookings`, Buchung `CONFIRMING`) -> `Zahlung` (`POST /v1/bookings/{id}/payment`, Operation) -> Statusbeobachtung über `GET /v1/bookings/{id}` (Fahrgäste lesen den Zahlstatus im Block `payment`, `GET /v1/operations/{id}` ist ihnen verwehrt) bis zu einem eindeutigen Ergebnis.

| Phase | Auslöser | Anzeige | Weiter |
|---|---|---|---|
| `review` | Angebot gelesen | Zusammenfassung, Betrag des `fare_quote`, Countdown, wenn ein Hold besteht | Buchen |
| `working` | Buchen | „Plätze werden reserviert“ bzw. „Buchung wird bestätigt“, Schaltfläche gesperrt | Ergebnis |
| `sca` | `payment.status = REQUIRES_ACTION` | Hinweis auf Bestätigung bei der Bank, Countdown, „zurückgekehrt“ | `POST .../payment/return`, dann Beobachtung (erst der verifizierte Status ändert die Anzeige) |
| `success` | `booking.state = CONFIRMED` | Buchungsnummer, Buchungsstatus und Zahlungsstatus **getrennt** | Reise |
| `declined` | Zahlung `AUTHORIZATION_FAILED` bzw. Storno wegen `PAYMENT_FAILED` | „Zahlung abgelehnt, nichts berechnet“ | neue Suche |
| `holdLost` | `409 HOLD_EXPIRED` nach der Bestätigung oder Storno mit Zahlungsfehlercode `AUTHORIZATION_EXPIRED`/`BOOKING_RELEASED`/`AMOUNT_MISMATCH` | „Reservierung abgelaufen“, Zahlstand (z. B. „aufgehoben“), nie Erfolg | neue Suche |
| `expired` | `409 OFFER_EXPIRED`/`HOLD_EXPIRED` vor der Bestätigung | „Angebot abgelaufen“ | neue Suche |
| `capacity` | `409 CAPACITY_UNAVAILABLE` | „Platz nicht mehr verfügbar“ | neue Suche |
| `review` mit Hinweis | `409 REQUOTE_REQUIRED`, `412`, geänderter Stand nach dem Hold | neuer Preis, Zustimmung zurückgenommen; bei geändertem Preis zusätzlich ausdrückliche Preiszustimmung | erneut buchen |
| `unclear` | Netzabbruch (Status 0), `5xx`, `409 OPERATION_IN_PROGRESS`, Zahlstatus `UNKNOWN`, Beobachtung ohne Ergebnis | „Bestätigung wird geprüft“, Betrag, Kontakt (Hilfe), weder Erfolg noch Ablehnung | „Stand prüfen“ |

## Regeln

- **Betrag:** immer Serverwert (`gross` des Angebots bzw. `promise.gross`); der Hold und die Bestätigung tragen `max_gross` = angezeigter Betrag, die Zahlung `amount` = Betrag des `fare_quote`. Nie gerechnet oder gerundet.
- **Idempotenzschlüssel (Wiederverwendung):** je Absicht ein Schlüssel (`hold:<Angebot>:<Revision>`, `confirm:<Hold>`, `pay:<Buchung>`, `return:<Buchung>`); bei unbekanntem Ausgang wiederholt „Stand prüfen“ mit **demselben** Schlüssel, nach einer endgültigen Antwort ist der Schlüssel verbraucht (`CommandKeys`).
- **Neuladen und zweiter Tab:** Der Ablauf setzt am Serverstand fort: besteht ein Hold (`offer.hold`), wird er bestätigt (derselbe Hold liefert dieselbe Buchung); der Zahlungsstart erfolgt nur, wenn die Buchung noch keinen Zahlungsblock hat. Ein zweiter Tab mit altem Angebotsstand erhält `412`, zeigt den neuen Stand und bucht nie still.
- **Countdown:** Restfrist aus `hold.expires_at` (Serverzeit des Holds), `role="timer"` ohne Live-Ansage; Ablauf während SCA/Zahlung wird als Hinweis gemeldet, ein Erfolg wird nicht behauptet.
- **PSP-Komponente:** Die Anwendung sieht nie Kartendaten (SEC-008); `PspComponent` liefert nur `payment_method_ref`. Gebaut gegen den Simulator (D-03, blockiert nur die Produktivschaltung).
- **Barrierefreiheit:** Gruppen mit Beschriftung, Schaltfläche per `aria-disabled` statt `disabled` (Fokus bleibt), Fortschritt und Ergebnisse als `role="status"`/`alert`.
