import 'dart:io';

import 'package:taktovia_driver/run/driver_run.dart';

/// Testhilfen fuer Stoppliste, Navigation und Unterstuetzungshinweise (WP-M3-26/-28/-32): JSON-Projektion des Servers nach `driver-run-projection.v1`.
Map<String, Object?> stopJson(
  String id, {
  int sequence = 1,
  String kind = 'PICKUP',
  String name = 'Marktplatz',
  double lat = 51.05,
  double lon = 13.74,
  String? forecast = '2026-12-01T07:00:00Z',
  String? stopRevision,
  Map<String, Object?>? assistance,
}) =>
    <String, Object?>{
      'route_stop_id': id,
      'sequence': sequence,
      'kind': kind,
      'planned_at': '2026-12-01T07:00:00Z',
      'forecast_at': forecast,
      'stop_name': name,
      'position': <String, Object?>{'latitude': lat, 'longitude': lon},
      'passenger_count': 2,
      'booking_ids': <Object?>['b-$id'],
      'change': null,
      'support_hints': <Object?>[],
      'stop_revision_id': stopRevision ?? 'sr-$id',
      'boarding_count': 2,
      'alighting_count': 0,
      'resource_need': null,
      if (assistance != null) 'assistance': assistance,
    };

Map<String, Object?> runJson({int? revision = 3, bool valid = true, String release = 'RELEASED', List<Map<String, Object?>>? stops}) => <String, Object?>{
      'shift_id': 's-1',
      'vehicle_id': 'v-1',
      'shift_state': 'STARTED',
      'shift_valid': valid,
      'release_state': release,
      'missing_requirements': <Object?>[],
      'plan_revision': revision,
      'plan_as_of': '2026-12-01T07:00:00Z',
      'acknowledged_revision': null,
      'stops': stops ?? <Map<String, Object?>>[stopJson('a'), stopJson('b', sequence: 2, kind: 'DROPOFF', name: 'Bahnhof', lat: 51.04, lon: 13.73, forecast: null)],
    };

DriverRun runOf(Map<String, Object?> json) => DriverRun.fromJson(json);

/// Server-Testdouble: liefert je Aufruf die naechste Antwort; Netzfehler als [SocketException].
class ScriptedRunApi implements DriverRunApi {
  final List<Object> script = <Object>[];
  final List<String?> seenEtags = <String?>[];

  @override
  Future<RunReply> fetch(String shiftId, {String? ifNoneMatch}) async {
    seenEtags.add(ifNoneMatch);
    final Object next = script.removeAt(0);
    if (next is SocketException) throw next;
    return next as RunReply;
  }
}
