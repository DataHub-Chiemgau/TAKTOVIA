import 'models.dart';
import 'ports.dart';

/// Test-Gegenstelle, die den Vertrag von `POST /v1/driver/events` (`recordDriverEvent`)
/// nachbildet: APPLIED, DUPLICATE oder REVIEW_REQUIRED. Kein echter Server, keine Netzwerkverbindung.
class FakeDriverEventServer implements DriverEventApi {
  FakeDriverEventServer({DateTime Function()? clock, this.planRevision = 1})
      : _clock = clock ?? (() => DateTime.now().toUtc());

  final DateTime Function() _clock;

  /// Aktuelle Planrevision des Servers.
  int planRevision;
  bool online = true;
  bool tokenValid = true;

  /// Simuliert eine verlorene Antwort: Ereignis wird angewendet, der Client sieht aber einen Netzfehler.
  bool dropNextResponse = false;
  final Set<String> cancelledStopIds = <String>{};

  /// Optionale Verzoegerung je Aufruf (Nebenlaeufigkeitstests).
  Future<void> Function()? beforeProcess;

  final Map<String, _Stored> _stored = <String, _Stored>{};
  final Map<String, int> _appliedCount = <String, int>{};
  int calls = 0;

  /// Anzahl der Anwendungen (APPLIED) je Ereignis; Zielwert 1.
  int appliedCount(String id) => _appliedCount[id] ?? 0;
  int get distinctEvents => _stored.length;
  Iterable<int> get allAppliedCounts => _appliedCount.values;

  /// Empfangszeit des Servers zu einem Ereignis.
  DateTime? receivedAt(String id) => _stored[id]?.receivedAt;

  @override
  Future<ApiResponse> recordDriverEvent(QueuedEvent event) async {
    calls++;
    if (beforeProcess != null) await beforeProcess!();
    if (!online) throw const NetworkUnavailableException();
    if (!tokenValid) throw const AuthExpiredException();

    final _Stored? prior = _stored[event.deviceEventId];
    final ApiResponse response;
    if (prior != null) {
      response = ApiResponse(
        outcome: ServerOutcome.duplicate,
        serverReceivedAtUtc: prior.receivedAt,
        serverRevision: planRevision,
        originalOutcome: prior.outcome,
        reviewReason: prior.reason,
      );
    } else {
      final DateTime now = _clock();
      ServerOutcome outcome = ServerOutcome.applied;
      ReviewReason? reason;
      if (event.baseRevision < planRevision) {
        outcome = ServerOutcome.reviewRequired;
        reason = ReviewReason.stalePlanRevision;
      } else if (event.type == 'checkIn' && cancelledStopIds.contains(event.payload['stopId'])) {
        outcome = ServerOutcome.reviewRequired;
        reason = ReviewReason.checkInVsCancellation;
      }
      _stored[event.deviceEventId] = _Stored(now, outcome, reason);
      if (outcome == ServerOutcome.applied) {
        _appliedCount[event.deviceEventId] = (_appliedCount[event.deviceEventId] ?? 0) + 1;
      }
      response = ApiResponse(
        outcome: outcome,
        serverReceivedAtUtc: now,
        serverRevision: planRevision,
        reviewReason: reason,
      );
    }
    if (dropNextResponse) {
      dropNextResponse = false;
      throw const NetworkUnavailableException('Antwort verloren');
    }
    return response;
  }
}

class _Stored {
  const _Stored(this.receivedAt, this.outcome, this.reason);
  final DateTime receivedAt;
  final ServerOutcome outcome;
  final ReviewReason? reason;
}
