import 'models.dart';
import 'ports.dart';

/// Speicher im Arbeitsspeicher; Referenzverhalten fuer den Port, nur fuer Tests.
class InMemoryOutboxStore implements OutboxStore, SnapshotStore {
  final Map<String, QueuedEvent> _byId = <String, QueuedEvent>{};
  int _nextSeq = 1;
  DutySnapshot? _snapshot;

  @override
  Future<bool> insertIfAbsent(QueuedEvent event) async {
    if (_byId.containsKey(event.deviceEventId)) return false;
    _byId[event.deviceEventId] = event.copyWith(seq: _nextSeq++);
    return true;
  }

  @override
  Future<List<QueuedEvent>> claimBatch({
    required String owner,
    required DateTime now,
    required Duration lease,
    required int limit,
  }) async {
    final List<QueuedEvent> candidates = _byId.values.where((QueuedEvent e) {
      if (e.state == EventState.pending) return true;
      return e.state == EventState.inFlight && !e.leaseUntilUtc!.isAfter(now);
    }).toList()
      ..sort((QueuedEvent a, QueuedEvent b) => a.seq.compareTo(b.seq));
    final List<QueuedEvent> claimed = <QueuedEvent>[];
    for (final QueuedEvent e in candidates.take(limit)) {
      final QueuedEvent c = e.copyWith(
        state: EventState.inFlight,
        attempts: e.attempts + 1,
        claimOwner: () => owner,
        leaseUntilUtc: () => now.add(lease),
      );
      _byId[e.deviceEventId] = c;
      claimed.add(c);
    }
    return claimed;
  }

  @override
  Future<void> release(String deviceEventId, String owner) async {
    final QueuedEvent? e = _byId[deviceEventId];
    if (e == null || e.state != EventState.inFlight || e.claimOwner != owner) return;
    _byId[deviceEventId] = e.copyWith(
      state: EventState.pending,
      claimOwner: () => null,
      leaseUntilUtc: () => null,
    );
  }

  @override
  Future<bool> complete(
    String deviceEventId,
    String owner, {
    required EventState state,
    ServerOutcome? outcome,
    DateTime? serverReceivedAtUtc,
    int? serverRevision,
    ReviewReason? reviewReason,
  }) async {
    final QueuedEvent? e = _byId[deviceEventId];
    if (e == null || e.state != EventState.inFlight || e.claimOwner != owner) return false;
    _byId[deviceEventId] = e.copyWith(
      state: state,
      outcome: outcome,
      serverReceivedAtUtc: serverReceivedAtUtc,
      serverRevision: serverRevision,
      reviewReason: reviewReason,
      claimOwner: () => null,
      leaseUntilUtc: () => null,
    );
    return true;
  }

  @override
  Future<void> quarantine(String deviceEventId) async {
    final QueuedEvent? e = _byId[deviceEventId];
    if (e == null) return;
    _byId[deviceEventId] = e.copyWith(
      state: EventState.quarantined,
      claimOwner: () => null,
      leaseUntilUtc: () => null,
    );
  }

  @override
  Future<void> markConflictHint(String deviceEventId) async {
    final QueuedEvent? e = _byId[deviceEventId];
    if (e != null) _byId[deviceEventId] = e.copyWith(conflictHint: true);
  }

  @override
  Future<List<QueuedEvent>> all() async {
    final List<QueuedEvent> l = _byId.values.toList()
      ..sort((QueuedEvent a, QueuedEvent b) => a.seq.compareTo(b.seq));
    return l;
  }

  @override
  Future<void> saveSnapshot(DutySnapshot snapshot) async => _snapshot = snapshot;

  @override
  Future<DutySnapshot?> loadSnapshot() async => _snapshot;

  /// Nur Tests: veraendert die Nutzdaten einer Zeile ohne die Integritaetssumme zu aendern.
  void tamperPayloadForTest(String deviceEventId, Map<String, Object?> payload) {
    final QueuedEvent e = _byId[deviceEventId]!;
    _byId[deviceEventId] = e.copyWith(payload: payload);
  }
}
