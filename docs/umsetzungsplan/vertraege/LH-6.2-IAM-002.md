# LH-6.2-IAM-002 – OIDC-Ressourcenserver, IdP-Realm und MFA

- **Verantwortlicher:** Rolle Plattform (Modul `identity`, `backend/app`, `infra/`)
- **Arbeitspaket:** WP-M1-04
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Passwörter werden nur mit zeitgemäßem adaptivem Hash gespeichert; alternative passwortlose Anmeldung ist vorgesehen.

**Originalabnahme (unverändert):** Sicherheitsprüfung findet keine Klartextpasswörter oder unsicheren Standard-Hashes.

## Vertrag

- **Tabellen / DDL:** keine Passwort- oder Tokenspalte im System. Schema-Lint verbietet Spaltennamen `password*` (Prüfung gegen `V1__kernschema.sql`, `V2` und alle Folgemigrationen). Passwörter liegen ausschließlich im IdP (Keycloak). Das Schema-Lint wird als `db/tests/test_08_keine_passwortspalten.py` angelegt (die Nummern 01–06 sind in `db/tests/` bereits belegt).
- **API:** Resource Server prüft Signatur, `iss`, `aud`, `exp` (JWKS mit Rotation); Fehler (falsche Signatur, fremder Issuer, falsche Audience, abgelaufen, `alg=none`) ergibt 401. Service-Accounts über Client Credentials, pro Adapter und Zweck begrenzt (außerhalb Scope 403, keine Menschen-Sitzung, kein Mandantenwechsel).
- **IdP-Realm als Code (`infra/`):** Clients Passenger-Web und Operator-Web (public, Authorization Code + PKCE); Passwort-Policy mit adaptivem Hash (Algorithmus und Parameter sind im Realm-Export festzuhalten); passwortloser Anmeldeweg konfiguriert; getrennte Identitätsdomänen je Umgebung (Kap. 01 §1.8).
- **Zustandsautomat:** keiner. **JSON-Schema / Ereignisse:** Token-Claims-Vertrag (Subject, Authentisierungsstärke, Session-ID) unter `vertraege/schemas/` vor Codebeginn.
- **Parameter und Kennzahlen:** keine in `kataloge.csv`.
- **Oberfläche:** keine (Login-Oberfläche WP-M1-44).
- **Entscheidungen:** D-05 (Hostingregion/verwalteter IdP) – blockiert nur Produktivschaltung.
- **Offene Punkte:** OP-12 (Verfahren und Zielgruppe der passwortlosen Anmeldung nicht benannt; Abnahme als Konfigurationsnachweis des IdP, mit Auftraggeber abzustimmen); OP-13 (Tokenlaufzeiten nicht beziffert). Blockiert den Bau nicht; Hash-Algorithmus und Parameter sind im Realm-Vertrag festzulegen.

## Tests

- **Positiv:** Login mit PKCE; Sicherheitsprüfung (Schema-Scan, IdP-Konfigurationsexport, Log-Stichprobe) findet keine Klartextpasswörter und keinen unsicheren Standard-Hash; IdP-Export zeigt adaptiven Hash und konfigurierten passwortlosen Weg.
- **Negativ:** falscher Issuer und falsche Audience (401); Schema-Lint schlägt bei Spalte `password*` an; Passwort im Klartext in Logs wird erkannt (Log-Stichprobe mit Testpasswort); manipulierte Signatur; `alg=none`; abgelaufenes Token; Token einer Staging-Domäne gegen Produktion; Service-Account außerhalb seines Scopes (403), Service-Account mit Mandantenwechsel oder Menschen-Sitzung (abgewiesen).
- **Konkurrierend:** JWKS-Schlüsselrotation während laufender Anfragen ohne Annahme alter, zurückgezogener Schlüssel.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: db/tests/test_08_keine_passwortspalten.py::test_schema_ohne_password_spalten; backend/app/src/test/kotlin/de/taktovia/app/security/ResourceServerTest.kt::pkce_login_und_gueltiges_token_erfolgreich; backend/app/src/test/kotlin/de/taktovia/app/security/ResourceServerTest.kt::falscher_issuer_audience_und_abgelaufen_ergeben_401; backend/app/src/test/kotlin/de/taktovia/app/security/ResourceServerTest.kt::staging_token_gegen_produktion_abgewiesen; backend/app/src/test/kotlin/de/taktovia/app/security/ServiceAccountScopeTest.kt::ausserhalb_scope_403_kein_mandantenwechsel; backend/app/src/test/kotlin/de/taktovia/app/security/PasswordLogScanTest.kt::testpasswort_nicht_in_logs; backend/app/src/test/kotlin/de/taktovia/app/security/ResourceServerTest.kt::manipulierte_signatur_und_alg_none_ergeben_401; backend/app/src/test/kotlin/de/taktovia/app/security/IdpRealmExportTest.kt::adaptiver_hash_und_passwortlos_konfiguriert; backend/app/src/test/kotlin/de/taktovia/app/security/ResourceServerTest.kt::jwks_rotation_waehrend_anfragen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
