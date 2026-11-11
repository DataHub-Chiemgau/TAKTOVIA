# LH-6.1-SYS-001 – Isolation außerhalb der Datenbank und mandantengetrennte Bestände

- **Verantwortlicher:** Rolle Plattform (Modul `backend/platform`)
- **Arbeitspaket:** WP-M1-11
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Plattform verwaltet unabhängig voneinander mehrere Betreiber, Verkehrsangebote und Regionen mit jeweils eigenen Ressourcen und Regeln.

**Originalabnahme (unverändert):** Ein Testmandant kann keine Objekte eines anderen Mandanten lesen oder verändern.

## Vertrag

- **Tabellen / DDL:** Keine neuen Tabellen. Nachweis, dass `config_revision`, `membership` und alle Stammdaten aus WP-M1-05/-09 (`organization`, `service`, `cost_object`) mandantenlokal sind: `tenant_id` NOT NULL, zusammengesetzte Schlüssel `(tenant_id,id)`, zusammengesetzte FKs, RLS aktiv (Kap. 04 §4.5/§4.6; Grundlage WP-M1-01). Datenbanktest unter der nicht privilegierten Anwendungsrolle, nicht als Tabellenbesitzer. Mandantenbezogene Marken-/Benachrichtigungsregel-Einstellungen liegen als Werte in `config_revision` (Datenmodell für Marke offen, OP-09).
- **Außerhalb der Datenbank (Kern dieses Vertrags):**
  - Cache-Schlüssel enthalten immer Tenant, Dienst, Ressourcen-ID und Policyrevision (Kap. 02 §4.3); Zugriff ohne Tenant im Schlüssel ist per Architekturtest verboten.
  - Objektspeicher: Schlüssel mit Mandantenpräfix; signierte URLs erst nach Objekt- und Zweckprüfung, Gültigkeit Default höchstens 60 s (Planungsdefault Kap. 02 §4.3); URL von Mandant B mit Sitzung A wird abgewiesen.
  - Ereignishülle: Eigentums-`tenant_id` plus explizite Empfängeradressierung (JSON-Schema `api/events/`, Name im Bau festzulegen; Adressierungsfelder fehlen im Kern-DDL, OP-16).
  - Listen-/Export-Baustein mit erzwungenem Tenantfilter.
- **API:** Alle M1-Endpunkte (Detail, Liste, Änderung, Löschung). Fremdes und nicht vorhandenes Objekt liefern gleichartige Antwort (404 `NOT_FOUND` als `application/problem+json`, keine Existenzdetails; Fehlermodell Kap. 05 §5.1 und Fehlertabelle "Fremdes Objekt"; fehlender Funktionsscope bleibt 403). Manipulierter Mandantenheader wird nicht als Autorisierungsquelle verwendet. Dokumentierte Liste der M1-Lese-, Listen- und Exportwege dient als Eingabe des Cross-Tenant-Scans.
- **Festlegung im Bau (WP-M1-11):** Bausteine in `backend/platform`: `cache.CacheKey`/`TenantCache` (Schlüssel `t:<mandant>:s:<dienst|->:r:<ressource>:p:<policyrevision>`, In-Memory und Redis), `storage.ObjectKey`/`TenantObjectStore`/`SignedUrls` (Schlüssel `tenants/<mandant>/<name>`, URL `/v1/objects/<mandant>/<name>?exp&purpose&sig`, HMAC-SHA256, höchstens 60 s, fremder Mandant = 404; der S3/MinIO-Adapter ist nicht gebaut und nicht ausgeführt), `outbox.EnvelopeAddressing` (Eigentümer + Empfänger Pflicht, `RecipientPolicy`), `isolation.TenantListing` (erzwungener Tenantfilter, Positivliste). Architekturregeln `architecture/IsolationRules`. Der Cross-Tenant-Scan liegt in `app/isolation/CrossTenantIsolationTest` und prüft jeden gebauten Endpunkt mit Pfadparameter und jeden Listenweg auf Vollständigkeit.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Ereignishülle mit `tenant_id` und Empfängeradressierung; Sicherheitsereignis bei Fremdzugriff ohne Personen-/Inhaltsdaten von B (Audit über WP-M1-12/-46).
- **Parameter und Kennzahlen:** `notification_quiet_hours`, `customer_notify_before_pickup_min`, `reporting_timezone` als Beispiele mandantengetrennter Werte (kataloge.csv J.7: `notification_quiet_hours` und `reporting_timezone` Ebene Mandant, `customer_notify_before_pickup_min` Ebene Dienst); `/v1/config/effective` für B vor/nach Publikation in A identisch.
- **Oberfläche:** keine eigene; Fehlerzustand "nicht gefunden" bleibt für fremde und fehlende Objekte gleich.
- **Entscheidungen:** D-02 (blockiert den Bau nicht).
- **Offene Punkte:** OP-09 (Datenmodell Marke/Benachrichtigungsregeln je Mandant); OP-16 (Adressierungsfelder Outbox); OP-01/OP-02/OP-38: Cross-Tenant-Scan in M1 nur über M1-Endpunkte und technische Wege; Such-, Medien-, Beleg-, Export- und Finanzendpunkte folgen mit M2/M4, erst dann ist PH-AT-001 vollständig (Vollabnahme WP-M2-07, WP-M2-38, WP-M4-21, WP-M4-22, WP-M4-33, WP-M4-37, WP-M4-64). Blockiert den Bau nicht.

## Tests

- **Positiv:** Mandanten A und B arbeiten parallel; zwei Mandanten verwalten unabhängig Dienste, Regionen, Stammdaten und Regeln; Anlegen/Ändern in A erzeugt keine Zeile, kein Ereignis, keinen Cacheeintrag in B; Rollenzuweisungen sowie Marken- und Benachrichtigungsregel-Einstellungen je Mandant getrennt abrufbar.
- **Negativ:** bekannte B-UUID über jeden M1-Endpunkt (Detail, Liste, Änderung, Löschung) mit Sitzung A; signierte Objektspeicher-URL von B mit Sitzung A; Cache-Poisoning-Versuch mit identischer Ressourcen-ID; UPDATE mit fremdem FK; manipulierter Mandantenheader; leerer Transaktionskontext und Connection-Pool-Wiederverwendung ohne Resttenantkontext; Architekturtest "kein Cache-/Storage-Zugriff ohne Tenant im Schlüssel"; Sicherheitsprotokoll enthält bei Fremdzugriff ein Ereignis ohne B-Personen-/Inhaltsdaten; Ereignis (Outbox/Subscription) von A wird nie an einen Empfänger von B adressiert und eine Ereignishülle ohne Eigentums-`tenant_id` oder ohne Empfängeradressierung wird abgewiesen; Listen-/Export-Baustein ohne Tenantfilter ist per Architekturtest nicht verwendbar; Datenbanktest unter der nicht privilegierten Anwendungsrolle (RLS und zusammengesetzte FKs, kein Tabellenbesitzer).
- **Konkurrierend:** gleichzeitige Publikation in A und B mit identischer `scope_id` – keine gegenseitige Wirkung; Vorher/Nachher-Vergleich von `/v1/config/effective` für B identisch; parallele Kontextwechsel A/B auf demselben Connection-Pool ohne Kontextrest (Verweis auf `db/tests/test_isolation_alle_tabellen.py`).
- **Referenztests:** PH-AT-001 (soweit M1-Endpunkte), E2E-091, AT-18.

Testfälle: PH-AT-001; E2E-091; AT-18; geplant:backend/app/src/test/kotlin/de/taktovia/app/isolation/CrossTenantIsolationTest.kt::fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen; geplant:backend/app/src/test/kotlin/de/taktovia/app/isolation/CrossTenantIsolationTest.kt::manipulierte_mandantenangabe_wird_nicht_beachtet_und_sicherheitsereignis_enthaelt_keine_b_daten; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/isolation/ObjectStorageIsolationTest.kt::signierte_url_von_b_mit_sitzung_a_abgewiesen_und_hoechstens_60s; geplant:backend/app/src/test/kotlin/de/taktovia/architecture/CacheKeyArchitectureTest.kt::kein_cache_zugriff_ohne_tenant_im_schluessel; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/isolation/EventEnvelopeIsolationTest.kt::ereignis_nur_an_adressierte_empfaenger_des_eigentuemers; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/isolation/TenantListingIsolationTest.kt::liste_und_export_zeigen_nur_den_eigenen_mandanten; geplant:db/tests/test_isolation_alle_tabellen.py::test_keine_cross_tenant_leaks_alle_tabellen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (erforderlich: Datentrennung) | | | |
