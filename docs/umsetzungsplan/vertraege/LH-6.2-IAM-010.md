# LH-6.2-IAM-010 – OIDC-Ressourcenserver, IdP-Realm und MFA

- **Verantwortlicher:** Rolle Plattform (Modul `identity`, `backend/app`)
- **Arbeitspaket:** WP-M1-04
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Für privilegierte Konten ist Mehrfaktor-Authentifizierung vorgesehen.

**Originalabnahme (unverändert):** Administratorzugriff ohne zweiten Faktor schlägt fehl.

## Vertrag

- **Tabellen / DDL:** keine; Rollen aus `membership.role_code` (WP-M1-07).
- **API:** MFA-Durchsetzung über den Authentisierungsnachweis im Token (Authentisierungsstärke aus dem Token-Claims-Vertrag, siehe IAM-002). Gültiges Token einer privilegierten Rolle ohne zweiten Faktor wird an jeder geschützten API mit 403 abgewiesen; mit zweitem Faktor gelingt der Zugriff. Abweisung wird über den Sicherheitsereignis-Port (WP-M1-46) protokolliert; Abfrage und Alarmierung weist WP-M1-12 nach.
- **IdP-Realm (`infra/`):** MFA-Pflichtfluss für privilegierte Rollen.
- **Zustandsautomat / Schema / Parameter:** keine. **Oberfläche:** keine (Web-Login WP-M1-44).
- **Entscheidungen:** D-05 – blockiert nur Produktivschaltung.
- **Offene Punkte:** OP-11 („privilegierte Konten" nicht definiert; Kap. 02 §4.1 verlangt MFA für Mitarbeiter allgemein). Die Liste privilegierter Rollen ist vor Codebeginn festzulegen; sie blockiert die Mechanik nicht, sie ist Konfigurationsdaten. Mindestens die Administratorrolle (Originalabnahme) gilt als privilegiert.

## Tests

- **Positiv:** Administrator mit zweitem Faktor greift erfolgreich zu.
- **Negativ:** Administrator mit gültigem Token ohne zweiten Faktor erhält an jedem geschützten Endpunkt 403 und es entsteht ein Sicherheitsereignis; Token mit manipulierten Authentisierungsclaims wird abgewiesen; Nicht-privilegierte Rolle wird nicht fälschlich geblockt.
- **Konkurrierend:** Rollenzuweisung „Administrator" während laufender Sitzung ohne zweiten Faktor – Folgeanfrage wird abgewiesen; parallele Anfragen mit und ohne zweiten Faktor werden jeweils korrekt beschieden.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/app/src/test/kotlin/de/taktovia/app/security/MfaEnforcementTest.kt::admin_ohne_zweiten_faktor_403; geplant:backend/app/src/test/kotlin/de/taktovia/app/security/MfaEnforcementTest.kt::admin_mit_zweitem_faktor_erfolgreich; geplant:backend/app/src/test/kotlin/de/taktovia/app/security/MfaEnforcementTest.kt::jeder_geschuetzte_endpunkt_verlangt_mfa_fuer_admin; geplant:backend/app/src/test/kotlin/de/taktovia/app/security/MfaEnforcementTest.kt::abweisung_erzeugt_sicherheitsereignis; geplant:backend/app/src/test/kotlin/de/taktovia/app/security/MfaEnforcementTest.kt::manipulierte_authentisierungsclaims_abgewiesen; geplant:backend/app/src/test/kotlin/de/taktovia/app/security/MfaEnforcementTest.kt::nicht_privilegierte_rolle_nicht_geblockt; geplant:backend/app/src/test/kotlin/de/taktovia/app/security/MfaEnforcementTest.kt::rollenzuweisung_waehrend_sitzung_ohne_mfa_abgewiesen; geplant:backend/app/src/test/kotlin/de/taktovia/app/security/MfaEnforcementTest.kt::parallele_anfragen_mit_und_ohne_faktor; geplant:backend/app/src/test/kotlin/de/taktovia/app/security/IdpRealmExportTest.kt::mfa_pflichtfluss_fuer_privilegierte_rollen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
