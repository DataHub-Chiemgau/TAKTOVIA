// NICHT AUSGEFUEHRT: in der Entwicklungsumgebung ohne Flutter/Dart-SDK geschrieben (WP-M3-13). Der Server-Teil ist durch InspectionApiTest nachgewiesen.
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/pruefung/inspection_start.dart';
import 'package:taktovia_driver/shift/shift_start.dart';

class FakeTransport implements JsonTransport {
  FakeTransport(this.reply);
  ({int status, String body}) reply;
  final List<Map<String, Object?>> bodies = <Map<String, Object?>>[];
  final List<String> keys = <String>[];
  @override
  Future<({int status, String body})> get(String path) async => reply;
  @override
  Future<({int status, String body})> post(String path, Map<String, Object?> body, {required String idempotencyKey}) async {
    bodies.add(body);
    keys.add(idempotencyKey);
    return reply;
  }
}

Map<String, Object?> runJson() => <String, Object?>{
      'id': 'i-1',
      'template_revision_id': 'r-1',
      'status': 'IN_PROGRESS',
      'template': <String, Object?>{
        'steps': <Object?>[
          for (int i = 1; i <= 8; i++) <String, Object?>{'step_code': 'S$i', 'title': 'Punkt $i', 'mandatory': true, 'safety_critical': i == 1, 'answer_kind': 'OK_DEFECT_NA'},
        ],
      },
    };

void main() {
  HttpInspectionApi api(FakeTransport t) => HttpInspectionApi(t, newKey: () => 'key-1', now: () => DateTime.utc(2026, 12, 1, 6));

  test('Start sendet weder Vorlage noch Mandanten und zeigt Pruefung n / Gesamt der gewaehlten Revision', () async {
    final FakeTransport t = FakeTransport((status: 201, body: jsonEncode(runJson())));
    final InspectionRun run = await api(t).start(shiftId: 's-1', vehicleId: 'v-1');
    expect(t.bodies.single.keys.toSet(), <String>{'shift_id', 'vehicle_id', 'kind', 'client_started_at'});
    expect(t.keys.single, 'key-1');
    expect(run.progress(2), 'Pruefung 3 / 8');
    expect(run.templateRevisionId, 'r-1');
  });

  test('200 (offener Lauf besteht) liefert denselben Lauf', () async {
    final InspectionRun run = await api(FakeTransport((status: 200, body: jsonEncode(runJson())))).start(shiftId: 's-1', vehicleId: 'v-1');
    expect(run.id, 'i-1');
  });

  test('Fehler werden auf feste Ursachen abgebildet', () async {
    Future<InspectionStartFailure> fail(int status, Map<String, Object?> body) async {
      try {
        await api(FakeTransport((status: status, body: jsonEncode(body)))).start(shiftId: 's-1', vehicleId: 'v-1');
      } on InspectionStartException catch (e) {
        return e.failure;
      }
      throw StateError('kein Fehler');
    }

    expect(await fail(409, <String, Object?>{'code': 'INVALID_STATE_TRANSITION'}), InspectionStartFailure.notStarted);
    expect(await fail(409, <String, Object?>{'code': 'CONFLICT', 'detail': 'TEMPLATE_UNAVAILABLE: ...'}), InspectionStartFailure.noTemplate);
    expect(await fail(404, <String, Object?>{'code': 'NOT_FOUND'}), InspectionStartFailure.notFound);
    expect(await fail(500, <String, Object?>{'code': 'INTERNAL_ERROR'}), InspectionStartFailure.other);
  });
}
