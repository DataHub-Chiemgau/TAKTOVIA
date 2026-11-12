// NICHT AUSGEFUEHRT (kein Flutter/Dart in dieser Umgebung, siehe README): ungeprueft.
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/shift/service_status.dart';

void main() {
  test('ui10_statusumschaltung_und_offline_hinweis', () {
    // sechs Status plus Anfangszustand sind unterscheidbar
    expect(ServiceStatus.values.map((ServiceStatus s) => s.code).toSet().length, 7);
    expect(ServiceStatus.values.where((ServiceStatus s) => s.dispatchable), <ServiceStatus>[ServiceStatus.available, ServiceStatus.enRoute, ServiceStatus.waiting]);

    // Umschaltung: aus Verfuegbarkeit sind Anfahrt, Pause, Stoerung und Ende angeboten, nach Ende nichts mehr
    expect(switchTargets(ServiceStatus.available), containsAll(<ServiceStatus>[ServiceStatus.enRoute, ServiceStatus.breakTime, ServiceStatus.disrupted, ServiceStatus.ended]));
    expect(switchTargets(ServiceStatus.enRoute).contains(ServiceStatus.ended), isFalse, reason: 'Ende nach Anfahrt ist unzulaessig');
    expect(switchTargets(ServiceStatus.ended), isEmpty);
    for (final MapEntry<ServiceStatus, Set<ServiceStatus>> e in driverEdges.entries) {
      expect(e.value.contains(ServiceStatus.notReady), isFalse, reason: 'NOT_READY ist nie ein Meldeziel (${e.key})');
    }

    // Offline-Hinweis: Stoerung und Ende wirken erst ab Serverempfang
    expect(offlineHint(ServiceStatus.disrupted, online: false), contains('erst'));
    expect(offlineHint(ServiceStatus.ended, online: false), contains('Server'));
    expect(offlineHint(ServiceStatus.breakTime, online: false), isNot(contains('erst')));
    expect(offlineHint(ServiceStatus.disrupted, online: true), isNull);

    // Meldung: gleiche event_id bei Wiederholung, Geraetezeit in UTC, offline gekennzeichnet
    final DateTime t = DateTime.utc(2026, 11, 12, 8);
    final Map<String, Object?> body = serviceStatusBody(eventId: 'e1', target: ServiceStatus.disrupted, occurredAt: t, deviceRecordedAt: t, offlineRecorded: true);
    expect(body['target_status'], 'DISRUPTED');
    expect(body['offline_recorded'], isTrue);
    expect(body.containsKey('tenant_id'), isFalse);
    expect(() => serviceStatusBody(eventId: 'e2', target: ServiceStatus.notReady, occurredAt: t, deviceRecordedAt: t, offlineRecorded: false), throwsArgumentError);
  });
}
