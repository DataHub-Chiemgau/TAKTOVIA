import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/auth/session_gate.dart';
import 'package:taktovia_driver/net/driver_http.dart';
import 'package:taktovia_driver/offline/models.dart';

class RecordingTransport implements HttpTransport {
  RecordingTransport(this.reply);
  HttpReply reply;
  final List<Map<String, Object?>> bodies = <Map<String, Object?>>[];
  final List<Map<String, String>> sentHeaders = <Map<String, String>>[];
  @override
  Future<HttpReply> post(Uri url, {required Map<String, String> headers, required String body}) async {
    bodies.add((jsonDecode(body) as Map<Object?, Object?>).cast<String, Object?>());
    sentHeaders.add(headers);
    return reply;
  }
}

class NoopWiper implements LocalDataWiper {
  @override
  Future<void> wipeAll() async {}
  @override
  Future<void> wipeOrderData() async {}
  @override
  Future<int> unsyncedCount() async => 0;
}

QueuedEvent event() => QueuedEvent(
      deviceEventId: '11111111-2222-4333-8444-555555555555',
      type: 'checkIn',
      payload: const <String, Object?>{'bookingId': 'b1', 'stopId': 's1'},
      baseRevision: 4,
      capturedAtUtc: DateTime.utc(2026, 11, 12, 8, 0),
      integrity: 'x',
    );

HttpDriverEventApi api(RecordingTransport t, {SessionGate? gate}) => HttpDriverEventApi(
      transport: t,
      baseUri: Uri.parse('https://api.example'),
      accessToken: () async => 'tok',
      deviceId: 'dev-1',
      shiftId: 'shift-1',
      vehicleId: 'veh-1',
      gate: gate,
    );

void main() {
  test('Idempotency-Key und event_id sind die device_event_id; Geraetezeit geht als device_recorded_at hinaus', () async {
    final RecordingTransport t = RecordingTransport(const HttpReply(200, '{"event_id":"x","state":"APPLIED","received_at":"2026-11-12T08:01:00Z","current_plan_revision":4,"reason_code":null}'));
    final ApiResponse r = await api(t).recordDriverEvent(event());
    expect(t.sentHeaders.single['Idempotency-Key'], event().deviceEventId);
    expect(t.bodies.single['event_id'], event().deviceEventId);
    expect(t.bodies.single['device_recorded_at'], '2026-11-12T08:00:00.000Z');
    expect(t.bodies.single.containsKey('received_at'), isFalse);
    expect(r.outcome, ServerOutcome.applied);
    expect(r.serverReceivedAtUtc, DateTime.utc(2026, 11, 12, 8, 1));
  });

  test('DUPLICATE einer REVIEW_REQUIRED-Verarbeitung bleibt erkennbar', () async {
    final RecordingTransport t = RecordingTransport(const HttpReply(200, '{"event_id":"x","state":"DUPLICATE","original_state":"REVIEW_REQUIRED","received_at":"2026-11-12T08:01:00Z","current_plan_revision":5,"reason_code":"STALE_PLAN"}'));
    final ApiResponse r = await api(t).recordDriverEvent(event());
    expect(r.outcome, ServerOutcome.duplicate);
    expect(r.originalOutcome, ServerOutcome.reviewRequired);
    expect(r.reviewReason, ReviewReason.stalePlanRevision);
  });

  test('401 mit Sperrhinweis setzt die Pforte auf revoked und die Zeile bleibt in der Outbox (AuthExpired)', () async {
    final RecordingTransport t = RecordingTransport(const HttpReply(401, '{}', headers: <String, String>{'www-authenticate': 'Bearer error="invalid_token", error_description="session revoked"'}));
    final SessionGate g = SessionGate(wiper: NoopWiper())..signedIn();
    await expectLater(api(t, gate: g).recordDriverEvent(event()), throwsA(isA<AuthExpiredException>()));
    expect(g.state, SessionState.revoked);
  });

  test('503 und 429 bleiben wiederholbar, 409 wird Klaerfall', () async {
    await expectLater(api(RecordingTransport(const HttpReply(503, '{}'))).recordDriverEvent(event()), throwsA(isA<NetworkUnavailableException>()));
    await expectLater(api(RecordingTransport(const HttpReply(429, '{}'))).recordDriverEvent(event()), throwsA(isA<NetworkUnavailableException>()));
    final ApiResponse r = await api(RecordingTransport(const HttpReply(409, '{}'))).recordDriverEvent(event());
    expect(r.outcome, ServerOutcome.reviewRequired);
  });
}
