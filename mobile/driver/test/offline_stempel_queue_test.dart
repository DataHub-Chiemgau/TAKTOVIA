// WP-M4-12 / LH-6.24-TIM-003: Offline-Stempel in der lokalen Queue mit stabiler device_event_id und einmaliger Uebernahme.
// NICHT AUSGEFUEHRT: Flutter/Dart ist in dieser Umgebung nicht lauffaehig; die Tests sind ungeprueft. Die Serverseite (genau einmal, REVIEW_REQUIRED, 72-h-unabhaengige
// Deduplikation, parallele Laeufe) ist durch TimeEventOfflineSyncTest und TimeEventStempelnApiTest mit echter Datenbank belegt.
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/offline/models.dart';
import 'package:taktovia_driver/offline/sync_engine.dart';
import 'package:taktovia_driver/zeit/work_time.dart';

import 'zeit_fakes.dart';

void main() {
  test('queue_haelt_device_event_id_stabil', () async {
    final Rig r = Rig();
    r.server.offline = true;
    final QueuedEvent kommen = await r.controller.stamp(StampKind.workStart);
    r.advance(const Duration(hours: 1));
    final QueuedEvent gehen = await r.controller.stamp(StampKind.workEnd);
    // mehrere gescheiterte Laeufe aendern die Ereignis-ID und die Erfassungszeit (Geraeteuhr) nicht
    for (int i = 0; i < 3; i++) {
      r.advance(const Duration(minutes: 1));
      final SyncResult s = await r.engine.sync();
      expect(s.stoppedBy, SyncStop.network);
    }
    final List<QueuedEvent> queued = await r.store.all();
    expect(queued.map((QueuedEvent e) => e.deviceEventId).toList(), <String>[kommen.deviceEventId, gehen.deviceEventId]);
    expect(queued.every((QueuedEvent e) => e.state == EventState.pending), isTrue, reason: 'Status „nicht synchronisiert“');
    expect(queued.first.capturedAtUtc, DateTime.utc(2026, 11, 12, 6));
    expect((await r.controller.status()).unsynced, 2);
    // Wiederverbindung: beide Stempel werden genau einmal uebernommen, als offline gekennzeichnet
    r.server.offline = false;
    final SyncResult ok = await r.engine.sync();
    expect(ok.applied, 2);
    expect(r.server.rows.keys.toList(), <String>[kommen.deviceEventId, gehen.deviceEventId]);
    expect(r.server.bodies.every((Map<String, Object?> b) => b['origin'] == 'OFFLINE'), isTrue);
    expect(r.server.bodies.first['device_recorded_at'], '2026-11-12T06:00:00.000Z');
    expect((await r.controller.status()).unsynced, 0);
  });

  test('verlorene Antwort: Wiederholung liefert DUPLICATE, keine zweite Zeile', () async {
    final Rig r = Rig();
    r.server.offline = true;
    final QueuedEvent e = await r.controller.stamp(StampKind.workStart);
    r.server.offline = false;
    r.server.loseNextResponse = true;
    expect((await r.engine.sync()).stoppedBy, isNot(SyncStop.none));
    r.advance(const Duration(minutes: 2));
    final SyncResult again = await r.engine.sync();
    expect(again.duplicate + again.applied, 1);
    expect(r.server.rows.length, 1);
    expect(r.server.rows.keys.single, e.deviceEventId);
    expect((await r.store.all()).single.state, EventState.synced);
  });

  test('unplausible Geraetezeit: REVIEW_REQUIRED bleibt als „zu pruefen“ sichtbar, nichts wird korrigiert', () async {
    final Rig r = Rig();
    r.server.offline = true;
    // Geraeteuhr 3 Stunden vorgestellt
    final Rig skew = Rig(start: DateTime.utc(2026, 11, 12, 9));
    skew.server.serverNow = () => DateTime.utc(2026, 11, 12, 6);
    skew.server.offline = true;
    final QueuedEvent e = await skew.controller.stamp(StampKind.workStart);
    skew.server.offline = false;
    final SyncResult s = await skew.engine.sync();
    expect(s.reviewRequired, 1);
    final QueuedEvent stored = (await skew.store.all()).single;
    expect(stored.state, EventState.reviewRequired);
    expect(stored.capturedAtUtc, DateTime.utc(2026, 11, 12, 9), reason: 'Gerätezeit unverändert');
    expect(skew.server.rows[e.deviceEventId]!['occurred_at'], '2026-11-12T09:00:00.000Z');
    expect((await skew.controller.status()).needsReview, 1);
    expect(r.server.rows, isEmpty);
  });
}
