# LH-6.6-DRV-003 – Navigation auf Basis der gültigen Stoppliste

- **Verantwortlicher:** Rolle Fahrer-App (`mobile/driver`, Flutter)
- **Arbeitspaket:** WP-M3-28
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Abbiegenavigation beziehungsweise Übergabe an geeignete Navigation verwendet dieselbe gültige Stoppliste.

**Originalabnahme (unverändert):** Navigationsziel entspricht nächstem disponierten Stopp.

## Vertrag

- **Tabellen / DDL:** keine; Koordinate und Name des nächsten Stopps aus `stop_revision` (`point`) über die Fahrerprojektion (DRV-002).
- **API:** keine neuen Pfade; Quelle ist `GET /v1/driver/shifts/{id}/run`. Die Fahrerprojektion enthält je nächstem Stopp Koordinate, Haltepunktname und `stop_revision` bzw. Planrevision.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Projektionsschema aus DRV-002.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-10, Aktion „Navigation starten" mit Übergabe der Zielkoordinate an die Geräte-Navigation über eine Navigationsübergabe-Schnittstelle (Anbieterwechsel möglich); Anzeige des Ziels mit Haltepunktname; Hinweis bei geändertem Ziel nach Planänderung; die Navigationsübergabe-Schnittstelle ist durch einen Test mit Ersatzanbieter belegt (Anbieterwechsel ohne Änderung der Stopplistenlogik); Aktion deaktiviert ohne gültige Stoppliste.
- **Entscheidungen:** D-09 (Routing, Karten, Telematik) blockiert nur die Produktivschaltung; gebaut wird gegen die Geräte-Navigation.
- **Offene Punkte:** OP-19 (eingebettete Abbiegenavigation oder Übergabe; geplant: Übergabe an die Geräte-Navigation). Verhalten bei Planänderung während laufender Navigation ist im Pflichtenheft nicht festgelegt; Vorgabe für den Bau: das alte Ziel wird nicht weiterverwendet, Hinweis und neue Übergabe. WP-M0-30 (Routingvergleich) liefert Eingaben.

## Festlegungen des Baus (WP-M3-28)

- **Umsetzung:** `mobile/driver/lib/run/navigation_handover.dart` (Ziel aus der gültigen Stoppliste, Port `NavigationProvider`, Standardanbieter `GeoUriNavigationProvider` mit `geo:`-URI der Geräte-Navigation, OP-19 geplante Übergabe). Ziel = erster noch nicht abgeschlossener Stopp; ohne gültige Stoppliste (keine Schicht, kein Stopp, ohne Einsatzfreigabe, alles abgeschlossen) ist die Aktion nicht verfügbar.
- **Planänderung:** nach einer Änderung des nächsten Stopps wird das alte Ziel nicht weiterverwendet, die App weist hin (`changedHint`) und übergibt das neue Ziel. Vor jeder Übergabe wird die aktuelle Revision gelesen; trifft während der Übergabe eine neue Revision mit anderem Ziel ein, folgt eine weitere Übergabe (höchstens drei Runden). Ein Ziel einer veralteten Revision wird nach Kenntnis der neuen nie übergeben (zwischen Prüfung und Aufruf der Geräte-Navigation bleibt ein nicht auszuschließendes Restfenster, das der Hinweis abdeckt).
- **Serverseite:** keine neue Operation; `stop_revision_id`, Koordinate und Name liefert die Fahrerprojektion (WP-M3-26).
- **Nicht ausgeführt:** Flutter war in der Erstellungsumgebung nicht verfügbar; die Dart-Tests (`navigation_handover_test.dart`) wurden nicht gestartet.

## Tests

- **Positiv:** übergebenes Ziel entspricht Koordinate und Revision des nächsten disponierten Stopps der gültigen Stoppliste.
- **Negativ:** Planänderung des nächsten Stopps, das alte Ziel wird nicht weiterverwendet, die App weist hin und übergibt das neue Ziel; ohne gültige Stoppliste (kein Stopp, keine Schicht) ist die Navigationsaktion nicht verfügbar; Stopp abgeschlossen, nächster Stopp wird Ziel.
- **Konkurrierend:** Planrevision trifft während der Übergabe ein, es wird nie ein Ziel einer veralteten Revision übergeben.
- **Hinweis Testort:** Die Fahrer-App ist Flutter (`mobile/driver`, Kap. 01 §1.1); Tests liegen als `mobile/driver/test/*_test.dart`, nicht unter `web/`.
- **Referenztests:** kein Katalogfall im WP genannt.
- Testfälle: nicht ausgeführt:mobile/driver/test/navigation_handover_test.dart::ziel_entspricht_koordinate_und_revision_des_naechsten_stopps; nicht ausgeführt:mobile/driver/test/navigation_handover_test.dart::planaenderung_uebergibt_neues_ziel_und_weist_hin; nicht ausgeführt:mobile/driver/test/navigation_handover_test.dart::stopp_abgeschlossen_naechster_stopp_wird_ziel; nicht ausgeführt:mobile/driver/test/navigation_handover_test.dart::ohne_stoppliste_keine_navigation; nicht ausgeführt:mobile/driver/test/navigation_handover_test.dart::planrevision_waehrend_uebergabe_kein_veraltetes_ziel; nicht ausgeführt:mobile/driver/test/navigation_handover_test.dart::uebergabe_schnittstelle_mit_ersatzanbieter

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
