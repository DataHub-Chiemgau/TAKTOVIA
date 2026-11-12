import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/shift/shift_start.dart';
import 'package:taktovia_driver/zeit/next_duty.dart';

// NICHT AUSGEFUEHRT: Flutter/Dart ist in dieser Umgebung nicht lauffaehig. Die Logik ist durch den Web-Test (EigeneZeit.test.tsx) und die
// Backend-Tests (RosterPublishTest: Eigensicht zeigt nur die veroeffentlichte Revision) fachlich abgesichert; dieser Test ist ungeprueft.
Map<String, Object?> shift(String id, String start, String end, {int revision = 2}) => <String, Object?>{
      'shift_id': id,
      'roster_id': 'r-1',
      'roster_revision': revision,
      'operating_day': start.substring(0, 10),
      'starts_at': start,
      'ends_at': end,
      'timezone': 'Europe/Berlin',
      'function': 'Fahrdienst',
      'state': 'PUBLISHED',
      'break_minutes': 30,
    };

class FakeTransport implements JsonTransport {
  FakeTransport(this.status, this.body);
  final int status;
  final String body;
  final List<String> paths = <String>[];
  @override
  Future<({int status, String body})> get(String path) async {
    paths.add(path);
    return (status: status, body: body);
  }

  @override
  Future<({int status, String body})> post(String path, Map<String, Object?> body, {required String idempotencyKey}) async => throw UnimplementedError();
}

void main() {
  test('naechster Dienst ist die erste noch nicht beendete veroeffentlichte Schicht', () {
    final List<OwnShift> shifts = <OwnShift>[
      OwnShift.fromJson(shift('a', '2026-12-01T05:00:00Z', '2026-12-01T13:00:00Z')),
      OwnShift.fromJson(shift('b', '2026-12-03T05:00:00Z', '2026-12-03T13:00:00Z')),
    ];
    expect(nextDuty(shifts, DateTime.utc(2026, 12, 1, 6))!.shiftId, 'a');
    expect(nextDuty(shifts, DateTime.utc(2026, 12, 2))!.shiftId, 'b');
    expect(nextDuty(shifts, DateTime.utc(2026, 12, 4)), isNull);
  });

  test('Eigensicht wird ueber den Vertragspfad gelesen; Fehlerstatus wirft', () async {
    final FakeTransport ok = FakeTransport(200, '{"items":[${_json(shift('a', '2026-12-01T05:00:00Z', '2026-12-01T13:00:00Z'))}]}');
    final List<OwnShift> got = await fetchOwnShifts(ok, from: '2026-11-30', to: '2026-12-06');
    expect(got.single.rosterRevision, 2);
    expect(ok.paths.single, '/v1/rosters/own-shifts?from=2026-11-30&to=2026-12-06');
    expect(() => fetchOwnShifts(FakeTransport(403, '{}'), from: 'x', to: 'y'), throwsStateError);
  });
}

String _json(Map<String, Object?> m) => m.entries.map((MapEntry<String, Object?> e) => '"${e.key}":${e.value is String ? '"${e.value}"' : e.value}').join(',').replaceAll(RegExp(r'^'), '{') + '}';
