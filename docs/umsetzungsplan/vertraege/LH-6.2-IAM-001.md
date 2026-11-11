# LH-6.2-IAM-001 – Fahrgastregistrierung mit Kontaktverifikation

- **Verantwortlicher:** Rolle Plattform (Modul `identity`)
- **Arbeitspaket:** WP-M1-28
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrgäste können sich per E-Mail oder Telefon mit sicheren Verifikationsmechanismen registrieren.

**Originalabnahme (unverändert):** Unverifizierte Kontaktdaten können nicht für sicherheitsrelevante Kontowiederherstellung verwendet werden.

## Vertrag

- **Tabellen / DDL:** `passenger_private` (`tenant_id`, `id`, `subject_token`, `contact_ciphertext bytea`, `retention_until`; `UNIQUE(tenant_id,subject_token)`, RLS aus V1). Kontaktdaten liegen ausschließlich verschlüsselt in `contact_ciphertext`. Neue Migration (ab V3): Verifikationsstatus und -zeitpunkt je Kontaktkanal (E-Mail, Telefon); Feldliste im Kern-DDL nicht enthalten (OP-23), wird mit der Migration festgelegt. Ein geänderter Kontakt setzt den Kanal auf unverifiziert zurück. Verschlüsselungs-/Schlüsselvertrag für `contact_ciphertext` ist vor Codebeginn zu beschreiben.
- **API:** `/v1/passengers` (Konto anlegen/lesen) und `/v1/me` (Verifikationsstatus). Für `/v1/passengers` existiert noch kein OpenAPI-Vertrag; er wird vor Codebeginn in `api/` angelegt (Statuscodes: 201, 400, 404, 409, 429). Wiederherstellungsantworten verraten nicht, ob ein Konto existiert (gleichförmige Antwort).
- **Verfahren:** Registrierung über den IdP (Self-Registration, Verifikationsaufgabe); E-Mail-Code über den IdP; Telefon über einen Versand-Port mit Test-SMS-Adapter in M1 (produktiver SMS-Kanal über Adapter `notification` mit M2). Verifikationscodes sind einmalig, zeitlich begrenzt, werden weder im Klartext gespeichert noch geloggt. Wiederherstellungsregel: nur verifizierter Kanal. Verifikationsversuche sind rate-limitiert und werden über den Sicherheitsereignis-Port (WP-M1-46) protokolliert.
- **Zustandsautomat:** Kanalstatus `UNVERIFIED` -> `VERIFIED`, Rücksprung nach `UNVERIFIED` bei Kontaktänderung. Kein eigenes Dokument unter `vertraege/zustaende/`; wird bei Bau in dieser Datei ergänzt.
- **JSON-Schema / Ereignisse:** Sicherheitsereignisse (Verifikationsversuch, Limit überschritten) über Port WP-M1-46; Schema dort.
- **Parameter und Kennzahlen:** keine in `kataloge.csv`. Codegültigkeit und Versuchslimit sind im Pflichtenheft nicht beziffert (OP-23).
- **Oberfläche:** UI-05 Basis: Registrierung, Kontakt verifizieren; Zustand „Verifikation fehlt".
- **Entscheidungen:** D-11 (Löschfristen für Kontaktdaten, `retention_until`) – blockiert nur Produktivschaltung.
- **Offene Punkte:** OP-23 (Registrierung vollständig im IdP oder eigene Oberfläche; Felder je Kanal; Codegültigkeit/Versuchslimit nicht beziffert; kein OpenAPI-Vertrag). Produktiver SMS-Kanal erst M2, Vollabnahme mit WP-M2-32, WP-M2-35, WP-M2-36 (OP-01, OP-34). Blockiert den Bau nicht, sofern Zahlen als Parameter mit Vorschlagswert geführt werden; Beschluss vor Produktivschaltung.

## Tests

- **Positiv:** Registrierung per E-Mail und per Telefonnummer (Test-SMS-Adapter); nach Verifikation ist der Kanal `VERIFIED`.
- **Negativ:** falscher Code; abgelaufener Code; Wiederholung eines bereits verbrauchten Codes; Wiederherstellung über unverifizierten Kanal wird abgelehnt; Konto-Enumeration (Antworten für existierendes und nicht existierendes Konto nicht unterscheidbar); Brute-Force auf Code (Rate-Limit 429, Sicherheitsereignis); geänderter Kontakt ist bis zur Neuverifikation nicht für Wiederherstellung nutzbar; Code erscheint weder in Logs noch in der Datenbank im Klartext; Kontaktdaten nur als `contact_ciphertext`.
- **Konkurrierend:** zwei parallele Registrierungen mit demselben Kontakt ergeben genau ein Konto (`UNIQUE(tenant_id,subject_token)`); paralleler Verifikationsversuch mit demselben Code gelingt höchstens einmal.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::unverifizierter_kontakt_nicht_fuer_wiederherstellung; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::parallele_registrierung_genau_ein_konto

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
