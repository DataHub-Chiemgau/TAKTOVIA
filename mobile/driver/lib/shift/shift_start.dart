import 'dart:convert';

/// Eigene Schicht des Fahrers (`GET /v1/driver/shifts`, WP-M3-25). `valid` ist wahr, wenn der Server die Schicht als angetreten und innerhalb
/// der Schichtzeit erkennt; die App entscheidet das nie selbst (Geraetezeit ist keine Serverzeit).
class DriverShift {
  const DriverShift({
    required this.shiftId,
    required this.vehicleId,
    required this.vehicleLabel,
    required this.startsAt,
    required this.endsAt,
    required this.state,
    required this.startableFrom,
    required this.valid,
  });

  final String shiftId;
  final String vehicleId;
  final String? vehicleLabel;
  final DateTime startsAt;
  final DateTime endsAt;
  final String state;
  final DateTime startableFrom;
  final bool valid;

  factory DriverShift.fromJson(Map<String, Object?> j) => DriverShift(
        shiftId: j['shift_id']! as String,
        vehicleId: j['vehicle_id']! as String,
        vehicleLabel: j['vehicle_label'] as String?,
        startsAt: DateTime.parse(j['starts_at']! as String).toUtc(),
        endsAt: DateTime.parse(j['ends_at']! as String).toUtc(),
        state: j['state']! as String,
        startableFrom: DateTime.parse(j['startable_from']! as String).toUtc(),
        valid: j['valid']! as bool,
      );
}

/// Ergebnis des Antritts: `alreadyStarted` heisst, ein anderes Geraet (oder eine Wiederholung) hat den Dienst schon angetreten; der Zustand ist derselbe.
class ShiftStartOutcome {
  const ShiftStartOutcome(this.shift, {required this.alreadyStarted});
  final DriverShift shift;
  final bool alreadyStarted;
}

/// Fehler des Servers bei Antritt/Lesen: Status und Problemcode (`application/problem+json`), nie Freitext des Servers im UI.
class ShiftApiException implements Exception {
  const ShiftApiException(this.status, this.code);
  final int status;
  final String? code;
}

abstract interface class ShiftApi {
  Future<List<DriverShift>> mine();
  Future<ShiftStartOutcome> start({required String shiftId, required String vehicleId, required String deviceId});
  Future<DriverShift> end({required String shiftId, required String deviceId});
}

/// Anzeigezustand von UI-09 „Dienststart“. Keine Einsatzfreigabe: diese kommt getrennt vom Server nach bestaetigter Pruefung (WP-M3-09).
enum ShiftView { none, notYetStartable, startable, started, ended, withdrawn }

class ShiftScreenState {
  const ShiftScreenState(this.view, this.shift);
  final ShiftView view;
  final DriverShift? shift;

  /// Aufbau aus der Serverliste. [serverNow] ist die vom Server gelieferte Zeit (`as_of`), nicht die Geraetezeit.
  static ShiftScreenState from(List<DriverShift> shifts, DateTime serverNow) {
    if (shifts.isEmpty) return const ShiftScreenState(ShiftView.none, null);
    final DriverShift s = shifts.first;
    switch (s.state) {
      case 'STARTED':
        return ShiftScreenState(s.valid ? ShiftView.started : ShiftView.ended, s);
      case 'ENDED':
        return ShiftScreenState(ShiftView.ended, s);
      case 'CANCELLED':
        return ShiftScreenState(ShiftView.withdrawn, s);
      default:
        return ShiftScreenState(serverNow.isBefore(s.startableFrom) ? ShiftView.notYetStartable : ShiftView.startable, s);
    }
  }
}

/// Transport fuer JSON-Aufrufe (Port); die Implementierung haengt Token, `Idempotency-Key` und `traceparent` an.
abstract interface class JsonTransport {
  Future<({int status, String body})> get(String path);
  Future<({int status, String body})> post(String path, Map<String, Object?> body, {required String idempotencyKey});
}

/// Adapter fuer `GET /v1/driver/shifts`, `POST /v1/driver/shift-starts` und `POST /v1/driver/shift-ends`. Der Antritt ist nur online moeglich (Serverentscheidung,
/// Kap. 06); ein Wiederholen mit demselben [idempotencyKey] ist fuer den Server derselbe Aufruf.
class HttpShiftApi implements ShiftApi {
  HttpShiftApi(this.transport, {required this.newKey});
  final JsonTransport transport;
  final String Function() newKey;

  Map<String, Object?> _ok(({int status, String body}) r) {
    final Map<String, Object?> j = (jsonDecode(r.body.isEmpty ? '{}' : r.body) as Map<Object?, Object?>).cast<String, Object?>();
    if (r.status != 200) throw ShiftApiException(r.status, j['code'] as String?);
    return j;
  }

  @override
  Future<List<DriverShift>> mine() async {
    final Map<String, Object?> j = _ok(await transport.get('/v1/driver/shifts'));
    return (j['items']! as List<Object?>).map((Object? e) => DriverShift.fromJson((e! as Map<Object?, Object?>).cast<String, Object?>())).toList();
  }

  @override
  Future<ShiftStartOutcome> start({required String shiftId, required String vehicleId, required String deviceId}) async {
    final Map<String, Object?> j = _ok(await transport.post('/v1/driver/shift-starts', <String, Object?>{'shift_id': shiftId, 'vehicle_id': vehicleId, 'device_id': deviceId}, idempotencyKey: newKey()));
    return ShiftStartOutcome(DriverShift.fromJson((j['shift']! as Map<Object?, Object?>).cast<String, Object?>()), alreadyStarted: j['already_started']! as bool);
  }

  @override
  Future<DriverShift> end({required String shiftId, required String deviceId}) async {
    final Map<String, Object?> j = _ok(await transport.post('/v1/driver/shift-ends', <String, Object?>{'shift_id': shiftId, 'device_id': deviceId}, idempotencyKey: newKey()));
    return DriverShift.fromJson(j);
  }
}
