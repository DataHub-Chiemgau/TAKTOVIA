# Nachweis WP-M2-37 – Schrittzählung der Standardfahrt (LH-6.3-PAX-001)

Originalabnahme: „Standardfahrt ist ohne Umwege in höchstens fünf Hauptschritten buchbar.“

## Definitionen (Arbeitsannahme OP-32, durch den Auftraggeber zu bestätigen)

- **Standardfahrt:** ein Fahrgast, Sofortfahrt, hinterlegtes Zahlungsmittel (keine eigene Zahlungsmittelauswahl im Weg; Zahlung ist Sache von WP-M2-30/-42).
- **Hauptschritt:** ein Bildschirmabschnitt mit genau einer Entscheidung, nach der es weitergeht (Konstante `HAUPTSCHRITTE` in `web/passenger/src/booking/schritte.ts`, Anzeige „Schritt x von 4“).
- **Nicht mitgezählt:** Anmeldung (Voraussetzung), Abbruch/Neusuche, Korrekturen nach Fehlern.
- **Verfügbarkeit** auf der Startansicht = Betriebsstatus des Dienstes (`GET /v1/services/{id}/availability`), nicht freie Kapazität.

## Zählung des Standardwegs

| Nr. | Hauptschritt | Ansicht | API-Aufrufe |
|---|---|---|---|
| 1 | Start und Ziel wählen | UI-01 `/` | `GET /v1/places` (Suche je Ort) |
| 2 | Zeit (Standard „Jetzt losfahren“), Reisende (Standard 1) und Unterstützung angeben, „Verbindungen suchen“ | UI-01 `/` | `POST /v1/ride-offers` |
| 3 | Angebot prüfen und „Angebot auswählen“ | UI-02 `/angebote?offer=…` | `GET /v1/ride-offers/{id}`, `GET /v1/stops/{id}` |
| 4 | „Verbindlich buchen – Betrag“ (Bestätigung) | UI-02 `/angebote?offer=…` | `POST …/hold`, `GET /v1/ride-offers/{id}`, `POST /v1/bookings` |

Ergebnis: **4 Hauptschritte** (Obergrenze 5), auf zwei Routen (`/`, `/angebote`), ohne Umweg über andere Bereiche (Konto, Reisen, Hilfe). Die Bestätigung zeigt Buchungsnummer, Status, Fenster und Preis aus der Zusage der Buchung.

## Belege

- `web/passenger/src/booking/StartView.test.tsx::standardfahrt_in_hoechstens_fuenf_schritten` durchläuft Start → Angebot → Bestätigung gegen einen Serversatz (Speicher, `buchungsWelt.tsx`), zeichnet die Hauptschritte und besuchten Routen auf und prüft genau ein Hold, eine Bestätigung, eine Buchung.
- `backend/app/src/test/kotlin/de/taktovia/app/booking/StandardfahrtHttpApiTest.kt::standardweg_angebot_hold_bestaetigung_liste_in_vier_aufrufen` belegt den Aufrufweg gegen die echte Anwendung und Datenbank.

## Grenzen

- Ein Browser-Ende-zu-Ende-Lauf mit echtem Backend und Keycloak ist **nicht ausgeführt** (Dienste in dieser Umgebung nicht verfügbar).
- Der Bestätigungsschritt ist die Minimalfassung von UI-03; PSP, SCA, Countdown des Holds und Zahlungsstatus folgen mit WP-M2-42. Die Zählung ändert sich dadurch nicht (Zahlung über hinterlegtes Zahlungsmittel), ist dann aber mit WP-M2-42 erneut zu prüfen.
- Die Fußwegangabe in UI-02 ist eine Luftlinien-Näherung (siehe Vertrag LH-6.3-PAX-005).
