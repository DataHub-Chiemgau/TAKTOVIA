import 'dart:convert';

import '../shift/shift_start.dart';

/// Ein gemessener Standortpunkt. [measuredAtUtc] ist die Geraetezeit der Messung (der Server haelt die Empfangszeit getrennt und
/// behandelt die Geraetezeit nie als Serverzeit). Geschwindigkeit und Richtung werden nicht erfasst (Datenminimierung, WP-M3-34).
class PositionSample {
  const PositionSample({required this.latitude, required this.longitude, required this.accuracyMeters, required this.measuredAtUtc});
  final double latitude;
  final double longitude;
  final double accuracyMeters;
  final DateTime measuredAtUtc;

  Map<String, Object?> toJson() => <String, Object?>{
        'position': <String, Object?>{'latitude': latitude, 'longitude': longitude},
        'accuracy_meters': accuracyMeters,
        'measured_at': measuredAtUtc.toUtc().toIso8601String(),
      };
}

/// Erfassungsregeln (Planungsannahmen OP-04, D-05 offen: Sendeintervall; D-10/D-11 offen: Fristen). Erfasst wird **nur im angetretenen Dienst**
/// und nur mit einem Hinweis an den Fahrer; ausserhalb des Dienstes wird nichts gemessen, nichts gepuffert und nichts gesendet.
class PositionCapturePolicy {
  const PositionCapturePolicy({this.interval = const Duration(seconds: 10), this.maxBatch = 100, this.maxBufferAge = const Duration(hours: 24)});
  final Duration interval;
  final int maxBatch;

  /// Aeltere ungesendete Punkte werden verworfen (Minimierung; der Server weist Punkte ausserhalb des Dienstes ohnehin ab).
  final Duration maxBufferAge;

  /// Erfassung ist nur erlaubt, wenn der Server die Schicht als angetreten und gueltig meldet (`valid`), nie nach lokaler Zeitrechnung.
  bool mayCapture(ShiftScreenState state) => state.view == ShiftView.started && (state.shift?.valid ?? false);
}

/// Ergebnis einer Sendung, wie es die App braucht: gespeichert/bereits gespeichert (`accepted`), einzeln abgewiesen (`rejected`).
class PositionUploadResult {
  const PositionUploadResult({required this.accepted, required this.rejected});
  final int accepted;
  final int rejected;
}

abstract interface class PositionApi {
  /// `POST /v1/driver/positions`. Wirft [ShiftApiException] bei Serverfehlern (404 fremd/unbekannt, 409 Dienst nicht angetreten oder Erfassung nicht aktiviert, 403 Sitzung/Geraet).
  Future<PositionUploadResult> send({required String shiftId, required String vehicleId, required String deviceId, required List<PositionSample> samples});
}

/// Puffer ungesendeter Punkte (im Speicher; die verschluesselte Outbox der App uebernimmt die Persistenz, WP-M3-24). Nach Wiederverbindung werden die Punkte
/// mit ihrer urspruenglichen Messzeit nachgeliefert; sie gelten fuer die Leitstelle nie als aktuelle Position.
class PositionBuffer {
  PositionBuffer(this.policy);
  final PositionCapturePolicy policy;
  final List<PositionSample> _items = <PositionSample>[];

  int get length => _items.length;

  void add(PositionSample s, {required DateTime nowUtc}) {
    _items.add(s);
    _items.removeWhere((PositionSample p) => nowUtc.difference(p.measuredAtUtc) > policy.maxBufferAge);
  }

  List<PositionSample> peek() => List<PositionSample>.unmodifiable(_items.take(policy.maxBatch));

  void drop(int count) => _items.removeRange(0, count.clamp(0, _items.length));

  void clear() => _items.clear();
}

/// Sendet gepufferte Punkte in Paketen. Bei 409 (Dienst nicht angetreten/entzogen, Erfassung nicht aktiviert) oder 404 (Schicht nicht mehr gueltig) wird der Puffer
/// geleert und die Erfassung beendet (nichts ausserhalb des Dienstes behalten); bei Netzfehlern bleibt der Puffer fuer die Nachlieferung erhalten.
class PositionUploader {
  PositionUploader({required this.api, required this.buffer});
  final PositionApi api;
  final PositionBuffer buffer;

  /// Liefert `true`, wenn die Erfassung weiterlaufen darf.
  Future<bool> flush({required String shiftId, required String vehicleId, required String deviceId}) async {
    while (buffer.length > 0) {
      final List<PositionSample> batch = buffer.peek();
      try {
        await api.send(shiftId: shiftId, vehicleId: vehicleId, deviceId: deviceId, samples: batch);
        buffer.drop(batch.length);
      } on ShiftApiException catch (e) {
        if (e.status == 409 || e.status == 404) {
          buffer.clear();
          return false;
        }
        return true; // 403/5xx/429: spaeter erneut versuchen, Puffer bleibt
      }
    }
    return true;
  }
}

/// Adapter fuer `POST /v1/driver/positions` ueber den JSON-Transport.
class HttpPositionApi implements PositionApi {
  HttpPositionApi(this.transport, {required this.newKey});
  final JsonTransport transport;
  final String Function() newKey;

  @override
  Future<PositionUploadResult> send({required String shiftId, required String vehicleId, required String deviceId, required List<PositionSample> samples}) async {
    final ({int status, String body}) r = await transport.post(
      '/v1/driver/positions',
      <String, Object?>{'shift_id': shiftId, 'vehicle_id': vehicleId, 'device_id': deviceId, 'samples': samples.map((PositionSample s) => s.toJson()).toList()},
      idempotencyKey: newKey(),
    );
    final Map<String, Object?> j = (jsonDecode(r.body.isEmpty ? '{}' : r.body) as Map<Object?, Object?>).cast<String, Object?>();
    if (r.status != 200) throw ShiftApiException(r.status, j['code'] as String?);
    return PositionUploadResult(accepted: j['accepted']! as int, rejected: j['rejected']! as int);
  }
}
