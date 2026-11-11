import 'dart:convert';

import 'package:crypto/crypto.dart';

/// Zustand eines Ereignisses in der lokalen Outbox.
enum EventState {
  /// Erfasst, noch nicht uebertragen.
  pending,

  /// Von einem Sync-Lauf beansprucht (Lease), Antwort steht aus.
  inFlight,

  /// Server hat APPLIED oder DUPLICATE (eines angewendeten Ereignisses) bestaetigt.
  synced,

  /// Server hat REVIEW_REQUIRED geantwortet: NICHT synchronisiert, Klaerfall.
  reviewRequired,

  /// Lokale Integritaetspruefung fehlgeschlagen; wird nie gesendet.
  quarantined,
}

/// Antwort des Servers nach `recordDriverEvent` (Vertrag: APPLIED | DUPLICATE | REVIEW_REQUIRED).
enum ServerOutcome { applied, duplicate, reviewRequired }

/// Grund fuer REVIEW_REQUIRED (Klaerfall; der Workflow selbst ist M3).
enum ReviewReason { stalePlanRevision, checkInVsCancellation, other }

/// Kanonische JSON-Darstellung mit sortierten Schluesseln (fuer die Integritaetssumme).
String canonicalJson(Object? value) {
  if (value is Map) {
    final List<String> keys = value.keys.map((Object? k) => k.toString()).toList()..sort();
    final StringBuffer b = StringBuffer('{');
    for (int i = 0; i < keys.length; i++) {
      if (i > 0) b.write(',');
      b.write(jsonEncode(keys[i]));
      b.write(':');
      b.write(canonicalJson(value[keys[i]]));
    }
    b.write('}');
    return b.toString();
  }
  if (value is List) {
    return '[${value.map(canonicalJson).join(',')}]';
  }
  return jsonEncode(value);
}

/// Ein Ereignis der Fahrer-Outbox (Kap. 01 §1.5).
class QueuedEvent {
  const QueuedEvent({
    this.seq = 0,
    required this.deviceEventId,
    required this.type,
    required this.payload,
    required this.baseRevision,
    required this.capturedAtUtc,
    required this.integrity,
    this.state = EventState.pending,
    this.attempts = 0,
    this.claimOwner,
    this.leaseUntilUtc,
    this.serverReceivedAtUtc,
    this.serverRevision,
    this.outcome,
    this.reviewReason,
    this.conflictHint = false,
  });

  /// Lokale Reihenfolge (vom Speicher vergeben, aufsteigend). Nicht die Erfassungszeit.
  final int seq;

  /// Vom Geraet vergebene, global eindeutige Kennung; Idempotenzschluessel gegenueber dem Server.
  final String deviceEventId;
  final String type;
  final Map<String, Object?> payload;

  /// Letzte dem Geraet bekannte Serverrevision (Planrevision) zum Erfassungszeitpunkt.
  final int baseRevision;

  /// Erfassungszeit laut GERAETEUHR (UTC). Wird nie durch die Serverzeit ueberschrieben.
  final DateTime capturedAtUtc;

  /// HMAC ueber die unveraenderlichen Felder; erkennt Manipulation einzelner Zeilen.
  final String integrity;

  final EventState state;
  final int attempts;
  final String? claimOwner;
  final DateTime? leaseUntilUtc;

  /// Empfangszeit laut SERVERUHR (UTC), getrennt von `capturedAtUtc`.
  final DateTime? serverReceivedAtUtc;
  final int? serverRevision;
  final ServerOutcome? outcome;
  final ReviewReason? reviewReason;

  /// Lokaler Hinweis: Dienststand meldet Storno, waehrend ein Check-in in der Queue liegt.
  final bool conflictHint;

  /// Unveraenderlicher Teil, der in die Integritaetssumme eingeht.
  String get immutableCore =>
      '$deviceEventId|$type|$baseRevision|${capturedAtUtc.millisecondsSinceEpoch}|${canonicalJson(payload)}';

  static String computeIntegrity(List<int> key, QueuedEvent e) =>
      Hmac(sha256, key).convert(utf8.encode(e.immutableCore)).toString();

  bool verifyIntegrity(List<int> key) => computeIntegrity(key, this) == integrity;

  QueuedEvent copyWith({
    int? seq,
    Map<String, Object?>? payload,
    EventState? state,
    int? attempts,
    String? Function()? claimOwner,
    DateTime? Function()? leaseUntilUtc,
    DateTime? serverReceivedAtUtc,
    int? serverRevision,
    ServerOutcome? outcome,
    ReviewReason? reviewReason,
    bool? conflictHint,
  }) {
    return QueuedEvent(
      seq: seq ?? this.seq,
      deviceEventId: deviceEventId,
      type: type,
      payload: payload ?? this.payload,
      baseRevision: baseRevision,
      capturedAtUtc: capturedAtUtc,
      integrity: integrity,
      state: state ?? this.state,
      attempts: attempts ?? this.attempts,
      claimOwner: claimOwner != null ? claimOwner() : this.claimOwner,
      leaseUntilUtc: leaseUntilUtc != null ? leaseUntilUtc() : this.leaseUntilUtc,
      serverReceivedAtUtc: serverReceivedAtUtc ?? this.serverReceivedAtUtc,
      serverRevision: serverRevision ?? this.serverRevision,
      outcome: outcome ?? this.outcome,
      reviewReason: reviewReason ?? this.reviewReason,
      conflictHint: conflictHint ?? this.conflictHint,
    );
  }
}

/// Antwort der Gegenstelle.
class ApiResponse {
  const ApiResponse({
    required this.outcome,
    required this.serverReceivedAtUtc,
    required this.serverRevision,
    this.reviewReason,
    this.originalOutcome,
  });

  final ServerOutcome outcome;
  final DateTime serverReceivedAtUtc;
  final int serverRevision;
  final ReviewReason? reviewReason;

  /// Bei DUPLICATE: Ergebnis der ersten Verarbeitung (damit ein verlorenes
  /// REVIEW_REQUIRED nicht als "synchronisiert" erscheint).
  final ServerOutcome? originalOutcome;
}

/// Netz nicht verfuegbar oder Antwort verloren: Ereignis bleibt in der Queue.
class NetworkUnavailableException implements Exception {
  const NetworkUnavailableException([this.message = 'Netz nicht verfuegbar']);
  final String message;
  @override
  String toString() => 'NetworkUnavailableException: $message';
}

/// Token abgelaufen: Ereignisse bleiben in der Queue, kein Verlust.
class AuthExpiredException implements Exception {
  const AuthExpiredException();
  @override
  String toString() => 'AuthExpiredException';
}

/// Zuletzt bestaetigter Dienst mit Stoppliste (Offline-Lesestand, Kap. 01 §1.5).
class StopEntry {
  const StopEntry({required this.stopId, required this.sequence, required this.label, required this.status});
  final String stopId;
  final int sequence;
  final String label;
  final String status;

  Map<String, Object?> toJson() => <String, Object?>{
        'stopId': stopId,
        'sequence': sequence,
        'label': label,
        'status': status,
      };

  factory StopEntry.fromJson(Map<String, Object?> j) => StopEntry(
        stopId: j['stopId']! as String,
        sequence: j['sequence']! as int,
        label: j['label']! as String,
        status: j['status']! as String,
      );
}

class DutySnapshot {
  const DutySnapshot({
    required this.dutyId,
    required this.planRevision,
    required this.asOfUtc,
    required this.stops,
  });
  final String dutyId;
  final int planRevision;
  final DateTime asOfUtc;
  final List<StopEntry> stops;

  /// Kennzeichnung "Stand von ..." (UTC, ohne Sekunden).
  String get standLabel {
    String two(int v) => v.toString().padLeft(2, '0');
    final DateTime t = asOfUtc.toUtc();
    return 'Stand von ${t.year}-${two(t.month)}-${two(t.day)} ${two(t.hour)}:${two(t.minute)} UTC';
  }

  Map<String, Object?> toJson() => <String, Object?>{
        'dutyId': dutyId,
        'planRevision': planRevision,
        'asOfMs': asOfUtc.millisecondsSinceEpoch,
        'stops': stops.map((StopEntry s) => s.toJson()).toList(),
      };

  factory DutySnapshot.fromJson(Map<String, Object?> j) => DutySnapshot(
        dutyId: j['dutyId']! as String,
        planRevision: j['planRevision']! as int,
        asOfUtc: DateTime.fromMillisecondsSinceEpoch(j['asOfMs']! as int, isUtc: true),
        stops: (j['stops']! as List<Object?>)
            .map((Object? e) => StopEntry.fromJson((e! as Map<Object?, Object?>).cast<String, Object?>()))
            .toList(),
      );
}
