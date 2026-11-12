import 'dart:convert';

import '../offline/models.dart';
import '../offline/offline_queue.dart';
import '../offline/ports.dart';
import '../offline/sync_engine.dart';
import '../shift/shift_start.dart' show JsonTransport;

// WP-M4-11 / WP-M4-12 (LH-6.24-TIM-002, -003): Stempeln in der Fahrer-App und Offline-Stempel.
// NICHT AUSGEFUEHRT: Flutter/Dart ist in dieser Umgebung nicht lauffaehig; weder `flutter analyze` noch `flutter test` lagen vor. Die Serverseite ist durch Backend-Tests mit echter
// Datenbank belegt (TimeEventStempelnTest, TimeEventOfflineSyncTest, TimeEventStempelnApiTest).

/// Ereignisart eines Stempels; [wire] ist der Wert von `kind` in `POST /v1/time-events`.
enum StampKind {
  workStart('WORK_START'),
  workEnd('WORK_END'),
  breakStart('BREAK_START'),
  breakEnd('BREAK_END');

  const StampKind(this.wire);
  final String wire;

  static StampKind fromWire(String w) => StampKind.values.firstWhere((StampKind k) => k.wire == w);
}

/// Arbeitszeitzustand: nicht gestartet/beendet, Arbeit laeuft, Pause (Zustandsautomat `vertraege/zustaende/time_event.md`).
enum WorkPhase { idle, working, onBreak }

/// Folgezustand oder `null`, wenn der Stempel im Zustand nicht vorgesehen ist (Doppel-Kommen, Pause ausserhalb der Arbeit, Gehen ohne Kommen).
WorkPhase? phaseAfter(WorkPhase phase, StampKind kind) {
  switch (phase) {
    case WorkPhase.idle:
      return kind == StampKind.workStart ? WorkPhase.working : null;
    case WorkPhase.working:
      if (kind == StampKind.breakStart) return WorkPhase.onBreak;
      if (kind == StampKind.workEnd) return WorkPhase.idle;
      return null;
    case WorkPhase.onBreak:
      if (kind == StampKind.breakEnd) return WorkPhase.working;
      if (kind == StampKind.workEnd) return WorkPhase.idle;
      return null;
  }
}

/// Der Stempel passt nicht zum Zustand (z. B. zweites Einstempeln); es wird nichts erfasst. Die Oberflaeche zeigt den Grund, nicht Freitext des Servers.
class StampNotAllowed implements Exception {
  const StampNotAllowed(this.kind, this.phase);
  final StampKind kind;
  final WorkPhase phase;
  @override
  String toString() => 'StampNotAllowed: $kind im Zustand $phase';
}

/// Outbox-Typ der Stempel (siehe `OfflinePolicy.outboxTypes`).
const String kTimeEventType = 'timeEvent';

/// Anzeigezustand der Arbeitszeit. `unsynced` = lokal erfasst, noch nicht vom Server bestaetigt; `needsReview` = vom Server als moeglicher Konflikt markiert oder abgewiesen („zu pruefen“).
class WorkTimeStatus {
  const WorkTimeStatus({required this.phase, required this.since, required this.unsynced, required this.needsReview});
  final WorkPhase phase;
  final DateTime? since;
  final int unsynced;
  final int needsReview;
}

/// Lokale Sicht: Zustand aus den Stempeln der Outbox (nach Erfassungsreihenfolge `seq`) auf der Basis [baseline] (Serverstand beim Laden).
WorkTimeStatus statusFrom(List<QueuedEvent> events, {WorkPhase baseline = WorkPhase.idle, DateTime? baselineSince}) {
  final List<QueuedEvent> stamps = events.where((QueuedEvent e) => e.type == kTimeEventType && e.state != EventState.quarantined).toList()
    ..sort((QueuedEvent a, QueuedEvent b) => a.seq.compareTo(b.seq));
  WorkPhase phase = baseline;
  DateTime? since = baselineSince;
  for (final QueuedEvent e in stamps) {
    final WorkPhase? next = phaseAfter(phase, StampKind.fromWire(e.payload['kind']! as String));
    if (next != null) {
      phase = next;
      since = e.capturedAtUtc;
    }
  }
  return WorkTimeStatus(
    phase: phase,
    since: since,
    unsynced: stamps.where((QueuedEvent e) => e.state != EventState.synced && e.state != EventState.reviewRequired).length,
    needsReview: stamps.where((QueuedEvent e) => e.state == EventState.reviewRequired || e.conflictHint).length,
  );
}

/// Zustand aus `GET /v1/time-events/intervals` (Serverstand): letztes Intervall ohne Ende = Arbeit laeuft, mit offener Pause = Pause.
({WorkPhase phase, DateTime? since}) phaseFromIntervals(String body) {
  final Map<String, Object?> j = (jsonDecode(body) as Map<Object?, Object?>).cast<String, Object?>();
  final List<Object?> items = j['intervals']! as List<Object?>;
  if (items.isEmpty) return (phase: WorkPhase.idle, since: null);
  final Map<String, Object?> last = (items.last! as Map<Object?, Object?>).cast<String, Object?>();
  if (last['end'] != null || last['start'] == null) return (phase: WorkPhase.idle, since: null);
  final List<Object?> breaks = last['breaks']! as List<Object?>;
  final bool onBreak = breaks.isNotEmpty && (breaks.last! as Map<Object?, Object?>)['end'] == null;
  return (phase: onBreak ? WorkPhase.onBreak : WorkPhase.working, since: DateTime.parse(last['start']! as String).toUtc());
}

/// Erfassung der Stempel: zuerst lokal und dauerhaft sichern (verschluesselte Outbox, stabile `deviceEventId`), danach, wenn eine Verbindung besteht, sofort uebertragen.
/// Das Stempeln haengt nie an Fahrzeugfreigabe, Pruefung oder Fahrtzustand (WP-M4-11 AK 3) und die App vergibt nie die Person: der Server nimmt sie aus der Sitzung.
class WorkTimeController {
  WorkTimeController({required this.queue, required this.store, required this.sync, required this.timezone, DateTime Function()? clock})
      : _clock = clock ?? (() => DateTime.now().toUtc());

  final OfflineQueue queue;
  final OutboxStore store;

  /// Startet einen Sync-Lauf (wiederholter Aufruf waehrend eines Laufs liefert denselben Lauf).
  final Future<SyncResult> Function() sync;

  /// IANA-Zone des Dienstes (z. B. `Europe/Berlin`), wird als `time_zone` mitgesendet; die Zeitpunkte tragen immer den UTC-Offset.
  final String timezone;
  final DateTime Function() _clock;
  WorkPhase _baseline = WorkPhase.idle;
  DateTime? _baselineSince;

  /// Uebernimmt den Serverstand (aus [phaseFromIntervals]); Stempel in der lokalen Queue gelten darueber.
  void applyServerPhase(WorkPhase phase, DateTime? since) {
    _baseline = phase;
    _baselineSince = since;
  }

  Future<WorkTimeStatus> status() async => statusFrom(await store.all(), baseline: _baseline, baselineSince: _baselineSince);

  /// Erfasst einen Stempel. Wirft [StampNotAllowed] bei einem Stempel, der im Zustand nicht vorgesehen ist (nichts wird erfasst). Gibt das lokal gesicherte Ereignis zurueck;
  /// die Uebertragung kann offen bleiben (Status „nicht synchronisiert“), der Stempel geht nie verloren.
  Future<QueuedEvent> stamp(StampKind kind, {String? shiftId, bool tryUpload = true}) async {
    final WorkTimeStatus s = await status();
    if (phaseAfter(s.phase, kind) == null) throw StampNotAllowed(kind, s.phase);
    final DateTime now = _clock().toUtc();
    final QueuedEvent e = await queue.record(type: kTimeEventType, payload: <String, Object?>{
      'kind': kind.wire,
      'occurredAt': now.toIso8601String(),
      'deviceRecordedAt': now.toIso8601String(),
      'timezone': timezone,
      'shiftId': shiftId,
    });
    if (tryUpload) {
      try {
        await sync();
      } on Object {
        // bleibt in der Queue; der naechste Lauf uebertraegt ihn (gleiche deviceEventId, am Server dauerhaft dedupliziert)
      }
    }
    return e;
  }
}

/// Gegenstelle fuer Stempel (`POST /v1/time-events`); Stempel laufen nicht ueber `/v1/driver/events`. Andere Typen reicht [other] weiter, so teilen sich beide dieselbe Outbox und
/// denselben Sync-Lauf (`SyncEngine`). Die Person steht nie im Body; sie ist die angemeldete Person.
class TimeEventRoutingApi implements DriverEventApi {
  TimeEventRoutingApi({required this.transport, required this.other, DateTime Function()? clock, this.onlineWindow = const Duration(seconds: 30)})
      : _clock = clock ?? (() => DateTime.now().toUtc());

  final JsonTransport transport;
  final DriverEventApi other;
  final DateTime Function() _clock;

  /// Wurde der Stempel binnen dieser Zeit nach der Erfassung und im ersten Versuch uebertragen, gilt er als online erfasst, sonst als offline.
  final Duration onlineWindow;

  @override
  Future<ApiResponse> recordDriverEvent(QueuedEvent event) async {
    if (event.type != kTimeEventType) return other.recordDriverEvent(event);
    final bool fresh = event.attempts <= 1 && _clock().difference(event.capturedAtUtc) <= onlineWindow;
    final Map<String, Object?> body = <String, Object?>{
      'event_id': event.deviceEventId,
      'kind': event.payload['kind'],
      'occurred_at': event.payload['occurredAt'],
      'time_zone': event.payload['timezone'],
      'device_recorded_at': event.payload['deviceRecordedAt'],
      'channel': 'APP',
      'origin': fresh ? 'ONLINE' : 'OFFLINE',
      'shift_id': event.payload['shiftId'],
    };
    final ({int status, String body}) r;
    try {
      // Idempotency-Key = deviceEventId: eine Wiederholung ist fuer den Server derselbe Aufruf; die Ereignis-ID dedupliziert ausserdem dauerhaft.
      r = await transport.post('/v1/time-events', body, idempotencyKey: event.deviceEventId);
    } on Object {
      throw const NetworkUnavailableException();
    }
    if (r.status == 401) throw const AuthExpiredException();
    if (r.status == 429 || r.status >= 500) throw NetworkUnavailableException('HTTP ${r.status}');
    if (r.status == 200 || r.status == 201) {
      final Map<String, Object?> j = (jsonDecode(r.body) as Map<Object?, Object?>).cast<String, Object?>();
      final ServerOutcome outcome = switch (j['result']) {
        'DUPLICATE' => ServerOutcome.duplicate,
        'REVIEW_REQUIRED' => ServerOutcome.reviewRequired,
        _ => ServerOutcome.applied,
      };
      return ApiResponse(
        outcome: outcome,
        serverReceivedAtUtc: DateTime.parse(j['received_at']! as String).toUtc(),
        serverRevision: 0,
        reviewReason: outcome == ServerOutcome.reviewRequired ? ReviewReason.other : null,
        // Wiederholung eines markierten Stempels: bleibt Klaerfall
        originalOutcome: j['conflict_flag'] != null ? ServerOutcome.reviewRequired : null,
      );
    }
    // 400/403/404/409/422: vom Server abgewiesen. Der Stempel wird NICHT verworfen, sondern bleibt als „zu pruefen“ lokal sichtbar (Klaerung erst ueber den Korrekturantrag, M7).
    return ApiResponse(outcome: ServerOutcome.reviewRequired, serverReceivedAtUtc: _clock(), serverRevision: 0, reviewReason: ReviewReason.other);
  }
}
