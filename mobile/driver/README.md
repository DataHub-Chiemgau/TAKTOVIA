# mobile/driver

Flutter-Fahrer-App mit verschlüsselter lokaler Outbox (Offlinebetrieb, Kap. 01 §1.5).

- **Verantwortlich:** Betrieb/Mobil (Rolle laut D-12, noch keine Personen benannt)
- **Regeln:** [CONTRIBUTING.md](../../CONTRIBUTING.md)
- **Stand:** Gerüst (WP-M0-06) und Logik des Spikes (c) (WP-M0-28) liegen als Dateien vor. **Nichts davon wurde ausgeführt**, siehe unten.

## Festgeschriebene Werkzeuge

| Baustein | Version | Quelle |
|---|---|---|
| Flutter (stable) | 3.47.9 | `releases_linux.json` von storage.googleapis.com am 11.11.2026; Archiv-SHA-256 `25ed8c3c…044c5a` stimmt mit dem Manifest überein (Archiv geladen, entpackt) |
| Dart | 3.13.7 (liegt Flutter 3.47.9 bei) | dasselbe Manifest |
| `sqlite3` (Dart-Paket, mit SQLite3MultipleCiphers) | 3.9.1 | pub.dev-API am 11.11.2026 |
| `crypto` | 3.0.8 | pub.dev-API am 11.11.2026 |
| `flutter_lints` | 6.0.0 | pub.dev-API am 11.11.2026 |

Die Lizenzen sind nicht einzeln geprüft (Flutter BSD-3-Clause laut Plan, `sqlite3` MIT laut pub.dev-Tags); Eintrag in `docs/umsetzungsplan/versionen.md` durch die Zuständigen. Begründung der Wahl: [docs/entscheidung-verschluesselte-sqlite.md](docs/entscheidung-verschluesselte-sqlite.md).

- **WP-M4-14/-20 (nicht ausgeführt):** `lib/zeit/next_duty.dart` (Eigensicht `GET /v1/rosters/own-shifts`, „Nächster Dienst“) mit `test/zeit/next_duty_test.dart`; reine Dart-Logik, ohne Widgetanbindung, weder analysiert noch getestet. Die Anzeige UI-13 ist als Web-Ansicht gebaut und getestet (`web/operator/src/zeit`).

- **WP-M3-31 (nicht ausgeführt):** `lib/shift/service_status.dart` (Dienststatus, Umschaltziele des Fahrers, Hinweis auf die Wirkung offline gemeldeter Störung und Ende, Body von `reportDriverServiceStatus`) mit `test/dienststatus_test.dart`; reine Dart-Logik, ohne Widgetanbindung, weder analysiert noch getestet.

## Was fehlt (Blocker)

- **Flutter/Dart nicht ausgeführt.** Das Flutter-SDK wurde heruntergeladen und geprüft, der Start (`flutter --version`) wurde von der Berechtigungsprüfung der Sitzung abgelehnt (Ausführung heruntergeladenen Codes). Es liegen daher **kein** `flutter analyze`, **kein** `flutter test`, **kein** `pubspec.lock`, **kein** Build-Ergebnis vor. Die Dart-Dateien sind ungeprüft und können Syntax- oder Typfehler enthalten.
- **`android/` und `ios/` fehlen.** Sie entstehen durch `flutter create --platforms=android,ios --org de.taktovia .` im Verzeichnis `mobile/driver`; die vorhandenen `lib/`, `test/` und `pubspec.yaml` anschließend gegen das Ergebnis abgleichen. Damit ist WP-M0-06 AK 1 (Debug-Artefakt je Plattform in CI) offen; die CI-Stufe liegt in WP-M0-12 (`.github/`, nicht Teil dieser Zuweisung).
- **Zielgeräteliste** (D-05, D-12) liegt nicht vor; ohne Angabe wird auf Referenzgeräten gebaut.

## Erste Schritte (mit Flutter 3.47.9)

```bash
cd mobile/driver
flutter create --platforms=android,ios --org de.taktovia .   # erzeugt android/ und ios/
flutter pub get                                              # erzeugt pubspec.lock (einchecken)
flutter analyze                                              # Lint; Verstoß bricht ab
flutter test                                                 # Widget-Test und Offline-Logiktests
```

Negativtest Lint (WP-M0-06): eine unbenutzte Variable oder einen unbenutzten Import einfügen; `flutter analyze` muss mit Exit-Code ungleich 0 enden (`analysis_options.yaml` setzt diese Befunde auf `error`). Noch nicht ausgeführt.

## Driver-App-Basis (WP-M3-24)

Anmeldung (`lib/auth`: PKCE, OIDC-Ablauf, `SessionGate`), Offline-Aktionsliste (`lib/offline/offline_actions.dart`), Schluessel und Abraeumen (`lib/offline/local_data.dart`), HTTP-Adapter fuer `recordDriverEvent` (`lib/net/driver_http.dart`), Rahmen mit Offlinebanner und Barrierefreiheitsgrundlagen (`lib/main.dart`, `lib/ui`). Protokoll, Datenmodell und Verschluesselungskonzept: [docs/driver-app-basis.md](docs/driver-app-basis.md); Verteilung: [ci/distribute.yml.example](ci/distribute.yml.example). **Nicht ausgefuehrt**; die Serverseite ist durch Backend-Tests belegt.

## Belegerfassung per Kamera und Offline-Synchronisation (WP-M4-25, WP-M4-26)

`lib/beleg/`: geführte Erfassung in höchstens fünf Eingabeschritten (Foto, Zuschnitt/Drehung, Vorgang, Speichern; `receipt_capture_flow.dart`), Qualitätsprüfung (Auflösung, Schärfe; Schwellen sind Planungsdefaults, Offener Punkt 13; `receipt_quality.dart`), Entwurf mit vor dem Upload erzeugter Client-UUID, lokal berechnetem Hash des Rohbilds und getrennter Erfassungszeit (`receipt_draft.dart`), verschlüsselte Ablage (`sqlite_receipt_store.dart`), Synchronisation mit Wiederaufnahme und Hash-Vergleich (`receipt_sync.dart`), HTTP-Adapter für `/v1/expenses/receipts` (`receipt_http.dart`) und Bildschirm mit getrennter Anzeige „lokal“, „synchronisiert“ und „freigegeben“ (`receipt_capture_screen.dart`). Tests: `test/beleg_erfassung_test.dart`, `test/offline_beleg_sync_test.dart` (Attrappen in `test/beleg_fakes.dart`).

**Ausgeführt** mit Flutter 3.47.9 (`flutter test`, 69 Tests der App grün, `flutter analyze` ohne Befund in `lib/beleg` und den Belegtests; die vorhandenen Befunde in `lib/offline/sqlite_outbox_store.dart` und zwei älteren Tests bleiben unberührt): Logik, Widgets, verschlüsselte Ablage, Synchronisation gegen eine Server-Attrappe. **Nicht ausgeführt:** kein Lauf auf einem Gerät, kein HTTP gegen das echte Backend (`receipt_http.dart` ungeprüft), keine Anbindungen an Gerätefunktionen (Kamera-Plugin, Bildzuschnitt, Fahrbewegung aus den Positionsdaten, Zuweisungsliste aus dem Offline-Lesestand) als Implementierungen der Ports in `receipt_ports.dart`; ebenso kein Upload-Ziel, solange am Server kein Objektspeicher-Adapter angebunden ist (`upload` im Ticket ist dann `null`, der Entwurf bleibt lokal mit Hinweis). Die Serverseite ist durch Backend-Tests mit echter Datenbank belegt (`ReceiptCaptureTest`, `OfflineReceiptSyncTest`, `ReceiptCaptureApiTest`, `db/tests/test_expense_client_uuid.py`).

## Stempeln und Offline-Stempel (WP-M4-11, WP-M4-12)

`lib/zeit/work_time.dart` (Zustandsautomat Kommen/Pause/Gehen, `WorkTimeController`: Stempel zuerst in der verschlüsselten Outbox mit stabiler `deviceEventId`, danach Übertragung; `TimeEventRoutingApi`: Stempel laufen über `POST /v1/time-events`, nicht über `/v1/driver/events`, und teilen sich Outbox und `SyncEngine` mit den übrigen Ereignissen) und `lib/zeit/work_time_screen.dart` (Bereich „Arbeitszeit“ im Dienststart: Status als Symbol und Text, Touchflächen mindestens 44×44, Einsatzfreigabe getrennt angezeigt, während der Fahrbewegung kein Dialog). Tests: `test/arbeitszeit_stempeln_test.dart`, `test/offline_stempel_queue_test.dart` (Attrappen in `test/zeit_fakes.dart`). **Nicht ausgeführt:** Flutter/Dart war in der Umgebung nicht lauffähig, die Dart-Dateien sind weder analysiert noch getestet; die Serverseite ist durch `TimeEventStempelnTest`, `TimeEventOfflineSyncTest` und `TimeEventStempelnApiTest` mit echter Datenbank belegt. Nicht gebaut: Anbindung von `WorkTimeMovement` an die Positionsdaten und der Einbau des Bereichs in den Dienststart-Bildschirm.

## Aufbau

- `lib/main.dart` – App-Gerüst ohne Bildschirme (UI-09 ff. ab M3).
- `lib/offline/` – Offline-Queue-Logik des Spikes WP-M0-28, reines Dart ohne Flutter-Abhängigkeit:
  - `models.dart` – Ereignis (`device_event_id`, `baseRevision` = letzte Serverrevision, Erfassungszeit Geräteuhr, Empfangszeit Server, Zustand), Offline-Lesestand mit „Stand von …“.
  - `ports.dart` – Ports `OutboxStore`, `SnapshotStore`, `DriverEventApi`.
  - `offline_queue.dart` – Erfassung; Konfliktverdacht Check-in vs. Storno.
  - `sync_engine.dart` – Sync mit Lease (zwei Prozesse), at-least-once Sendung + serverseitige Idempotenz = genau einmal wirksam.
  - `memory_store.dart` – Referenzspeicher für Tests.
  - `sqlite_outbox_store.dart` – verschlüsselte SQLite-Ablage (Adapter, ungeprüft).
  - `fake_driver_event_server.dart` – Test-Gegenstelle für `recordDriverEvent` (`APPLIED`/`DUPLICATE`/`REVIEW_REQUIRED`).
- `test/` – Widget-Test und Logiktests; `test/offline/sqlite_outbox_store_test.dart` braucht den nativen SQLite3MultipleCiphers-Build.
- `docs/` – Entscheidung zur verschlüsselten SQLite, Spike-Drehbuch, Messprotokoll-Vorlage.

## Entwurfsentscheidungen des Spikes

- **Genau einmal wirksam:** Client sendet at-least-once; ein Ereignis bleibt in der Outbox, bis eine Serverantwort vorliegt. Der Server dedupliziert über `device_event_id`. Eine verlorene Antwort führt zu `DUPLICATE`; war die erste Verarbeitung `REVIEW_REQUIRED`, liefert der Server das im Feld `originalOutcome` mit (Annahme für den Vertrag `recordDriverEvent`, mit der API-Spezifikation abzugleichen).
- **Nebenläufigkeit:** `claimBatch` ist atomar (SQLite: `BEGIN IMMEDIATE`) und vergibt Leases; abgelaufene Leases werden von einem anderen Lauf übernommen. Die Reihenfolge zwischen zwei gleichzeitigen Läufen ist nicht garantiert; der Server muss über `baseRevision` entscheiden, nicht über Eingangsreihenfolge (offen für M3).
- **Zeiten:** Erfassungszeit (Geräteuhr) wird nie überschrieben; die Serverempfangszeit steht getrennt. Lokale Reihenfolge folgt `seq`, nicht der Uhr.
- **Manipulation:** HMAC je Zeile über die unveränderlichen Felder (Schlüssel getrennt vom Datenbankschlüssel zu halten); fehlerhafte Zeilen werden in Quarantäne gesetzt und nie gesendet. Die Verschlüsselung der Datei (Seitenauthentisierung) schützt zusätzlich die Gesamtdatei.
- **Klärfall Check-in vs. Storno:** nur Erkennung und Kennzeichnung (`conflictHint`, `ReviewReason`). Der Workflow ist M3.

## Dienststart (WP-M3-25), nicht ausgeführt

`lib/shift/shift_start.dart` (Modelle, Port `ShiftApi`, Adapter `HttpShiftApi` für `GET /v1/driver/shifts`, `POST /v1/driver/shift-starts|shift-ends`, Anzeigezustand `ShiftScreenState` für UI-09: Leerzustand, noch nicht startbar, startbar, angetreten, entzogen) und `test/shift/shift_start_test.dart`. **Nicht ausgeführt** (kein Flutter in der Umgebung, siehe oben): kein `flutter analyze`, kein `flutter test`. Die Gegenstelle ist durch `backend/app/src/test/kotlin/de/taktovia/app/driver/DriverShiftApiTest.kt` gegen den echten Server nachgewiesen. Die App entscheidet nie selbst, ob ein Dienst gültig ist (Serverzeit, `valid`), und zeigt keine Aufträge ohne Serverbestätigung.

## Positionserfassung (WP-M3-34), nicht ausgeführt

`lib/position/position_capture.dart` (Erfassungsregel `PositionCapturePolicy`: nur im vom Server als angetreten und gültig gemeldeten Dienst, Hinweis `position.notice`; Puffer und Nachlieferung in Paketen zu höchstens 100 Punkten mit der ursprünglichen Messzeit; Adapter `HttpPositionApi` für `POST /v1/driver/positions`) und `test/position/position_capture_test.dart`. **Nicht ausgeführt** (kein Flutter in der Umgebung): kein `flutter analyze`, kein `flutter test`; die Anbindung an die Plattform-Ortung (Android/iOS, Hintergrundortung, Berechtigungsdialog) ist nicht gebaut und gehört zur Zielgeräteliste (D-05) und zur Freigabe von D-09/D-10. Planungsannahmen: Sendeintervall 10 s (D-05 offen), keine Geschwindigkeit und Richtung (Minimierung). Die Gegenstelle ist durch `backend/app/src/test/kotlin/de/taktovia/app/driver/DriverPositionApiTest.kt` gegen den echten Server nachgewiesen.

- **WP-M3-29 (nicht ausgefuehrt):** `lib/fahrt/wait_period.dart` (Wartefrist am Stopp, No-Show erst nach `no_show_grace_min` und mit Grund) mit `test/fahrt/wait_period_test.dart`; `lib/net/driver_http.dart` reicht `no_show_reason` und `no_show_wait_seconds` an `recordDriverEvent`. Weder analysiert noch getestet (kein Flutter-SDK); die Widgetanbindung der grossen Check-in/out-Flaechen und die Sperre der Erfassungsdialoge waehrend der Fahrbewegung (Kap. 06 §6.7) stehen aus.
- **WP-M3-30 (nicht gebaut):** Die Flutter-Oberflaeche UI-10 fuer die Fahrgastliste (`GET /v1/driver/shifts/{id}/passengers`), die Codeeingabe/-scan und den Fehlerzustand "falscher Code" ist nicht gebaut; `lib/net/driver_http.dart` kennt `boarding_code` noch nicht. Serverseitig und per Vertrag sind Pruefung, Sperre und Liste fertig (`docs/umsetzungsplan/vertraege/LH-6.6-DRV-006.md`).
