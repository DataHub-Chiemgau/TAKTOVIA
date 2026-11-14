// NICHT AUSGEFUEHRT: in der Erstellungsumgebung war kein Dart/Flutter-SDK ausfuehrbar (WP-M5-09). Der gleiche Zustandsautomat ist in den Web-Anwendungen
// (web/design-system/src/network/connection.test.tsx, web/passenger und web/operator) ausgefuehrt und gruen.
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/net/connection_state.dart';
import 'package:taktovia_driver/offline/models.dart';
import 'package:taktovia_driver/offline/offline_actions.dart';
import 'package:taktovia_driver/ui/strings.dart';

DateTime t0 = DateTime.utc(2026, 11, 11, 8);

DutySnapshot snapshot() => DutySnapshot(
      dutyId: 'd-1',
      planRevision: 7,
      asOfUtc: DateTime.utc(2026, 11, 11, 7, 45),
      stops: const <StopEntry>[StopEntry(stopId: 's-1', sequence: 1, label: 'Altmarkt', status: 'planned')],
    );

ConnectionTracker offline() {
  final ConnectionTracker t = ConnectionTracker();
  t.linkChanged(false, t0);
  t.tick(t0.add(const Duration(seconds: 5)));
  return t;
}

void main() {
  test('offline_zeigt_letzten_dienst_mit_datenstand_ohne_echtzeit', () {
    final ConnectionTracker t = offline();
    final DutySnapshot snap = snapshot();
    final ConnectionView v = buildConnectionView(t, snapshot: snap);
    expect(v.state, DriverConnection.offline);
    expect(v.showEta, isFalse, reason: 'keine laufende ETA');
    expect(v.showLiveBadge, isFalse, reason: 'keine „live“-Kennzeichnung');
    expect(v.noLiveText, contains('07:45 UTC'), reason: 'Datenstand des Plans');
    expect(v.bannerText.toLowerCase(), isNot(contains('live')));
    expect(snap.standLabel, contains('07:45'));
    expect(snap.stops.single.label, 'Altmarkt', reason: 'die Stoppliste bleibt sichtbar');
  });

  test('offline_gesperrte_aktionen_nennen_grund_und_naechsten_schritt', () {
    final ConnectionTracker t = offline();
    for (final DriverAction a in <DriverAction>[DriverAction.requestOperationalReadiness, DriverAction.submitInspectionForRelease, DriverAction.liftVehicleBlock, DriverAction.bindBooking]) {
      final ActionGate g = gateAction(a, t);
      expect(g.allowed, isFalse, reason: '$a');
      final String text = g.text(Strings.de)!;
      expect(text, contains('Naechster Schritt'), reason: '$a nennt den naechsten Schritt');
      expect(text.length, greaterThan(40));
    }
    expect(gateAction(DriverAction.reportDamage, t).allowed, isTrue);
    expect(gateAction(DriverAction.completeInspectionLocally, t).allowed, isTrue);
  });

  test('zustandswechsel_ueber_live_region_nicht_nur_farbe', () {
    final ConnectionTracker t = offline();
    expect(t.announcements.map((AnnouncedChange a) => a.state), <DriverConnection>[DriverConnection.offline]);
    final ConnectionView v = buildConnectionView(t);
    expect(v.liveRegionText, startsWith('Offline'));
    expect(v.icon, 'cloud_off', reason: 'Symbol zusaetzlich zum Text');
    expect(v.bannerText, isNotEmpty);
    t.linkChanged(true, t0.add(const Duration(seconds: 10)));
    t.serverStateLoaded(t0.add(const Duration(seconds: 12)));
    expect(t.announcements.map((AnnouncedChange a) => a.state), <DriverConnection>[DriverConnection.offline, DriverConnection.syncing, DriverConnection.online]);
    for (final DriverConnection s in DriverConnection.values) {
      expect(kConnectionIcons[s], isNotNull, reason: 'jeder Zustand hat ein Symbol');
      expect(Strings.de.of('connection.${s.name}'), isNot('connection.${s.name}'));
    }
  });

  test('wiederverbindung_offline_erst_nach_serverstand_ausstehende_wird_uebertragen', () {
    final ConnectionTracker t = offline();
    t.setPending(2);
    t.linkChanged(true, t0.add(const Duration(seconds: 10)));
    expect(t.state, DriverConnection.syncing, reason: 'Verbindung da, Serverstand noch nicht geladen');
    expect(t.serverRestricted, isTrue);
    expect(gateAction(DriverAction.submitInspectionForRelease, t).allowed, isFalse);
    expect(gateAction(DriverAction.submitInspectionForRelease, t).text(Strings.de), startsWith('Der aktuelle Stand wird noch geladen.'));
    final ConnectionView v = buildConnectionView(t);
    expect(v.bannerText, contains('2 Ereignisse werden uebertragen'));
    expect(v.showLiveBadge, isFalse);
    t.serverStateLoaded(t0.add(const Duration(seconds: 12)));
    expect(t.state, DriverConnection.syncing, reason: 'ausstehende Ereignisse werden noch uebertragen');
    expect(t.serverRestricted, isFalse);
    t.setPending(0);
    expect(t.state, DriverConnection.online);
  });

  test('instabile_verbindung_kein_flackern_keine_doppelten_aktionen', () {
    final ConnectionTracker t = ConnectionTracker(offlineDelay: const Duration(seconds: 3));
    DateTime now = t0;
    for (int i = 0; i < 10; i++) {
      t.linkChanged(false, now);
      now = now.add(const Duration(seconds: 1));
      t.tick(now);
      t.linkChanged(true, now);
      now = now.add(const Duration(seconds: 1));
      t.tick(now);
    }
    expect(t.state, DriverConnection.online);
    expect(t.announcements, isEmpty, reason: 'keine Ansage bei kurzen Aussetzern');
    expect(gateAction(DriverAction.submitInspectionForRelease, t).allowed, isTrue);
    // dauerhafter Verlust: genau eine Ansage, auch bei weiteren Verlustmeldungen
    t.linkChanged(false, now);
    t.linkChanged(false, now.add(const Duration(seconds: 1)));
    t.tick(now.add(const Duration(seconds: 4)));
    t.tick(now.add(const Duration(seconds: 5)));
    expect(t.announcements.length, 1);
    expect(t.state, DriverConnection.offline);
  });

  test('offline_checkin_trifft_storno_zeigt_klaerfall', () {
    final QueuedEvent checkIn = QueuedEvent(
      deviceEventId: 'e-1',
      type: 'checkIn',
      payload: const <String, Object?>{'stopId': 's-1'},
      baseRevision: 7,
      capturedAtUtc: t0,
      integrity: '',
      conflictHint: true,
    );
    expect(conflictNoticeKeys(<QueuedEvent>[checkIn]), <String>['connection.conflict.checkInVsCancel']);
    final QueuedEvent review = checkIn.copyWith(state: EventState.reviewRequired, reviewReason: ReviewReason.checkInVsCancellation, conflictHint: false);
    expect(conflictNoticeKeys(<QueuedEvent>[review]), hasLength(1));
    final ConnectionTracker t = ConnectionTracker();
    t.setConflicts(1);
    expect(t.state, DriverConnection.conflict);
    expect(buildConnectionView(t).bannerText, contains('Synchronisationskonflikt'));
    expect(Strings.de.of('connection.conflict.checkInVsCancel'), contains('still ueberschrieben'));
  });
}
