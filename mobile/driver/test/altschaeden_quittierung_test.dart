// NICHT AUSGEFUEHRT: in der Entwicklungsumgebung ohne Flutter/Dart-SDK geschrieben (WP-M3-21). Der Server-Teil ist durch KnownDamageAcknowledgementTest und KnownDefectApiTest nachgewiesen.
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/pruefung/known_defects.dart';
import 'package:taktovia_driver/shift/shift_start.dart';

class ScriptedTransport implements JsonTransport {
  ScriptedTransport(this.state);
  final Map<String, bool> state; // defect_id -> quittiert
  final List<Map<String, Object?>> postBodies = <Map<String, Object?>>[];

  Map<String, Object?> defect(String id, bool acked) => <String, Object?>{
        'defect_id': id, 'case_number': 'SCH-2026-00000$id', 'category_code': 'BODY', 'kind': 'SCRATCH', 'vehicle_area': 'LEFT_SIDE',
        'photos': <Object?>[], 'still_open': true, 'acknowledged': acked, 'acknowledged_at': null, 'acknowledged_defect_revision': acked ? 1 : null,
      };

  @override
  Future<({int status, String body})> get(String path) async {
    final int open = state.values.where((bool a) => !a).length;
    return (status: 200, body: jsonEncode(<String, Object?>{
      'inspection_id': 'i-1', 'vehicle_id': 'v-1', 'defects': <Object?>[for (final MapEntry<String, bool> e in state.entries) defect(e.key, e.value)],
      'acknowledgement_required': open > 0, 'unacknowledged_count': open,
    }));
  }

  @override
  Future<({int status, String body})> post(String path, Map<String, Object?> body, {required String idempotencyKey}) async {
    postBodies.add(body);
    final String id = path.split('/')[5];
    state[id] = true;
    return (status: 201, body: jsonEncode(defect(id, true)));
  }
}

void main() {
  test('vorhandene_schaeden_ansehen_vor_pflichtpruefung_start', () async {
    final ScriptedTransport t = ScriptedTransport(<String, bool>{'1': false, '2': false});
    final KnownDefectsGate gate = KnownDefectsGate(HttpKnownDefectApi(t, newKey: () => 'key-1'), 'i-1');
    final KnownDefectList list = await gate.load();
    expect(list.defects.length, 2);
    expect(list.isEmptyState, isFalse);
    expect(gate.mayStartMandatoryInspection, isFalse); // erst ansehen und quittieren
    await gate.acknowledge('1');
    expect(gate.mayStartMandatoryInspection, isFalse);
    await gate.acknowledge('2');
    expect(gate.mayStartMandatoryInspection, isTrue);
    // die Quittierung sendet keinerlei Felder (keine Verursacherangabe)
    expect(t.postBodies.every((Map<String, Object?> b) => b.isEmpty), isTrue);
  });

  test('leerzustand_ohne_quittierungspflicht', () async {
    final KnownDefectsGate gate = KnownDefectsGate(HttpKnownDefectApi(ScriptedTransport(<String, bool>{}), newKey: () => 'key-1'), 'i-1');
    final KnownDefectList list = await gate.load();
    expect(list.isEmptyState, isTrue);
    expect(list.acknowledgementRequired, isFalse);
    expect(gate.mayStartMandatoryInspection, isTrue);
  });
}
