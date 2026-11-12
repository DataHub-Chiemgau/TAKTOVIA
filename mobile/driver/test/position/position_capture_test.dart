import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/position/position_capture.dart';
import 'package:taktovia_driver/shift/shift_start.dart';

class FakePositionApi implements PositionApi {
  FakePositionApi(this.behaviour);
  Object? Function(List<PositionSample>) behaviour;
  final List<List<PositionSample>> sent = <List<PositionSample>>[];
  @override
  Future<PositionUploadResult> send({required String shiftId, required String vehicleId, required String deviceId, required List<PositionSample> samples}) async {
    sent.add(samples);
    final Object? r = behaviour(samples);
    if (r is Exception) throw r;
    return const PositionUploadResult(accepted: 1, rejected: 0);
  }
}

PositionSample sample(DateTime t) => PositionSample(latitude: 51.05, longitude: 13.74, accuracyMeters: 4.5, measuredAtUtc: t);

DriverShift shift(String state, bool valid) => DriverShift(
      shiftId: 's',
      vehicleId: 'v',
      vehicleLabel: 'A-12',
      startsAt: DateTime.utc(2026, 12, 1, 6),
      endsAt: DateTime.utc(2026, 12, 1, 14),
      state: state,
      startableFrom: DateTime.utc(2026, 12, 1, 5, 30),
      valid: valid,
    );

void main() {
  const PositionCapturePolicy policy = PositionCapturePolicy();

  test('erfasst wird nur im angetretenen, gueltigen Dienst', () {
    expect(policy.mayCapture(ShiftScreenState.from(<DriverShift>[], DateTime.utc(2026, 12, 1, 7))), isFalse);
    expect(policy.mayCapture(ShiftScreenState.from(<DriverShift>[shift('PLANNED', false)], DateTime.utc(2026, 12, 1, 7))), isFalse);
    expect(policy.mayCapture(ShiftScreenState.from(<DriverShift>[shift('STARTED', true)], DateTime.utc(2026, 12, 1, 7))), isTrue);
    expect(policy.mayCapture(ShiftScreenState.from(<DriverShift>[shift('CANCELLED', false)], DateTime.utc(2026, 12, 1, 7))), isFalse);
  });

  test('Nachlieferung: Pakete zu hoechstens 100 Punkten mit urspruenglicher Messzeit', () async {
    final PositionBuffer buffer = PositionBuffer(policy);
    final DateTime now = DateTime.utc(2026, 12, 1, 8);
    for (int i = 0; i < 250; i++) {
      buffer.add(sample(now.subtract(Duration(seconds: 250 - i))), nowUtc: now);
    }
    final FakePositionApi api = FakePositionApi((_) => null);
    expect(await PositionUploader(api: api, buffer: buffer).flush(shiftId: 's', vehicleId: 'v', deviceId: 'd'), isTrue);
    expect(api.sent.map((List<PositionSample> b) => b.length), <int>[100, 100, 50]);
    expect(api.sent.first.first.measuredAtUtc, now.subtract(const Duration(seconds: 250)));
    expect(buffer.length, 0);
  });

  test('Netzfehler behaelt den Puffer, 409 oder 404 leert ihn und beendet die Erfassung', () async {
    final DateTime now = DateTime.utc(2026, 12, 1, 8);
    final PositionBuffer buffer = PositionBuffer(policy)..add(sample(now), nowUtc: now);
    expect(await PositionUploader(api: FakePositionApi((_) => const ShiftApiException(503, null)), buffer: buffer).flush(shiftId: 's', vehicleId: 'v', deviceId: 'd'), isTrue);
    expect(buffer.length, 1);
    expect(await PositionUploader(api: FakePositionApi((_) => const ShiftApiException(409, 'INVALID_STATE_TRANSITION')), buffer: buffer).flush(shiftId: 's', vehicleId: 'v', deviceId: 'd'), isFalse);
    expect(buffer.length, 0);
  });

  test('zu alte ungesendete Punkte werden verworfen', () {
    final DateTime now = DateTime.utc(2026, 12, 2, 12);
    final PositionBuffer buffer = PositionBuffer(policy)
      ..add(sample(DateTime.utc(2026, 12, 1, 6)), nowUtc: now)
      ..add(sample(now), nowUtc: now);
    expect(buffer.length, 1);
  });
}
