import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/shift/shift_start.dart';

class FakeTransport implements JsonTransport {
  FakeTransport(this.reply);
  ({int status, String body}) reply;
  final List<String> paths = <String>[];
  final List<Map<String, Object?>> bodies = <Map<String, Object?>>[];
  final List<String> keys = <String>[];
  @override
  Future<({int status, String body})> get(String path) async {
    paths.add(path);
    return reply;
  }

  @override
  Future<({int status, String body})> post(String path, Map<String, Object?> body, {required String idempotencyKey}) async {
    paths.add(path);
    bodies.add(body);
    keys.add(idempotencyKey);
    return reply;
  }
}

Map<String, Object?> shiftJson({String state = 'PLANNED', bool valid = false}) => <String, Object?>{
      'shift_id': 's-1',
      'vehicle_id': 'v-1',
      'vehicle_label': 'A-12',
      'starts_at': '2026-12-01T06:00:00Z',
      'ends_at': '2026-12-01T14:00:00Z',
      'timezone': 'Europe/Berlin',
      'state': state,
      'startable_from': '2026-12-01T05:30:00Z',
      'started_at': null,
      'ended_at': null,
      'valid': valid,
    };

void main() {
  test('ohne Schicht: Leerzustand, keine Auftraege', () {
    expect(ShiftScreenState.from(<DriverShift>[], DateTime.utc(2026, 12, 1, 6)).view, ShiftView.none);
  });

  test('Anzeigezustand folgt dem Server: nicht startbar, startbar, angetreten, entzogen', () async {
    final FakeTransport t = FakeTransport((status: 200, body: jsonEncode(<String, Object?>{'items': <Object?>[shiftJson()], 'as_of': '2026-12-01T05:00:00Z'})));
    final List<DriverShift> list = await HttpShiftApi(t, newKey: () => 'k').mine();
    expect(ShiftScreenState.from(list, DateTime.utc(2026, 12, 1, 5)).view, ShiftView.notYetStartable);
    expect(ShiftScreenState.from(list, DateTime.utc(2026, 12, 1, 5, 30)).view, ShiftView.startable);
    expect(ShiftScreenState.from(<DriverShift>[DriverShift.fromJson(shiftJson(state: 'STARTED', valid: true))], DateTime.utc(2026, 12, 1, 7)).view, ShiftView.started);
    expect(ShiftScreenState.from(<DriverShift>[DriverShift.fromJson(shiftJson(state: 'CANCELLED'))], DateTime.utc(2026, 12, 1, 7)).view, ShiftView.withdrawn);
  });

  test('Antritt sendet Schicht, Fahrzeug und Geraet mit Idempotency-Key, nie einen Mandanten', () async {
    final FakeTransport t = FakeTransport((status: 200, body: jsonEncode(<String, Object?>{'shift': shiftJson(state: 'STARTED', valid: true), 'already_started': true})));
    final ShiftStartOutcome r = await HttpShiftApi(t, newKey: () => 'key-1').start(shiftId: 's-1', vehicleId: 'v-1', deviceId: 'd-1');
    expect(r.alreadyStarted, isTrue);
    expect(t.bodies.single, <String, Object?>{'shift_id': 's-1', 'vehicle_id': 'v-1', 'device_id': 'd-1'});
    expect(t.keys.single, 'key-1');
  });

  test('Serverfehler werden als Status und Code gemeldet', () async {
    final FakeTransport t = FakeTransport((status: 404, body: jsonEncode(<String, Object?>{'code': 'NOT_FOUND'})));
    expect(() => HttpShiftApi(t, newKey: () => 'k').start(shiftId: 'x', vehicleId: 'v', deviceId: 'd'), throwsA(isA<ShiftApiException>().having((ShiftApiException e) => e.status, 'status', 404)));
  });
}
