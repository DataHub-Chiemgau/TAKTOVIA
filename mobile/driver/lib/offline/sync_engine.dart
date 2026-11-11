import 'models.dart';
import 'ports.dart';

enum SyncStop { none, network, authExpired }

class SyncResult {
  const SyncResult({
    required this.applied,
    required this.duplicate,
    required this.reviewRequired,
    required this.quarantined,
    required this.stoppedBy,
  });
  final int applied;
  final int duplicate;
  final int reviewRequired;
  final int quarantined;
  final SyncStop stoppedBy;
}

/// Uebertraegt Ereignisse der Outbox an den Server.
///
/// Genau einmal wirksam entsteht aus: At-least-once-Sendung (ein Ereignis bleibt in der
/// Queue, bis eine Serverantwort vorliegt) + Idempotenz ueber `deviceEventId` am Server
/// (zweite Sendung -> DUPLICATE). Der Client versucht nicht, Exactly-once-Transport zu bauen.
class SyncEngine {
  SyncEngine({
    required this.store,
    required this.api,
    required this.owner,
    required this.integrityKey,
    DateTime Function()? clock,
    this.lease = const Duration(seconds: 30),
    this.batchSize = 100,
  }) : _clock = clock ?? (() => DateTime.now().toUtc());

  final OutboxStore store;
  final DriverEventApi api;

  /// Eindeutige Kennung dieses Prozesses/Laufs (Lease-Besitzer).
  final String owner;
  final List<int> integrityKey;
  final Duration lease;
  final int batchSize;
  final DateTime Function() _clock;

  Future<SyncResult>? _running;

  /// Wiederholter Aufruf waehrend eines laufenden Syncs liefert denselben Lauf.
  Future<SyncResult> sync() => _running ??= _run().whenComplete(() => _running = null);

  Future<SyncResult> _run() async {
    int applied = 0, duplicate = 0, review = 0, quarantined = 0;
    SyncStop stop = SyncStop.none;

    while (stop == SyncStop.none) {
      final List<QueuedEvent> batch = await store.claimBatch(
        owner: owner,
        now: _clock(),
        lease: lease,
        limit: batchSize,
      );
      if (batch.isEmpty) break;

      for (int i = 0; i < batch.length && stop == SyncStop.none; i++) {
        final QueuedEvent e = batch[i];
        if (!e.verifyIntegrity(integrityKey)) {
          await store.quarantine(e.deviceEventId);
          quarantined++;
          continue;
        }
        try {
          final ApiResponse r = await api.recordDriverEvent(e);
          // Ein DUPLICATE einer frueher als REVIEW_REQUIRED bewerteten Verarbeitung bleibt Klaerfall.
          final ServerOutcome effective =
              r.outcome == ServerOutcome.duplicate && r.originalOutcome == ServerOutcome.reviewRequired
                  ? ServerOutcome.reviewRequired
                  : r.outcome;
          final bool isReview = effective == ServerOutcome.reviewRequired;
          await store.complete(
            e.deviceEventId,
            owner,
            state: isReview ? EventState.reviewRequired : EventState.synced,
            outcome: r.outcome,
            serverReceivedAtUtc: r.serverReceivedAtUtc,
            serverRevision: r.serverRevision,
            reviewReason: isReview ? (r.reviewReason ?? ReviewReason.other) : null,
          );
          switch (r.outcome) {
            case ServerOutcome.applied:
              applied++;
            case ServerOutcome.duplicate:
              duplicate++;
            case ServerOutcome.reviewRequired:
              review++;
          }
        } on NetworkUnavailableException {
          stop = SyncStop.network;
        } on AuthExpiredException {
          stop = SyncStop.authExpired;
        } on Object {
          // Unbekannter Fehler: Ereignis bleibt in der Queue, Lauf endet.
          await _releaseFrom(batch, i);
          rethrow;
        }
        if (stop != SyncStop.none) {
          await _releaseFrom(batch, i);
        }
      }
    }
    return SyncResult(
      applied: applied,
      duplicate: duplicate,
      reviewRequired: review,
      quarantined: quarantined,
      stoppedBy: stop,
    );
  }

  Future<void> _releaseFrom(List<QueuedEvent> batch, int from) async {
    for (int j = from; j < batch.length; j++) {
      await store.release(batch[j].deviceEventId, owner);
    }
  }
}
