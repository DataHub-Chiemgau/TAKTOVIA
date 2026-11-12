# Telemetrie je Komponente (WP-M5-03)

Metriken, Traces und strukturierte Logs aller Serverkomponenten (Kap. 01 §1.2) laufen in eine zentrale Betriebsplattform. Dieses Dokument
legt die Konvention fest (Detailvertrag "Namens- und Labelkonvention" des WP) und weist aus, was geprüft ist und was nicht.

## Weg der Daten

| Komponente | Metriken | Traces | Logs |
|---|---|---|---|
| Fachkern (`backend/app`) | Micrometer, Prometheus-Ansicht auf dem Verwaltungsport (`TAKTOVIA_MANAGEMENT_PORT`, Standard-Scrape `:9464`) | `spring-boot-starter-opentelemetry`, OTLP/HTTP an den Collector (`TAKTOVIA_OTLP_TRACES_ENDPOINT`) | stdout, maskiert (Logback-Layout `logback-spring.xml`), Container-Logdatei, Collector `filelog` |
| Outbox-Relay (im Fachkern) | `taktovia_outbox_*` (Tiefe, Alter, Dead-Letter, Verzögerung Commit bis Veröffentlichung) | Teil des Fachkern-Traces | wie Fachkern |
| Routing-Worker (`workers/routing`) | `GET /metrics` (`taktovia_routing_*`: Aufrufdauer, Solverlaufzeit, Timeouts, Queue-Wartezeit) | Span je Aufruf, Elternkontext aus `traceparent`, OTLP/HTTP-JSON | stdout, JSON, maskiert |
| Dokumenten-Worker (`workers/documents`) | `GET /metrics` (`taktovia_documents_*`) | Span je Aufgabe (`Task.traceparent`) | stdout, JSON, maskiert |
| Gateway (nginx) | über Zugriffslog und Fachkern-Latenz | `traceparent` wird unverändert weitergereicht, `X-Request-Id` ergänzt | Zugriffslog `taktovia_masked` ohne Query und Kopfzeilen |
| RabbitMQ, Redis, PostgreSQL | Exporter (`rabbitmq_prometheus`, `redis_exporter`, `postgres_exporter`) | – | Containerlogs |

Prometheus (`infra/observability/prometheus.yml`) scrapt jede Komponente; Tempo nimmt Traces, Loki Logs; Grafana (`infra/observability/dashboards/`)
zeigt Fachkern, Worker und Infrastruktur. Overlay: `infra/compose/docker-compose.telemetry.yml`.

## Correlation-ID und Trace

- Die Correlation-ID ist die Trace-ID des W3C-`traceparent` (Kap. 05 §5.1). Gültiger Header: Trace-ID übernommen. Ungültiger oder fehlender Header:
  neue Trace-ID, die der Fachkern zugleich als `X-Correlation-Id`, `correlation_id` der Fehlerantwort und Logfeld verwendet (er schreibt sie als gültigen
  `traceparent` in die Anfrage, damit Tracing und Fehlerantwort dieselbe ID tragen).
- Fachkern an Worker: der Aufrufer sendet `traceparent` (Routing: HTTP-Kopfzeile, Dokumente: `Task.traceparent`); der Worker-Span hat dieselbe Trace-ID und den
  Aufrufer als Elternspan.

## Namens- und Labelkonvention

- Eigene Metriken: `taktovia_<komponente>_<größe>[_<einheit>]`, Kleinbuchstaben, Einheiten `_seconds`, `_total`, `_bytes`, `_ratio`.
- Erlaubte Labelschlüssel (Positivliste in `MetricConvention.ALLOWED_LABELS`, im Worker `ALLOWED_LABELS`): `component, route_class, method, status_class, result, kind,
  queue, outcome, error_class, tenant_id, worker, event_type, consumer, state, operation, reason, phase`. Werte: technische Zeichen, höchstens 64 Zeichen;
  `tenant_id` nur als UUID.
- **Verboten** als Labelschlüssel und -wert (Kap. 01 §1.1, Kap. 05 §5.1): Personen, Kontakte (Name, E-Mail, Telefon), Orts- und Suchbegriffe, Adressen, Zahlungsdetails, Token.
  Durchgesetzt in drei Schichten: Micrometer-`LabelPolicyFilter` bzw. Worker-`Registry` verwerfen verletzende Reihen (nur Zählung, nie der Wert), der Collector löscht
  PII-Attribute (`attributes/pii_guard`), und das Prüfskript `tools/check_metric_labels.py` prüft eine Scrape-Ausgabe (CI und Betrieb).
- Pfade werden nie als Label verwendet; die Latenz trägt die Endpunktklasse `offer | booking | driver | operator | standard | health | other`.

## Kennzahlen (technisch, keine Fachkennzahlen aus `kataloge.csv`)

`taktovia_http_server_request_seconds{route_class,method,status_class}`; `hikaricp_connections_*` (Datenbankpool, Spring/Micrometer);
`taktovia_db_lock_wait_seconds{operation}` (Hilfsfunktion `PlatformMetrics.measureLock`, **Einbau in Hold/Confirm erfolgt mit den Buchungsoperationen (M2), dort noch nicht vorhanden**);
`taktovia_outbox_depth|dead_lettered|oldest_age_seconds{tenant_id}`; `taktovia_outbox_publish_delay_seconds` (Aufzeichnungszeit `recorded_at` bis Veröffentlichung;
`recorded_at` ist der Transaktionsbeginn, die Verzögerung ist damit eine obere Schranke für den Commit); `taktovia_inbox_lag_seconds|gap_snapshots{tenant_id}`
(Verzug Empfang minus Ereigniszeit der letzten 5 min; die `inbox` führt nur verarbeitete Ereignisse, der Queue-Rückstand selbst kommt aus RabbitMQ `rabbitmq_queue_messages_ready`);
`taktovia_operation_open|lease_overdue|deadline_overdue{tenant_id}`; `taktovia_payment_intent_indeterminate{tenant_id}` (`CAPTURE_UNKNOWN`, `RECONCILING`);
`taktovia_telemetry_dropped_total`.

## Mandantenfähigkeit und Zugang

Mandantenbezogene Reihen tragen `tenant_id` (UUID, opak); Auswertung je Mandant über die Dashboard-Variable. Die Betriebsplattform (Grafana, Prometheus, Loki, Tempo)
ist **nur für Betriebsidentitäten** vorgesehen: kein anonymer Zugang, keine Anmeldung über Mandantenrollen, Verwaltungsport nur im Betriebsnetz. Ein Mandant hat keinen
Zugang zu dieser Plattform und kann damit keine fremde Telemetrie sehen. `infra/check_telemetry.py` prüft den anonymen Zugang und fehlende Geheimnisse im Overlay;
die tatsächliche Anmeldung (Betriebs-Realm) ist nicht ausgeführt.

## Ausfallsicherheit

Jeder Aufruf der Instrumentierung läuft über `SafeTelemetry.safely` bzw. fängt Ausnahmen (Worker): Telemetrie stört nie das Geschäft. Ist der Collector nicht erreichbar,
verwirft der Exporter Spans im Hintergrund (Fachkern) bzw. zählt `export_failures` (Worker); Anfragen und Zustellungen laufen unverändert (Tests: `TelemetryApiTest`,
`TelemetryTest`, `test_telemetry.py`).

## Aufbewahrung (OP-08 offen)

Das Pflichtenheft legt keine Aufbewahrungsdauer fest. Arbeitsannahme bis zur Entscheidung: Metriken 15 Tage, Traces 7 Tage, Logs 14 Tage (`loki.yaml`, `tempo.yaml`,
Prometheus-Flag im Betriebs-Compose). Die lokale Entwicklung hält 2 Tage. D-11 (Supportzugriff, Aufbewahrung) blockiert nur die Produktivschaltung.

## Nicht ausgeführt

Docker-Dienste (Collector, Prometheus, Tempo, Loki, Grafana, Exporter) sind in der Entwicklungsumgebung nicht gestartet; belegt sind Konfigurationsprüfung
(`infra/check.py`, `infra/check_telemetry.py`), die Anwendungs- und Worker-Tests gegen echte HTTP-Endpunkte und ein lokaler OTLP-Sammler im Test. Dashboards sind nicht in Grafana
geladen. Die Instrumentierung des Fachkerns schließt nur die in diesem Repository vorhandenen Komponenten ein; der Relay- und Operation-Zeitgeber laufen erst mit der
`TenantSource` des Betriebs (OP-06/OP-08), die Warteschlangenmetriken ebenso.
