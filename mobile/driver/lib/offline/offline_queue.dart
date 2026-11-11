import 'dart:math';

import 'models.dart';
import 'ports.dart';

/// UUID v4 aus einem kryptografischen Zufallsgenerator.
String generateUuidV4([Random? random]) {
  final Random r = random ?? Random.secure();
  final List<int> b = List<int>.generate(16, (_) => r.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  String hex(int v) => v.toRadixString(16).padLeft(2, '0');
  final String h = b.map(hex).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
}

/// Erfassungsseite der Queue: Ereignis lokal sichern, bevor irgendein Netzaufruf erfolgt.
class OfflineQueue {
  OfflineQueue({
    required this.store,
    required this.snapshots,
    required this.integrityKey,
    DateTime Function()? clock,
    String Function()? idGenerator,
  })  : _clock = clock ?? (() => DateTime.now().toUtc()),
        _newId = idGenerator ?? generateUuidV4;

  final OutboxStore store;
  final SnapshotStore snapshots;
  final List<int> integrityKey;
  final DateTime Function() _clock;
  final String Function() _newId;

  /// Erfasst ein Ereignis. `baseRevision` ist die zuletzt bekannte Serverrevision
  /// (0, wenn noch kein Dienststand vorliegt). `capturedAtUtc` ist die Geraeteuhr.
  Future<QueuedEvent> record({required String type, required Map<String, Object?> payload}) async {
    final DutySnapshot? snap = await snapshots.loadSnapshot();
    final QueuedEvent draft = QueuedEvent(
      deviceEventId: _newId(),
      type: type,
      payload: Map<String, Object?>.unmodifiable(payload),
      baseRevision: snap?.planRevision ?? 0,
      capturedAtUtc: _clock().toUtc(),
      integrity: '',
    );
    final QueuedEvent withIntegrity = QueuedEvent(
      deviceEventId: draft.deviceEventId,
      type: draft.type,
      payload: draft.payload,
      baseRevision: draft.baseRevision,
      capturedAtUtc: draft.capturedAtUtc,
      integrity: QueuedEvent.computeIntegrity(integrityKey, draft),
    );
    await store.insertIfAbsent(withIntegrity);
    return withIntegrity;
  }

  /// Uebernimmt einen neuen Dienststand vom Server. Liegen noch nicht gesendete Check-ins
  /// fuer inzwischen stornierte Halte in der Queue, werden sie lokal als Konfliktverdacht
  /// markiert. Sie werden trotzdem gesendet: der Server entscheidet (REVIEW_REQUIRED).
  Future<void> applyServerSnapshot(DutySnapshot snapshot, {Set<String> cancelledStopIds = const <String>{}}) async {
    await snapshots.saveSnapshot(snapshot);
    if (cancelledStopIds.isEmpty) return;
    for (final QueuedEvent e in await store.all()) {
      final bool open = e.state == EventState.pending || e.state == EventState.inFlight;
      if (open && e.type == 'checkIn' && cancelledStopIds.contains(e.payload['stopId'])) {
        await store.markConflictHint(e.deviceEventId);
      }
    }
  }

  /// Anzahl noch nicht bestaetigter Ereignisse (pending, inFlight, reviewRequired, quarantined).
  Future<int> unsyncedCount() async =>
      (await store.all()).where((QueuedEvent e) => e.state != EventState.synced).length;

  Future<DutySnapshot?> readOffline() => snapshots.loadSnapshot();
}
