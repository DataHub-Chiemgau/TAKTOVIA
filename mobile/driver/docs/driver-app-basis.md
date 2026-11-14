# Driver-App-Basis (WP-M3-24): Sync-Protokoll, lokales Datenmodell, Offline-Umfang

**Stand 11.11.2026. Nichts in `mobile/driver` wurde ausgeführt (kein Flutter in der Umgebung).** Der Nachweis der Serverseite ist echt: `DriverApiTest`, `DriverSyncTest` (Backend) und `db/tests/test_m3_24_fahrer_eingang.py`; sie bilden die Gegenstelle der App ab (Idempotenz, Sitzungssperre, Gerätebindung). Die Dart-Dateien und ihre Tests sind ungeprüft und können Syntax- oder Typfehler enthalten.

## 1. Sync-Protokoll (`POST /v1/driver/events`, `recordDriverEvent`)

- **Reihenfolge:** die Outbox liefert strikt nach `seq` (lokale Erfassungsreihenfolge, nicht nach Uhrzeit). Die Engine sendet ein Ereignis erst, wenn das vorige beantwortet ist; bei Netzfehler, 429 oder 5xx endet der Lauf und alle beanspruchten Ereignisse gehen frei (Reihenfolge bleibt erhalten). Der Server entscheidet über `plan_revision` (letzte bekannte Serverrevision), nicht über die Eingangsreihenfolge.
- **Wiederholung:** at-least-once vom Gerät, genau einmal wirksam am Server. `device_event_id` = `event_id` = `Idempotency-Key`. Ein Ereignis bleibt in der Outbox, bis eine Serverantwort vorliegt. Ein zweites Eintreffen liefert `DUPLICATE` (Dedupe über Primärschlüssel `(tenant_id,event_id)`, dauerhaft, auch nach Ablauf des Idempotenzregisters); innerhalb der Registerfrist wiederholt das Register die gespeicherte erste Antwort. Backoff: 1 s, 5 s, 30 s, 2 min, 10 min, danach 10 min mit Jitter (Planungsdefault, Kap. 05 §5.6).
- **Antworten:** `APPLIED` (wirksam; Zeile `synced`), `DUPLICATE` (Zeile `synced`; mit `original_state=REVIEW_REQUIRED` bleibt sie Klärfall), `REVIEW_REQUIRED` mit `reason_code` (`STALE_PLAN` u. a.; Zeile `reviewRequired`, keine ausgeführte Beförderungsbuchung). 409 `IDEMPOTENCY_CONFLICT` (gleiche `event_id`, anderer Inhalt) und 403/404/422 sind nicht mit demselben Inhalt wiederholbar: die Zeile bleibt als Klärfall sichtbar.
- **Sitzung:** 401 beendet die Nutzung (`SessionGate`): `WWW-Authenticate … session revoked` = gesperrt, sonst abgelaufen; Auftragsdaten verschwinden, die Outbox bleibt verschlüsselt erhalten und wird nach erneuter Anmeldung desselben Kontos gesendet. 403 `FORBIDDEN` bei beendeter oder an eine andere Sitzung gebundener Gerätebindung: Gerät mit `POST /v1/driver/devices` neu binden.
- **Zeiten:** Gerätezeit nur als `device_recorded_at`/`occurred_at`; die Empfangszeit (`received_at`) setzt der Server und kommt nur aus der Antwort.

## 2. Lokales Datenmodell und Verschlüsselung

- Eine SQLite3MultipleCiphers-Datei (`sqlcipher`-Modus, Entscheidung in [entscheidung-verschluesselte-sqlite.md](entscheidung-verschluesselte-sqlite.md)) mit Tabellen `outbox` (je Ereignis: `device_event_id`, `type`, `payload`, `base_revision`, Erfassungszeit, Integritätssumme, Zustand, Serverempfangszeit) und `snapshot` (zuletzt bestätigter Dienst mit Stoppliste und Stand).
- Schlüssel: zwei zufällige 256-Bit-Schlüssel (Datenbank, Zeilenintegrität) in Android Keystore/iOS Keychain (`SecretVault`, `LocalKeys`), nie in der Datei, im Repository oder im Log. Die Datei ist ohne Schlüssel nicht lesbar (Seitenverschlüsselung); die HMAC je Zeile erkennt Manipulation einzelner Zeilen.
- Abmeldung: `SessionGate.signOut` verweigert bei offenen Ereignissen (außer `force`), sonst löscht `FileLocalDataWiper.wipeAll` Schlüssel, Datei und Begleitdateien. Gesperrte Sitzung: nur Auftragsdaten (Snapshot) werden entfernt, die Outbox bleibt verschlüsselt für dasselbe Konto; bei Kontowechsel wird sie verworfen (Annahme, mit D-11 zu bestätigen).

## 3. Offline zulässige Aktionen (`OfflinePolicy`)

Zulässig: Beleg erfassen, Schaden melden, Zeitereignis, Fahrerereignisse des laufenden Dienstes, Prüfentwurf anlegen. **Nie offline:** Einsatzfreigabe anfordern/Prüfung zur Freigabe abschließen, Sperre aufheben, verbindliche Buchung (Kap. 01 §1.5); die App zeigt „freigegeben“ erst nach Serverantwort. Test: `test/offline/offline_actions_test.dart` (nicht ausgeführt).

## 4. Nicht Teil dieses WP

Beleg- und Zeiterfassung (M4), Passenger-App (M7), Hintergrundortung (WP-M3-34), Navigation (WP-M3-28). Offen: Zielgeräteliste (D-05), Anbieter der Testverteilung, `android/` und `ios/` (entstehen mit `flutter create`), Verdrahtung des Hauptprogramms mit OIDC-Paket und Schlüsselbundpaket (Versionen beim ersten Flutter-Lauf festlegen und prüfen).

## 5. Offline-Prüfung (WP-M3-33)

**Nichts davon in Dart wurde ausgeführt** (`lib/pruefung/offline_inspection.dart`, `test/pruefung/offline_inspection_test.dart`); die Serverseite ist durch `OfflineInspectionSyncTest` (Backend, echte Datenbank, parallele Transaktionen) belegt.

- **Voraussetzung lokal:** Vorlagenrevision und Liste der Altschäden des Fahrzeugs nach dem letzten Online-Kontakt (`OfflineInspectionBundle`). Fehlt eines, beginnt offline keine Prüfung und die App sagt, warum (`inspection.offline.noTemplate`, `inspection.offline.noKnownDefects`).
- **Abschluss lokal:** `inspectionSubmit` in der Outbox. Angezeigt werden drei getrennte Zustände: „lokal abgeschlossen, Freigabe ausstehend“, „synchronisiert“, „freigegeben“. „Freigegeben“ nur mit Serverstand `RELEASED` aus `GET /v1/driver/shifts/{id}/run`. Ein kritisches Nein zeigt sofort „nicht fahren, Leitstelle kontaktieren“; die Sperre setzt der Server beim Sync.
- **Sync:** Der Lauf wird nachträglich gestartet (`POST /v1/inspections`, stabiler Idempotenzschlüssel je Ereignis, `client_started_at` = Gerätezeit) und mit `client_submitted_at` (Gerätezeit) und `device_event_id` abgeschlossen. 409 `IMMUTABLE_RECORD` = schon verarbeitet (`DUPLICATE`). 422/404/403 = nicht übernommen (`REVIEW_REQUIRED`, Klärfall).
- **Neubewertung am Server beim Empfang (Entscheidung, in den Verträgen nicht vorgegeben):**
  - Eine Sperre, die bis zum Sync entsteht, verhindert die Freigabe; die Prüfung bleibt als Nachweis, Ergebnis und fehlende Voraussetzung (`DEFECT_BLOCK`) stehen in der Antwort.
  - Maßgeblich für „nach der Wiederfreigabeentscheidung“ ist der frühere Zeitpunkt aus Gerätezeit, Empfangszeit und Laufstart (Server). Die Gerätezeit kann die Neubewertung nicht umgehen.
  - Die Höchstgültigkeit der Freigabe (16 h, OP-33) zählt ab Prüfabschluss (Gerätezeit, nie nach der Empfangszeit); ein später Empfang erhält keine frische Gültigkeit (`INSPECTION_EXPIRED`).
  - Schicht beendet oder Fahrzeugwechsel (`VEHICLE_CHANGED`): Nachweis gespeichert, keine Freigabe.
  - Vorlagenrevision: ein vor dem Start online begonnener Lauf bleibt an seine Revision gebunden. Ein offline begonnener Lauf wird beim Sync gegen die dann aktuelle Revision gestartet; passen die Antworten nicht, antwortet der Server 422 ohne jede Wirkung, die App zeigt „vom Server nicht übernommen“ und die Prüfung ist neu durchzuführen.
- **Schadenmeldung offline:** unverändert `device_event_id` (genau eine Akte mit Fallnummer; Tests `DefectCaseCreateTest`, WP-M3-43); lokaler Status UI-10 als `DraftStatus`.
