// NICHT AUSGEFUEHRT: in der Entwicklungsumgebung ohne Flutter/Dart-SDK geschrieben (WP-M3-19). Der Server-Teil ist durch ReadinessQualificationTest und ReadinessQualificationApiTest nachgewiesen.
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/pruefung/readiness_declaration.dart';
import 'package:taktovia_driver/ui/strings.dart';

void main() {
  test('selbstauskunft_enthaelt_nur_ja_nein_ohne_freitext', () {
    final Map<String, Object?> j = ReadinessDeclaration(stepCode: 'EINSATZBEREITSCHAFT', answer: ReadinessAnswer.nein, answeredAt: DateTime.utc(2026, 5, 4, 5, 55)).toJson();
    expect(j.keys.toSet(), <String>{'step_code', 'answer', 'answered_at'});
    expect(j['answer'], 'NEIN');
  });

  test('meldung_bei_fehlender_qualifikation_ist_neutral', () {
    final String key = readinessMessageKey(answer: ReadinessAnswer.ja, missingRequirementCodes: <String>['QUALIFICATION_MISSING']);
    final String text = Strings.de.of(key);
    expect(key, 'inspection.qualificationMissing');
    expect(text.toLowerCase().contains('qualifikation'), isFalse);
    expect(readinessMessageKey(answer: ReadinessAnswer.nein, missingRequirementCodes: <String>[]), 'inspection.readiness.notReady');
  });
}
