// NICHT AUSGEFUEHRT: in der Entwicklungsumgebung ohne Flutter/Dart-SDK geschrieben (WP-M3-09, WP-M3-16). Der Server-Teil ist durch ReadinessGateTest, ReadinessGateApiTest und CriticalDefectBlockTest nachgewiesen.
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/pruefung/release_gate.dart';

void main() {
  test('ohne_freigabe_keine_auftraege_und_keine_bereitmeldung_mit_fehlenden_schritten', () {
    final ReleaseGateView v = ReleaseGateView.fromRunJson(<String, Object?>{
      'release_state': 'NOT_RELEASED',
      'missing_requirements': <Object?>[
        <String, Object?>{'code': 'INSPECTION_OPEN', 'inspection_id': 'i-1', 'step_codes': <Object?>['BELEUCHTUNG', 'REIFEN']},
      ],
    });
    expect(v.ordersExecutable, isFalse);
    expect(v.mayReportReady, isFalse);
    expect(v.texts.single, contains('offen: BELEUCHTUNG, REIFEN'));
  });

  test('nach_serverfreigabe_sind_auftraege_ausfuehrbar', () {
    final ReleaseGateView v = ReleaseGateView.fromRunJson(<String, Object?>{'release_state': 'RELEASED', 'missing_requirements': <Object?>[]});
    expect(v.ordersExecutable, isTrue);
  });

  test('fehlende_qualifikation_wird_neutral_gemeldet', () {
    final String t = requirementText(const MissingRequirement(code: 'QUALIFICATION_MISSING'));
    expect(t.toLowerCase().contains('qualifikation'), isFalse);
  });

  test('sperranzeige_nennt_vorgangsnummer_und_kontakt_ohne_aufhebeaktion', () {
    final BlockNotice n = BlockNotice.fromJson(<String, Object?>{'block_kind': 'VEHICLE', 'case_number': 'SPE-2026-000007', 'dispatch_contact': '+49 351 000'});
    expect(n.text, contains('SPE-2026-000007'));
    expect(n.text, contains('+49 351 000'));
    expect(n.text, contains('kritischer Mangel'));
    expect(criticalLabel('Bremsen', safetyCritical: true), startsWith('Sicherheitskritisch'));
  });

  test('ausstattungspunkt_bietet_ja_mangel_nicht_pruefbar_ohne_nicht_anwendbar', () {
    expect(kEquipmentAnswerOptions.map((EquipmentAnswerOption o) => o.answer), <String>['OK', 'DEFECT', 'NOT_CHECKABLE']);
    expect(kEquipmentAnswerOptions.where((EquipmentAnswerOption o) => o.requiresNote).single.answer, 'DEFECT');
  });
}
