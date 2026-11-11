import 'dart:convert';
import 'dart:io';

import '../auth/session_gate.dart';
import '../offline/models.dart';
import '../offline/ports.dart';

/// Antwort des HTTP-Transports (Port, damit der Adapter ohne Netz pruefbar ist).
class HttpReply {
  const HttpReply(this.status, this.body, {this.headers = const <String, String>{}});
  final int status;
  final String body;
  final Map<String, String> headers;
}

abstract interface class HttpTransport {
  /// Wirft [SocketException] oder [HttpException] bei Netzfehlern.
  Future<HttpReply> post(Uri url, {required Map<String, String> headers, required String body});
}

/// Transport mit `dart:io`.
class IoHttpTransport implements HttpTransport {
  IoHttpTransport({HttpClient? client}) : _client = client ?? HttpClient();
  final HttpClient _client;

  @override
  Future<HttpReply> post(Uri url, {required Map<String, String> headers, required String body}) async {
    final HttpClientRequest req = await _client.postUrl(url);
    headers.forEach(req.headers.set);
    req.add(utf8.encode(body));
    final HttpClientResponse res = await req.close();
    final String text = await res.transform(utf8.decoder).join();
    final Map<String, String> h = <String, String>{};
    res.headers.forEach((String k, List<String> v) => h[k.toLowerCase()] = v.join(','));
    return HttpReply(res.statusCode, text, headers: h);
  }
}

/// Zuordnung der internen Ereignistypen der Outbox zu den Typen von `recordDriverEvent`.
const Map<String, String> serverEventType = <String, String>{
  'arrived': 'ARRIVAL',
  'checkIn': 'CHECK_IN',
  'checkOut': 'CHECK_OUT',
  'noShow': 'NO_SHOW',
  'breakStart': 'BREAK_START',
  'breakEnd': 'BREAK_END',
  'disruption': 'DISRUPTION',
  'shiftEnd': 'SHIFT_END',
};

/// Adapter fuer `POST /v1/driver/events`. Die `device_event_id` ist zugleich `event_id` und Idempotency-Key: jede Wiederholung
/// derselben Outbox-Zeile ist fuer den Server dasselbe Kommando (genau einmal wirksam). Die Geraetezeit geht als
/// `device_recorded_at` hinaus und wird nie als Serverzeit gelesen; die Empfangszeit kommt ausschliesslich aus der Antwort.
class HttpDriverEventApi implements DriverEventApi {
  HttpDriverEventApi({
    required this.transport,
    required this.baseUri,
    required this.accessToken,
    required this.deviceId,
    required this.shiftId,
    required this.vehicleId,
    this.gate,
  });

  final HttpTransport transport;
  final Uri baseUri;
  final Future<String?> Function() accessToken;
  final String deviceId;
  final String shiftId;
  final String vehicleId;
  final SessionGate? gate;

  @override
  Future<ApiResponse> recordDriverEvent(QueuedEvent e) async {
    final String? token = await accessToken();
    if (token == null) throw const AuthExpiredException();
    final String? serverType = serverEventType[e.type];
    if (serverType == null) throw ArgumentError('Ereignistyp ${e.type} hat keine Entsprechung am Server');
    final Map<String, Object?> body = <String, Object?>{
      'event_id': e.deviceEventId,
      'device_id': deviceId,
      'shift_id': shiftId,
      'vehicle_id': vehicleId,
      'plan_revision': e.baseRevision < 1 ? 1 : e.baseRevision,
      'event_type': serverType,
      'occurred_at': e.capturedAtUtc.toUtc().toIso8601String(),
      'device_recorded_at': e.capturedAtUtc.toUtc().toIso8601String(),
      'offline_recorded': true,
      if (e.payload['bookingId'] != null) 'booking_id': e.payload['bookingId'],
      if (e.payload['stopId'] != null) 'route_stop_id': e.payload['stopId'],
    };
    final HttpReply r;
    try {
      r = await transport.post(
        baseUri.resolve('/v1/driver/events'),
        headers: <String, String>{
          'Authorization': 'Bearer $token',
          'Content-Type': 'application/json',
          'Idempotency-Key': e.deviceEventId,
        },
        body: jsonEncode(body),
      );
    } on SocketException {
      throw const NetworkUnavailableException();
    } on HttpException {
      throw const NetworkUnavailableException();
    }
    if (r.status == 401) {
      await gate?.onServerStatus(401, wwwAuthenticate: r.headers['www-authenticate']);
      throw const AuthExpiredException();
    }
    if (r.status == 200) return _parse(r.body);
    if (r.status == 429 || r.status >= 500) throw NetworkUnavailableException('HTTP ${r.status}');
    // 400/403/404/409/422: nicht wiederholbar mit demselben Inhalt; die Zeile bleibt als Klaerfall sichtbar.
    return ApiResponse(
      outcome: ServerOutcome.reviewRequired,
      serverReceivedAtUtc: DateTime.now().toUtc(),
      serverRevision: e.baseRevision,
      reviewReason: ReviewReason.other,
    );
  }

  ApiResponse _parse(String body) {
    final Map<String, Object?> j = (jsonDecode(body) as Map<Object?, Object?>).cast<String, Object?>();
    final ServerOutcome outcome = switch (j['state']) {
      'APPLIED' => ServerOutcome.applied,
      'DUPLICATE' => ServerOutcome.duplicate,
      _ => ServerOutcome.reviewRequired,
    };
    final Object? original = j['original_state'];
    return ApiResponse(
      outcome: outcome,
      serverReceivedAtUtc: DateTime.parse(j['received_at']! as String).toUtc(),
      serverRevision: j['current_plan_revision']! as int,
      reviewReason: j['reason_code'] == 'STALE_PLAN' ? ReviewReason.stalePlanRevision : (j['reason_code'] != null ? ReviewReason.other : null),
      originalOutcome: original == 'REVIEW_REQUIRED' ? ServerOutcome.reviewRequired : (original == 'APPLIED' ? ServerOutcome.applied : null),
    );
  }
}
