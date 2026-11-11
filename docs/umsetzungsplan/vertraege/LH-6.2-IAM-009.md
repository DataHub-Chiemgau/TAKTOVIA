# LH-6.2-IAM-009 – Sitzungsverwaltung und Sitzungssperre

- **Verantwortlicher:** Rolle Plattform (Modul `identity`)
- **Arbeitspaket:** WP-M1-10
- **Status:** Vertrag in Arbeit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Zugangs- und Sitzungsverwaltung ermöglicht Abmeldung aller Geräte, Wiederherstellung und Sperrung.

**Originalabnahme (unverändert):** Eine gesperrte Sitzung kann anschließend keine geschützte API mehr aufrufen.

## Vertrag

- **Tabellen / DDL:** neue Tabelle für Sitzungssperren/Sitzungsregister mit RLS (nicht in `kernschema.sql`/Kap. 04 §4.4, OP-13). PostgreSQL führt, Redis ist nur Cache der Sperrliste; bei Ausfall des Cache entscheidet die Datenbank, im Zweifel Zugriff verweigert (fail-closed). Spalten und Cache-TTL sind noch nicht festgelegt.
- **API:** Prüfung „session status" in jeder Anfrage (Kap. 05 §5.1); gesperrte Sitzung ergibt 401, Refresh nicht mehr möglich. Erweiterung `/v1/me` um Sitzungsliste und „alle Geräte abmelden"; Admin-Operation Konto/Sitzung sperren und entsperren, beschränkt auf berechtigte Rollen. OpenAPI der Sitzungsoperationen ist noch zu schreiben.
- **Verfahren:** Abmeldung aller Geräte beendet IdP-Sitzungen und setzt lokale Sperre; Wiederherstellung über den IdP nur über im IdP als verifiziert geführten Kontakt (Verifikation: WP-M1-28) und beendet alle bestehenden Sitzungen. IdP-Admin-Anbindung mit eigenem, eng berechtigtem Service-Account.
- **Ereignisse:** Sicherheitsereignisse Logout, Sperre, Entsperrung, Wiederherstellung über Port aus WP-M1-46; je ein Auditeintrag mit Akteur und Zeitpunkt (Login-Ereignisse des IdP: WP-M1-12).
- **Zustandsautomat:** Sitzung `ACTIVE` -> `REVOKED` (Sperre, Abmeldung); Konto gesperrt/entsperrt. Details mit dem DDL-Vertrag.
- **Parameter und Kennzahlen:** maximale Tokenlaufzeit und Cache-TTL der Sperrliste sind nicht beziffert (OP-13).
- **Oberfläche:** UI-05 Basis (eigene Sitzungen, alle Geräte abmelden); UI-18 Basis (Nutzer sperren).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-13 (Datenmodell, Tokenlaufzeit, Cache-TTL, Wiederherstellungsverfahren nicht beschrieben, `/v1/me` nur in Stichworten); OP-34 (UI-05 Vollausbau erst M2, Vollabnahme WP-M2-35, WP-M2-36). Blockierend für den Bau: DDL, OpenAPI der Sitzungsoperationen, Tokenlaufzeit und Cache-TTL sowie das Wiederherstellungsverfahren müssen als Detailvertrag festgelegt werden, bevor codiert wird; daher noch nicht bereit.

## Tests

- **Positiv:** Abmelden, erneutes Anmelden; „alle Geräte abmelden" beendet alle Sitzungen des Kontos, zweites Gerät erhält bei nächster Anfrage 401.
- **Negativ:** gesperrte Sitzung ruft `/v1/me` und einen schreibenden Endpunkt auf (401, Refresh scheitert); Sperrversuch durch unberechtigte Rolle; Wiederherstellung über unverifizierten Kontakt abgelehnt; Sperre bei ausgefallenem Redis (fail-closed über Datenbank); fremder Mandant kann Sitzungen nicht sperren oder lesen.
- **Konkurrierend:** Sperre während 50 paralleler Anfragen – keine nach Commit der Sperre begonnene Anfrage erfolgreich.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::gesperrte_sitzung_ruft_keine_geschuetzte_api_auf; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::sperre_waehrend_50_parallelen_anfragen; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::redis_ausfall_fail_closed

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
