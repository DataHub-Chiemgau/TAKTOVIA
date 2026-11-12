/// Dienststatus der Fahrer-App (UI-10 Statusumschaltung, WP-M3-31, LH-6.6-DRV-007).
///
/// NICHT AUSGEFUEHRT: Flutter/Dart steht in dieser Umgebung nicht zur Verfuegung (siehe README); die Datei und ihr Test sind ungeprueft.
///
/// Die App zeigt nur an und meldet. Entscheiden tut der Server (Zustandsautomat `fahrzeug_dienststatus.md`): ein unzulaessiger Wechsel
/// wird dort mit 409 abgewiesen, ein offline erfasster, der nicht mehr passt, ergibt `REVIEW_REQUIRED`. Die Angebotsliste hier ist nur
/// die Spiegelung der Kanten fuer die Fahrerrolle, damit die Oberflaeche keine aussichtslosen Schaltflaechen zeigt.
enum ServiceStatus {
  notReady('NOT_READY'),
  available('AVAILABLE'),
  enRoute('EN_ROUTE'),
  waiting('WAITING'),
  breakTime('BREAK'),
  disrupted('DISRUPTED'),
  ended('ENDED');

  const ServiceStatus(this.code);
  final String code;

  static ServiceStatus parse(String code) => ServiceStatus.values.firstWhere((ServiceStatus s) => s.code == code);

  /// Nur Verfuegbarkeit, Anfahrt und Wartezeit werden neu disponiert (Originalabnahme DRV-007).
  bool get dispatchable => this == available || this == enRoute || this == waiting;
}

/// Kanten, die der Fahrer selbst melden darf (Spalte "Akteur" des Automaten mit "Fahrer"); `NOT_READY` ist nie ein Meldeziel.
const Map<ServiceStatus, Set<ServiceStatus>> driverEdges = <ServiceStatus, Set<ServiceStatus>>{
  ServiceStatus.notReady: <ServiceStatus>{ServiceStatus.available, ServiceStatus.disrupted, ServiceStatus.ended},
  ServiceStatus.available: <ServiceStatus>{ServiceStatus.enRoute, ServiceStatus.breakTime, ServiceStatus.disrupted, ServiceStatus.ended},
  ServiceStatus.enRoute: <ServiceStatus>{ServiceStatus.waiting, ServiceStatus.disrupted},
  ServiceStatus.waiting: <ServiceStatus>{ServiceStatus.enRoute, ServiceStatus.available, ServiceStatus.breakTime, ServiceStatus.disrupted},
  ServiceStatus.breakTime: <ServiceStatus>{ServiceStatus.available, ServiceStatus.ended},
  ServiceStatus.disrupted: <ServiceStatus>{ServiceStatus.ended},
  ServiceStatus.ended: <ServiceStatus>{},
};

/// Status, die die Umschaltung von [current] aus anbietet.
List<ServiceStatus> switchTargets(ServiceStatus current) => (driverEdges[current] ?? <ServiceStatus>{}).toList();

/// Hinweis zur Wirkung einer Meldung ohne Verbindung (WP-M3-31 AK 6): Stoerung und Ende wirken auf die Disponierbarkeit erst ab Serverempfang.
/// `null`, wenn kein Hinweis noetig ist (online oder ein Status, der die Disponierbarkeit nicht einschraenkt).
String? offlineHint(ServiceStatus target, {required bool online}) {
  if (online) return null;
  if (target == ServiceStatus.disrupted || target == ServiceStatus.ended) {
    return 'Ohne Verbindung gespeichert. ${target == ServiceStatus.disrupted ? 'Die Stoerung' : 'Das Ende'} wirkt auf die Disposition erst, sobald der Server die Meldung empfangen hat.';
  }
  return 'Ohne Verbindung gespeichert. Die Meldung wird uebertragen, sobald eine Verbindung besteht.';
}

/// Body von `POST /v1/driver/shifts/{shift_id}/service-status`. Die `event_id` bleibt bei jeder Wiederholung gleich (der Server wirkt genau einmal);
/// `occurred_at` ist die Geraetezeit, die Serverempfangszeit setzt der Server.
Map<String, Object?> serviceStatusBody({
  required String eventId,
  required ServiceStatus target,
  required DateTime occurredAt,
  required DateTime deviceRecordedAt,
  required bool offlineRecorded,
  String? deviceId,
}) {
  if (target == ServiceStatus.notReady) throw ArgumentError('NOT_READY ist kein Meldeziel');
  return <String, Object?>{
    'event_id': eventId,
    'target_status': target.code,
    if (deviceId != null) 'device_id': deviceId,
    'occurred_at': occurredAt.toUtc().toIso8601String(),
    'device_recorded_at': deviceRecordedAt.toUtc().toIso8601String(),
    'offline_recorded': offlineRecorded,
  };
}
