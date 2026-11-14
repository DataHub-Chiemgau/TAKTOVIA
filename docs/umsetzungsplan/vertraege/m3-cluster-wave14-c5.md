# Cluster wave14-c5: Auftragsliste, Leitstellenbuchung, Überfälligkeit (WP-M3-37, -39, -42)

Arbeitsstand der Umsetzung und die dabei getroffenen Arbeitsannahmen; die Detailverträge der Anforderungen (`LH-6.7-OPS-002`, `-004`, `-006`) bleiben führend, hier steht nur, was sie offen ließen.

## WP-M3-37 Auftragsliste mit Suche (OPS-002)

- **Schnittstelle:** `GET /v1/operator/bookings` (Operator-Variante, Scope `operator:read`); die Fahrgastliste `GET /v1/bookings` bleibt unverändert. Antwort `{items, next_cursor, as_of}`; der Cursor ist an den Filter gebunden (Fingerabdruck ohne Suchbegriffe).
- **Zeitraum:** Zusagefenster der Buchung (Beginn des Abholfensters bis späteste Ankunft) schneidet `[from, to)` (Arbeitsannahme des Vertrags).
- **Fahrgast (OP-26 offen):** gesucht wird der **exakte Fahrgastverweis** (Konto-ID, nur Rollen Disposition, Callcenter, Support). Eine Suche über Name oder Kontakt gibt es nicht: die Kontaktdaten liegen verschlüsselt (Feldschutz), ein durchsuchbares Verfahren (Blind-Index, Suchfeldumfang, Rollen) ist nicht entschieden. Das Fahrgastfeld der Originalabnahme ist deshalb **nicht vollständig** erfüllt.
- **Dienstscope:** `ServiceScopeAuthorizer.currentServices` in jeder Abfrage (auch jeder Folgeseite), Rechteentzug wirkt sofort.

## WP-M3-39 Leitstellenbuchung (OPS-004)

- **Anlegen:** `POST /v1/ride-offers` mit `booking_subject.kind = DELEGATED` und `passenger_id` (Rolle Disposition oder Callcenter, Schreibrecht am Dienst, aktives Fahrgastkonto), Hold über die vorhandene Route (erneute Prüfung durch `assertHoldAllowed`), `POST /v1/bookings` mit `reason_code` (Pflicht) und `reason_text`. Die Buchung gehört dem Fahrgast, der Disponent ist `created_by`, Akteur im Änderungsprotokoll `booking_change` und im Audit (`dispatch.booking.*`).
- **Ändern (Mindestumfang, OP-30):** `POST /v1/bookings/{id}/changes` (Scope `dispatch:write`, nur Disposition) ist ein atomarer **Tausch**: Folgebuchung (neue Buchungsnummer) und Storno der bisherigen Buchung unter den Sperren beider Umläufe in einer Transaktion; ein Änderungseintrag `REPLACE_OFFER` mit alter und neuer Zusage. Scheitert ein Schritt, bleibt die bisherige Buchung unverändert und der neue Hold wird freigegeben. Weil Alt- und Neu-Hold gleichzeitig zählen, kann ein Wunsch auf demselben, bereits vollen Fahrzeug nicht bedient werden.
- **Zustimmung (OP-30 offen):** weicht der Preis ab oder verschiebt sich der Beginn des Abholfensters um mindestens 5 Minuten, ist `passenger_consent` (Kanal, Zeitpunkt) Pflicht, sonst 422 und nichts wird wirksam (Planungsannahme, kein Katalogparameter). Preisdifferenzen werden nicht abgerechnet: die bisherige Buchung wird nach der Betreiberregel erstattet, die Folgebuchung folgt dem normalen Zahlungsweg.
- **Betriebsnachricht:** das Storno der bisherigen Buchung läuft als Betreiberstorno (`booking.cancelled`, Initiator `OPERATOR`), die Folgebuchung löst `booking.confirmed` aus; der Fahrgast erhält deshalb bei einer Änderung zwei Nachrichten (Storno und Bestätigung). Eine eigene Änderungsnachricht gehört zu WP-M2-34 und ist offen.
- **Storno:** `POST /v1/bookings/{id}/cancel` schreibt bei Betriebspersonal zusätzlich `booking_change` (`CANCEL`) und Audit; `reason_text` ist optional.
- **Offen:** Legitimation des Disponenten über das aktive Konto hinaus, Buchung für nicht registrierte Fahrgäste, endgültiger Grundkatalog (OP-30, D-12).

## WP-M3-42 Überfälligkeit und ausstehende Informationen (OPS-006)

- **Hinweise:** `operator_flag`, Arten `OVERDUE` (Ursache Abholung überfällig) und `INFO_PENDING` (Standort veraltet, Fahrerbestätigung der Planrevision fehlt, offener Klärfall des Fahrers); Betroffenenzahl = Buchungen des Dienstes (Arbeitsannahme OP-06: Zählung nach Buchungen). Sichtbar in `GET /v1/operator/bookings` (`flags`), `GET /v1/operator/vehicles` (`flags`) und der Arbeitsliste `GET /v1/operator/flags`.
- **Schwellen:** `on_time_pickup_tolerance_min` (5), `vehicle_unavailability_alert_min` (15), `plan_ack_overdue_min` (5), `alert_escalation_delay_min` (10) als **Planungsdefaults** über die Eigenschaften `taktovia.overdue.*`; der Parameterkatalog ist eingefroren und kennt sie nicht, die Auswertung liest sie über den Port `ThresholdSource` bei jeder Auswertung neu. Die beim Auslösen wirksame Schwelle steht unveränderlich am Hinweis.
- **Lauf:** `OperatorFlags.evaluate(tenant)`, Lease je Mandant als transaktionsbezogene Advisory-Sperre (der Lauf ist eine Transaktion), zusätzlich der Teilindex gegen einen zweiten offenen Hinweis; geplant nur mit `taktovia.overdue.enabled=true` und einer `TenantSource`.
- **Alarm:** jeder neue Hinweis löst einen Alarm der Alarm-Grundlage aus (`OVERDUE` bzw. `INFO_PENDING`, Dedupe über den Hinweisschlüssel), die Erledigung schließt ihn.
- **Offen:** Zuordnung der Überfälligkeitsarten zu Parametern und Zählweise (OP-06), Schwellen vor Abnahme vom Auftraggeber zu bestätigen.
