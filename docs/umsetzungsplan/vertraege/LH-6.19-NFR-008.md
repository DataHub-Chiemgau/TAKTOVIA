# LH-6.19-NFR-008 – Alerting für kritische Fehlerszenarien

- **Verantwortlicher:** Rolle Betrieb/Plattform (Observability, Alarmregeln als Code in `infra/`)
- **Arbeitspaket:** WP-M5-04
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Serverkomponenten sind beobachtbar mittels Metriken, Traces, Logs und Alerting.

**Originalabnahme (unverändert):** Für definierte kritische Fehlerszenarien werden Alerts erzeugt.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle für technische Alarme im Betriebsmonitoring. Soweit technische Alarme in die Fachalarmierung übergehen: `business_alert`, `alert_policy_revision`, `alert_acknowledgement` (Kap. 04 §4.4, Dedupe `(policy,event,recipient)`; in db/migrations V1 bis V3 nicht angelegt, Abgrenzung offen, OP-07). Überwachte Quellen: `outbox` (Stau), `operation` (überfällige Leases), `payment_intent` (unbestimmt länger als Schwelle).
- **API:** keine fachliche API. Alarmregeln als Code unter `infra/`; Anlegen, Ändern, Stummschalten, Quittieren nur für Betriebsrollen und protokolliert (Audit).
- **Zustandsautomat:** (Arbeitsannahme aus WP-M5-04 Kriterium 3/6 und WP-M3-52, Dedupe und Quittierung) Alarm: offen -> (quittiert) -> geschlossen nach Behebung; ein anhaltender Fehler erzeugt genau einen offenen Alarm (Dedupe). Stummschaltung (Wartungszeitraum) unterdrückt den Ruf, nicht das Protokoll.
- **JSON-Schema / Ereignisse:** Szenariokatalog „kritische Fehlerszenarien“ (WP-M5-01) mit Schwelle, Schweregrad, Empfänger, Eskalationsstufe, Runbook-Verweis, Dedupe-/Stummschaltregel.
- **Pflichtszenarien (mindestens):** Ausfall Kernbuchung; Datenbank nicht erreichbar/Replikationsverzug; Stau in `outbox`; Messaging nicht erreichbar; Routing-Worker ohne Antwort bzw. Solver-Timeouts über Schwelle; PSP nicht erreichbar bzw. `payment_intent` länger als Schwelle unbestimmt; überfällige `operation`-Leases; Redis-Ausfall; ablaufende Zertifikate; Fehlerratenanstieg je Endpunktklasse. Die Regeln „fehlgeschlagenes Backup“ und „Archivierungs-/Replikationsverzug“ stehen im Katalog, werden aber mit WP-M5-14 geliefert und ausgelöst; die Abnahme ist erst mit deren Auslösung vollständig, der Katalogabgleich „Szenario <-> ausgelöster Alarm“ weist keine Lücke aus.
- **Weitere Anforderungen:** je Serverkomponente mindestens eine aktive Alarmregel; je Serverkomponente (Fachkern, Gateway, Routing-Worker, Dokumenten-Worker, Outbox-Relay) Metriken, Traces und Logs vorhanden; Alarmtexte ohne Fahrgast-/Personaldaten; unabhängige Totmannprüfung der Alarmierungskette; Auslöse- und Zustellzeit protokolliert; jeder Alarm hat einen im Katalog definierten Empfänger (Katalog aus WP-M5-01, OP-07).
- **Parameter und Kennzahlen:** `alert_escalation_delay_min`, `vehicle_unavailability_alert_min` (kataloge.csv J.7; nur soweit technische Alarme in die fachliche Eskalation übergehen; freigegebene Werte vor Implementierung ergänzen).
- **Oberfläche:** keine Fachoberfläche; Betriebshandbuch-Verweis je Szenario (WP-M5-26).
- **Entscheidungen:** D-05 (Supportzeiten/Rufbereitschaft) blockiert die Produktivschaltung der Rufbereitschaft, nicht den Bau.
- **Offene Punkte:** OP-07 (Szenarien im Pflichtenheft nicht aufgezählt; Empfänger; ob technische Alarme in `business_alert` geführt werden), OP-08 (Aufbewahrung Telemetrie, D-11). Der Szenariokatalog entsteht in WP-M5-01 und ist Vorbedingung der Abnahme; er blockiert den Bau der Regeln nicht.

## Tests

- **Positiv:** je Szenario des Katalogs eine gezielte Auslösung erzeugt einen Alarm, der den definierten Empfänger erreicht; Auslöse- und Zustellzeit protokolliert; Katalogabgleich ohne Lücke; Alarm schließt nach Behebung.
- **Negativ:** Serverkomponente ohne aktive Alarmregel bzw. Katalogszenario ohne ausgelösten Alarm lässt den Katalogabgleich fehlschlagen (inkl. Backup- und Archivierungs-/Replikationsalarme aus WP-M5-14); Schwelle knapp unterschritten erzeugt keinen Alarm; stummgeschalteter Wartungszeitraum erzeugt keinen Ruf, bleibt aber protokolliert; Alarmtext enthält keine Fahrgast-/Personaldaten; Änderung/Stummschaltung/Quittierung durch Nicht-Betriebsrolle wird abgewiesen; Stopp von Sammler oder Zustellweg wird von der Totmannprüfung bemerkt; anhaltender Fehler erzeugt keine Alarmflut.
- **Konkurrierend:** zwei gleichzeitige Ausfälle erzeugen zwei unterscheidbare Alarme; paralleles Quittieren desselben Alarms durch zwei Betriebsrollen ergibt genau eine wirksame Quittierung.
- **Referenztests:** keine Katalog-ID im WP genannt; Nachweis im Szenariokatalog aus WP-M5-01 und in WP-M5-31.
- Testfälle: tests/acceptance/alerting/test_alert_scenarios.py::test_je_szenario_alarm_zugestellt; tests/acceptance/alerting/test_alert_scenarios.py::test_katalogabgleich_szenario_alarm_ohne_luecke; tests/acceptance/alerting/test_alert_scenarios.py::test_je_komponente_alarmregel_und_telemetrie; tests/acceptance/alerting/test_alert_scenarios.py::test_gleichzeitige_ausfaelle_zwei_alarme; tests/acceptance/alerting/test_alert_scenarios.py::test_schwelle_knapp_unterschritten_kein_alarm; tests/acceptance/alerting/test_alert_scenarios.py::test_anhaltender_fehler_genau_ein_offener_alarm_schliesst_nach_behebung; tests/acceptance/alerting/test_alert_scenarios.py::test_stummschaltung_kein_ruf_aber_protokoll; tests/acceptance/alerting/test_alert_scenarios.py::test_alarmtext_ohne_fahrgast_und_personaldaten; tests/acceptance/alerting/test_alert_scenarios.py::test_nicht_betriebsrolle_darf_regel_nicht_aendern_stummschalten_quittieren; tests/acceptance/alerting/test_alert_scenarios.py::test_paralleles_quittieren_genau_eine_wirksame; tests/acceptance/alerting/test_alert_scenarios.py::test_totmannpruefung_alarmkette

## Festlegungen des Baus (WP-M5-04)

- Katalog, Regeln, Routing, Totmannprüfung, Betriebswerkzeug und Runbook: `infra/alerting/README.md`; Handlungsschritte: `docs/betrieb/alarm-handlungsschritte.md`.
- **Abnahme nicht vollständig:** der Katalog führt `BACKUP_FEHLGESCHLAGEN` und `ARCHIVIERUNGS_REPLIKATIONSVERZUG` (Lieferung WP-M5-14, noch ohne Regel); `test_katalogabgleich_szenario_alarm_ohne_luecke` weist sie als ausstehend aus. Nachweis nur gegen die Nachbildung von Regelauswertung und Routing (Prometheus und Alertmanager laufen hier nicht); promtool-Fälle liegen bei, nicht ausgeführt.
- Empfänger sind Rollen (`betrieb-bereitschaft`, `betrieb-plattform`, `betriebsleitung`); Personen und Rufbereitschaft folgen aus D-05 und OP-07.

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
