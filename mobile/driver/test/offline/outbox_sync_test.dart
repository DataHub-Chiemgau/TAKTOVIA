// Reine Dart-Logiktests des Spikes WP-M0-28 (ohne Geraet, ohne SQLite).
// NICHT AUSGEFUEHRT: in der Erstellungsumgebung war kein Dart/Flutter-SDK ausfuehrbar.
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/offline/fake_driver_event_server.dart';
import 'package:taktovia_driver/offline/memory_store.dart';
import 'package:taktovia_driver/offline/models.dart';
import 'package:taktovia_driver/offline/offline_queue.dart';
import 'package:taktovia_driver/offline/sync_engine.dart';

class MutableClock {
  MutableClock(this.now);
  DateTime now;
  DateTime call() => now;
}

final List<int> key = List<int>.generate(32, (int i) => i);

class Fixture {
  Fixture({int planRevision = 1})
      : deviceClock = MutableClock(DateTime.utc(2026, 11, 11, 8)),
        serverClock = MutableClock(DateTime.utc(2026, 11, 11, 8)) {
    server = FakeDriverEventServer(clock: serverClock.call, planRevision: planRevision);
    queue = OfflineQueue(store: store, snapshots: store, integrityKey: key, clock: deviceClock.call);
  }
  final MutableClock deviceClock;
  final MutableClock serverClock;
  final InMemoryOutboxStore store = InMemoryOutboxStore();
  late final FakeDriverEventServer server;
  late final OfflineQueue queue;

  SyncEngine engine(String owner, {int batchSize = 100, Duration lease = const Duration(seconds: 30)}) => SyncEngine(
        store: store,
        api: server,
        owner: owner,
        integrityKey: key,
        clock: deviceClock.call,
        batchSize: batchSize,
        lease: lease,
      );

  Future<void> snapshot(int revision) => queue.applyServerSnapshot(DutySnapshot(
        dutyId: 'duty-1',
        planRevision: revision,
        asOfUtc: deviceClock.now,
        stops: const <StopEntry>[
          StopEntry(stopId: 's1', sequence: 1, label: 'Halt 1', status: 'planned'),
        ],
      ));
}

void main() {
  test('Erfassung: eindeutige device_event_id, letzte Serverrevision, Erfassungszeit', () async {
    final Fixture f = Fixture();
    await f.snapshot(7);
    final QueuedEvent a = await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1'});
    final QueuedEvent b = await f.queue.record(type: 'departed', payload: <String, Object?>{'stopId': 's1'});
    expect(a.deviceEventId, isNot(b.deviceEventId));
    expect(a.baseRevision, 7);
    expect(a.capturedAtUtc, DateTime.utc(2026, 11, 11, 8));
    expect(a.state, EventState.pending);
    expect(await f.queue.unsyncedCount(), 2);
  });

  test('Online-Erfassung und Sync: jedes Ereignis genau einmal APPLIED, danach nichts mehr zu senden', () async {
    final Fixture f = Fixture();
    await f.snapshot(1);
    final QueuedEvent e = await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1'});
    final SyncResult r1 = await f.engine('A').sync();
    expect(r1.applied, 1);
    final SyncResult r2 = await f.engine('A').sync();
    expect(r2.applied + r2.duplicate, 0);
    expect(f.server.appliedCount(e.deviceEventId), 1);
    expect(await f.queue.unsyncedCount(), 0);
  });

  test('1.000 Offline-Ereignisse: Verlust 0, jedes genau einmal APPLIED', () async {
    final Fixture f = Fixture();
    await f.snapshot(1);
    f.server.online = false;
    for (int i = 0; i < 1000; i++) {
      await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1', 'n': i});
    }
    final SyncResult off = await f.engine('A', batchSize: 50).sync();
    expect(off.stoppedBy, SyncStop.network);
    expect(await f.queue.unsyncedCount(), 1000);

    f.server.online = true;
    final SyncResult on = await f.engine('A', batchSize: 50).sync();
    expect(on.applied, 1000);
    expect(f.server.distinctEvents, 1000);
    expect(f.server.allAppliedCounts.every((int c) => c == 1), isTrue);
    expect(await f.queue.unsyncedCount(), 0);
  });

  test('Verlorene Antwort: Wiederholung ergibt DUPLICATE, Wirkung bleibt einmalig', () async {
    final Fixture f = Fixture();
    await f.snapshot(1);
    final QueuedEvent e = await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1'});
    f.server.dropNextResponse = true;
    final SyncResult r1 = await f.engine('A').sync();
    expect(r1.stoppedBy, SyncStop.network);
    expect((await f.store.all()).single.state, EventState.pending);
    final SyncResult r2 = await f.engine('A').sync();
    expect(r2.duplicate, 1);
    expect(f.server.appliedCount(e.deviceEventId), 1);
    expect((await f.store.all()).single.state, EventState.synced);
  });

  test('Abbruch waehrend des Syncs (Lease laeuft ab): anderer Lauf uebernimmt, keine doppelte Wirkung', () async {
    final Fixture f = Fixture();
    await f.snapshot(1);
    for (int i = 0; i < 10; i++) {
      await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1', 'n': i});
    }
    // Lauf "A" beansprucht, wird aber hart beendet (kein complete/release).
    final List<QueuedEvent> orphaned = await f.store.claimBatch(
      owner: 'A',
      now: f.deviceClock.now,
      lease: const Duration(seconds: 30),
      limit: 10,
    );
    expect(orphaned, hasLength(10));
    // Vor Ablauf der Lease: nichts zu holen.
    expect((await f.engine('B').sync()).applied, 0);
    f.deviceClock.now = f.deviceClock.now.add(const Duration(seconds: 31));
    final SyncResult r = await f.engine('B').sync();
    expect(r.applied, 10);
    expect(f.server.allAppliedCounts.every((int c) => c == 1), isTrue);
  });

  test('Zwei Prozesse syncen gleichzeitig: keine doppelte Wirkung', () async {
    final Fixture f = Fixture();
    await f.snapshot(1);
    for (int i = 0; i < 200; i++) {
      await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1', 'n': i});
    }
    f.server.beforeProcess = () => Future<void>.delayed(Duration.zero);
    final List<SyncResult> rs = await Future.wait(<Future<SyncResult>>[
      f.engine('A', batchSize: 20).sync(),
      f.engine('B', batchSize: 20).sync(),
    ]);
    expect(rs[0].applied + rs[1].applied, 200);
    expect(f.server.distinctEvents, 200);
    expect(f.server.allAppliedCounts.every((int c) => c == 1), isTrue);
    expect(await f.queue.unsyncedCount(), 0);
  });

  test('Wiederholung waehrend laufender Uebertragung im selben Prozess teilt den Lauf', () async {
    final Fixture f = Fixture();
    await f.snapshot(1);
    await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1'});
    final SyncEngine e = f.engine('A');
    final Future<SyncResult> a = e.sync();
    final Future<SyncResult> b = e.sync();
    expect(identical(a, b), isTrue);
    await a;
    expect(f.server.allAppliedCounts.single, 1);
  });

  test('Abgelaufenes Token: Ereignisse bleiben in der Queue und gehen nach Erneuerung durch', () async {
    final Fixture f = Fixture();
    await f.snapshot(1);
    for (int i = 0; i < 3; i++) {
      await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1', 'n': i});
    }
    f.server.tokenValid = false;
    final SyncResult r1 = await f.engine('A').sync();
    expect(r1.stoppedBy, SyncStop.authExpired);
    expect((await f.store.all()).every((QueuedEvent e) => e.state == EventState.pending), isTrue);
    f.server.tokenValid = true;
    expect((await f.engine('A').sync()).applied, 3);
  });

  test('Veraltete Planrevision: REVIEW_REQUIRED, lokal nicht "synchronisiert"', () async {
    final Fixture f = Fixture(planRevision: 1);
    await f.snapshot(1);
    final QueuedEvent e = await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1'});
    f.server.planRevision = 2; // Disposition hat inzwischen umgeplant
    final SyncResult r = await f.engine('A').sync();
    expect(r.reviewRequired, 1);
    final QueuedEvent stored = (await f.store.all()).single;
    expect(stored.state, EventState.reviewRequired);
    expect(stored.reviewReason, ReviewReason.stalePlanRevision);
    expect(await f.queue.unsyncedCount(), 1);
    expect(f.server.appliedCount(e.deviceEventId), 0);
  });

  test('Verlorenes REVIEW_REQUIRED wird bei Wiederholung (DUPLICATE) nicht zu "synchronisiert"', () async {
    final Fixture f = Fixture(planRevision: 1);
    await f.snapshot(1);
    await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1'});
    f.server.planRevision = 2;
    f.server.dropNextResponse = true;
    await f.engine('A').sync();
    await f.engine('A').sync();
    expect((await f.store.all()).single.state, EventState.reviewRequired);
  });

  test('Klaerfall Check-in vs. Storno: lokaler Hinweis, Server entscheidet', () async {
    final Fixture f = Fixture();
    await f.snapshot(1);
    f.server.online = false;
    final QueuedEvent ci = await f.queue.record(type: 'checkIn', payload: <String, Object?>{'stopId': 's1'});
    // Server storniert den Halt, Geraet erfaehrt es per Snapshot (gleiche Revision) vor dem Sync.
    f.server.cancelledStopIds.add('s1');
    await f.queue.applyServerSnapshot(
      DutySnapshot(dutyId: 'duty-1', planRevision: 1, asOfUtc: f.deviceClock.now, stops: const <StopEntry>[]),
      cancelledStopIds: <String>{'s1'},
    );
    expect((await f.store.all()).single.conflictHint, isTrue);
    f.server.online = true;
    final SyncResult r = await f.engine('A').sync();
    expect(r.reviewRequired, 1);
    final QueuedEvent stored = (await f.store.all()).single;
    expect(stored.state, EventState.reviewRequired);
    expect(stored.reviewReason, ReviewReason.checkInVsCancellation);
    expect(f.server.appliedCount(ci.deviceEventId), 0);
  });

  test('Erfassungs- und Empfangszeit bleiben getrennt, auch bei verstellter Geraeteuhr', () async {
    final Fixture f = Fixture();
    await f.snapshot(1);
    f.deviceClock.now = DateTime.utc(2020, 1, 1); // Uhr verstellt
    final QueuedEvent e = await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1'});
    f.deviceClock.now = DateTime.utc(2026, 11, 11, 9);
    f.serverClock.now = DateTime.utc(2026, 11, 11, 9, 0, 5);
    await f.engine('A').sync();
    final QueuedEvent stored = (await f.store.all()).single;
    expect(stored.capturedAtUtc, DateTime.utc(2020, 1, 1));
    expect(stored.serverReceivedAtUtc, DateTime.utc(2026, 11, 11, 9, 0, 5));
    expect(stored.capturedAtUtc, e.capturedAtUtc);
    expect(stored.verifyIntegrity(key), isTrue);
  });

  test('Manipulierte Queue-Zeile: Quarantaene, wird nicht gesendet', () async {
    final Fixture f = Fixture();
    await f.snapshot(1);
    final QueuedEvent a = await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's1'});
    final QueuedEvent b = await f.queue.record(type: 'arrived', payload: <String, Object?>{'stopId': 's2'});
    f.store.tamperPayloadForTest(a.deviceEventId, <String, Object?>{'stopId': 'sX'});
    final SyncResult r = await f.engine('A').sync();
    expect(r.quarantined, 1);
    expect(r.applied, 1);
    expect(f.server.appliedCount(a.deviceEventId), 0);
    expect(f.server.appliedCount(b.deviceEventId), 1);
    expect((await f.store.all()).first.state, EventState.quarantined);
  });

  test('Offline-Lesestand: Dienst und Stoppliste mit Stand lesbar', () async {
    final Fixture f = Fixture();
    await f.snapshot(3);
    final DutySnapshot? s = await f.queue.readOffline();
    expect(s, isNotNull);
    expect(s!.stops.single.stopId, 's1');
    expect(s.standLabel, 'Stand von 2026-11-11 08:00 UTC');
    // Rundlauf ueber JSON, wie ihn die SQLite-Ablage nutzt.
    final DutySnapshot back = DutySnapshot.fromJson(s.toJson());
    expect(back.planRevision, 3);
    expect(back.standLabel, s.standLabel);
  });

  test('UUID v4 hat Format und ist eindeutig', () {
    final Set<String> ids = <String>{for (int i = 0; i < 1000; i++) generateUuidV4()};
    expect(ids, hasLength(1000));
    expect(ids.every((String s) => RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$').hasMatch(s)), isTrue);
  });
}
