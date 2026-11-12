// WP-M4-11 / LH-6.24-TIM-002: Stempeln in der Fahrer-App.
// NICHT AUSGEFUEHRT: Flutter/Dart ist in dieser Umgebung nicht lauffaehig; die Tests sind ungeprueft. Die Serverseite ist durch Backend-Tests mit echter Datenbank belegt
// (TimeEventStempelnTest, TimeEventStempelnApiTest: AT-45 mit echter Vor-Schicht-Checkliste).
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/offline/models.dart';
import 'package:taktovia_driver/zeit/work_time.dart';
import 'package:taktovia_driver/zeit/work_time_screen.dart';

import 'zeit_fakes.dart';

class Moving implements WorkTimeMovement {
  Moving(this.vehicleMoving);
  @override
  bool vehicleMoving;
}

Widget host(WorkTimeController c, WorkTimeMovement m, {bool released = false}) => MaterialApp(home: Scaffold(body: WorkTimeScreen(controller: c, movement: m, releaseGranted: released)));

void main() {
  test('at45_stempeln_pause_gehen', () async {
    final Rig r = Rig();
    // Kommen vor jeder Fahrzeugfreigabe und vor Fahrtbeginn: die App kennt dafuer nur /v1/time-events
    await r.controller.stamp(StampKind.workStart);
    expect((await r.controller.status()).phase, WorkPhase.working);
    r.advance(const Duration(hours: 2));
    await r.controller.stamp(StampKind.breakStart);
    expect((await r.controller.status()).phase, WorkPhase.onBreak);
    r.advance(const Duration(minutes: 30));
    await r.controller.stamp(StampKind.breakEnd);
    r.advance(const Duration(hours: 5));
    await r.controller.stamp(StampKind.workEnd);
    expect((await r.controller.status()).phase, WorkPhase.idle);
    expect(r.server.paths.toSet(), <String>{'/v1/time-events'});
    expect(r.server.rows.values.map((Map<String, Object?> e) => e['kind']).toList(), <Object?>['WORK_START', 'BREAK_START', 'BREAK_END', 'WORK_END']);
    expect(r.server.bodies.every((Map<String, Object?> b) => !b.containsKey('person_ref')), isTrue, reason: 'die Person ist immer die angemeldete Person');
    expect(r.server.bodies.every((Map<String, Object?> b) => b['origin'] == 'ONLINE'), isTrue);
  });

  test('doppeltes Einstempeln wird lokal abgewiesen und nichts erfasst', () async {
    final Rig r = Rig();
    await r.controller.stamp(StampKind.workStart);
    await expectLater(r.controller.stamp(StampKind.workStart), throwsA(isA<StampNotAllowed>()));
    await expectLater(r.controller.stamp(StampKind.breakEnd), throwsA(isA<StampNotAllowed>()));
    expect(r.server.rows.length, 1);
    expect((await r.store.all()).length, 1);
  });

  test('Stempel ohne Sitzung (401) bleibt lokal gesichert und geht beim naechsten Lauf raus', () async {
    final Rig r = Rig();
    r.server.statusOverride = 401;
    final QueuedEvent e = await r.controller.stamp(StampKind.workStart);
    expect((await r.store.all()).single.state, EventState.pending);
    r.server.statusOverride = 0;
    await r.engine.sync();
    expect(r.server.rows.keys.single, e.deviceEventId);
  });

  testWidgets('kein_erfassungsdialog_waehrend_fahrbewegung_und_status_nicht_nur_farbe', (WidgetTester tester) async {
    final Rig r = Rig();
    await r.controller.stamp(StampKind.workStart);
    final Moving moving = Moving(true);
    await tester.pumpWidget(host(r.controller, moving));
    await tester.pumpAndSettle();
    // Status als Text (und Symbol), nicht nur als Farbe
    expect(find.textContaining('Arbeitszeit: gestartet'), findsOneWidget);
    expect(find.byIcon(Icons.play_circle_outline), findsOneWidget);
    // Einsatzfreigabe getrennt vom Arbeitszeitbeginn sichtbar
    expect(find.textContaining('Einsatzfreigabe: noch nicht erteilt'), findsOneWidget);
    // Touchflaechen mindestens 44 x 44
    for (final String label in <String>['Pause beginnen', 'Gehen']) {
      final Size s = tester.getSize(find.widgetWithText(InkWell, label));
      expect(s.width >= 44 && s.height >= 44, isTrue, reason: label);
    }
    // waehrend der Fahrt: Gehen oeffnet keinen Dialog und erfasst nichts
    await tester.tap(find.widgetWithText(InkWell, 'Gehen'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    expect(find.byKey(const ValueKey<String>('worktime-hint')), findsOneWidget);
    expect(r.server.rows.length, 1, reason: 'kein Stempel waehrend der Fahrt');
    // nach dem Halt: Bestaetigungsdialog fuer Gehen
    moving.vehicleMoving = false;
    await tester.tap(find.widgetWithText(InkWell, 'Gehen'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
  });
}
