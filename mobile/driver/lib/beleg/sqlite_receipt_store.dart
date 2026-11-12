import 'dart:convert';
import 'dart:typed_data';

import 'package:sqlite3/sqlite3.dart';

import 'receipt_draft.dart';
import 'receipt_ports.dart';

/// Verschlüsselte SQLite-Ablage der Belegentwürfe (WP-M4-26), gleiche Verschlüsselung wie die Outbox (WP-M3-24, `SqliteOutboxStore`: SQLite3MultipleCiphers, Schlüssel im Schlüsselbund des Geräts).
/// Rohbild, bearbeitete Ansicht und Metadaten stehen in einer Zeile und werden in einer Anweisung geschrieben (atomar).
///
/// Mit `flutter test` ausgeführt (`test/beleg_sqlite_store_test.dart`: Neustart, falscher Schlüssel, kein Klartext); nicht auf einem Gerät.
class SqliteReceiptDraftStore implements ReceiptDraftStore {
  SqliteReceiptDraftStore._(this._db);
  final Database _db;

  /// [hexKey]: 64 Hex-Zeichen (256 Bit), dieselbe Schlüsselquelle wie die Outbox.
  static SqliteReceiptDraftStore open(String path, {required String hexKey}) {
    if (!RegExp(r'^[0-9a-fA-F]{64}$').hasMatch(hexKey)) throw ArgumentError('hexKey muss aus genau 64 Hexzeichen bestehen');
    final Database db = sqlite3.open(path);
    try {
      db.execute("PRAGMA cipher = 'sqlcipher';");
      db.execute('PRAGMA key = "x\'$hexKey\'";');
      db.execute('PRAGMA busy_timeout = 5000;');
      db.select('SELECT count(*) FROM sqlite_master;');
      db.execute('PRAGMA journal_mode = WAL;');
      db.execute('''
        CREATE TABLE IF NOT EXISTS receipt_draft (
          seq INTEGER PRIMARY KEY AUTOINCREMENT,
          client_uuid TEXT NOT NULL UNIQUE,
          meta TEXT NOT NULL,
          raw BLOB NOT NULL,
          edited BLOB
        );
      ''');
    } on Object {
      db.close();
      rethrow;
    }
    return SqliteReceiptDraftStore._(db);
  }

  void close() => _db.close();

  @override
  Future<void> save(ReceiptDraft d) async {
    // Das Rohbild einer vorhandenen Zeile wird nie überschrieben (nur Metadaten und bearbeitete Ansicht ändern sich).
    _db.execute(
      'INSERT INTO receipt_draft (client_uuid, meta, raw, edited) VALUES (?, ?, ?, ?) '
      'ON CONFLICT(client_uuid) DO UPDATE SET meta = excluded.meta, edited = excluded.edited',
      <Object?>[d.clientUuid, jsonEncode(d.toStoredJson()), d.rawBytes, d.editedBytes],
    );
  }

  ReceiptDraft _row(Row r) => ReceiptDraft.fromStored(
        (jsonDecode(r['meta'] as String) as Map<Object?, Object?>).cast<String, Object?>(),
        raw: Uint8List.fromList(r['raw'] as List<int>),
        edited: r['edited'] == null ? null : Uint8List.fromList(r['edited'] as List<int>),
      );

  @override
  Future<ReceiptDraft?> load(String clientUuid) async {
    final ResultSet rs = _db.select('SELECT * FROM receipt_draft WHERE client_uuid = ?', <Object?>[clientUuid]);
    return rs.isEmpty ? null : _row(rs.first);
  }

  @override
  Future<List<ReceiptDraft>> all() async => _db.select('SELECT * FROM receipt_draft ORDER BY seq').map(_row).toList();

  @override
  Future<List<ReceiptDraft>> unsynced() async => (await all()).where((ReceiptDraft d) => d.status != ReceiptSyncStatus.synced).toList();
}
