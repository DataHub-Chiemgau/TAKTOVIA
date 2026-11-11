# LH-6.21-CHK-002 – Prüflauf-Start mit exakt freigegebener Vorlage

- **Verantwortlicher:** Rolle Flotte/Sicherheit (Modul `fleet-safety`, Inspection)
- **Arbeitspaket:** WP-M3-13
- **Status:** Vertrag in Arbeit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Fahrer-App startet nach Anmeldung eine geführte Prüfung anhand von Schicht, Fahrzeugklasse, Bedienart und konfigurierter Checklistenversion.

**Originalabnahme (unverändert):** Der Fahrer erhält exakt die freigegebene Vorlage für sein Fahrzeug und seinen Dienst.

## Vertrag

- **Tabellen / DDL:** `inspection_run` (neu, WP-M3-02; Fahrer/Prüfer, Fahrzeug, Schicht, feste Vorlagenrevision `checklist_template_revision`, Zustand „in Bearbeitung"; weitere Spalten laut WP-M3-14); höchstens ein offener Lauf je (Fahrer, Fahrzeug, Schicht) per Teil-Unique-Index über den Zustand „in Bearbeitung"; Fremdschlüssel auf `staff_shift`, `vehicle` und `checklist_template_revision` mit `tenant_id` (Mandant erzwungen); RLS wie V1.
- **API:** `POST /v1/inspections` (idempotent über den Header `Idempotency-Key`; gleicher Schlüssel mit anderem Inhalt: 409 `IDEMPOTENCY_CONFLICT`), `GET /v1/inspections/{id}`. Die Vorlage bestimmt allein der Server aus Schicht, Fahrzeugklasse, Bedienart und freigegebener Checklistenversion: genau eine freigegebene Revision; der Request enthält keine Vorlagenwahl, ein mitgesendetes Vorlagenfeld ändert die gewählte Revision nicht. Keine oder mehrere passende freigegebene Vorlagen: kein Prüflauf, Freigabe bleibt aus, Hinweis an die Leitstelle (Fehlercode im OpenAPI-Vertrag). Schicht, die dem Fahrer nicht zugewiesen ist, oder fremder Mandant: 404 `NOT_FOUND`. Wiederholter Start mit gleichem Schlüssel liefert denselben Lauf. Start nur nach erfolgter Dienstanmeldung (Originaltext „nach Anmeldung"; Zustand der Anmeldung laut WP-M3-25).
- **Zustandsautomat:** `inspection_run` (in Bearbeitung ... Abschluss in WP-M3-14); Vorlagenrevision Entwurf, freigegeben, abgelöst (WP-M3-10). Nur freigegebene Revisionen werden gewählt; eine laufende Prüfung behält ihre Revision, ein neuer Lauf erhält die neue.
- **JSON-Schema / Ereignisse:** OpenAPI `/v1/inspections`; Hinweis-Ereignis an die Leitstelle bei fehlender oder mehrdeutiger Vorlage (Ereignisschema noch festzulegen).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-09 „Pflichtprüfung starten", geführte Schrittfolge „Prüfung 3 / 8" (Schrittzahl = Anzahl Prüfelemente der gewählten Revision); Zustände Offline, Mangel, andere Fahrzeugrevision (Kap. 06 §6.9).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-14 (Attribute Fahrzeugklasse und Bedienart sind weder im Schema noch in einem Katalog definiert; die Auswahlregel ist nicht baubar, bis geklärt; blockiert den Bau der Vorlagenauswahl). Vertrag bleibt deshalb in Arbeit. Offline-Start gehört zu WP-M3-33.

## Tests

- **Positiv:** zwei Fahrzeugklassen erhalten unterschiedliche freigegebene Vorlagen; der Fahrer erhält exakt die freigegebene Vorlagenrevision für Fahrzeug und Dienst (Originalabnahme); gleicher Idempotenzschlüssel liefert denselben Lauf; neue Vorlagenrevision nach Start: laufender Lauf behält seine, ein neuer Lauf erhält die neue; UI zeigt Schrittfolge „n / Gesamt" der gewählten Revision.
- **Negativ:** Schicht, die dem Fahrer nicht zugewiesen ist, und fremde Schicht anderer Mandanten: 404; Start ohne Dienstanmeldung abgewiesen; keine passende freigegebene Vorlage: kein Lauf, Hinweis an die Leitstelle; mehrere passende Vorlagen: kein Lauf; Vorlage im Entwurf oder abgelöst wird nicht verwendet; Vorlage eines anderen Mandanten wird nie gewählt; vom Client mitgesendete Vorlage ändert die serverbestimmte Revision nicht; gleicher Schlüssel mit anderem Inhalt: 409 `IDEMPOTENCY_CONFLICT`.
- **Konkurrierend:** Doppelstart von zwei Geräten (auch mit verschiedenen Idempotenzschlüsseln): genau ein offener Prüflauf je (Fahrer, Fahrzeug, Schicht); Vorlagenfreigabe parallel zum Start: der Lauf erhält genau eine vollständige Revision, nie eine Mischung.
- **Referenztests:** keine Katalog-ID im WP genannt; Bezug AT-26/AT-27 über WP-M3-09/WP-M3-14.
- Testfälle: geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionStartTest.kt::fahrer_erhaelt_exakt_freigegebene_vorlage; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionStartTest.kt::keine_passende_vorlage_kein_pruflauf; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionStartTest.kt::fremde_schicht_ergibt_404_und_client_vorlage_wirkungslos; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionStartTest.kt::neue_revision_laufender_lauf_behaelt_seine; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionStartTest.kt::doppelstart_zwei_geraete_ein_offener_lauf

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
