import 'dart:convert';

import '../shift/shift_start.dart';

/// Altschaden in der minimierten Fahrerprojektion (`KnownDefect`, WP-M3-21): Fallnummer, Kategorie, Art, Bereich, Fotos. Keine Beschreibung, kein Melder, keine vertraulichen Dokumente.
class KnownDefect {
  const KnownDefect({required this.defectId, required this.caseNumber, required this.categoryCode, this.kind, this.vehicleArea, required this.photoIds, required this.stillOpen, required this.acknowledged});

  final String defectId;
  final String caseNumber;
  final String categoryCode;
  final String? kind;
  final String? vehicleArea;
  final List<String> photoIds;
  final bool stillOpen;
  final bool acknowledged;

  factory KnownDefect.fromJson(Map<String, Object?> j) => KnownDefect(
        defectId: j['defect_id']! as String,
        caseNumber: j['case_number']! as String,
        categoryCode: j['category_code']! as String,
        kind: j['kind'] as String?,
        vehicleArea: j['vehicle_area'] as String?,
        photoIds: ((j['photos'] as List<Object?>?) ?? <Object?>[]).map((Object? p) => (p! as Map<Object?, Object?>)['evidence_id']! as String).toList(),
        stillOpen: j['still_open']! as bool,
        acknowledged: j['acknowledged']! as bool,
      );
}

/// Altschaeden eines Pruefungslaufs. Eine leere Liste ist der ausdrueckliche Leerzustand („keine offenen Altschaeden“) ohne Quittierungspflicht.
class KnownDefectList {
  const KnownDefectList({required this.defects, required this.acknowledgementRequired, required this.unacknowledgedCount});

  final List<KnownDefect> defects;
  final bool acknowledgementRequired;
  final int unacknowledgedCount;

  bool get isEmptyState => defects.isEmpty;

  factory KnownDefectList.fromJson(Map<String, Object?> j) => KnownDefectList(
        defects: ((j['defects'] as List<Object?>?) ?? <Object?>[]).map((Object? e) => KnownDefect.fromJson((e! as Map<Object?, Object?>).cast<String, Object?>())).toList(),
        acknowledgementRequired: j['acknowledgement_required']! as bool,
        unacknowledgedCount: j['unacknowledged_count']! as int,
      );
}

class KnownDefectException implements Exception {
  const KnownDefectException(this.status);
  final int status;
}

/// `GET /v1/inspections/{id}/known-defects` und `POST .../known-defects/{defect_id}/acknowledge`. Der Body der Quittierung ist immer ein leeres Objekt: es gibt kein Verursacher- oder Schuldfeld (DMG-012).
class HttpKnownDefectApi {
  HttpKnownDefectApi(this.transport, {required this.newKey});
  final JsonTransport transport;
  final String Function() newKey;

  Future<KnownDefectList> list(String inspectionId) async {
    final ({int status, String body}) r = await transport.get('/v1/inspections/$inspectionId/known-defects');
    if (r.status != 200) throw KnownDefectException(r.status);
    return KnownDefectList.fromJson((jsonDecode(r.body) as Map<Object?, Object?>).cast<String, Object?>());
  }

  /// 201 (neu) und 200 (bereits quittiert, zweites Geraet) sind beide „quittiert“; der Schluessel macht die Wiederholung nach Zeitueberschreitung zum selben Aufruf.
  Future<KnownDefect> acknowledge(String inspectionId, String defectId, {String? idempotencyKey}) async {
    final ({int status, String body}) r = await transport.post(
      '/v1/inspections/$inspectionId/known-defects/$defectId/acknowledge',
      <String, Object?>{},
      idempotencyKey: idempotencyKey ?? newKey(),
    );
    if (r.status != 200 && r.status != 201) throw KnownDefectException(r.status);
    return KnownDefect.fromJson((jsonDecode(r.body) as Map<Object?, Object?>).cast<String, Object?>());
  }
}

/// UI-09: „Vorhandene Schaeden ansehen“ steht vor „Pflichtpruefung starten“. Die Pruefung beginnt erst, wenn alle offenen Altschaeden gesehen/bestehend quittiert sind; im Leerzustand
/// entfaellt die Quittierung. Der Server setzt dieselbe Regel beim Abschluss durch (409 `KNOWN_DEFECTS_UNACKNOWLEDGED`).
class KnownDefectsGate {
  KnownDefectsGate(this.api, this.inspectionId);
  final HttpKnownDefectApi api;
  final String inspectionId;
  KnownDefectList? current;

  Future<KnownDefectList> load() async => current = await api.list(inspectionId);

  /// „Pflichtpruefung starten“ ist erst freigegeben, wenn nichts mehr zu quittieren ist.
  bool get mayStartMandatoryInspection => current != null && current!.unacknowledgedCount == 0;

  Future<void> acknowledge(String defectId) async {
    await api.acknowledge(inspectionId, defectId);
    await load();
  }
}
