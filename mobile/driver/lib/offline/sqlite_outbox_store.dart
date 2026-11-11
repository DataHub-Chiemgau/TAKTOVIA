import 'dart:convert';

import 'package:sqlite3/sqlite3.dart';

import 'models.dart';
import 'ports.dart';

/// Verschluesselte SQLite-Ablage der Outbox und des Offline-Lesestands.
///
/// NICHT AUSGEFUEHRT: Dieser Adapter wurde in der Erstellungsumgebung weder
/// analysiert noch getestet (siehe README). Die PRAGMA-Schreibweise folgt der
/// Dokumentation von SQLite3MultipleCiphers/SQLCipher und ist beim ersten Lauf
/// mit Flutter zu verifizieren. Der Schluessel gehoert in den Schluesselbund des
/// Geraets (Android Keystore / iOS Keychain), nie in diese Datei oder ins Repository.
class SqliteOutboxStore implements OutboxStore, SnapshotStore {
  SqliteOutboxStore._(this._db);

  final Database _db;

  /// Oeffnet (oder legt an) die Datenbank. [hexKey]: 64 Hex-Zeichen (256 Bit).
  /// Wirft [SqliteException], wenn der Schluessel nicht passt oder die Datei
  /// manipuliert ist ("file is not a database").
  static SqliteOutboxStore open(String path, {required String hexKey}) {
    if (!RegExp(r'^[0-9a-fA-F]{64}$').hasMatch(hexKey)) {
      throw ArgumentError('hexKey muss aus genau 64 Hexzeichen bestehen');
    }
    final Database db = sqlite3.open(path);
    try {
      db.execute("PRAGMA cipher = 'sqlcipher';");
      db.execute('PRAGMA key = "x\'$hexKey\'";');
      db.execute('PRAGMA busy_timeout = 5000;');
      // Erzwingt die Schluesselpruefung (liest die erste Seite).
      db.select('SELECT count(*) FROM sqlite_master;');
      db.execute('PRAGMA journal_mode = WAL;');
      db.execute('''
        CREATE TABLE IF NOT EXISTS outbox (
          seq INTEGER PRIMARY KEY AUTOINCREMENT,
          device_event_id TEXT NOT NULL UNIQUE,
          type TEXT NOT NULL,
          payload TEXT NOT NULL,
          base_revision INTEGER NOT NULL,
          captured_at_ms INTEGER NOT NULL,
          integrity TEXT NOT NULL,
          state TEXT NOT NULL,
          attempts INTEGER NOT NULL DEFAULT 0,
          claim_owner TEXT,
          lease_until_ms INTEGER,
          server_received_at_ms INTEGER,
          server_revision INTEGER,
          outcome TEXT,
          review_reason TEXT,
          conflict_hint INTEGER NOT NULL DEFAULT 0
        );
      ''');
      db.execute('CREATE INDEX IF NOT EXISTS outbox_state_seq ON outbox(state, seq);');
      db.execute('''
        CREATE TABLE IF NOT EXISTS snapshot (
          id INTEGER PRIMARY KEY CHECK (id = 1),
          json TEXT NOT NULL
        );
      ''');
      db.execute('PRAGMA user_version = 1;');
    } on Object {
      db.dispose();
      rethrow;
    }
    return SqliteOutboxStore._(db);
  }

  void close() => _db.dispose();

  @override
  Future<bool> insertIfAbsent(QueuedEvent e) async {
    _db.execute(
      'INSERT OR IGNORE INTO outbox (device_event_id, type, payload, base_revision, captured_at_ms, integrity, state) '
      'VALUES (?, ?, ?, ?, ?, ?, ?)',
      <Object?>[
        e.deviceEventId,
        e.type,
        canonicalJson(e.payload),
        e.baseRevision,
        e.capturedAtUtc.millisecondsSinceEpoch,
        e.integrity,
        EventState.pending.name,
      ],
    );
    return _db.updatedRows == 1;
  }

  @override
  Future<List<QueuedEvent>> claimBatch({
    required String owner,
    required DateTime now,
    required Duration lease,
    required int limit,
  }) async {
    final int nowMs = now.millisecondsSinceEpoch;
    _db.execute('BEGIN IMMEDIATE;'); // Schreibsperre: gilt auch gegenueber einem zweiten Prozess
    try {
      final ResultSet rows = _db.select(
        'SELECT seq FROM outbox WHERE state = ? OR (state = ? AND lease_until_ms <= ?) ORDER BY seq LIMIT ?',
        <Object?>[EventState.pending.name, EventState.inFlight.name, nowMs, limit],
      );
      final List<int> seqs = rows.map((Row r) => r['seq'] as int).toList();
      for (final int s in seqs) {
        _db.execute(
          'UPDATE outbox SET state = ?, attempts = attempts + 1, claim_owner = ?, lease_until_ms = ? WHERE seq = ?',
          <Object?>[EventState.inFlight.name, owner, nowMs + lease.inMilliseconds, s],
        );
      }
      final List<QueuedEvent> out = <QueuedEvent>[];
      for (final int s in seqs) {
        out.add(_map(_db.select('SELECT * FROM outbox WHERE seq = ?', <Object?>[s]).single));
      }
      _db.execute('COMMIT;');
      return out;
    } on Object {
      _db.execute('ROLLBACK;');
      rethrow;
    }
  }

  @override
  Future<void> release(String deviceEventId, String owner) async {
    _db.execute(
      'UPDATE outbox SET state = ?, claim_owner = NULL, lease_until_ms = NULL '
      'WHERE device_event_id = ? AND state = ? AND claim_owner = ?',
      <Object?>[EventState.pending.name, deviceEventId, EventState.inFlight.name, owner],
    );
  }

  @override
  Future<bool> complete(
    String deviceEventId,
    String owner, {
    required EventState state,
    ServerOutcome? outcome,
    DateTime? serverReceivedAtUtc,
    int? serverRevision,
    ReviewReason? reviewReason,
  }) async {
    _db.execute(
      'UPDATE outbox SET state = ?, outcome = ?, server_received_at_ms = ?, server_revision = ?, '
      'review_reason = ?, claim_owner = NULL, lease_until_ms = NULL '
      'WHERE device_event_id = ? AND state = ? AND claim_owner = ?',
      <Object?>[
        state.name,
        outcome?.name,
        serverReceivedAtUtc?.millisecondsSinceEpoch,
        serverRevision,
        reviewReason?.name,
        deviceEventId,
        EventState.inFlight.name,
        owner,
      ],
    );
    return _db.updatedRows == 1;
  }

  @override
  Future<void> quarantine(String deviceEventId) async {
    _db.execute(
      'UPDATE outbox SET state = ?, claim_owner = NULL, lease_until_ms = NULL WHERE device_event_id = ?',
      <Object?>[EventState.quarantined.name, deviceEventId],
    );
  }

  @override
  Future<void> markConflictHint(String deviceEventId) async {
    _db.execute('UPDATE outbox SET conflict_hint = 1 WHERE device_event_id = ?', <Object?>[deviceEventId]);
  }

  @override
  Future<List<QueuedEvent>> all() async =>
      _db.select('SELECT * FROM outbox ORDER BY seq').map(_map).toList();

  @override
  Future<void> saveSnapshot(DutySnapshot s) async {
    _db.execute(
      'INSERT INTO snapshot (id, json) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET json = excluded.json',
      <Object?>[jsonEncode(s.toJson())],
    );
  }

  @override
  Future<DutySnapshot?> loadSnapshot() async {
    final ResultSet rs = _db.select('SELECT json FROM snapshot WHERE id = 1');
    if (rs.isEmpty) return null;
    final Object? decoded = jsonDecode(rs.single['json'] as String);
    return DutySnapshot.fromJson((decoded! as Map<Object?, Object?>).cast<String, Object?>());
  }

  static DateTime? _ms(Object? v) =>
      v == null ? null : DateTime.fromMillisecondsSinceEpoch(v as int, isUtc: true);

  static QueuedEvent _map(Row r) {
    final Object? payload = jsonDecode(r['payload'] as String);
    return QueuedEvent(
      seq: r['seq'] as int,
      deviceEventId: r['device_event_id'] as String,
      type: r['type'] as String,
      payload: (payload! as Map<Object?, Object?>).cast<String, Object?>(),
      baseRevision: r['base_revision'] as int,
      capturedAtUtc: _ms(r['captured_at_ms'])!,
      integrity: r['integrity'] as String,
      state: EventState.values.byName(r['state'] as String),
      attempts: r['attempts'] as int,
      claimOwner: r['claim_owner'] as String?,
      leaseUntilUtc: _ms(r['lease_until_ms']),
      serverReceivedAtUtc: _ms(r['server_received_at_ms']),
      serverRevision: r['server_revision'] as int?,
      outcome: r['outcome'] == null ? null : ServerOutcome.values.byName(r['outcome'] as String),
      reviewReason: r['review_reason'] == null ? null : ReviewReason.values.byName(r['review_reason'] as String),
      conflictHint: (r['conflict_hint'] as int) == 1,
    );
  }
}
