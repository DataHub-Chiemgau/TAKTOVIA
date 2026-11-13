// NICHT AUSGEFÜHRT: Flutter ist in der Erstellungsumgebung nicht verfügbar (mobile/driver/README.md). Die Logik ist reines Dart.
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/pruefung/step_defect_origin.dart';

StepDefectDraft draft() => StepDefectDraft(
      inspectionId: '00000000-0000-4000-8000-0000000000a1',
      stepCode: 'STEP_2',
      vehicleId: '00000000-0000-4000-8000-000000000001',
      deviceEventId: '00000000-0000-4000-8000-0000000000e1',
    );

void main() {
  test('foto_zeigt_aufnahmedatum_und_mangel_melden_an_jedem_negativen_schritt', () {
    // an jedem negativ beantworteten Schritt, auch an nicht kritischen; nicht an positiv beantworteten
    expect(canReportDefectAtStep('DEFECT', safetyCritical: true), isTrue);
    expect(canReportDefectAtStep('DEFECT', safetyCritical: false), isTrue);
    expect(canReportDefectAtStep('OK', safetyCritical: false), isFalse);
    // das Foto nennt sein Aufnahmedatum sichtbar
    final CapturedPhoto photo = CapturedPhoto(ticketId: '00000000-0000-4000-8000-0000000000f1', capturedAtUtc: DateTime.utc(2026, 11, 5, 9, 30));
    expect(photo.label(), contains('Aufgenommen am'));
    expect(photo.label(), contains('2026'));
    expect(photo.label(), contains('(Gerätezeit)'));
  });

  test('referenzen_vorbelegt_pflichtangaben_wie_der_server', () {
    final StepDefectDraft d = draft();
    expect(d.missingPointers, <String>['/description', '/category_code']);
    expect(() => d.toReportBody(), throwsStateError);
    d.text = 'Riss in der Scheibe';
    d.categoryCode = 'GLASS';
    d.photos.add(CapturedPhoto(ticketId: '00000000-0000-4000-8000-0000000000f1', capturedAtUtc: DateTime.utc(2026, 11, 5, 9, 30)));
    final Map<String, Object?> body = d.toReportBody();
    expect(body['source_inspection_id'], '00000000-0000-4000-8000-0000000000a1');
    expect(body['source_step_code'], 'STEP_2');
    expect(body['evidence_ids'], <String>['00000000-0000-4000-8000-0000000000f1']);
    expect(body.containsKey('tenant_id'), isFalse);
    expect(body.containsKey('status'), isFalse);
  });

  test('gegenreferenz_prueferschritt_zur_akte', () {
    final List<DefectRef> refs = <DefectRef>[DefectRef.fromJson(<String, Object?>{'step_code': 'STEP_2', 'defect_id': 'd1', 'case_number': 'SCH-2026-000001'})];
    expect(defectRefForStep(refs, 'STEP_2')?.caseNumber, 'SCH-2026-000001');
    expect(defectRefForStep(refs, 'STEP_3'), isNull);
  });
}
