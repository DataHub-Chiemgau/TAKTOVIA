import 'dart:convert';

/// Notwendige Unterstuetzung an einem Stopp (WP-M3-32, DRV-008): nur Rollstuhlbedarf und Ein-/Ausstiegshilfe, keine Diagnosen. Die Werte stehen nie in `toString`
/// (Logs, Crashreports) und werden nur angezeigt, wenn der Server sie in der Antwort mitliefert; die App leitet sie nie selbst ab.
class StopAssistance {
  const StopAssistance({required this.wheelchairRequired, required this.boardingAssist, required this.alightingAssist});
  final bool wheelchairRequired;
  final bool boardingAssist;
  final bool alightingAssist;

  factory StopAssistance.fromJson(Map<String, Object?> j) => StopAssistance(
        wheelchairRequired: j['wheelchair_required']! as bool,
        boardingAssist: j['boarding_assist']! as bool,
        alightingAssist: j['alighting_assist']! as bool,
      );

  @override
  String toString() => 'StopAssistance(redacted)';
}

/// Ein Stopp der Fahrerprojektion (`GET /v1/driver/shifts/{id}/run`, Schema `driver-run-projection.v1`). [forecastAt] == null heisst „keine Prognose“:
/// die App erfindet nie eine Zeit. [sequence] ist die Position in der Stoppfolge der Planrevision (der Server sortiert, die App sortiert nie um).
class RunStop {
  const RunStop({
    required this.routeStopId,
    required this.sequence,
    required this.kind,
    required this.plannedAt,
    required this.forecastAt,
    required this.stopName,
    required this.latitude,
    required this.longitude,
    required this.passengerCount,
    required this.stopRevisionId,
    required this.assistance,
    this.change,
    this.previousPlannedAt,
  });

  final String routeStopId;
  final int sequence;
  final String kind;
  final DateTime plannedAt;
  final DateTime? forecastAt;
  final String stopName;
  final double latitude;
  final double longitude;
  final int passengerCount;
  final String? stopRevisionId;
  final StopAssistance? assistance;

  /// Kennzeichnung gegenueber der Vergleichsbasis (WP-M3-27): `NEW` oder `MOVED` (mit [previousPlannedAt]); `null` = unveraendert. Entfallene Stopps stehen in [DriverRun.removedStops].
  final String? change;
  final DateTime? previousPlannedAt;

  factory RunStop.fromJson(Map<String, Object?> j) {
    final Map<String, Object?> pos = (j['position']! as Map<Object?, Object?>).cast<String, Object?>();
    final Object? a = j['assistance'];
    return RunStop(
      routeStopId: j['route_stop_id']! as String,
      sequence: j['sequence']! as int,
      kind: j['kind']! as String,
      plannedAt: DateTime.parse(j['planned_at']! as String).toUtc(),
      forecastAt: j['forecast_at'] == null ? null : DateTime.parse(j['forecast_at']! as String).toUtc(),
      stopName: j['stop_name']! as String,
      latitude: (pos['latitude']! as num).toDouble(),
      longitude: (pos['longitude']! as num).toDouble(),
      passengerCount: j['passenger_count']! as int,
      stopRevisionId: j['stop_revision_id'] as String?,
      assistance: a == null ? null : StopAssistance.fromJson((a as Map<Object?, Object?>).cast<String, Object?>()),
      change: j['change'] as String?,
      previousPlannedAt: j['previous_planned_at'] == null ? null : DateTime.parse(j['previous_planned_at']! as String).toUtc(),
    );
  }
}

/// Ein gegenueber der Vergleichsbasis entfallener Stopp (WP-M3-27); nur Haltepunktname, Art, fruehere Planzeit und Buchungsreferenzen, keine Personendaten.
class RemovedStop {
  const RemovedStop({required this.stopName, required this.kind, required this.plannedAt});
  final String stopName;
  final String kind;
  final DateTime plannedAt;

  factory RemovedStop.fromJson(Map<String, Object?> j) => RemovedStop(stopName: j['stop_name']! as String, kind: j['kind']! as String, plannedAt: DateTime.parse(j['planned_at']! as String).toUtc());
}

/// Die Fahrerprojektion des Umlaufs. [planRevision] ist die Revision, auf die sich Stoppliste und Navigationsziel beziehen (ETag).
class DriverRun {
  const DriverRun({
    required this.shiftId,
    required this.planRevision,
    required this.planAsOf,
    required this.shiftValid,
    required this.releaseState,
    required this.stops,
    this.revisionState = 'CURRENT',
    this.acknowledgementRequired = false,
    this.acknowledgedRevision,
    this.baseRevision,
    this.removedStops = const <RemovedStop>[],
  });
  final String shiftId;
  final int? planRevision;
  final DateTime planAsOf;
  final bool shiftValid;
  final String releaseState;
  final List<RunStop> stops;

  /// Revisionsstatus der Fahrersicht (WP-M3-27): `CURRENT`, `NEW_UNCONFIRMED`, `AUTO_ADOPTED`, `CONFIRMED`; den Zustand „vergangen“ bildet nur die App (siehe `plan_change.dart`).
  final String revisionState;
  final bool acknowledgementRequired;
  final int? acknowledgedRevision;
  final int? baseRevision;
  final List<RemovedStop> removedStops;

  /// Gueltige Stoppliste: angetretene, freigegebene Schicht mit mindestens einem Stopp (Serverentscheidung, nicht die der App).
  bool get hasValidList => shiftValid && releaseState == 'RELEASED' && stops.isNotEmpty;

  factory DriverRun.fromJson(Map<String, Object?> j) => DriverRun(
        shiftId: j['shift_id']! as String,
        planRevision: j['plan_revision'] as int?,
        planAsOf: DateTime.parse(j['plan_as_of']! as String).toUtc(),
        shiftValid: j['shift_valid']! as bool,
        releaseState: j['release_state']! as String,
        stops: (j['stops']! as List<Object?>).map((Object? e) => RunStop.fromJson((e! as Map<Object?, Object?>).cast<String, Object?>())).toList(growable: false),
        revisionState: j['revision_state'] as String? ?? 'CURRENT',
        acknowledgementRequired: j['acknowledgement_required'] as bool? ?? false,
        acknowledgedRevision: j['acknowledged_revision'] as int?,
        baseRevision: j['base_revision'] as int?,
        removedStops: ((j['removed_stops'] as List<Object?>?) ?? const <Object?>[]).map((Object? e) => RemovedStop.fromJson((e! as Map<Object?, Object?>).cast<String, Object?>())).toList(growable: false),
      );
}

/// Antwort des bedingten Abrufs: 200 mit Projektion, 304 (nichts geaendert, [run] ist null), sonst Fehler (Status, Problemcode).
class RunReply {
  const RunReply(this.status, this.etag, this.run, {this.code});
  final int status;
  final String? etag;
  final DriverRun? run;
  final String? code;
}

abstract interface class DriverRunApi {
  /// Bedingter Abruf; [ifNoneMatch] ist der zuletzt bekannte ETag. Wirft [SocketException] ohne Verbindung.
  Future<RunReply> fetch(String shiftId, {String? ifNoneMatch});
}

/// Transport mit Zugriff auf `ETag`/`If-None-Match` (Port; die Implementierung haengt Token und `traceparent` an).
abstract interface class ConditionalTransport {
  Future<({int status, String body, String? etag})> get(String path, {String? ifNoneMatch});
}

class HttpDriverRunApi implements DriverRunApi {
  HttpDriverRunApi(this.transport);
  final ConditionalTransport transport;

  @override
  Future<RunReply> fetch(String shiftId, {String? ifNoneMatch}) async {
    final ({int status, String body, String? etag}) r = await transport.get('/v1/driver/shifts/$shiftId/run', ifNoneMatch: ifNoneMatch);
    if (r.status == 304) return RunReply(304, r.etag, null);
    final Map<String, Object?> j = (jsonDecode(r.body.isEmpty ? '{}' : r.body) as Map<Object?, Object?>).cast<String, Object?>();
    if (r.status != 200) return RunReply(r.status, null, null, code: j['code'] as String?);
    return RunReply(200, r.etag, DriverRun.fromJson(j));
  }
}
