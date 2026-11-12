import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/fahrt/wait_period.dart';

// WP-M3-29: nicht ausgefuehrt (kein Flutter-SDK in der Entwicklungsumgebung).
void main() {
  final DateTime arrived = DateTime.utc(2026, 12, 1, 6);
  const WaitPeriod p = WaitPeriod(arrivedAtUtc: DateTime.utc(2026, 12, 1, 6), graceMinutes: 10);

  test('No-Show erst auf der Grenze und danach', () {
    expect(p.noShowAllowed(DateTime.utc(2026, 12, 1, 6, 9, 59)), isFalse);
    expect(p.noShowAllowed(DateTime.utc(2026, 12, 1, 6, 10)), isTrue);
    expect(p.noShowAllowedFromUtc, arrived.add(const Duration(minutes: 10)));
  });

  test('Nutzlast nur mit Grund und nach Ablauf der Frist', () {
    final DateTime later = DateTime.utc(2026, 12, 1, 6, 11);
    expect(p.noShowPayload(bookingId: 'b', stopId: 's', reason: null, nowUtc: later), isNull);
    expect(p.noShowPayload(bookingId: 'b', stopId: 's', reason: NoShowReason.notPresent, nowUtc: DateTime.utc(2026, 12, 1, 6, 5)), isNull);
    final Map<String, Object?>? payload = p.noShowPayload(bookingId: 'b', stopId: 's', reason: NoShowReason.refusedToBoard, nowUtc: later);
    expect(payload!['noShowReason'], 'REFUSED_TO_BOARD');
    expect(payload['noShowWaitSeconds'], 660);
  });

  test('Wartezeit ist nie negativ', () {
    expect(p.waited(DateTime.utc(2026, 12, 1, 5)), Duration.zero);
  });
}
