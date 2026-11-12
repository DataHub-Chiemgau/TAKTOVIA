import 'dart:convert';

import '../shift/shift_start.dart';

/// Pruefschritt der festen Vorlagenrevision des Laufs (`Inspection.template.steps`, WP-M3-13). Die Reihenfolge ist die des Servers.
class InspectionStep {
  const InspectionStep({required this.code, required this.title, required this.mandatory, required this.safetyCritical, required this.answerKind});

  final String code;
  final String title;
  final bool mandatory;
  final bool safetyCritical;
  final String answerKind;

  factory InspectionStep.fromJson(Map<String, Object?> j) => InspectionStep(
        code: j['step_code']! as String,
        title: j['title']! as String,
        mandatory: j['mandatory']! as bool,
        safetyCritical: j['safety_critical']! as bool,
        answerKind: j['answer_kind']! as String,
      );
}

/// Ein gestarteter Pruefungslauf. Die Vorlage hat der Server bestimmt; die App waehlt und aendert sie nie (CHK-002).
class InspectionRun {
  const InspectionRun({required this.id, required this.templateRevisionId, required this.status, required this.steps});

  final String id;
  final String templateRevisionId;
  final String status;
  final List<InspectionStep> steps;

  /// Anzeige „Pruefung n / Gesamt“ (1-basiert); die Gesamtzahl ist die Schrittzahl der gewaehlten Revision.
  String progress(int index) => 'Pruefung ${index + 1} / ${steps.length}';

  factory InspectionRun.fromJson(Map<String, Object?> j) => InspectionRun(
        id: j['id']! as String,
        templateRevisionId: j['template_revision_id']! as String,
        status: j['status']! as String,
        steps: ((j['template']! as Map<Object?, Object?>)['steps']! as List<Object?>)
            .map((Object? e) => InspectionStep.fromJson((e! as Map<Object?, Object?>).cast<String, Object?>()))
            .toList(),
      );
}

/// Warum der Start scheiterte; die App zeigt nur Texte aus [Strings], nie Freitext des Servers.
enum InspectionStartFailure { notStarted, noTemplate, notFound, other }

class InspectionStartException implements Exception {
  const InspectionStartException(this.failure, this.status);
  final InspectionStartFailure failure;
  final int status;
}

/// `POST /v1/inspections`: sendet Schicht, Fahrzeug, Art und Geraetezeit mit `Idempotency-Key`, **keine** Vorlage und keinen Mandanten. Der Start ist nur online moeglich;
/// ein Wiederholen mit demselben Schluessel ist fuer den Server derselbe Aufruf. 200 (offener Lauf besteht schon) und 201 liefern beide den Lauf.
class HttpInspectionApi {
  HttpInspectionApi(this.transport, {required this.newKey, required this.now});
  final JsonTransport transport;
  final String Function() newKey;
  final DateTime Function() now;

  Future<InspectionRun> start({required String shiftId, required String vehicleId, String kind = 'PRE_SHIFT', String? idempotencyKey}) async {
    final ({int status, String body}) r = await transport.post(
      '/v1/inspections',
      <String, Object?>{'shift_id': shiftId, 'vehicle_id': vehicleId, 'kind': kind, 'client_started_at': now().toUtc().toIso8601String()},
      idempotencyKey: idempotencyKey ?? newKey(),
    );
    final Map<String, Object?> j = (jsonDecode(r.body.isEmpty ? '{}' : r.body) as Map<Object?, Object?>).cast<String, Object?>();
    if (r.status == 200 || r.status == 201) return InspectionRun.fromJson(j);
    final String? code = j['code'] as String?;
    final String detail = (j['detail'] as String?) ?? '';
    if (r.status == 409 && code == 'INVALID_STATE_TRANSITION') throw InspectionStartException(InspectionStartFailure.notStarted, r.status);
    if (r.status == 409 && detail.startsWith('TEMPLATE_')) throw InspectionStartException(InspectionStartFailure.noTemplate, r.status);
    if (r.status == 404) throw InspectionStartException(InspectionStartFailure.notFound, r.status);
    throw InspectionStartException(InspectionStartFailure.other, r.status);
  }
}
