# LH-6.5-OPT-010 – Planrevisionen verteilen und Clients synchronisieren

- **Verantwortlicher:** Rolle Disposition/Plattform (Modul `booking-dispatch`, Änderungsstrom)
- **Arbeitspaket:** WP-M2-28
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Routenänderungen werden versioniert und an betroffene Fahrer sowie Fahrgäste verteilt.

**Originalabnahme (unverändert):** Veraltete Client-Revisionsnummer wird erkannt und synchronisiert.

## Vertrag

- **Tabellen / DDL:** `vehicle_run.revision` und `booking.revision` (V1) als Revisionszähler; je Planänderung ein `outbox`-Ereignis mit `aggregate_id` = Run, `aggregate_revision` und Liste betroffener Buchungen; Consumer deduplizieren über `inbox` `(consumer, event_id)` und prüfen die Aggregatrevision (V1). Adressierte Projektion je Empfänger: Fahrgast nur eigene Buchung, Fahrer nur zugeordneter Run (Kap. 02 §4.3), Mandanten- und Empfängerbindung.
- **API:** ETag = Revision auf `GET /v1/bookings/{id}` und auf der Lesesicht des Fahrtenplans; schreibende Zugriffe mit `If-Match`, fehlend `428`, veraltet `412 REVISION_MISMATCH` mit aktuellem Stand (api/common.yaml). Änderungsstrom (SSE oder WebSocket, OP-25) mit Nachladen über Snapshotabruf nach Verbindungsabbruch.
- **Zustandsautomat:** keiner (Zustandsautomaten der Buchung/Runs offen, OP-04).
- **JSON-Schema / Ereignisse:** Ereignisschema Planrevision unter `api/events/` (derzeit nur README; Dateiname z. B. `plan.revision_published.v1.schema.json` ist Vorschlag, im Bau festzulegen); `outbox` ist je `(tenant_id, aggregate_id, aggregate_revision, event_type)` eindeutig. Ereignisadressierung PH-AT-017.
- **Parameter und Kennzahlen:** Keine.
- **Oberfläche:** Fahrgast-Empfängerkanal (Wirkung in UI-02/UI-03 über WP-M2-37) und Fahrer-Projektion mit Revision (Verbraucher Driver-App, M3); in M2 mit Testclients nachgewiesen.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-25 (Protokoll des Stroms: SSE/WebSocket, Authentisierung, Wiederaufnahme, Reihenfolge je Aggregat), OP-04 (Zustandsautomat). Regel "relevante Änderung für Fahrgast" wird im Bau festgelegt. Vollabnahme mit realer Driver-App erst mit WP-M3-24, -27, -29. Blockiert den Bau nicht.

## Tests

- **Positiv:** Routenänderung erzeugt neue Revision und je betroffenem Fahrgast und Fahrer-Empfängerkanal genau eine fachlich wirksame Benachrichtigung; Änderung erreicht verbundenen Testclient; nach Synchronisation zeigt der Client die aktuelle Revision; nach Verbindungsabbruch schließt Snapshotabruf die Lücke; eine Lücke oder ungewöhnliche Reihenfolge der Revisionen löst Nachlesen aus.
- **Negativ:** Schreiben oder Lesen mit veraltetem ETag liefert `412 REVISION_MISMATCH` mit aktuellem Stand; Revision 8 vor 7 oder dreifach zugestellte Revision 8 setzt Clientstand nie auf ältere Revision zurück; Fahrgast erhält keine Daten anderer Buchungen desselben Umlaufs; fremder Kanal ohne Zugriff.
- **Konkurrierend:** Zwei Mandanten am selben Strom-Endpunkt: keine mandantenübergreifende Zustellung; gleichzeitige Routenänderung und Clientschreibzugriff auf derselben Revision: genau einer gewinnt, der andere erhält `412`.
- **Referenztests:** PH-AT-017 (Reihenfolge und Verspätung von Events).
- Testfälle: PH-AT-017; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/PlanRevisionSyncTest.kt::veraltete_revision_liefert_412_mit_aktuellem_stand; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/PlanRevisionSyncTest.kt::ungeordnete_und_doppelte_events_setzen_nie_zurueck; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/PlanRevisionSyncTest.kt::zwei_mandanten_am_selben_strom_getrennt; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/PlanRevisionSyncTest.kt::planaenderung_erreicht_je_fahrgast_und_fahrer_genau_eine_wirksame_benachrichtigung; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/PlanRevisionSyncTest.kt::nach_verbindungsabbruch_schliesst_der_snapshot_die_luecke; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/PlanRevisionSyncTest.kt::gleichzeitige_routenaenderung_und_clientschreibzugriff_genau_einer_gewinnt; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/PlanRevisionSyncTest.kt::fremder_kanal_und_betriebspersonal_haben_keinen_zugriff; backend/app/src/test/kotlin/de/taktovia/app/booking/PlanChangeHttpApiTest.kt::get_booking_mit_veraltetem_if_match_liefert_412_mit_aktuellem_etag

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
