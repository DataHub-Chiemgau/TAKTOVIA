/// Abweichung aus einem Prüfschritt (WP-M3-20, LH-6.21-CHK-011, LH-6.22-DMG-003, UI-09): „Nein – Mangel melden“ an jedem negativ beantworteten Prüfschritt öffnet die Erfassung mit
/// vorbelegten Referenzen (Prüflauf, Prüfschritt, Fahrzeug). Fotos zeigen ihr Aufnahmedatum sichtbar.
/// NICHT AUSGEFÜHRT: Flutter ist in der Erstellungsumgebung nicht verfügbar (siehe README); die serverseitige Gegenprobe (Pflichtangaben, höchstens eine Akte je Prüfschritt, Foto ohne
/// Medienprüfung unsichtbar, Gegenreferenz) läuft im Backend (`DefectFromInspectionTest`, `DefectOriginHttpApiTest`).
library;

import '../schaden/defect_draft.dart';

/// Antwortwert „negativ“ im Prüflauf (Serverwert `DEFECT`).
const String kAnswerDefect = 'DEFECT';

/// Die Abweichungserfassung ist an **jedem** negativ beantworteten Prüfschritt verfügbar, nicht nur an sicherheitskritischen (CHK-011, Kriterium 6).
bool canReportDefectAtStep(String answer, {required bool safetyCritical}) => answer == kAnswerDefect;

/// Foto mit Aufnahmedatum (Gerätezeit, als nicht vertrauenswürdig gekennzeichnet; die Serverempfangszeit steht getrennt am Server). Ein Foto ohne Aufnahmedatum gibt es nicht
/// (Uploadticket verlangt `captured_at`).
class CapturedPhoto {
  const CapturedPhoto({required this.ticketId, required this.capturedAtUtc});

  final String ticketId;
  final DateTime capturedAtUtc;

  /// Sichtbare Beschriftung, immer mit dem Aufnahmedatum (`TT.MM.JJJJ HH:MM`, lokale Zeit des Geräts), nie nur ein Symbol.
  String label() {
    final DateTime t = capturedAtUtc.toLocal();
    String two(int n) => n.toString().padLeft(2, '0');
    return 'Aufgenommen am ${two(t.day)}.${two(t.month)}.${t.year} ${two(t.hour)}:${two(t.minute)} (Gerätezeit)';
  }
}

/// Entwurf der Abweichung zu einem Prüfschritt: Herkunft, Text, Kategorie und Fotos. Mandant, Meldender, Zeit, Fallnummer und Status setzt der Server.
class StepDefectDraft {
  StepDefectDraft({required this.inspectionId, required this.stepCode, required this.vehicleId, required this.deviceEventId, DateTime? occurredAtUtc, this.categoryCode, this.text = '', List<CapturedPhoto>? photos})
      : occurredAtUtc = occurredAtUtc ?? DateTime.now().toUtc(),
        photos = photos ?? <CapturedPhoto>[];

  /// Vorbelegte Referenzen, nicht änderbar.
  final String inspectionId;
  final String stepCode;
  final String vehicleId;

  /// Stabil je Erfassung: Wiederholung nach Zeitüberschreitung ergibt dieselbe Akte (Server: höchstens eine Akte je Prüfschritt).
  final String deviceEventId;
  final DateTime occurredAtUtc;
  String? categoryCode;
  String text;
  final List<CapturedPhoto> photos;

  /// Fehlende Pflichtangaben als JSON-Pointer wie in der Serverantwort 422.
  List<String> get missingPointers => <String>[
        if (text.trim().isEmpty) '/description',
        if (categoryCode == null) '/category_code',
      ];

  bool get isComplete => missingPointers.isEmpty && text.length <= kMaxDefectNoteLength;

  /// Rumpf von `POST /v1/defects` mit Herkunft Prüfschritt; die Fotos stehen als abgeschlossene Uploadtickets (`evidence_ids`).
  Map<String, Object?> toReportBody() {
    if (!isComplete) throw StateError('Pflichtangaben fehlen: ${missingPointers.join(', ')}');
    return <String, Object?>{
      'vehicle_id': vehicleId,
      'occurred_at': occurredAtUtc.toIso8601String(),
      'category_code': categoryCode,
      'description': text.trim(),
      'source_inspection_id': inspectionId,
      'source_step_code': stepCode,
      'evidence_ids': photos.map((CapturedPhoto p) => p.ticketId).toList(growable: false),
      'device_event_id': deviceEventId,
      'confirm_new_despite_known': false,
    };
  }
}

/// Sprung Prüfung <-> Schadenakte (UI-11): die Gegenreferenz liefert der Server (`GET /v1/inspections/{id}` -> `defect_refs`, `GET /v1/defects/{id}` -> `source_*`).
class DefectRef {
  const DefectRef({required this.stepCode, required this.defectId, required this.caseNumber});

  factory DefectRef.fromJson(Map<String, Object?> json) =>
      DefectRef(stepCode: json['step_code']! as String, defectId: json['defect_id']! as String, caseNumber: json['case_number']! as String);

  final String stepCode;
  final String defectId;
  final String caseNumber;
}

/// Gegenreferenz zu einem Prüfschritt (oder `null`, wenn dazu noch keine Akte besteht).
DefectRef? defectRefForStep(List<DefectRef> refs, String stepCode) {
  for (final DefectRef r in refs) {
    if (r.stepCode == stepCode) return r;
  }
  return null;
}
