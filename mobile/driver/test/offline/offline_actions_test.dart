import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/offline/offline_actions.dart';

void main() {
  const OfflinePolicy p = OfflinePolicy();

  test('offline keine Aktion fuer Freigabe, Sperraufhebung oder verbindliche Buchung', () {
    final List<DriverAction> offline = p.available(online: false);
    expect(offline, containsAll(<DriverAction>[DriverAction.recordReceipt, DriverAction.reportDamage, DriverAction.recordTimeEvent]));
    for (final DriverAction a in OfflinePolicy.serverOnly) {
      expect(offline.contains(a), isFalse, reason: '$a');
      expect(p.isAvailable(a, online: true), isTrue, reason: 'online ist die Aktion angeboten, entschieden wird am Server');
    }
  });

  test('jede Aktion ist entweder offline zulaessig oder nur am Server', () {
    final Set<DriverAction> all = DriverAction.values.toSet();
    expect(OfflinePolicy.offlineAllowed.union(OfflinePolicy.serverOnly), all);
    expect(OfflinePolicy.offlineAllowed.intersection(OfflinePolicy.serverOnly), isEmpty);
  });

  test('unbekannter Outbox-Typ wird abgewiesen', () {
    p.requireOutboxType('checkIn');
    expect(() => p.requireOutboxType('releaseVehicle'), throwsA(isA<OfflineActionNotAllowed>()));
  });
}
