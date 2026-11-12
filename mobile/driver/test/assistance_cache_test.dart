import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/run/assistance_cache.dart';
import 'package:taktovia_driver/run/driver_run.dart';
import 'package:taktovia_driver/run/run_list.dart';

import 'run_fakes.dart';

/// WP-M3-32 / LH-6.6-DRV-008: nach Fahrtabschluss und Schichtende sind Unterstuetzungshinweise weder abrufbar noch im lokalen Cache. NICHT AUSGEFUEHRT (Flutter war nicht verfuegbar).
void main() {
  final Map<String, Object?> needy = <String, Object?>{'wheelchair_required': true, 'boarding_assist': true, 'alighting_assist': false};

  test('lokaler_speicher_nach_schichtende_ohne_merkmale', () async {
    final MemoryRunSnapshotStore store = MemoryRunSnapshotStore();
    final ScriptedRunApi api = ScriptedRunApi();
    final RunListController c = RunListController(api: api, store: store, shiftId: 's-1', now: () => DateTime.utc(2026, 12, 1, 7));
    api.script.add(RunReply(200, '"3"', runOf(runJson(stops: <Map<String, Object?>>[stopJson('a', assistance: needy), stopJson('b', sequence: 2)]))));
    final RunListState s = await c.refresh();
    expect(s.run!.stops.first.assistance!.wheelchairRequired, isTrue);
    expect(s.run!.stops.first.assistance.toString(), 'StopAssistance(redacted)', reason: 'Werte stehen nie in toString');
    expect(await store.read('s-1'), contains('"assistance"'), reason: 'der Stand mit Hinweis liegt waehrend der Schicht lokal');

    // Schichtende (Server: shift_valid=false, keine Stopps): der lokale Stand wird geloescht, danach keine Merkmale mehr im Speicher
    api.script.add(RunReply(200, '"3"', runOf(runJson(valid: false, stops: <Map<String, Object?>>[]))));
    final RunListState ended = await c.refresh();
    expect(ended.view, RunListView.empty);
    expect(await store.read('s-1'), isNull);
    expect(store.data.values.any((String v) => v.contains('assistance')), isFalse);

    // Fahrtabschluss: der Server liefert die Stopps ohne Merkmale, der gespeicherte Stand traegt keine mehr
    api.script.add(RunReply(200, '"4"', runOf(runJson(revision: 4, stops: <Map<String, Object?>>[stopJson('a', assistance: needy)]))));
    await c.refresh();
    api.script.add(RunReply(200, '"5"', runOf(runJson(revision: 5, stops: <Map<String, Object?>>[stopJson('a')]))));
    await c.refresh();
    expect(jsonDecode((await store.read('s-1'))!).toString().contains('assistance'), isFalse);

    // explizites Aufraeumen (Schichtende lokal erkannt) und Sicherheitsnetz nach Absturz
    final AssistanceCache cache = AssistanceCache(store);
    await store.write('s-2', jsonEncode(runJson(stops: <Map<String, Object?>>[stopJson('x', assistance: needy)])));
    expect(await cache.purgeAll(), 1);
    expect(store.data.containsKey('s-2'), isFalse);
    await c.clear();
    await cache.onShiftEnded('s-1');
    expect(store.data, isEmpty);
  });
}
