import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/run/driver_run.dart';
import 'package:taktovia_driver/run/run_list.dart';

import 'run_fakes.dart';

/// WP-M3-26 / LH-6.6-DRV-002, UI-10: Zustaende der Stoppliste. NICHT AUSGEFUEHRT (Flutter war in der Erstellungsumgebung nicht verfuegbar); die Logik ist reines Dart ohne Widgets.
void main() {
  RunListController controller(ScriptedRunApi api, MemoryRunSnapshotStore store, DateTime Function() now) =>
      RunListController(api: api, store: store, shiftId: 's-1', now: now);

  test('leer_fehler_offline_unterscheidbar_mit_plan_stand', () async {
    final ScriptedRunApi api = ScriptedRunApi();
    final MemoryRunSnapshotStore store = MemoryRunSnapshotStore();
    DateTime clock = DateTime.utc(2026, 12, 1, 7);
    final RunListController c = controller(api, store, () => clock);

    // Liste: Stopps in Reihenfolge der Antwort, Plan-Stand (Revision und Zeit) sichtbar, naechster Handlungsschritt = erster Stopp
    api.script.add(RunReply(200, '"3"', runOf(runJson())));
    RunListState s = await c.refresh();
    expect(s.view, RunListView.list);
    expect(s.planRevision, 3);
    expect(s.fetchedAt, clock);
    expect(s.run!.stops.map((RunStop e) => e.routeStopId), <String>['a', 'b']);
    expect(s.nextStop!.routeStopId, 'a');
    // fehlt die Prognose, wird das ausgewiesen statt eine Zeit zu erfinden
    expect(RunListState.forecastText(s.run!.stops[1], 'keine Prognose', (DateTime d) => d.toIso8601String()), 'keine Prognose');
    expect(RunListState.forecastText(s.run!.stops[0], 'keine Prognose', (DateTime d) => d.toIso8601String()), isNot('keine Prognose'));

    // Offline: derselbe letzte Stand mit Zeit, eigener Zustand
    clock = DateTime.utc(2026, 12, 1, 7, 5);
    api.script.add(const SocketException('offline'));
    s = await c.refresh();
    expect(s.view, RunListView.offline);
    expect(s.planRevision, 3);
    expect(s.fetchedAt, DateTime.utc(2026, 12, 1, 7));
    expect(s.run!.stops.length, 2);

    // Fehler (Abruf gescheitert): eigener Zustand, letzter Stand mit Zeit und Revision, Fehlercode ohne Freitext des Servers
    api.script.add(const RunReply(503, null, null, code: 'SERVICE_UNAVAILABLE'));
    s = await c.refresh();
    expect(s.view, RunListView.error);
    expect(s.errorCode, 'SERVICE_UNAVAILABLE');
    expect(s.planRevision, 3);
    expect(s.run!.stops.length, 2);

    // bedingter Abruf: der ETag der letzten Antwort wird mitgesendet, 304 behaelt den Plan-Stand
    api.script.add(const RunReply(304, '"3"', null));
    s = await c.refresh();
    expect(api.seenEtags.last, '"3"');
    expect(s.view, RunListView.list);
    expect(s.planRevision, 3);

    // Leerzustand: Schicht ohne Auftraege; der lokale Stand wird geloescht, Zustand ist weder Fehler noch Offline
    api.script.add(RunReply(200, '"1"', runOf(runJson(revision: 1, stops: <Map<String, Object?>>[]))));
    s = await c.refresh();
    expect(s.view, RunListView.empty);
    expect(s.nextStop, isNull);
    expect(await store.read('s-1'), isNull);

    // alle vier Zustaende sind verschieden
    expect(<RunListView>{RunListView.list, RunListView.offline, RunListView.error, RunListView.empty}.length, 4);
  });

  test('offline_ohne_gespeicherten_stand_und_wiederherstellung_nach_neustart', () async {
    final ScriptedRunApi api = ScriptedRunApi();
    final MemoryRunSnapshotStore store = MemoryRunSnapshotStore();
    final RunListController first = controller(api, store, () => DateTime.utc(2026, 12, 1, 7));
    api.script.add(const SocketException('offline'));
    RunListState s = await first.refresh();
    expect(s.view, RunListView.offline);
    expect(s.run, isNull, reason: 'ohne Stand wird kein Plan erfunden');
    api.script.add(RunReply(200, '"3"', runOf(runJson())));
    await first.refresh();
    // neuer Start der App: der gespeicherte Stand steht offline zur Verfuegung
    final ScriptedRunApi api2 = ScriptedRunApi();
    final RunListController second = controller(api2, store, () => DateTime.utc(2026, 12, 1, 8));
    await second.restore();
    api2.script.add(const SocketException('offline'));
    s = await second.refresh();
    expect(s.view, RunListView.offline);
    expect(s.planRevision, 3);
    expect(s.run!.stops.length, 2);
  });
}
