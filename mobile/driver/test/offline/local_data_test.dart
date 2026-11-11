import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/auth/oidc.dart';
import 'package:taktovia_driver/auth/session_gate.dart';
import 'package:taktovia_driver/offline/local_data.dart';
import 'package:taktovia_driver/offline/memory_store.dart';
import 'package:taktovia_driver/offline/models.dart';

class MemoryVault implements SecretVault {
  final Map<String, String> m = <String, String>{};
  @override
  Future<String?> read(String key) async => m[key];
  @override
  Future<void> write(String key, String value) async => m[key] = value;
  @override
  Future<void> delete(String key) async => m.remove(key);
}

void main() {
  test('Schluessel: 256 Bit, nur im Schluesselbund, beim zweiten Start derselbe', () async {
    final MemoryVault v = MemoryVault();
    final LocalKeys a = await LocalKeys.loadOrCreate(v, Random(7));
    final LocalKeys b = await LocalKeys.loadOrCreate(v, Random(99));
    expect(a.dbHexKey.length, 64);
    expect(a.dbHexKey, b.dbHexKey);
    expect(a.dbHexKey, isNot(v.m[integrityKeyName]), reason: 'Datenbank- und Integritaetsschluessel sind getrennt');
  });

  test('Abmeldung loescht Datei und Schluessel; Auftragsdaten-Wipe behaelt die Outbox', () async {
    final Directory dir = await Directory.systemTemp.createTemp('driver_wipe');
    final File db = File('${dir.path}/outbox.db')..writeAsStringSync('x');
    final MemoryVault v = MemoryVault();
    await LocalKeys.loadOrCreate(v);
    bool snapshotCleared = false;
    final FileLocalDataWiper w = FileLocalDataWiper(dbPath: db.path, vault: v, outbox: InMemoryOutboxStore(), clearSnapshot: () async => snapshotCleared = true);
    await w.wipeOrderData();
    expect(snapshotCleared, isTrue);
    expect(db.existsSync(), isTrue);
    expect(v.m.isNotEmpty, isTrue);
    await w.wipeAll();
    expect(db.existsSync(), isFalse);
    expect(v.m, isEmpty);
    await dir.delete(recursive: true);
  });

  test('ohne aktive Sitzung oder mit abgelaufenem Token liefert die Quelle keinen Token', () async {
    final SessionGate gate = SessionGate(wiper: FileLocalDataWiper(dbPath: '/nicht/da', vault: MemoryVault(), outbox: InMemoryOutboxStore(), clearSnapshot: () async {}));
    final TokenSet t = TokenSet(accessToken: 'tok', refreshToken: null, expiresAtUtc: DateTime.utc(2026, 11, 12, 9), subject: 's');
    final DateTime now = DateTime.utc(2026, 11, 12, 8);
    expect(await accessTokenFor(gate, () => t, () => now)(), isNull, reason: 'nicht angemeldet');
    gate.signedIn();
    expect(await accessTokenFor(gate, () => t, () => now)(), 'tok');
    expect(await accessTokenFor(gate, () => t, () => DateTime.utc(2026, 11, 12, 10))(), isNull, reason: 'abgelaufen');
  });
}
