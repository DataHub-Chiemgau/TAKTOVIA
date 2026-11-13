# LH-6.21-CHK-001 – Readiness-Gate vor Bereitstatus und Disposition

- **Verantwortlicher:** Rolle Flotte/Sicherheit (Modul `fleet-safety`, Readiness-Domänendienst)
- **Arbeitspaket:** WP-M3-09
- **Status:** Bereit (unter Planungsannahme PA-07, siehe planungsannahmen.md; keine Entscheidung des Auftraggebers)
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Vor dem ersten Bereitstatus jeder Kombination aus Fahrer, Fahrzeug und Schicht ist eine gültige, abgeschlossene und serverseitig bestätigte Vor-Schicht-Prüfung erforderlich.

**Originalabnahme (unverändert):** Ohne gültige Prüfung lässt sich das Fahrzeug weder freigeben noch für neue Fahrten disponieren.

## Vertrag

- **Tabellen / DDL:** `resource_release` (V1: `tenant_id`, `id`, `vehicle_id`, `shift_id`, `safety_revision`, `inspection_ref uuid NOT NULL`, `qualification_revision`, `valid_until timestamptz NOT NULL`, `revoked_at`; FK auf `vehicle` und `staff_shift`; additiv FK `inspection_ref -> inspection_run` laut WP-M3-02), `vehicle.active_block`, `vehicle.safety_revision`. Regel: Die erste Bereitmeldung je (Fahrer, Fahrzeug, Schicht) verlangt eine `resource_release` mit `inspection_ref` einer abgeschlossenen, serverseitig bestätigten (Submit, WP-M3-14) und nicht abgelaufenen Prüfung genau dieser Kombination. Eine Prüfung einer anderen Schicht, eines anderen Fahrzeugs oder eines anderen Fahrers erfüllt die Bedingung nicht; eine Prüfung im Zustand „in Bearbeitung" ebenfalls nicht; eine widerrufene (`revoked_at`) oder abgelaufene (`valid_until`) Freigabe auch nicht. Sperrkern (WP-M3-08): `assertDispatchable(vehicle, shift, safety_revision)` (Arbeitsname).
- **API:** `/v1/operator/vehicles` und `/v1/driver/shifts/{id}/run` liefern Freigabestatus und „fehlende Voraussetzungen" (Liste offener Prüfschritte/Bedingungen; Antwortschema im Detailvertrag festzulegen). Aufträge werden erst nach Einsatzfreigabe als ausführbar ausgeliefert; vorher nur Dienst, Fahrzeug, fehlende Voraussetzungen. „Verfügbar schalten" ohne Freigabe wird serverseitig abgewiesen (Endpunkt und Fehlercode der Bereitmeldung sind in Kap. 05 nicht genannt, im OpenAPI-Entwurf festzulegen); das Fahrzeug ist nicht Kandidat neuer Fahrten (Kandidatenfilter WP-M2-15, im Hold-Commit `assertDispatchable`). Fremde Schicht oder fremdes Fahrzeug: 404 `NOT_FOUND`. Ein abgewiesener Bereitversuch steht mit Akteur und fehlender Voraussetzung im `audit_event`. Der Client liefert weder Freigabe noch Prüfergebnis, beides entscheidet allein der Server.
- **Zustandsautomat:** Einsatzfreigabe (WP-M3-08, `vertraege/zustaende/` noch festzulegen).
- **JSON-Schema / Ereignisse:** Antwortschema „fehlende Voraussetzungen"; Ereignisse Einsatzfreigabe erteilt/widerrufen (WP-M3-08).
- **Parameter und Kennzahlen:** `vehicle_readiness_require_checks` (Katalog J.3: „Pflichtprüfung vor Dienst", Bool, Ebene Dienst, Validierung „Sicherheitsprüfungen dürfen nicht abgeschaltet werden"); ein Konfigurationsentwurf, der die Pflichtprüfung sicherheitskritischer Schritte abschaltet, wird bei der Publikation (`POST /v1/config/revisions/{id}/publish`) abgewiesen.
- **Oberfläche:** UI-09 „Fahrzeugfreigabe: Prüfung erforderlich" mit Liste fehlender Schritte, Zustände Offline, Mangel, andere Fahrzeugrevision (Kap. 06 §6.9); UI-07 Fahrzeug als nicht einsatzbereit mit Grund (Zustand „Sperre"). Die App zeigt „freigegeben" erst nach Serverantwort; Arbeitszeitbeginn und technische Einsatzfreigabe sind getrennt (Kap. 06 §6.7).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-09 (was `false` von `vehicle_readiness_require_checks` zulässig abschaltet; `inspection_ref` ist NOT NULL); OP-33 (Regel für `valid_until`, Neuprüfung nach Pause innerhalb derselben Schicht; Originaltext verlangt Prüfung nur vor dem „ersten Bereitstatus"); OP-01 (Bedeutung `active_block` Default `true`). OP-09 und OP-33 blockieren die Parameterwirkung und die Gültigkeitsregel („gültige" Prüfung), nicht den Kern „ohne bestätigte Prüfung keine Freigabe"; Vertrag bleibt bis zur Klärung in Arbeit.

- **Planungsannahme:** PA-07 in [planungsannahmen.md](../planungsannahmen.md) gilt als Arbeitsstand; offene Punkte oben bleiben bis zur Entscheidung des Auftraggebers bestehen.

## Tests

- **Positiv:** nach bestätigter, abgeschlossener Prüfung derselben Kombination ist die Bereitmeldung möglich; Aufträge werden ausführbar ausgeliefert (gemeinsam mit WP-M3-14, AT-27).
- **Negativ:** AT-26 (Fahrzeug ohne Prüfung verfügbar schalten: keine Einsatzfreigabe, Liste fehlender Prüfschritte sichtbar); manipulierter Client meldet „bereit" ohne Prüfung: serverseitig abgewiesen; Prüfung für anderes Fahrzeug / anderen Fahrer / andere Schicht erfüllt nicht; Prüflauf nur „in Bearbeitung" (nicht abgeschlossen oder nicht serverbestätigt) erfüllt nicht; widerrufene oder abgelaufene Freigabe (`revoked_at`, `valid_until`) erfüllt nicht; Prüfergebnis ohne Freigabe (z. B. gesperrt) ergibt keine Bereitmeldung; Konfigurationsentwurf, der die Sicherheitsprüfung abschaltet, bei Publikation abgewiesen; vor Freigabe keine Aufträge in `/run`; Fahrzeug ohne Freigabe ist nicht Kandidat neuer Fahrten; abgewiesener Versuch im `audit_event`; Schicht oder Fahrzeug eines anderen Mandanten: 404.
- **Konkurrierend:** Bereitmeldung parallel zum Widerruf der Freigabe oder zu einer neuen Sperre: in keiner Serialisierung entsteht eine Zusage auf ein nicht freigegebenes Fahrzeug (Bezug PH-AT-020, Kernnachweis in WP-M3-08); zwei gleichzeitige Bereitmeldungen: höchstens eine Einsatzfreigabe.
- **Referenztests:** AT-26, AT-27 (WP-M3-09), PH-AT-020 (Pflichtenheft Kap. 08: Bezug CHK, AT-26 bis 35).
- Testfälle: AT-26; AT-27; PH-AT-020; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/ReadinessGateTest.kt::bereitmeldung_ohne_pruefung_wird_serverseitig_abgewiesen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/ReadinessGateTest.kt::pruefung_anderer_kombination_oder_offener_lauf_erfuellt_nicht; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/ReadinessGateTest.kt::bereitmeldung_parallel_zu_freigabewiderruf; backend/app/src/test/kotlin/de/taktovia/app/fleet/ReadinessGateApiTest.kt::bereitmeldung_ohne_pruefung_wird_serverseitig_abgewiesen; backend/app/src/test/kotlin/de/taktovia/app/fleet/ReadinessGateApiTest.kt::bereitmeldung_parallel_zu_freigabewiderruf; backend/modules/config/src/test/kotlin/de/taktovia/config/ParameterRegistryTest.kt::vehicle_readiness_require_checks_false_abgewiesen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
