# LH-6.2-IAM-006 – Fahrgastprofil und Präferenzen

- **Verantwortlicher:** Rolle Plattform (Modul `identity`)
- **Arbeitspaket:** WP-M1-29
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Profilverwaltung umfasst Kontaktdaten, Sprache, Favoriten, Benachrichtigungskanäle und bevorzugte Haltestellen.

**Originalabnahme (unverändert):** Änderungen sind nach erneuter Anmeldung konsistent abrufbar.

## Vertrag

- **Tabellen / DDL:** `passenger_private` (Kontakt verschlüsselt in `contact_ciphertext`) und neue Tabelle `passenger_preference` (Passenger 1:n Präferenz, Kap. 04 §4.4) mit `(tenant_id,id)`, RLS und Revision. Feldliste von `passenger_preference` ist im Pflichtenheft nicht festgelegt (OP-30) und wird im DDL-Vertrag vor Codebeginn bestimmt. Bevorzugte Haltestellen referenzieren `stop_revision` (Fixture-Haltepunkte in M1). Trennung Kontakt (verschlüsselt) von Präferenzen.
- **API:** `/v1/me` (eigenes Profil lesen/ändern) mit Revision/ETag; Änderungen mit `If-Match`, veralteter ETag ergibt 412. `/v1/passengers`: nur berechtigte, minimierte Betreibersicht; Kontakt und Unterstützungsmerkmale nur mit Feldrecht und Zweck (WP-M1-31). Fremde Profil-ID ergibt 404. Profil-Schema in OpenAPI vor Codebeginn.
- **Zustandsautomat / Schema / Ereignisse / Parameter:** keine.
- **Oberfläche:** UI-05 Basis, Konto: Kontakt, Sprache, Benachrichtigungskanäle, Favoriten, bevorzugte Haltestellen; Zustände „Verifikation fehlt", „Löschstatus"; Einbettung der Auswahl des Unterstützungsbedarfs aus WP-M1-31.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-30 (Unterschied Favoriten und bevorzugte Haltestellen nicht beschrieben; Darstellung vor Haltestellenstamm; Feldliste `passenger_preference`); OP-34 (UI-05 in M1 oder M2); OP-01 (Nachweis gegen gepflegten Haltepunktstamm in M2, Vollabnahme WP-M2-05, WP-M2-35, WP-M2-36). Blockiert den Bau nicht, M1-Nachweis mit Fixture-Haltepunkten.

## Tests

- **Positiv:** alle fünf Profilbereiche ändern, abmelden, neu anmelden, abrufen (auch von einem zweiten Gerät) – unverändert.
- **Negativ:** fremdes Profil (404); ungültige Sprache (zulässige Sprachwerte sind im Pflichtenheft nicht aufgelistet und im Profil-Schema festzulegen); nicht existierende Haltestellenreferenz; veralteter ETag (412); Betreibersicht zeigt Kontakt und Unterstützungsmerkmale nicht ohne Feldrecht und Zweck.
- **Konkurrierend (Datentrennung):** parallele Änderung von zwei Sitzungen führt nicht zu stillem Überschreiben (412); Mandant A kann Profile von Mandant B nicht lesen (RLS, `passenger_preference` eingeschlossen).
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerProfileTest.kt::aenderungen_nach_neuanmeldung_konsistent; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerProfileTest.kt::fremdes_profil_ergibt_404; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerProfileTest.kt::ungueltige_sprache_und_unbekannte_haltestelle_abgewiesen; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerProfileTest.kt::veralteter_etag_ergibt_412; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerProfileTest.kt::betreibersicht_ohne_feldrecht_ohne_kontakt; backend/modules/identity/src/test/kotlin/de/taktovia/identity/PassengerProfileTest.kt::parallele_aenderung_zwei_sitzungen_412; db/tests/test_10_passenger_preference.py::test_rls_fremder_mandant_liest_keine_praeferenz; web/passenger/src/konto/konto.test.tsx::fuenf_profilbereiche_aenderbar_und_neu_geladen; backend/app/src/test/kotlin/de/taktovia/app/identity/PassengerApiTest.kt::profil_aendern_etag_412_428_und_ungueltige_werte; backend/app/src/test/kotlin/de/taktovia/app/identity/PassengerApiTest.kt::mandantentrennung_fremdes_profil_und_betreibersicht; db/tests/test_10_passenger_preference.py::test_unbekannte_und_fremde_haltestelle_wird_abgewiesen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
