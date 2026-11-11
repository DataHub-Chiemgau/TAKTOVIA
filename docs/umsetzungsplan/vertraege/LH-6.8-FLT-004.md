# LH-6.8-FLT-004 – Kapazitäts- und Stellplatzkonfiguration

- **Verantwortlicher:** Rolle Flotte/Sicherheit (Modul `fleet-safety`, Ressourcenvektor für `booking-dispatch`)
- **Arbeitspaket:** WP-M3-04
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Barrierefrei nutzbare Stellplätze, Platzkombinationen und Restriktionen sind getrennt konfigurierbar.

**Originalabnahme (unverändert):** Ein Rollstuhlplatz reduziert Sitzkapazität gemäß Fahrzeugkonfiguration.

## Vertrag

- **Tabellen / DDL:** `vehicle.capacity_config jsonb NOT NULL` (V1) mit gespeicherter JSON-Schema-Version; die Konfiguration trennt Sitzplätze, Rollstuhlplätze (barrierefrei nutzbare Stellplätze), Ausstattung, Platzkombinationen (Umrüstzustände) und Restriktionen, je Teil unabhängig änderbar. Änderungen wirken nicht rückwirkend auf bestehende Holds/Buchungen (`capacity_hold.capacity_vector jsonb` und `booking.promise_snapshot` bleiben unverändert). RLS wie V1.
- **API:** `/v1/operator/vehicles` Teilressource Kapazitätskonfiguration (Lesen, Ändern mit `If-Match`: ohne 428, veraltet 412; 422 bei widersprüchlicher Kombination, unbekannter Dimension oder ungültigem Wert; fremder Mandant 404). Modul-API „verfügbarer Ressourcenvektor je Konfiguration" für `booking-dispatch`, abgestimmt mit WP-M2-14 (Dimensionen Sitz, Rollstuhl, Ausstattung).
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** JSON-Schema `capacity_config` (`vertraege/schemas/capacity_config.v<N>.schema.json`; Dimensionen Sitz, Rollstuhlplatz, Ausstattung; Kombinationen; Restriktionen); Ereignis Fahrzeug geändert (Outbox); Audit der Konfigurationsänderung im `audit_event`.
- **Parameter und Kennzahlen:** keine (`wheelchair_boarding_sec` gehört zu M2).
- **Oberfläche:** UI-11 Editor für Stellplätze, Kombinationen, Restriktionen mit Tabellenalternative.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-17 (Semantik „eingeschränkt" im Ressourcenvektor, betrifft WP-M3-12, nicht diesen Vertrag); Wertekatalog Ausstattung OP-14. Beide blockieren den Bau nicht. Der Wert n („ein Rollstuhlplatz ersetzt n Sitzplätze") ist Konfigurationsdatum des Fahrzeugs, keine feste Zahl des Systems.

## Tests

- **Positiv:** Musterfahrzeug mit zwei Kombinationen; Konfiguration „ein Rollstuhlplatz ersetzt n Sitzplätze": Belegung des Rollstuhlplatzes reduziert die verfügbare Sitzkapazität um genau n, Disposition erhält diesen Vektor (Originalabnahme); Stellplätze, Kombinationen und Restriktionen sind unabhängig voneinander änderbar (Änderung des einen lässt die anderen unverändert); Änderung wird auditiert.
- **Negativ:** widersprüchliche Kombination (mehr belegte Fläche als vorhanden, z. B. Ersatz von mehr Sitzplätzen als vorhanden) abgewiesen; unbekannte Dimension abgewiesen; negative oder nicht ganzzahlige Werte abgewiesen; ohne `If-Match` 428, veraltete Revision 412; fremder Mandant: 404; Änderung wirkt nicht rückwirkend auf bestehende Holds/Buchungen.
- **Konkurrierend:** Konfigurationsänderung parallel zu einem Hold: der Hold prüft gegen eine konsistente Revision, keine Überbuchung (beide Serialisierungen); zwei gleichzeitige Änderungen derselben Revision: genau eine gewinnt.
- **Referenztests:** keine Katalog-ID im WP für FLT-004 genannt; PH-AT-004 (letzter Platz unter Konkurrenz) bleibt als Gegenprobe des Ressourcenvektors im M2-Kern bestehen (WP-M3-08 Kriterium 8), wird hier nicht beansprucht.
- Testfälle: geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/CapacityConfigTest.kt::rollstuhlplatz_reduziert_sitzkapazitaet_um_n; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/CapacityConfigTest.kt::konfigaenderung_parallel_zu_hold_keine_ueberbuchung; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/CapacityConfigTest.kt::widerspruechliche_kombination_wird_abgewiesen; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/CapacityConfigTest.kt::teile_unabhaengig_aenderbar_und_nicht_rueckwirkend

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
