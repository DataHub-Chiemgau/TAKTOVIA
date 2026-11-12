/// Wartefrist am Stopp (WP-M3-29, UI-10, `LH-6.6-DRV-005`). **Nicht ausgefuehrt** (kein Flutter-SDK in der Entwicklungsumgebung).
///
/// Die Ankunft startet die Wartefrist; der No-Show ist erst nach Ablauf von `no_show_grace_min` (Dienstparameter, vom Server geliefert; Planungsdefault 10 Minuten)
/// anbietbar und verlangt einen Grund. Das ist nur die Bedienlogik der Oberflaeche: massgeblich prueft der Server gegen die von ihm plausibilisierte Ankunftszeit
/// (zu frueh ist dort 422 `NO_SHOW_GRACE_NOT_ELAPSED`).
enum NoShowReason { notPresent, refusedToBoard, other }

const Map<NoShowReason, String> noShowReasonCode = <NoShowReason, String>{
  NoShowReason.notPresent: 'NOT_PRESENT',
  NoShowReason.refusedToBoard: 'REFUSED_TO_BOARD',
  NoShowReason.other: 'OTHER',
};

class WaitPeriod {
  const WaitPeriod({required this.arrivedAtUtc, required this.graceMinutes});
  final DateTime arrivedAtUtc;
  final int graceMinutes;

  DateTime get noShowAllowedFromUtc => arrivedAtUtc.toUtc().add(Duration(minutes: graceMinutes));

  /// Wartezeit bis [nowUtc], nie negativ.
  Duration waited(DateTime nowUtc) {
    final Duration d = nowUtc.toUtc().difference(arrivedAtUtc.toUtc());
    return d.isNegative ? Duration.zero : d;
  }

  /// Genau auf der Grenze ist die Frist abgelaufen (wie am Server).
  bool noShowAllowed(DateTime nowUtc) => !nowUtc.toUtc().isBefore(noShowAllowedFromUtc);

  /// Nutzlast der Outbox-Zeile fuer das No-Show-Ereignis; ohne Grund oder vor Ablauf der Frist wird sie nicht gebildet (die Oberflaeche bietet die Aktion dann nicht an).
  Map<String, Object?>? noShowPayload({required String bookingId, required String stopId, required NoShowReason? reason, required DateTime nowUtc}) {
    if (reason == null || !noShowAllowed(nowUtc)) return null;
    return <String, Object?>{
      'bookingId': bookingId,
      'stopId': stopId,
      'noShowReason': noShowReasonCode[reason],
      'noShowWaitSeconds': waited(nowUtc).inSeconds,
    };
  }
}
