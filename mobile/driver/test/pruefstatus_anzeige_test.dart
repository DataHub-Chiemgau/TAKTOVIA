// NICHT AUSGEFUEHRT: in der Entwicklungsumgebung ohne Flutter/Dart-SDK geschrieben (WP-M3-23). Der Server-Teil ist durch InspectionStatusProjectionTest und InspectionStatusDriverHttpTest nachgewiesen.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/pruefung/pruefstatus.dart';
import 'package:taktovia_driver/run/driver_run.dart';

void main() {
  final DateTime t = DateTime.utc(2026, 11, 12, 8, 5);

  testWidgets('sechs_werte_mit_text_symbol_und_datenstand', (WidgetTester tester) async {
    expect(Pruefstatus.values.map((Pruefstatus s) => s.label).toSet().length, 6);
    expect(Pruefstatus.values.map((Pruefstatus s) => s.icon).toSet().length, 6, reason: 'je Wert ein eigenes Symbol, nie nur Farbe');
    for (final Pruefstatus s in Pruefstatus.values) {
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: PruefstatusAnzeige(stand: PruefstatusStand(status: s, revision: 1, asOfUtc: t)))));
      expect(find.text('Pruefstatus: ${s.label}'), findsOneWidget);
      expect(find.byIcon(s.icon), findsOneWidget);
      expect(find.text('Stand 08:05 UTC'), findsOneWidget);
    }
    // unterbrochener Kanal: Veraltet-Kennzeichnung im Text
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: PruefstatusAnzeige(stand: PruefstatusStand(status: Pruefstatus.passed, revision: 1, asOfUtc: t), moeglicherweiseVeraltet: true))));
    expect(find.text('Stand 08:05 UTC, moeglicherweise veraltet'), findsOneWidget);
  });

  test('aelterer_stand_ueberschreibt_hoehere_revision_nicht_und_server_json_wird_gelesen', () {
    final PruefstatusStand blocked = PruefstatusStand(status: Pruefstatus.blocked, revision: 7, asOfUtc: t);
    final PruefstatusStand old = PruefstatusStand(status: Pruefstatus.passed, revision: 5, asOfUtc: t);
    expect(blocked.adopt(old).status, Pruefstatus.blocked);
    expect(old.adopt(blocked).status, Pruefstatus.blocked);
    expect(Pruefstatus.fromCode('UNBEKANNT'), Pruefstatus.pending);
    final DriverRun run = DriverRun.fromJson(<String, Object?>{
      'shift_id': 's', 'plan_revision': null, 'plan_as_of': '2026-11-12T08:05:00Z', 'shift_valid': true, 'release_state': 'BLOCKED', 'stops': <Object?>[],
      'inspection_status': 'UNCLEAR', 'inspection_status_revision': 9,
    });
    expect(Pruefstatus.fromCode(run.inspectionStatus), Pruefstatus.unclear);
    expect(run.inspectionStatusRevision, 9);
  });
}
