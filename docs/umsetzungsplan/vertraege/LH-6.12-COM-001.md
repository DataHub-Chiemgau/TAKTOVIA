# LH-6.12-COM-001 – Auslöser transaktionaler Benachrichtigungen

- **Verantwortlicher:** Rolle Kommunikation (Modul `communication-support`)
- **Arbeitspaket:** WP-M2-34
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Transaktionale Benachrichtigungen werden bei Bestätigung, relevanter ETA-Änderung, Ankunft, Storno und Ausfall ausgelöst.

**Originalabnahme (unverändert):** Jedes Szenario erzeugt eine passende Nachricht oder dokumentierten Kanalfehler.

## Vertrag

- **Tabellen / DDL:** Verbraucher legt je Ereignis und Empfänger einen Auftrag in `notification` an (DDL: LH-6.12-COM-003, WP-M2-32, Migration nach V3, nächste freie Versionsnummer; Unique `(tenant_id, event_id, recipient_ref, channel)`). Einmaligkeit des Ereignisverbrauchs über `inbox(tenant_id, consumer, event_id)` (V1, Konsument `communication-support`). Auslösende Ereignisse kommen aus `outbox` (V1). Kontakte nur als Referenz auf `passenger_private.contact_ciphertext`. RLS über `tenant_isolation` (V1) auf allen beteiligten Tabellen.
- **Zuordnung Ereignis -> Ereignistyp der Nachricht -> Empfänger** (Detailvertrag vorab, fünf Szenarien):

| Szenario | Quellereignis | Ereignistyp Nachricht | Empfänger |
|---|---|---|---|
| Bestätigung | Buchung bestätigt (WP-M2-23) | `booking.confirmed` | Fahrgast der Buchung |
| relevante ETA-Änderung | Planrevision mit ETA-Änderung (WP-M2-28), plus `promise.changed` mit Informationsauftrag (WP-M1-27) | `eta.changed` | Fahrgast der betroffenen Buchung |
| Ankunft | Fahrzeug am Treffpunkt angekommen (M3, bis dahin Testereignis) | `vehicle.arrived` | Fahrgast des Stopps |
| Storno | Buchung storniert durch Fahrgast (WP-M2-26) | `booking.cancelled_by_passenger` | Fahrgast der Buchung |
| Ausfall | Betreiberstorno/Fahrzeugausfall (WP-M2-27; Fahrzeugausfall aus M3 bis dahin Testereignis) | `booking.cancelled_by_operator` | betroffene Fahrgäste, jeder nur bei eigener Betroffenheit |

  Die Bezeichner der Ereignistypen (und Schlüsselnamen wie `booking.confirmed`) sind Arbeitsnamen, keine Namen aus dem Pflichtenheft; sie sind Vorschlag dieses Vertrags und werden mit den Ereignisschemas aus WP-M2-01 abgeglichen. Storno durch Fahrgast und Ausfall führen zu unterschiedlichen Nachrichtentypen.
- **Ablauf:** Verbraucher prüft Inbox (einmal je Ereignis), prüft Zweck Betriebsmitteilung (Port aus WP-M1-30, unabhängig von Marketing-Opt-in, Kap. 05 §5.7), wählt Kanal nach verfügbaren verifizierten Kontakten, löst Vorlage nach LH-6.12-COM-002 auf und übergibt an den asynchronen Versand (COM-003). Ohne nutzbaren Kanal oder ohne Vorlage: dokumentierter Kanalfehler am Auftrag (`notification`), Buchung bleibt unberührt. Ereignis zu stornierter Buchung: kein Versand von Bestätigungs-/ETA-Nachrichten, Ergebnis wird dokumentiert.
- **API:** keine eigene Schnittstelle; Lesen des Ergebnisses über `/v1/notifications` (COM-003).
- **Zustandsautomat:** `vertraege/zustaende/notification.md` (Auftrag/Versuch, COM-003); dieser Vertrag erzeugt nur den Zustand "angelegt" oder direkt "Kanalfehler".
- **JSON-Schema / Ereignisse:** Verbrauch der M2-Ereignisse aus `api/events/`; neu zu vereinbaren mit M3 sind die Schemas "Ankunft" (`vehicle.arrived`) und "Fahrzeugausfall" (Ereignisschemas laut `api/events/README.md`, Namensschema `<bereich.ereignis>.v<N>.schema.json`).
- **Parameter und Kennzahlen:** `customer_notify_before_pickup_min` (Katalog J.7, Ebene Dienst, "Reale ETA-Änderungen priorisiert"). Schwelle "relevante ETA-Änderung" ist kein Katalogparameter (OP 30).
- **Oberfläche:** keine eigene; Nachrichteninhalt wird in UI-05 nicht angezeigt (WP-M2-36 nur Sprachwahl).
- **Entscheidungen:** D-11 (Rechtsgrundlage/Löschfrist Kontaktdaten) blockiert nur die Produktivschaltung, nicht den Bau.
- **Offene Punkte:** OP 30 (WP-Dokument): Definition und Wert der Relevanzschwelle für ETA-Änderungen ist im Pflichtenheft nicht festgelegt; der Bau implementiert die Schwelle als dienstbezogenen Konfigurationswert mit einem im Test festgelegten Vorläuferwert, der Produktivwert braucht eine Fachentscheidung (blockiert den Bau nicht). Ob die Erinnerung nach `customer_notify_before_pickup_min` zu den P1-Auslösern zählt, ist offen und nicht Teil dieses Vertrags. Abgrenzung: Ankunft und Fahrzeugausfall sowie laufende ETA sind in M2 nur mit Testereignissen bzw. Plan-ETA nachweisbar; Vollabnahme erst mit WP-M3-29, WP-M3-53, WP-M4-61, WP-M4-62. OP 28 (Kanäle/Provider) wirkt auf die Kanalwahl, Bau gegen Simulator.

## Tests

- **Positiv:** je Szenario (Bestätigung, relevante ETA-Änderung, Ankunft, Storno, Ausfall) ein Ende-zu-Ende-Test vom Ereignis bis zum Simulator-Provider: Nachricht mit richtigem Ereignistyp, richtiger Vorlage und Buchungsreferenz; Storno und Ausfall ergeben verschiedene Typen.
- **Negativ:** kein nutzbarer Kanal -> Kanalfehler dokumentiert, Buchung unverändert; ETA-Änderung unter der Relevanzschwelle -> keine Nachricht; fehlende Vorlage -> Kanalfehler statt leerer Nachricht; Provider-Ausfall -> dokumentierter Fehlschlag; Ereignis zu stornierter Buchung -> keine Bestätigung/ETA-Nachricht; fehlende Marketingeinwilligung -> Betriebsnachricht wird trotzdem zugestellt, Marketinginhalt wird abgelehnt.
- **Konkurrierend:** dasselbe Ereignis zweimal oder parallel zugestellt -> genau eine Nachricht (Inbox und Unique); Storno trifft parallel zu ETA-Änderung -> keine ETA-Nachricht nach Storno. Datentrennung: Mitfahrende desselben Umlaufs erhalten nur bei eigener Betroffenheit eine Nachricht; fremder Mandant erzeugt keinen Auftrag.
- **Referenztests:** AT-07 (Infoteil), AT-01 (Benachrichtigungsteil).
- Testfälle: AT-07; AT-01; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationTriggerTest.kt::jedes_szenario_erzeugt_nachricht_oder_kanalfehler; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationTriggerTest.kt::storno_und_ausfall_haben_verschiedene_nachrichtentypen; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationTriggerTest.kt::kein_nutzbarer_kanal_dokumentiert_kanalfehler_buchung_unberuehrt; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationTriggerTest.kt::eta_aenderung_unter_schwelle_erzeugt_keine_nachricht; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationTriggerTest.kt::betriebsnachricht_ohne_marketingeinwilligung_zugestellt_marketinginhalt_abgelehnt; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationTriggerTest.kt::nur_betroffene_mitfahrende_erhalten_nachricht; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationTriggerTest.kt::doppeltes_ereignis_erzeugt_eine_nachricht; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationTriggerTest.kt::storno_parallel_zu_eta_aenderung_keine_eta_nachricht_nach_storno; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationTriggerTest.kt::fremder_mandant_erzeugt_keinen_auftrag

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

