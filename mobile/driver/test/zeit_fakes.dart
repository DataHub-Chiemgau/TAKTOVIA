// Attrappen fuer die Stempel-Tests (WP-M4-11/-12). NICHT AUSGEFUEHRT: Flutter/Dart ist in dieser Umgebung nicht lauffaehig.
import 'dart:convert';

import 'package:taktovia_driver/offline/memory_store.dart';
import 'package:taktovia_driver/offline/models.dart';
import 'package:taktovia_driver/offline/offline_queue.dart';
import 'package:taktovia_driver/offline/ports.dart';
import 'package:taktovia_driver/offline/sync_engine.dart';
import 'package:taktovia_driver/shift/shift_start.dart' show JsonTransport;
import 'package:taktovia_driver/zeit/work_time.dart';

/// Verhalten des Servers `POST /v1/time-events` wie das Backend (TimeEventStempelnApiTest): dauerhafte Deduplikation ueber `event_id` (200 `DUPLICATE`), Erstwirkung 201 `APPLIED`,
/// doppeltes Einstempeln online 409, Gerätezeit in der Zukunft `REVIEW_REQUIRED` mit `conflict_flag`.
class FakeTimeServer implements JsonTransport {
  FakeTimeServer({required this.serverNow});
  DateTime Function() serverNow;
  final Map<String, Map<String, Object?>> rows = <String, Map<String, Object?>>{};
  final List<String> paths = <String>[];
  final List<Map<String, Object?>> bodies = <Map<String, Object?>>[];
  bool offline = false;
  bool loseNextResponse = false;
  int statusOverride = 0;

  @override
  Future<({int status, String body})> get(String path) async => throw UnimplementedError();

  @override
  Future<({int status, String body})> post(String path, Map<String, Object?> body, {required String idempotencyKey}) async {
    if (offline) throw StateError('kein Netz');
    paths.add(path);
    bodies.add(body);
    if (statusOverride != 0) return (status: statusOverride, body: '{}');
    final String id = body['event_id']! as String;
    final Map<String, Object?>? known = rows[id];
    Map<String, Object?> row;
    int status;
    String result;
    if (known != null) {
      row = known;
      status = 200;
      result = 'DUPLICATE';
    } else {
      final DateTime occurred = DateTime.parse(body['occurred_at']! as String);
      final bool future = occurred.isAfter(serverNow().add(const Duration(minutes: 5)));
      final bool running = rows.values.isNotEmpty && _lastKind() != 'WORK_END';
      if (body['origin'] == 'ONLINE' && body['kind'] == 'WORK_START' && running) {
        return (status: 409, body: jsonEncode(<String, Object?>{'code': 'INVALID_STATE_TRANSITION'}));
      }
      row = <String, Object?>{
        'event_id': id,
        'kind': body['kind'],
        'occurred_at': body['occurred_at'],
        'device_recorded_at': body['device_recorded_at'],
        'received_at': serverNow().toUtc().toIso8601String(),
        'origin': body['origin'],
        'conflict_flag': future ? 'CLOCK_SKEW' : null,
      };
      rows[id] = row;
      status = 201;
      result = future ? 'REVIEW_REQUIRED' : 'APPLIED';
    }
    if (loseNextResponse) {
      loseNextResponse = false;
      throw StateError('Antwort verloren');
    }
    return (status: status, body: jsonEncode(<String, Object?>{...row, 'result': result}));
  }

  String? _lastKind() => rows.values.last['kind'] as String?;
}

class Rig {
  Rig({DateTime? start})
      : now = start ?? DateTime.utc(2026, 11, 12, 6),
        store = InMemoryOutboxStore() {
    server = FakeTimeServer(serverNow: () => now);
    queue = OfflineQueue(store: store, snapshots: store, integrityKey: key, clock: () => now);
    api = TimeEventRoutingApi(transport: server, other: _NoOtherApi(), clock: () => now);
    engine = SyncEngine(store: store, api: api, owner: 'test', integrityKey: key, clock: () => now);
    controller = WorkTimeController(queue: queue, store: store, sync: engine.sync, timezone: 'Europe/Berlin', clock: () => now);
  }

  static final List<int> key = List<int>.generate(32, (int i) => i);
  DateTime now;
  final InMemoryOutboxStore store;
  late final FakeTimeServer server;
  late final OfflineQueue queue;
  late final TimeEventRoutingApi api;
  late final SyncEngine engine;
  late final WorkTimeController controller;

  void advance(Duration d) => now = now.add(d);
}

class _NoOtherApi implements DriverEventApi {
  @override
  Future<ApiResponse> recordDriverEvent(QueuedEvent event) async => throw StateError('Stempel laufen nicht ueber /v1/driver/events');
}
