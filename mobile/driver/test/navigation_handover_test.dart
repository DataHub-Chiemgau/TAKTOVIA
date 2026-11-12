import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/run/driver_run.dart';
import 'package:taktovia_driver/run/navigation_handover.dart';

import 'run_fakes.dart';

/// Anbieter-Testdouble: merkt sich jede Uebergabe; optional greift [onCall] ein (z. B. eine Planrevision waehrend der Uebergabe).
class FakeProvider implements NavigationProvider {
  FakeProvider(this.id, {this.onCall});
  @override
  final String id;
  final void Function(NavigationTarget)? onCall;
  final List<NavigationTarget> calls = <NavigationTarget>[];
  @override
  Future<bool> navigateTo(NavigationTarget target) async {
    calls.add(target);
    onCall?.call(target);
    return true;
  }
}

/// WP-M3-28 / LH-6.6-DRV-003: Navigationsziel = naechster disponierter Stopp der gueltigen Stoppliste. NICHT AUSGEFUEHRT (Flutter war nicht verfuegbar); reines Dart.
void main() {
  DriverRun run({int revision = 3, List<Map<String, Object?>>? stops, bool valid = true}) => runOf(runJson(revision: revision, valid: valid, stops: stops));

  test('ziel_entspricht_koordinate_und_revision_des_naechsten_stopps', () async {
    DriverRun? current = run();
    final FakeProvider p = FakeProvider('geraet');
    final NavigationHandover h = NavigationHandover(provider: p, currentRun: () => current);
    expect(h.available(), isTrue);
    final NavigationTarget? t = await h.start();
    expect(p.calls.length, 1);
    final NavigationTarget got = p.calls.single;
    expect(t, same(got));
    expect(got.routeStopId, 'a');
    expect(got.latitude, 51.05);
    expect(got.longitude, 13.74);
    expect(got.stopRevisionId, 'sr-a');
    expect(got.planRevision, 3);
    expect(got.name, 'Marktplatz');
    expect(NavigationHandover.targetFor(current)!.routeStopId, 'a');
  });

  test('planaenderung_uebergibt_neues_ziel_und_weist_hin', () async {
    DriverRun? current = run();
    final FakeProvider p = FakeProvider('geraet');
    final NavigationHandover h = NavigationHandover(provider: p, currentRun: () => current);
    await h.start();
    expect(h.changedHint, isFalse);
    // Planaenderung: der naechste Stopp ist ein anderer (neuer Haltepunkt, neue Revision)
    current = run(revision: 4, stops: <Map<String, Object?>>[stopJson('c', lat: 51.10, lon: 13.80, name: 'Rathaus'), stopJson('b', sequence: 2, kind: 'DROPOFF', name: 'Bahnhof')]);
    final NavigationTarget? neu = await h.onRunUpdated();
    expect(neu!.routeStopId, 'c');
    expect(neu.planRevision, 4);
    expect(h.changedHint, isTrue, reason: 'die App weist auf das geaenderte Ziel hin');
    expect(p.calls.map((NavigationTarget t) => t.routeStopId), <String>['a', 'c']);
    // gleiches Ziel bei neuer Planrevision: keine neue Uebergabe, kein Hinweis
    final bool hintBefore = h.changedHint;
    current = run(revision: 5, stops: <Map<String, Object?>>[stopJson('c', lat: 51.10, lon: 13.80, name: 'Rathaus')]);
    expect(await h.onRunUpdated(), isNull);
    expect(p.calls.length, 2);
    expect(h.changedHint, hintBefore);
  });

  test('stopp_abgeschlossen_naechster_stopp_wird_ziel', () async {
    final DriverRun current = run();
    final FakeProvider p = FakeProvider('geraet');
    final NavigationHandover h = NavigationHandover(provider: p, currentRun: () => current);
    await h.start();
    expect(p.calls.single.routeStopId, 'a');
    await h.start(completed: <String>{'a'});
    expect(p.calls.last.routeStopId, 'b');
    expect(p.calls.last.name, 'Bahnhof');
    // alles abgeschlossen: keine Navigation mehr
    expect(h.available(completed: <String>{'a', 'b'}), isFalse);
  });

  test('ohne_stoppliste_keine_navigation', () async {
    final FakeProvider p = FakeProvider('geraet');
    DriverRun? current;
    final NavigationHandover h = NavigationHandover(provider: p, currentRun: () => current);
    expect(h.available(), isFalse, reason: 'keine Stoppliste');
    expect(await h.start(), isNull);
    current = run(stops: <Map<String, Object?>>[]);
    expect(h.available(), isFalse, reason: 'kein Stopp');
    current = run(valid: false);
    expect(h.available(), isFalse, reason: 'keine gueltige Schicht');
    current = runOf(runJson(release: 'NOT_RELEASED'));
    expect(h.available(), isFalse, reason: 'ohne Einsatzfreigabe');
    expect(await h.start(), isNull);
    expect(p.calls, isEmpty);
  });

  test('planrevision_waehrend_uebergabe_kein_veraltetes_ziel', () async {
    DriverRun? current = run();
    late FakeProvider p;
    bool changed = false;
    p = FakeProvider('geraet', onCall: (NavigationTarget t) {
      if (!changed) {
        // waehrend der Uebergabe des Ziels aus Revision 3 trifft Revision 4 mit anderem naechsten Stopp ein
        changed = true;
        current = run(revision: 4, stops: <Map<String, Object?>>[stopJson('c', lat: 51.10, lon: 13.80, name: 'Rathaus')]);
      }
    });
    final NavigationHandover h = NavigationHandover(provider: p, currentRun: () => current);
    final NavigationTarget? handed = await h.start();
    expect(p.calls.map((NavigationTarget t) => t.planRevision), <int?>[3, 4]);
    expect(handed!.routeStopId, 'c');
    expect(h.changedHint, isTrue);
    // nach Kenntnis der neuen Revision wird nie mehr ein Ziel der alten uebergeben
    final int callsBefore = p.calls.length;
    await h.start();
    expect(p.calls.skip(callsBefore).every((NavigationTarget t) => t.planRevision == 4), isTrue);
  });

  test('uebergabe_schnittstelle_mit_ersatzanbieter', () async {
    final DriverRun current = run();
    final FakeProvider a = FakeProvider('anbieter-a');
    final FakeProvider b = FakeProvider('anbieter-b');
    final NavigationHandover ha = NavigationHandover(provider: a, currentRun: () => current);
    final NavigationHandover hb = NavigationHandover(provider: b, currentRun: () => current);
    await ha.start();
    await hb.start();
    // Anbieterwechsel ohne Aenderung der Stopplistenlogik: dasselbe Ziel bei beiden Anbietern
    expect(a.calls.single.routeStopId, b.calls.single.routeStopId);
    expect(a.calls.single.latitude, b.calls.single.latitude);
    expect(a.calls.single.stopRevisionId, b.calls.single.stopRevisionId);
    // der Standardanbieter ist eine geo:-URI der Geraete-Navigation
    final Uri uri = GeoUriNavigationProvider.uriFor(a.calls.single);
    expect(uri.scheme, 'geo');
    expect(uri.toString(), startsWith('geo:51.050000,13.740000'));
    Uri? opened;
    final GeoUriNavigationProvider geo = GeoUriNavigationProvider((Uri u) async {
      opened = u;
      return true;
    });
    expect(await NavigationHandover(provider: geo, currentRun: () => current).start(), isNotNull);
    expect(opened, uri);
  });
}
