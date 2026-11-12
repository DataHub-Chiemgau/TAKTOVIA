// NICHT AUSGEFÜHRT: Flutter ist in der Erstellungsumgebung nicht verfügbar (mobile/driver/README.md). Die Logik des Entwurfs ist reines Dart.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/schaden/defect_capture_screen.dart';
import 'package:taktovia_driver/schaden/defect_draft.dart';

DefectDraft draft() => DefectDraft(vehicleId: '00000000-0000-4000-8000-000000000001', deviceEventId: '00000000-0000-4000-8000-0000000000e1');

void main() {
  test('musterfall_in_hoechstzahl_schritten', () {
    final DefectDraft d = draft();
    int steps = 0;
    while (d.nextStep != CaptureStep.send) {
      switch (d.nextStep) {
        case CaptureStep.kind:
          d.kind = 'DENT';
        case CaptureStep.area:
          d.vehicleArea = 'LEFT_SIDE';
        case CaptureStep.effect:
          d.observedEffect = 'COSMETIC_ONLY';
        case CaptureStep.photo:
          d.photoTicketIds.add('00000000-0000-4000-8000-0000000000f1');
        case CaptureStep.send:
          break;
      }
      steps++;
    }
    steps++; // Senden
    expect(steps, lessThanOrEqualTo(kMaxDefectCaptureSteps));
    expect(d.isComplete, isTrue);
    final Map<String, Object?> body = d.toReportBody();
    expect(body['capture_mode'], 'GUIDED');
    expect(body['category_code'], 'BODY');
    expect(body['device_event_id'], '00000000-0000-4000-8000-0000000000e1');
  });

  test('pflichtangaben_fehlen_nennt_feldverweise_wie_der_server', () {
    final DefectDraft d = draft();
    expect(d.missingPointers, <String>['/kind', '/vehicle_area', '/observed_effect', '/evidence_ids']);
    expect(() => d.toReportBody(), throwsStateError);
    d.note = 'x' * (kMaxDefectNoteLength + 1);
    expect(d.isComplete, isFalse);
  });

  testWidgets('status_anzeigen_getrennt_und_bedienbar_ohne_farbe', (WidgetTester tester) async {
    final DefectDraft d = draft();
    await tester.pumpWidget(MaterialApp(home: DefectCaptureScreen(draft: d)));
    expect(find.text('Art des Schadens'), findsOneWidget);
    expect(find.text('Entwurf (nur auf diesem Gerät)'), findsOneWidget);
    expect(find.byIcon(Icons.edit), findsOneWidget);
    d.status = DraftStatus.synced;
    await tester.pumpWidget(MaterialApp(home: DefectCaptureScreen(draft: d)));
    expect(find.text('Synchronisiert (an den Server übertragen)'), findsOneWidget);
    d.status = DraftStatus.accepted;
    d.caseNumber = 'SCH-2026-000001';
    await tester.pumpWidget(MaterialApp(home: DefectCaptureScreen(draft: d)));
    expect(find.text('Angenommen (Fallnummer SCH-2026-000001)'), findsOneWidget);
    expect(find.byIcon(Icons.verified), findsOneWidget);
    // Auswahl mit Symbol und Text, Touchfläche mindestens 44 x 44
    final Size size = tester.getSize(find.byType(ListTile).first);
    expect(size.height, greaterThanOrEqualTo(kMinTouchTargetForTests));
  });
}

const double kMinTouchTargetForTests = 44;
