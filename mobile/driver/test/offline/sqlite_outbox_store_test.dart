// Test der verschluesselten SQLite-Ablage. NICHT AUSGEFUEHRT (kein Dart/Flutter-SDK ausfuehrbar,
// Build-Hook fuer SQLite3MultipleCiphers ungeprueft). Auf Geraet/CI nachzuziehen (WP-M0-28 AK 3).
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/offline/models.dart';
import 'package:taktovia_driver/offline/sqlite_outbox_store.dart';

const String hexKey = '00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff';

QueuedEvent event(String id) => QueuedEvent(
      deviceEventId: id,
      type: 'arrived',
      payload: const <String, Object?>{'stopId': 's1'},
      baseRevision: 1,
      capturedAtUtc: DateTime.utc(2026, 11, 11),
      integrity: 'x',
    );

void main() {
  late Directory dir;
  setUp(() => dir = Directory.systemTemp.createTempSync('outbox'));
  tearDown(() => dir.deleteSync(recursive: true));

  test('Datei ist ohne Schluessel nicht lesbar und enthaelt keinen Klartext', () async {
    final String path = '${dir.path}/o.db';
    final SqliteOutboxStore s = SqliteOutboxStore.open(path, hexKey: hexKey);
    await s.insertIfAbsent(event('e-1'));
    s.close();
    expect(() => SqliteOutboxStore.open(path, hexKey: 'ff' * 32), throwsA(anything));
    final List<int> bytes = File(path).readAsBytesSync();
    expect(String.fromCharCodes(bytes).contains('stopId'), isFalse);
    expect(String.fromCharCodes(bytes).contains('SQLite format 3'), isFalse);
  });

  test('Einfuegen ist idempotent, Claim ist exklusiv', () async {
    final SqliteOutboxStore s = SqliteOutboxStore.open('${dir.path}/o.db', hexKey: hexKey);
    expect(await s.insertIfAbsent(event('e-1')), isTrue);
    expect(await s.insertIfAbsent(event('e-1')), isFalse);
    final DateTime now = DateTime.utc(2026, 11, 11);
    expect(await s.claimBatch(owner: 'A', now: now, lease: const Duration(seconds: 30), limit: 10), hasLength(1));
    expect(await s.claimBatch(owner: 'B', now: now, lease: const Duration(seconds: 30), limit: 10), isEmpty);
    s.close();
  });
}
