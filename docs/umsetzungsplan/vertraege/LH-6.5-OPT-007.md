# LH-6.5-OPT-007 – Routingadapter und Fahrzeitmatrix

- **Verantwortlicher:** Rolle Routing (`workers/routing`, Port `RoutingPort` im Modul `booking-dispatch`)
- **Arbeitspaket:** WP-M2-10
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Routing beachtet Straßengraph, erlaubte Fahrtrichtungen, Fahrzeugrestriktionen und definierte Stopps.

**Originalabnahme (unverändert):** Teststrecke mit gesperrtem Abschnitt wird nicht darüber geplant.

## Vertrag

- **Tabellen / DDL:** Neue Tabelle `route_matrix_cache` (neue Migration nach V3, nicht in V1–V3 vorhanden) mit `tenant_id` und RLS; Cache-Key aus Start, Ziel, Fahrzeugprofil und Kartendatenversion. Redis nur als flüchtiger Cache (Leitlinie 4), Keys enthalten den Mandanten (Kap. 01 §1.7). Cache-Treffer mit anderer Kartendatenversion werden nicht verwendet; bei neuem Kartenstand werden alte Einträge ungültig.
- **API:** Kein öffentlicher Endpunkt. Interner Port `RoutingPort` mit Operationen Matrix (n x m Fahrzeiten/Distanzen), Einzelroute mit Geometrie, Fußweg; Ergebnis trägt Fahrzeugprofil und Kartendatenversion; Timeout oder Ausfall der Kartenquelle liefert einen definierten Fehler, nie ein geschätztes Ergebnis. Fehler wird als technischer Fehler behandelt (OPT-011).
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Keine Ereignisse. Anbieterneutrale Schnittstelle (Arbeitsstand Valhalla/OSM, Kap. 01 §1.1); Anfrage-/Antwort-/Fehlerschema des Ports wird im Bau als Kotlin-Typen in `booking-dispatch` festgelegt.
- **Parameter und Kennzahlen:** Zeitbudget innerhalb der 1 s lokalen Kandidatensuche (Planungsdefault Kap. 01 §1.4). Fahrzeugprofil (Abmessungen/Restriktionen) als Parameter. Keine Katalogparameter.
- **Oberfläche:** keine.
- **Entscheidungen:** D-09 (Routing, Karten, Telematik: Bau mit Valhalla/OSM; Produktivschaltung erst nach Beschluss und Lizenzprüfung). Aufrufart Kern <-> Worker aus Spike WP-M0-27.
- **Offene Punkte:** Aufrufart Kern <-> Worker (synchron oder Queue) offen bis Empfehlung aus Spike WP-M0-27, siehe OP-38 des WP-Dokuments (Schnittstelle Kern/Routing-Worker); Cache-Invalidierung bei neuem Kartenstand wird im Bau festgelegt. Blockiert den Bau nicht.

## Tests

- **Positiv:** Teststrecke mit gesperrtem Abschnitt: keine gelieferte Route führt darüber, die Fahrzeit entspricht der Umfahrung; Einbahnstraße wird nicht entgegen der Fahrtrichtung geplant; Profil mit Restriktion erhält eine andere Route als Profil ohne; Start und Ziel sind definierte Haltepunktkoordinaten.
- **Negativ:** Cache-Treffer mit abweichender Kartendatenversion wird nicht verwendet; Timeout/Ausfall der Kartenquelle liefert definierten Fehler statt geschätztem Wert; frei gesnappter Start/Ziel statt Haltepunkt wird abgewiesen; unerreichbares Ziel liefert definierten Fehler statt Route; Redis-Schlüssel ohne Mandantenanteil sind nicht zulässig; Cache-Eintrag eines anderen Mandanten wird nie geliefert (Datentrennung, `route_matrix_cache` und Redis).
- **Konkurrierend:** Zwei Mandanten fragen gleichzeitig dieselbe Relation mit identischem Profil ab: jeder erhält nur seinen eigenen Cache-Eintrag; paralleler Kartenstandwechsel und Abfrage liefert nie ein Ergebnis mit gemischter Version.
- **Referenztests:** Keine Katalog-ID für diese Anforderung im WP genannt; Nachweis über Testfixture (Kartenausschnitt mit Sperre, Einbahnstraße, Restriktion).
- Testfälle: workers/routing/tests/test_routing_adapter.py::test_gesperrter_abschnitt_wird_umfahren; workers/routing/tests/test_routing_adapter.py::test_einbahnstrasse_profilrestriktion_und_haltepunktbindung; workers/routing/tests/test_routing_adapter.py::test_timeout_und_unerreichbares_ziel_liefern_definierten_fehler; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RoutingCacheKeyTest.kt::redis_schluessel_enthaelt_mandant_profil_und_kartenversion; workers/routing/tests/test_routing_adapter.py::test_cache_treffer_anderer_kartenversion_wird_verworfen; db/tests/test_route_matrix_cache.py::test_cache_mandantengebunden

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung (Cache)).
