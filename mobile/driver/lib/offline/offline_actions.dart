/// Aktionen der Fahrer-App und ihre Offline-Zulaessigkeit (Kap. 01 §1.5, WP-M3-24 AK 6).
///
/// Offline zulaessig sind nur nachtraeglich pruefbare Erfassungen: Beleg, Schaden, Zeitereignis und die Fahrerereignisse
/// des laufenden Dienstes. Alles, was eine Einsatzfreigabe, eine Sperraufhebung oder eine verbindliche Buchung ausloest,
/// entscheidet allein der Server und ist ohne Verbindung nicht verfuegbar.
enum DriverAction {
  recordReceipt,
  reportDamage,
  recordTimeEvent,
  recordStopEvent,
  startInspectionDraft,
  requestOperationalReadiness,
  submitInspectionForRelease,
  liftVehicleBlock,
  bindBooking,
}

class OfflineActionNotAllowed implements Exception {
  const OfflineActionNotAllowed(this.action);
  final DriverAction action;
  @override
  String toString() => 'OfflineActionNotAllowed: $action';
}

class OfflinePolicy {
  const OfflinePolicy();

  /// Aktionen, die auch ohne Verbindung angeboten werden.
  static const Set<DriverAction> offlineAllowed = <DriverAction>{
    DriverAction.recordReceipt,
    DriverAction.reportDamage,
    DriverAction.recordTimeEvent,
    DriverAction.recordStopEvent,
    DriverAction.startInspectionDraft,
  };

  /// Aktionen, die nie offline moeglich sind (Server entscheidet): Freigabe, Sperraufhebung, verbindliche Buchung.
  static const Set<DriverAction> serverOnly = <DriverAction>{
    DriverAction.requestOperationalReadiness,
    DriverAction.submitInspectionForRelease,
    DriverAction.liftVehicleBlock,
    DriverAction.bindBooking,
  };

  bool isAvailable(DriverAction a, {required bool online}) => online || offlineAllowed.contains(a);

  List<DriverAction> available({required bool online}) =>
      DriverAction.values.where((DriverAction a) => isAvailable(a, online: online)).toList();

  /// Ereignistypen der Outbox, die ein Offline-Eintrag haben darf (Anbindung an die Servertypen von `recordDriverEvent`).
  static const Set<String> outboxTypes = <String>{
    'arrived', 'departed', 'checkIn', 'checkOut', 'noShow', 'breakStart', 'breakEnd', 'disruption', 'shiftEnd', 'damageReport', 'receipt', 'timeEvent',
  };

  void requireOutboxType(String type) {
    if (!outboxTypes.contains(type)) throw OfflineActionNotAllowed(DriverAction.requestOperationalReadiness);
  }
}
