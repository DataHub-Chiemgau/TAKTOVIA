# Maskierung von Diagnosedaten (WP-M5-06, LH-6.19-NFR-015)

Logs, Trace-Attribute, Fehlerberichte und Alarmtexte enthalten keine ungeschützten Zahlungsdetails und keine unnötigen Kontaktinformationen. Das Pflichtenheft
legt keine Maskierungsregeln fest (OP-09); der Detailvertrag WP-M5-01 liegt noch nicht vor. Dieses Dokument enthält die Arbeitsannahme und gilt, bis WP-M5-01
andere Regeln setzt. Referenzimplementierung: `backend/platform/.../logging/Maskierung.kt`; gleiche Regeln in `workers/*/diagnose.py` und `web/api-client/src/fehlerbericht.ts`.

## Feldklassen und Ersatzdarstellung

| Klasse | Beispiel (erfunden) | Ersatz |
|---|---|---|
| Karte | 4111 1111 1111 1111 (Luhn und Kartennetz) | `[card-data-removed]` |
| IBAN | DE89 3704 0044 0532 0130 00 | `[maskiert:iban]` |
| E-Mail | erika.mustermann@example.test | `[maskiert:email]` |
| Telefon | +49 176 12345678, 0176 12345678, 030/1234567 | `[maskiert:telefon]` |
| Adresse | Musterstraße 12, 10115 Berlin; "10115 Berlin" | `[maskiert:adresse]` |
| Token / Geheimnis | JWT, `Bearer …`, `token=…`, `password=…`, `api_key=…`, `authorization: …` | Wert `[maskiert:token]` (Schlüssel bleibt) |
| Boarding-Code | `boarding_code=X7K9Q2`, `BC-AB12CD` | `[maskiert:boarding-code]` |

**Zulässige technische Bezeichner:** UUIDs (auch Fahrgast-, Mandanten-, Buchungs-UUID; Arbeitsannahme zu OP-09), W3C-Trace- und Span-IDs, Zeitpunkte, Zähler, Statuscodes,
Haltepunkt-IDs. **Nicht zulässig als Klartext:** Haltepunktname und Start-/Zielkoordinate eines Fahrgasts werden nicht protokolliert (stehen nicht auf der Positivliste).
Das Boarding-Code-Format ist nicht festgelegt (OP-18, nur Hash gespeichert); die Regel erkennt Schlüssel-Wert-Paare und die Annahme `BC-<alphanumerisch>`.

## Wo gilt die Maskierung

- **Logausgabe Fachkern:** Logback-Wörter `%safeMsg`/`%safeEx` (`logback-spring.xml`) führen Nachricht und Ausnahmetext durch `Maskierung.maskText`; Ausnahmen für Logs
  werden mit `Maskierung.beschreibe` (Klasse, maskierte gekürzte Nachricht, Ursachenkette, kein Objektinhalt) beschrieben; strukturierte Zeilen über `LogZeile`
  (Positivliste je Modul, Mandanten- und Korrelationsbezug aus dem `LogKontext`).
- **Trace-Attribute:** `MaskingObservationFilter` (Schlüssel `user.*`, Such- und Kontaktschlüssel vollständig, übrige Werte maskiert; `http.url` ohne Query) vor dem
  Collector-Filter `attributes/pii_guard`.
- **`application/problem+json`:** `Problem.toMap` maskiert `detail` und `violations[].message`; Stacktraces, Klassennamen und Datenbankmeldungen stehen nie in der
  Antwort (`GlobalProblemAdvice`).
- **Alarmtexte:** `OperatorAlertService.json` maskiert jeden Wert; Warnungsattribute bleiben zusätzlich auf die Positivliste der `OperatorWarningPolicy` beschränkt.
- **Worker (Routing, Dokumente):** `JsonFormatter` (Nachricht maskiert, Zusatzfelder nur aus der Positivliste, Ausnahmen als Beschreibung ohne Stapel).
- **PSP-Adapter und Benachrichtigungsadapter:** laufen im Fachkern-Prozess und damit durch dasselbe Layout; der PSP-Adapter protokolliert nur Operation, Grund und Referenz.
- **Gateway:** Zugriffslog `taktovia_masked` ohne Query, Kopfzeilen, Referer und User-Agent.
- **Clients (Web):** `maskiereFehlerbericht` in `web/api-client` entfernt Kontaktdaten, Token und Query aus Fehlerberichten, bevor sie versendet werden.
- **Zentrale Logablage:** Collector-Prozessor `transform/log_masking` als zweite Schicht.

## Nachvollziehbarkeit

`audit_event` und Sicherheitsereignisse bleiben über IDs (Mandant, Akteur-Referenz, Objekt, Correlation-ID) nachvollziehbar; die Maskierung ersetzt nur Klartext der Feldklassen,
nie UUIDs (Test `MaskierungTest.audit_event_bleibt_ueber_ids_nachvollziehbar`).

## Prüfskript "Log-Stichprobe"

`python3 tools/log_stichprobe.py` durchsucht Logquellen je Komponente unabhängig von der Maskierungsimplementierung nach Karten (Luhn), IBAN (Prüfsumme),
E-Mail, Telefon, Adresse, Token und Boarding-Code. Es protokolliert Umfang (Zeilen je Quelle), Zeitraum (frühester/spätester Zeitstempel) und die Trefferliste
(Klasse, Quelle, Zeile, Hash-Präfix; nie den Klartext) als JSON, verlangt die Komponenten der Stichprobe (`--require`) und endet mit Exit-Code 1 bei einem Treffer oder einer
fehlenden Komponente. Als CI-Schritt gegen die Logs eines Testlaufs (`tests/acceptance/diagnose`), als Betriebsprüfung mit `--loki <URL>` gegen die zentrale Logablage
(Abfrage über die Loki-HTTP-API; **nicht gegen eine echte Loki-Instanz ausgeführt**, nur gegen einen lokalen HTTP-Nachbau der API).
