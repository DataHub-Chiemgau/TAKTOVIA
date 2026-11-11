import 'models.dart';

/// Speicher-Port der Outbox. Austauschbar: Speicher im Arbeitsspeicher (Tests)
/// oder verschluesselte SQLite-Datei (`SqliteOutboxStore`).
///
/// Alle Methoden muessen einzeln atomar sein; `claimBatch` insbesondere auch
/// gegenueber einem zweiten App-Prozess auf derselben Datei.
abstract interface class OutboxStore {
  /// Fuegt ein Ereignis ein. Liefert false, wenn `deviceEventId` schon existiert (idempotent).
  Future<bool> insertIfAbsent(QueuedEvent event);

  /// Beansprucht bis zu [limit] Ereignisse in Reihenfolge `seq`: Zustand pending oder
  /// inFlight mit abgelaufener Lease. Setzt inFlight, Besitzer, Lease, erhoeht attempts.
  Future<List<QueuedEvent>> claimBatch({
    required String owner,
    required DateTime now,
    required Duration lease,
    required int limit,
  });

  /// Gibt ein beanspruchtes Ereignis unveraendert frei (zurueck auf pending).
  Future<void> release(String deviceEventId, String owner);

  /// Schliesst ein beanspruchtes Ereignis mit Serverergebnis ab. Liefert false,
  /// wenn [owner] das Ereignis nicht (mehr) haelt; dann bleibt der Eintrag unveraendert.
  Future<bool> complete(
    String deviceEventId,
    String owner, {
    required EventState state,
    ServerOutcome? outcome,
    DateTime? serverReceivedAtUtc,
    int? serverRevision,
    ReviewReason? reviewReason,
  });

  /// Setzt Zustand quarantined (Integritaetsfehler); unabhaengig vom Besitzer.
  Future<void> quarantine(String deviceEventId);

  Future<void> markConflictHint(String deviceEventId);

  Future<List<QueuedEvent>> all();
}

/// Port fuer den Offline-Lesestand (zuletzt bestaetigter Dienst und Stoppliste).
abstract interface class SnapshotStore {
  Future<void> saveSnapshot(DutySnapshot snapshot);
  Future<DutySnapshot?> loadSnapshot();
}

/// Port der Server-Gegenstelle (`POST /v1/driver/events`, `recordDriverEvent`).
abstract interface class DriverEventApi {
  /// Wirft [NetworkUnavailableException] oder [AuthExpiredException].
  Future<ApiResponse> recordDriverEvent(QueuedEvent event);
}
