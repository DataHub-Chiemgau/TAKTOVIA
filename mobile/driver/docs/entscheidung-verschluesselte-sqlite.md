# Entscheidung: Bibliothek für verschlüsselte SQLite (WP-M0-06, Voraussetzung WP-M0-28)

Stand 11.11.2026. Status: **Vorschlag, ungeprüft auf Gerät** (nicht ausgeführt, siehe README).

## Gewählt

Dart-Paket `sqlite3` 3.9.1 mit Build-Hook-Option `source: sqlite3mc` (SQLite3MultipleCiphers, Chiffre-Modus `sqlcipher`), Zugriff direkt über `dart:ffi` ohne weiteren Wrapper.

## Gründe

- Die Paketseite von `sqlite3` beschreibt auf allen nativen Plattformen die Nutzung von SQLite3MultipleCiphers oder SQLCipher anstelle von SQLite (pub.dev am 11.11.2026).
- `sqlcipher_flutter_libs` ist laut Version `0.7.0+eol` (15.02.2026) am Lebensende und scheidet aus.
- Das Paket wird aktiv gepflegt (3.9.1 vom 08.11.2026) und benötigt keinen Plattformkanal; der Adapter bleibt in reinem Dart testbar.
- Das Port-Muster (`OutboxStore`) hält die Wahl austauschbar.

## Alternativen

- `sqflite_sqlcipher` 3.4.1: funktioniert über Plattformkanal, daher nicht ohne Gerät/Emulator testbar und ohne gleichzeitigen Mehrprozesszugriff durchdacht. Fallback, falls der Hook auf einem Zielgerät scheitert.
- `drift` auf `sqlite3`: sinnvoll für die Produktivumsetzung in M3 (Migrationen, typsichere Abfragen); für den Spike bewusst nicht eingesetzt.

## Offen

- Lizenzprüfung von SQLite3MultipleCiphers (nach Paketlabels MIT) und der Hook-Optionen im Detail.
- Schlüsselhaltung: 256-Bit-Schlüssel zufällig erzeugen, im Android Keystore bzw. in der iOS Keychain halten (z. B. `flutter_secure_storage` 11.2.0; nicht aufgenommen, solange nicht geprüft). Wiederherstellung nach Neuinstallation und Geräteverlust ist Teil der Empfehlung für M3.
- Prüfung, dass der Hook auf Android (alle ABIs) und iOS baut und die Chiffre aktiv ist (`PRAGMA cipher`).
