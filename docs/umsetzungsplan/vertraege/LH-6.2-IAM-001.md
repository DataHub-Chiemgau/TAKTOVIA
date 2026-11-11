# LH-6.2-IAM-001 – Fahrgastregistrierung mit Kontaktverifikation

- **Verantwortlicher:** Rolle Plattform (Modul `identity`)
- **Arbeitspaket:** WP-M1-28
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrgäste können sich per E-Mail oder Telefon mit sicheren Verifikationsmechanismen registrieren.

**Originalabnahme (unverändert):** Unverifizierte Kontaktdaten können nicht für sicherheitsrelevante Kontowiederherstellung verwendet werden.

## Vertrag

- **Tabellen / DDL:** `passenger_private` (`tenant_id`, `id`, `subject_token`, `contact_ciphertext bytea`, `retention_until`; `UNIQUE(tenant_id,subject_token)`, RLS aus V1). Kontaktdaten liegen ausschließlich verschlüsselt in `contact_ciphertext`. Neue Migration (nächste freie Version nach `V3__tenant_id_unveraenderlich.sql`, also V4): Verifikationsstatus und -zeitpunkt je Kontaktkanal (E-Mail, Telefon); Feldliste im Kern-DDL nicht enthalten (OP-23), wird mit der Migration festgelegt. Ein geänderter Kontakt setzt den Kanal auf unverifiziert zurück. Verschlüsselungs-/Schlüsselvertrag für `contact_ciphertext` ist vor Codebeginn zu beschreiben; darin ist auch festzulegen, wie `subject_token` gebildet wird (im Pflichtenheft nicht festgelegt). Der Test „gleicher Kontakt, ein Konto“ setzt gleichen `subject_token` voraus.
- **API:** `/v1/passengers` (Konto anlegen/lesen) und `/v1/me` (Verifikationsstatus). Für `/v1/passengers` existiert noch kein OpenAPI-Vertrag; er wird vor Codebeginn in `api/` angelegt (Statuscodes als Vorschlag, im OpenAPI-Vertrag zu bestätigen: 201, 400, 404, 409, 429). Wiederherstellungsantworten verraten nicht, ob ein Konto existiert (gleichförmige Antwort).
- **Verfahren:** Registrierung über den IdP (Self-Registration, Verifikationsaufgabe); E-Mail-Code über den IdP; Telefon über einen Versand-Port mit Test-SMS-Adapter in M1 (produktiver SMS-Kanal über Adapter `notification` mit M2). Verifikationscodes sind einmalig, zeitlich begrenzt, werden weder im Klartext gespeichert noch geloggt. Wiederherstellungsregel: nur verifizierter Kanal. Verifikationsversuche sind rate-limitiert und werden über den Sicherheitsereignis-Port (WP-M1-46) protokolliert.
- **Zustandsautomat:** Kanalstatus `UNVERIFIED` -> `VERIFIED`, Rücksprung nach `UNVERIFIED` bei Kontaktänderung. Kein eigenes Dokument unter `vertraege/zustaende/`; wird bei Bau in dieser Datei ergänzt.
- **JSON-Schema / Ereignisse:** Sicherheitsereignisse (Verifikationsversuch, Limit überschritten) über Port WP-M1-46; Schema dort.
- **Parameter und Kennzahlen:** keine in `kataloge.csv`. Codegültigkeit und Versuchslimit sind im Pflichtenheft nicht beziffert (OP-23).
- **Oberfläche:** UI-05 Basis: Registrierung, Kontakt verifizieren; Zustand „Verifikation fehlt".
- **Entscheidungen:** D-11 (Löschfristen für Kontaktdaten, `retention_until`) – blockiert nur Produktivschaltung.
- **Offene Punkte:** OP-23 (Registrierung vollständig im IdP oder eigene Oberfläche; Felder je Kanal; Codegültigkeit/Versuchslimit nicht beziffert; kein OpenAPI-Vertrag). Produktiver SMS-Kanal erst M2, Vollabnahme mit WP-M2-32, WP-M2-35, WP-M2-36 (OP-01, OP-34). Blockiert den Bau nicht, sofern Zahlen als Parameter mit Vorschlagswert geführt werden; Beschluss vor Produktivschaltung.

## Tests

- **Positiv:** Registrierung per E-Mail und per Telefonnummer (Test-SMS-Adapter); nach Verifikation ist der Kanal `VERIFIED`.
- **Negativ:** falscher Code; abgelaufener Code; Wiederholung eines bereits verbrauchten Codes; Wiederherstellung über unverifizierten Kanal wird abgelehnt; Konto-Enumeration (Antworten für existierendes und nicht existierendes Konto nicht unterscheidbar); Brute-Force auf Code (Rate-Limit 429, Sicherheitsereignis); geänderter Kontakt ist bis zur Neuverifikation nicht für Wiederherstellung nutzbar; Code erscheint weder in Logs noch in der Datenbank im Klartext; Kontaktdaten nur als `contact_ciphertext`; Mandant A kann `passenger_private`-Zeilen von Mandant B weder lesen noch über `/v1/passengers` (404) abrufen (RLS).
- **Konkurrierend:** zwei parallele Registrierungen mit demselben Kontakt ergeben genau ein Konto (`UNIQUE(tenant_id,subject_token)`); paralleler Verifikationsversuch mit demselben Code gelingt höchstens einmal.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::registrierung_email_und_telefon_kanal_verifiziert; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::falscher_abgelaufener_verbrauchter_code_abgelehnt; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::unverifizierter_kontakt_nicht_fuer_wiederherstellung; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::wiederherstellung_antwort_ohne_kontoenumeration; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::kontaktaenderung_setzt_kanal_zurueck; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::brute_force_ergibt_429_und_sicherheitsereignis; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::code_nie_im_klartext_in_log_oder_db; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::parallele_registrierung_genau_ein_konto; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerRegistrationTest.kt::paralleler_verifikationsversuch_hoechstens_einmal; db/tests/test_08_passenger_kontakt.py::test_fremder_mandant_liest_keinen_kontakt; backend/app/src/test/kotlin/de/taktovia/app/identity/PassengerApiTest.kt::registrierung_ueber_api_verifikation_und_profil; backend/app/src/test/kotlin/de/taktovia/app/identity/PassengerApiTest.kt::selbstregistrierung_nur_fuer_passenger_web_mit_genau_einem_mandanten; backend/app/src/test/kotlin/de/taktovia/app/identity/PassengerApiTest.kt::brute_force_ueber_api_ergibt_429_mit_retry_after; backend/app/src/test/kotlin/de/taktovia/app/identity/PassengerApiTest.kt::code_und_kontakt_nie_im_log_ueber_api; db/tests/test_08_passenger_kontakt.py::test_kontaktaenderung_setzt_kanal_zurueck_und_ist_unverifiziert; web/passenger/src/registrierung/registrierung.test.tsx

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
