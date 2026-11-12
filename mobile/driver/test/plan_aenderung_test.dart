import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/run/driver_run.dart';
import 'package:taktovia_driver/run/plan_change.dart';
import 'package:taktovia_driver/run/run_list.dart';

import 'run_fakes.dart';

/// WP-M3-27 / LH-6.6-DRV-004 (UI-10): Aenderungsanzeige (neu, entfallen, verschoben), kein Bestaetigungsdialog waehrend der Fahrbewegung, offline der letzte Plan mit Stand.
/// NICHT AUSGEFUEHRT (Flutter war in der Entwicklungsumgebung nicht verfuegbar); die Serverseite ist in PlanRevisionDeliveryTest/-HttpTest gegen die echte Anwendung belegt.
void main() {
  Map<String, Object?> changedRun({String state = 'NEW_UNCONFIRMED', bool required = true}) {
    final Map<String, Object?> j = runJson(revision: 5, stops: <Map<String, Object?>>[
      <String, Object?>{...stopJson('a'), 'change': 'MOVED', 'previous_planned_at': '2026-12-01T06:50:00Z'},
      <String, Object?>{...stopJson('c', sequence: 2, name: 'Rathaus'), 'change': 'NEW'},
    ]);
    return <String, Object?>{
      ...j,
      'revision_state': state,
      'acknowledgement_required': required,
      'base_revision': 3,
      'removed_stops': <Object?>[
        <String, Object?>{'stop_name': 'Bahnhof', 'kind': 'DROPOFF', 'planned_at': '2026-12-01T07:10:00Z', 'booking_ids': <Object?>['b-x']},
      ],
    };
  }

  test('aenderung_gekennzeichnet_neu_entfallen_verschoben', () {
    final MotionDetector motion = MotionDetector();
    final PlanChangePresenter p = PlanChangePresenter(motion: motion, now: () => DateTime.utc(2026, 12, 1, 7));
    final DriverRun run = DriverRun.fromJson(changedRun());
    final PlanChangeNotice n = p.notice(run);
    expect(n.added.map((RunStop s) => s.stopName), <String>['Rathaus']);
    expect(n.moved.single.previousPlannedAt, DateTime.utc(2026, 12, 1, 6, 50));
    expect(n.removed.single.stopName, 'Bahnhof');
    expect(n.acknowledgementRequired, isTrue);
    expect(p.view(run), PlanRevisionView.newUnconfirmed);
    expect(p.announcement(run), 'Planaenderung: 1 neu, 1 entfallen, 1 verschoben');
    // kleine, automatisch uebernommene Aenderung: angezeigt, keine Bestaetigung
    final DriverRun auto = DriverRun.fromJson(changedRun(state: 'AUTO_ADOPTED', required: false));
    expect(p.view(auto), PlanRevisionView.autoAdopted);
    expect(p.prompt(auto), AckPrompt.none);
    // der Server meldet eine neuere Revision (Weckruf), die App zeigt noch den alten Plan: „vergangen“, nie als aktuell
    expect(p.view(run, serverRevision: 6), PlanRevisionView.past);
    // alter Server ohne die neuen Felder: unveraenderter Plan
    expect(p.view(DriverRun.fromJson(runJson())), PlanRevisionView.current);
  });

  test('kein_bestaetigungsdialog_waehrend_fahrbewegung', () {
    DateTime now = DateTime.utc(2026, 12, 1, 7);
    final MotionDetector motion = MotionDetector();
    final PlanChangePresenter p = PlanChangePresenter(motion: motion, now: () => now);
    final DriverRun run = DriverRun.fromJson(changedRun());
    // ohne Positionsmessung: unbekannt, wie Bewegung behandelt (nie ein Dialog im Zweifel)
    expect(p.prompt(run), AckPrompt.deferredUntilStop);
    motion.onPosition(speedMetersPerSecond: 12.0, at: now);
    expect(motion.motion(now), Motion.moving);
    expect(p.prompt(run), AckPrompt.deferredUntilStop, reason: 'waehrend der Fahrt nur Ansage/Anzeige, Bestaetigung beim naechsten Halt');
    expect(p.announcement(run), isNotEmpty);
    // eine aeltere Messung ueberschreibt die neuere nie
    motion.onPosition(speedMetersPerSecond: 0.0, at: now.subtract(const Duration(seconds: 20)));
    expect(motion.motion(now), Motion.moving);
    // Halt: Dialog erlaubt
    now = now.add(const Duration(seconds: 5));
    motion.onPosition(speedMetersPerSecond: 0.1, at: now);
    expect(p.prompt(run), AckPrompt.showNow);
    // veraltete Messung (Funkloch): wieder unbekannt, kein Dialog
    now = now.add(const Duration(minutes: 2));
    expect(motion.motion(now), Motion.unknown);
    expect(p.prompt(run), AckPrompt.deferredUntilStop);
    // nichts zu bestaetigen: nie ein Dialog
    expect(p.prompt(DriverRun.fromJson(changedRun(state: 'CONFIRMED', required: false))), AckPrompt.none);
  });

  test('offline_zeigt_letzten_plan_mit_stand', () async {
    final MemoryRunSnapshotStore store = MemoryRunSnapshotStore();
    final ScriptedRunApi api = ScriptedRunApi();
    final RunListController c = RunListController(api: api, store: store, shiftId: 's-1', now: () => DateTime.utc(2026, 12, 1, 7, 30));
    api.script.add(RunReply(200, '"5"', DriverRun.fromJson(changedRun())));
    await c.refresh();
    api.script.add(const SocketException('offline'));
    final RunListState s = await c.refresh();
    expect(s.view, RunListView.offline);
    expect(s.run!.planRevision, 5);
    final String text = OfflinePlanText.of(s, (DateTime d) => '${d.hour}:${d.minute.toString().padLeft(2, '0')}');
    expect(text, 'Offline: Plan Stand 7:30, nicht aktuell');
    // die Anzeige ist „vergangen“, sobald der Server eine neuere Revision gemeldet hat
    final PlanChangePresenter p = PlanChangePresenter(motion: MotionDetector(), now: () => DateTime.utc(2026, 12, 1, 7, 31));
    expect(p.view(s.run!, serverRevision: 7), PlanRevisionView.past);
  });
}
