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
