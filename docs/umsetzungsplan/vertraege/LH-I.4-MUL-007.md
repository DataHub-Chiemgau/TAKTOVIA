# LH-I.4-MUL-007 – Isolation außerhalb der Datenbank und mandantengetrennte Bestände

- **Verantwortlicher:** Rolle Plattform (Modul `platform`)
- **Arbeitspaket:** WP-M1-11
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jeder Mandant besitzt getrennte Rollen, Dienstpläne, Fahrzeuge, Tarife, Finanzdaten, Marken und Benachrichtigungsregeln.

**Originalabnahme (unverändert):** Änderung der Tarifkonfiguration in Mandant A hat keine Auswirkungen auf B.

## Vertrag

- **Tabellen / DDL:** Nachweis, dass `config_revision`, `membership` und alle Stammdaten aus WP-M1-05/09 mandantenlokal sind (`tenant_id` im Primärschlüssel, RLS V1, `tenant_id` unveränderlich V3). Marken- und Benachrichtigungsregel-Einstellungen je Mandant als Konfigurationswerte im Register (`config_revision`, `scope_kind` Mandant); Rollenzuweisungen je Mandant in `membership`. Keine neue Tabelle für Marken (Datenmodell Branding nicht spezifiziert).
- **API:** kein eigener Pfad; Nachweis über `/v1/config/effective` (Vorher/Nachher-Vergleich für Mandant B identisch nach publizierter Änderung in A) und alle M1-Endpunkte. Fremde und nicht vorhandene Objekte antworten gleichartig (404).
- **Außerhalb der Datenbank:** Cache-Key-Baustein (Tenant, Dienst, Ressourcen-ID, Policyrevision; Kap. 02 §4.3); Objektspeicher mit Mandantenpräfix, signierte URLs erst nach Objekt-/Zweckprüfung, Default höchstens 60 s (Planungsdefault); Ereignishülle mit Eigentums-`tenant_id` und expliziter Empfängeradressierung (JSON-Schema mit dem Bau unter `api/events/`); Listen-/Export-Baustein mit erzwungenem Tenantfilter. Architekturtests „kein Cache-/Storage-Zugriff ohne Tenant im Schlüssel".
- **Zustandsautomat:** keine.
- **Parameter und Kennzahlen:** `notification_quiet_hours` (Mandant, Zeitspanne, Sicherheitsalarme ausgenommen), `customer_notify_before_pickup_min` (Dienst, Minuten), `reporting_timezone` (Mandant, IANA TZ, DST-sicher) als Beispiele mandantengetrennter Werte (`kataloge.csv`).
- **Oberfläche:** keine eigene.
- **Entscheidungen:** D-02 – blockiert den Bau nicht.
- **Offene Punkte:** OP-09 (Datenmodell Marke/Branding und Benachrichtigungsregeln im Pflichtenheft nicht spezifiziert; Dienstpläne, Fahrzeuge, Tarife, Finanzdaten entstehen erst in M2–M4). Tarifnachweis an `tariff_revision` und vollständiger Scan (Such-, Medien-, Beleg-, Export-, Finanzendpunkte) mit WP-M2-07, WP-M2-38, WP-M4-21, WP-M4-22, WP-M4-33, WP-M4-37, WP-M4-64; erst dann ist PH-AT-001 vollständig.

## Tests

- **Positiv:** A und B arbeiten parallel; eine publizierte Konfigurationsänderung in A lässt `/v1/config/effective` von B unverändert (Abnahme); Rollen, Marken- und Benachrichtigungseinstellungen je Mandant getrennt abrufbar.
- **Negativ:** bekannte B-UUID über jeden M1-Endpunkt (Detail, Liste, Änderung, Löschung); signierte URL von B mit Sitzung A; Cache-Poisoning-Versuch mit identischer Ressourcen-ID; Ereigniskanal liefert nichts an Fremdadressaten; Sicherheitsereignis ohne B-Personen-/Inhaltsdaten.
- **Konkurrierend (Datentrennung):** gleichzeitige Publikation in A und B mit identischer `scope_id` – keine gegenseitige Wirkung; kein Cacheeintrag, keine Zeile, kein Ereignis in B durch Änderungen in A.
- **Referenztests:** PH-AT-001 (soweit M1-Endpunkte), E2E-091, AT-18.
- Testfälle: AT-18; E2E-091; PH-AT-001; geplant:db/tests/test_mandantenlokale_bestaende.py::test_config_revision_membership_stammdaten_haben_tenant_id_im_pk_und_rls; geplant:tests/acceptance/test_mandantenisolation_m1.py::test_fremde_b_uuid_ueber_jeden_m1_endpunkt_gleichartig_404_ohne_b_daten_im_sicherheitsereignis; geplant:tests/acceptance/test_mandantenisolation_m1.py::test_rollen_marken_benachrichtigungswerte_je_mandant_getrennt; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/TenantIsolationTest.kt::signierte_url_von_b_mit_sitzung_a_abgewiesen_und_hoechstens_60s; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/TenantIsolationTest.kt::cache_poisoning_identische_ressourcen_id; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/TenantIsolationTest.kt::ereignis_ohne_empfaengeradressierung_an_fremdadressat_abgewiesen; geplant:tests/acceptance/test_mandantenisolation_m1.py::test_konfigaenderung_a_aendert_wirksame_konfiguration_b_nicht; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/TenantIsolationTest.kt::cache_key_ohne_tenant_wird_abgewiesen; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/TenantIsolationTest.kt::parallele_publikation_gleiche_scope_id_ohne_wirkung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
