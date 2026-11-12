// WP-M4-26: verschlüsselte lokale Ablage der Belegentwürfe (Neustart, Schlüssel, Rohbild bleibt unverändert). Ausgeführt mit Flutter 3.47.9 (flutter test).
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/beleg/receipt_draft.dart';
import 'package:taktovia_driver/beleg/sqlite_receipt_store.dart';

import 'beleg_fakes.dart';

const String hexKey = '00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff';

void main() {
  late Directory dir;
  setUp(() => dir = Directory.systemTemp.createTempSync('belege'));
  tearDown(() => dir.deleteSync(recursive: true));

  test('entwurf_ueberlebt_neustart_mit_gleicher_uuid_und_ist_verschluesselt', () async {
    final String path = '${dir.path}/b.db';
    final ReceiptDraft d = ReceiptDraft(
      clientUuid: '00000000-0000-4000-8000-0000000000c1',
      capturedAtUtc: DateTime.utc(2026, 12, 1, 7, 15),
      rawBytes: jpegBytes(5),
      mime: 'image/jpeg',
      caseRef: ownVehicle,
      editedBytes: jpegBytes(6, 900),
    );
    SqliteReceiptDraftStore s = SqliteReceiptDraftStore.open(path, hexKey: hexKey);
    await s.save(d);
    s.close();
    // „App-Neustart“: Datei erneut öffnen
    s = SqliteReceiptDraftStore.open(path, hexKey: hexKey);
    final ReceiptDraft? back = await s.load(d.clientUuid);
    expect(back, isNotNull);
    expect(back!.status, ReceiptSyncStatus.local);
    expect(back.rawSha256, d.rawSha256);
    expect(back.rawBytes, d.rawBytes);
    expect(back.editedBytes, d.editedBytes);
    expect(back.capturedAtUtc, d.capturedAtUtc);
    expect(back.caseRef!.id, ownVehicle.id);
    // Rohbild wird bei einer späteren Aktualisierung nie überschrieben
    back.status = ReceiptSyncStatus.synced;
    back.documentId = 'doc-1';
    await s.save(back);
    expect((await s.load(d.clientUuid))!.rawBytes, d.rawBytes);
    expect(await s.unsynced(), isEmpty);
    s.close();
    expect(() => SqliteReceiptDraftStore.open(path, hexKey: 'ff' * 32), throwsA(anything)); // falscher Schlüssel
    expect(String.fromCharCodes(File(path).readAsBytesSync()).contains('client_uuid'), isFalse); // kein Klartext
  });
}
