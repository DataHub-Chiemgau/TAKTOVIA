import 'dart:convert';
import 'dart:io';

import 'driver_run.dart';

/// Anzeigezustand der Stoppliste (UI-10, WP-M3-26). Die Zustaende sind unterscheidbar: Leer (kein Auftrag), Fehler (Abruf gescheitert, letzter Stand mit Zeit),
/// Offline (keine Verbindung, letzter Stand mit Zeit) und Liste. Der Plan-Stand (Revision und Zeit) ist in jedem Zustand mit gespeichertem Stand sichtbar.
enum RunListView { loading, list, empty, error, offline }

class RunListState {
  const RunListState({required this.view, this.run, this.planRevision, this.fetchedAt, this.errorCode});
  final RunListView view;
  final DriverRun? run;
  final int? planRevision;
  final DateTime? fetchedAt;
  final String? errorCode;

  /// Naechster Handlungsschritt: der erste Stopp der Stoppfolge (die Reihenfolge legt der Server fest).
  RunStop? get nextStop => (view == RunListView.list && run != null && run!.stops.isNotEmpty) ? run!.stops.first : null;

  /// Hat der Stopp keine Prognose, wird das ausgewiesen statt eine Zeit zu erfinden.
  static String forecastText(RunStop s, String noForecast, String Function(DateTime) format) => s.forecastAt == null ? noForecast : format(s.forecastAt!);
}

/// Lokaler Speicher des letzten Stands (Port; die Umsetzung liegt im verschluesselten lokalen Speicher). Schluessel sind Schicht-IDs.
abstract interface class RunSnapshotStore {
  Future<String?> read(String shiftId);
  Future<void> write(String shiftId, String json);
  Future<void> delete(String shiftId);
  Future<List<String>> keys();
}

class MemoryRunSnapshotStore implements RunSnapshotStore {
  final Map<String, String> data = <String, String>{};
  @override
  Future<String?> read(String shiftId) async => data[shiftId];
  @override
  Future<void> write(String shiftId, String json) async => data[shiftId] = json;
  @override
  Future<void> delete(String shiftId) async => data.remove(shiftId);
  @override
  Future<List<String>> keys() async => data.keys.toList();
}

/// Steuert Abruf und Anzeige der Stoppliste einer Schicht: bedingter Abruf mit dem ETag, Ablage des letzten Stands, Zustaende Leer, Fehler, Offline.
/// Hat der Server keine gueltige Schicht oder keine Aufträge, wird der lokale Stand geloescht (nie ein alter Plan, nie Unterstuetzungshinweise von gestern).
class RunListController {
  RunListController({required this.api, required this.store, required this.shiftId, required this.now});
  final DriverRunApi api;
  final RunSnapshotStore store;
  final String shiftId;
  final DateTime Function() now;

  RunListState state = const RunListState(view: RunListView.loading);
  String? _etag;
  DriverRun? _last;
  int? _lastRevision;
  DateTime? _lastAt;

  /// Letzter bekannter Stand aus dem Speicher (z. B. nach App-Neustart, offline). Nur gueltige Listen werden angezeigt.
  Future<void> restore() async {
    final String? json = await store.read(shiftId);
    if (json == null) return;
    final DriverRun run = DriverRun.fromJson((jsonDecode(json) as Map<Object?, Object?>).cast<String, Object?>());
    _last = run;
    _lastRevision = run.planRevision;
    _lastAt = run.planAsOf;
  }

  Future<RunListState> refresh() async {
    try {
      final RunReply r = await api.fetch(shiftId, ifNoneMatch: _etag);
      if (r.status == 304) {
        // unveraendert: der Plan-Stand bleibt, der Abrufzeitpunkt wird erneuert
        _lastAt = now();
        state = RunListState(view: RunListView.list, run: _last, planRevision: _lastRevision, fetchedAt: _lastAt);
        return state;
      }
      if (r.status != 200 || r.run == null) {
        state = RunListState(view: RunListView.error, run: _last, planRevision: _lastRevision, fetchedAt: _lastAt, errorCode: r.code ?? 'HTTP_${r.status}');
        return state;
      }
      final DriverRun run = r.run!;
      _etag = r.etag;
      _lastAt = now();
      if (!run.hasValidList) {
        // keine gueltige Schicht oder keine Auftraege: Leerzustand, lokaler Stand wird geloescht
        _last = null;
        _lastRevision = run.planRevision;
        _etag = null;
        await store.delete(shiftId);
        state = RunListState(view: RunListView.empty, run: run, planRevision: run.planRevision, fetchedAt: _lastAt);
        return state;
      }
      _last = run;
      _lastRevision = run.planRevision;
      await store.write(shiftId, jsonEncode(_toJson(run)));
      state = RunListState(view: RunListView.list, run: run, planRevision: run.planRevision, fetchedAt: _lastAt);
      return state;
    } on SocketException {
      state = RunListState(view: RunListView.offline, run: _last, planRevision: _lastRevision, fetchedAt: _lastAt);
      return state;
    }
  }

  /// Raeumt den lokalen Stand (Schichtende, Fahrtabschluss, Abmeldung): Stoppliste und Unterstuetzungshinweise sind danach weder im Speicher noch im lokalen Cache.
  Future<void> clear() async {
    _last = null;
    _etag = null;
    _lastRevision = null;
    await store.delete(shiftId);
    state = const RunListState(view: RunListView.empty);
  }

  Map<String, Object?> _toJson(DriverRun run) => <String, Object?>{
        'shift_id': run.shiftId,
        'plan_revision': run.planRevision,
        'plan_as_of': run.planAsOf.toIso8601String(),
        'shift_valid': run.shiftValid,
        'release_state': run.releaseState,
        'stops': run.stops
            .map((RunStop s) => <String, Object?>{
                  'route_stop_id': s.routeStopId,
                  'sequence': s.sequence,
                  'kind': s.kind,
                  'planned_at': s.plannedAt.toIso8601String(),
                  'forecast_at': s.forecastAt?.toIso8601String(),
                  'stop_name': s.stopName,
                  'position': <String, Object?>{'latitude': s.latitude, 'longitude': s.longitude},
                  'passenger_count': s.passengerCount,
                  'stop_revision_id': s.stopRevisionId,
                  if (s.assistance != null)
                    'assistance': <String, Object?>{
                      'wheelchair_required': s.assistance!.wheelchairRequired,
                      'boarding_assist': s.assistance!.boardingAssist,
                      'alighting_assist': s.assistance!.alightingAssist,
                    },
                })
            .toList(),
      };
}
