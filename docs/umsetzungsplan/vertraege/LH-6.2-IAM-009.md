# LH-6.2-IAM-009 – Sitzungsverwaltung und Sitzungssperre

- **Verantwortlicher:** Rolle Plattform (Modul `identity`)
- **Arbeitspaket:** WP-M1-10
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Zugangs- und Sitzungsverwaltung ermöglicht Abmeldung aller Geräte, Wiederherstellung und Sperrung.

**Originalabnahme (unverändert):** Eine gesperrte Sitzung kann anschließend keine geschützte API mehr aufrufen.

## Vertrag

- **Tabellen / DDL:** Das Kernschema (V1/V2/V3) enthält keine Sitzungstabelle (OP-13); sie wird mit der nächsten freien Migration (nach V3, also V4) angelegt. Vorschlag zur Freigabe im Architekturreview: Tabelle `session_register` (`tenant_id`, `id`, `issuer`, `subject`, `idp_session_id`, `created_at`, `expires_at`, `revoked_at`, `revoked_by`, `revoke_reason`; `PRIMARY KEY(tenant_id,id)`, `UNIQUE(tenant_id,issuer,idp_session_id)`, RLS wie V1, `tenant_id` unveränderlich per Trigger aus V3). Kontosperre: `membership.revoked_at` aus V1 ist die Sperre der Mitgliedschaft; ein Konto-Sperrstatus je Subjekt wird in derselben Migration ergänzt (Feldliste bei der Freigabe). PostgreSQL führt; Redis ist nur Cache der Sperrliste (Einträge werden vor dem DB-Commit einer Sperre gesetzt und bei Rollback entfernt, damit nach Commit begonnene Anfragen nie ein „nicht gesperrt“ sehen). Bei Ausfall von Redis entscheidet die Datenbank; ist auch diese nicht erreichbar, wird der Zugriff verweigert (fail-closed). Keine Passwort- oder Tokenspalte (siehe IAM-002).
- **API:** Prüfung „session status“ in jeder Anfrage (Kap. 05 §5.1: issuer, audience, expiry, session status, Scopes); die Session-ID kommt aus dem Token-Claims-Vertrag (IAM-002). Gesperrte Sitzung ergibt 401, Refresh nicht mehr möglich (IdP-Sitzung beendet). Erweiterung `/v1/me` (Kap. 05 §5.7 nennt „gesperrte Sitzung“ nur als Stichwort) um Sitzungsliste und „alle Geräte abmelden“; Admin-Operation Konto/Sitzung sperren und entsperren, beschränkt auf berechtigte Rollen (Rechtematrix IAM-004). Pfade und Schemas der Sitzungsoperationen (Vorschlag: Unterpfade von `/v1/me` für eigene Sitzungen, ein Admin-Pfad für Sperre/Entsperrung) werden vor Codebeginn in `api/` als OpenAPI-Vertrag angelegt; Statuscodes mindestens 401 (gesperrt), 403 (Rolle fehlt), 404 (fremder Mandant oder Objekt).
- **Verfahren:** Abmeldung aller Geräte beendet IdP-Sitzungen und setzt lokale Sperre; Wiederherstellung über den IdP nur über im IdP als verifiziert geführten Kontakt (Verifikation: WP-M1-28) und beendet alle bestehenden Sitzungen. IdP-Admin-Anbindung mit eigenem, eng berechtigtem Service-Account.
- **Ereignisse:** Sicherheitsereignisse Logout, Sperre, Entsperrung, Wiederherstellung über Port aus WP-M1-46; je ein Auditeintrag mit Akteur und Zeitpunkt (Login-Ereignisse des IdP: WP-M1-12).
- **Zustandsautomat:** Sitzung `ACTIVE` -> `REVOKED` (Sperre, Abmeldung); Konto gesperrt/entsperrt. Details mit dem DDL-Vertrag.
- **Parameter und Kennzahlen:** keine in `kataloge.csv`. Maximale Access-Token-Laufzeit und Cache-TTL der Sperrliste sind im Pflichtenheft nicht beziffert (Kap. 01 §1.7: nur „kurze Tokenlaufzeit“, OP-13); sie werden als konfigurierbare Parameter mit Vorschlagswert geführt (Vorschlag: Token-Laufzeit höchstens 5 Minuten, Sperrlisten-Cache-TTL höchstens 60 Sekunden) und vor Produktivschaltung beschlossen. Die Sperrwirkung hängt nicht von der TTL ab (Prüfung in jeder Anfrage).
- **Oberfläche:** UI-05 Basis (eigene Sitzungen, alle Geräte abmelden); UI-18 Basis (Nutzer sperren).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-13 (Datenmodell, Tokenlaufzeit, Cache-TTL, Wiederherstellungsverfahren, `/v1/me` nur in Stichworten): der Vertrag legt oben DDL-Vorschlag, API-Rahmen und Parameter mit Vorschlagswerten fest; Freigabe von DDL, OpenAPI und Parametern im Architekturreview ist vor Codebeginn nötig (blockiert den Bau bis dahin). Wiederherstellungsverfahren: IdP-Wiederherstellung, nur über im IdP als verifiziert geführten Kontakt, beendet alle Sitzungen; Details der IdP-Konfiguration sind im Realm-Vertrag (IAM-002) festzuhalten. OP-34 (UI-05 Vollausbau erst M2, Vollabnahme WP-M2-35, WP-M2-36).

## Tests

- **Positiv:** Abmelden, erneutes Anmelden; „alle Geräte abmelden" beendet alle Sitzungen des Kontos, zweites Gerät erhält bei nächster Anfrage 401.
- **Negativ:** gesperrte Sitzung ruft `/v1/me` und einen schreibenden Endpunkt auf (401, Refresh scheitert); Sperrversuch durch unberechtigte Rolle; Wiederherstellung über unverifizierten Kontakt abgelehnt; Sperre bei ausgefallenem Redis (fail-closed über Datenbank); fremder Mandant kann Sitzungen nicht sperren oder lesen (404, RLS); Sperre und Entsperrung sowie „alle Geräte abmelden“ und Wiederherstellung erzeugen je einen Auditeintrag mit Akteur und Zeitpunkt.
- **Konkurrierend:** Sperre während 50 paralleler Anfragen – keine nach Commit der Sperre begonnene Anfrage erfolgreich; gleichzeitige Sperre und Entsperrung desselben Kontos ergeben einen definierten Endzustand.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::gesperrte_sitzung_ruft_keine_geschuetzte_api_auf; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::sperre_waehrend_50_parallelen_anfragen; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::redis_ausfall_fail_closed; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::abmelden_und_erneut_anmelden; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::alle_geraete_abmelden_zweites_geraet_401; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::sperrversuch_unberechtigte_rolle_verweigert; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::wiederherstellung_nur_verifizierter_kontakt; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::fremder_mandant_sitzung_404; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::sperre_entsperrung_auditeintrag; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::gleichzeitige_sperre_und_entsperrung_definierter_endzustand

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
