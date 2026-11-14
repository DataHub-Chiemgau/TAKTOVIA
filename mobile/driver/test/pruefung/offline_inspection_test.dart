// NICHT AUSGEFUEHRT: in der Erstellungsumgebung war kein Dart/Flutter-SDK ausfuehrbar (WP-M3-33). Die Serverseite ist durch OfflineInspectionSyncTest (Backend, echte Datenbank) belegt.
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/offline/memory_store.dart';
import 'package:taktovia_driver/offline/models.dart';
import 'package:taktovia_driver/offline/offline_actions.dart';
import 'package:taktovia_driver/offline/offline_queue.dart';
import 'package:taktovia_driver/offline/ports.dart';
import 'package:taktovia_driver/offline/sync_engine.dart';
import 'package:taktovia_driver/pruefung/inspection_start.dart';
import 'package:taktovia_driver/pruefung/known_defects.dart';
import 'package:taktovia_driver/pruefung/offline_inspection.dart';
import 'package:taktovia_driver/pruefung/release_gate.dart';

final List<int> key = List<int>.generate(32, (int i) => i);

List<InspectionStep> steps() => <InspectionStep>[
      for (int i = 1; i <= 3; i++) InspectionStep(code: 'S$i', title: 'Punkt $i', mandatory: true, safetyCritical: i == 1, answerKind: 'OK_DEFECT_NA'),
    ];

OfflineInspectionBundle bundle({KnownDefectList? defects, bool withDefects = true}) => OfflineInspectionBundle(
      vehicleId: 'v-1',
      templateRevisionId: 'r-1',
      steps: steps(),
      knownDefects: withDefects ? (defects ?? const KnownDefectList(defects: <KnownDefect>[], acknowledgementRequired: false, unacknowledgedCount: 0)) : null,
      cachedAtUtc: DateTime.utc(2026, 11, 11, 7),
    );

List<OfflineInspectionAnswer> answers({String s1 = 'OK'}) => <OfflineInspectionAnswer>[
      OfflineInspectionAnswer(stepCode: 'S1', answer: s1, note: s1 == 'DEFECT' ? 'Bremsfluessigkeit' : null, answeredAtUtc: DateTime.utc(2026, 11, 11, 8, 1)),
      OfflineInspectionAnswer(stepCode: 'S2', answer: 'OK', answeredAtUtc: DateTime.utc(2026, 11, 11, 8, 2)),
      OfflineInspectionAnswer(stepCode: 'S3', answer: 'OK', answeredAtUtc: DateTime.utc(2026, 11, 11, 8, 3)),
    ];

/// Gegenstelle mit Serververhalten: Start idempotent ueber den Schluessel, Abschluss genau einmal (zweiter Abschluss: 409), Freigabe nur ohne Sperre.
class FakeInspectionRemote implements InspectionRemote {
  bool online = true;
  bool blockedMeanwhile = false;
  bool dropNextResponse = false;
  int starts = 0, submits = 0, releases = 0;
  final Map<String, String> _runByKey = <String, String>{};
  final Set<String> _done = <String>{};

  @override
  Future<InspectionRemoteRun> start({required String shiftId, required String vehicleId, required String kind, required DateTime clientStartedAtUtc, required String idempotencyKey}) async {
    if (!online) throw const NetworkUnavailableException();
    starts++;
    return InspectionRemoteRun(id: _runByKey.putIfAbsent(idempotencyKey, () => 'run-${_runByKey.length + 1}'), revision: 1);
  }

  @override
  Future<InspectionRemoteResult> submit({required String inspectionId, required int revision, required List<Map<String, Object?>> answers, required DateTime clientSubmittedAtUtc, required String deviceEventId}) async {
    if (!online) throw const NetworkUnavailableException();
    final DateTime now = DateTime.utc(2026, 11, 11, 10);
    if (_done.contains(inspectionId)) return InspectionRemoteResult(status: 409, receivedAtUtc: now, revision: 2);
    _done.add(inspectionId);
    submits++;
    final bool released = !blockedMeanwhile;
    if (released) releases++;
    if (dropNextResponse) {
      dropNextResponse = false;
      throw const NetworkUnavailableException('Antwort verloren');
    }
    return InspectionRemoteResult(status: 200, released: released, missing: released ? const <String>[] : const <String>['DEFECT_BLOCK'], receivedAtUtc: now, revision: 2);
  }
}

class NoOtherEvents implements DriverEventApi {
  @override
  Future<ApiResponse> recordDriverEvent(QueuedEvent event) => throw StateError('kein anderes Ereignis erwartet');
}

void main() {
  late InMemoryOutboxStore store;
  late OfflineQueue queue;
  late InMemoryOfflineInspectionBundleStore bundles;
  late OfflineInspectionRecorder recorder;
  late FakeInspectionRemote remote;
  late SyncEngine engine;

  setUp(() {
    store = InMemoryOutboxStore();
    queue = OfflineQueue(store: store, snapshots: store, integrityKey: key, clock: () => DateTime.utc(2026, 11, 11, 8, 5));
    bundles = InMemoryOfflineInspectionBundleStore();
    recorder = OfflineInspectionRecorder(queue: queue, gate: OfflineInspectionGate(bundles));
    remote = FakeInspectionRemote();
    engine = SyncEngine(store: store, api: InspectionAwareEventApi(inspections: remote, delegate: NoOtherEvents()), owner: 'a', integrityKey: key, clock: () => DateTime.utc(2026, 11, 11, 10));
  });

  Future<OfflineCompletion> completeOffline({String s1 = 'OK'}) => recorder.complete(shiftId: 's-1', vehicleId: 'v-1', startedAtUtc: DateTime.utc(2026, 11, 11, 8), answers: answers(s1: s1));

  test('offline_abschluss_zeigt_freigabe_ausstehend_ohne_serverfreigabe', () async {
    await bundles.save(bundle());
    final OfflineCompletion c = await completeOffline();
    expect(c.doNotDrive, isFalse);
    expect(c.instructionKey, 'inspection.offline.releasePending');
    expect(offlineInspectionStatus(c.event), OfflineInspectionStatus.localCompleted);
    expect(remote.starts + remote.submits, 0, reason: 'offline wird nichts am Server ausgeloest');
    expect(OfflinePolicy.offlineAllowed.contains(DriverAction.completeInspectionLocally), isTrue);
    expect(OfflinePolicy.serverOnly.contains(DriverAction.submitInspectionForRelease), isTrue);
  });

  test('sync_wirkt_genau_einmal_auch_bei_verlorener_antwort_und_mehrfachuebertragung', () async {
    await bundles.save(bundle());
    final OfflineCompletion c = await completeOffline();
    remote.dropNextResponse = true;
    expect((await engine.sync()).stoppedBy, SyncStop.network);
    expect(offlineInspectionStatus((await store.all()).single), OfflineInspectionStatus.localCompleted, reason: 'ohne Antwort bleibt der Abschluss lokal');
    final SyncResult r = await engine.sync();
    expect(r.duplicate, 1);
    expect(remote.submits, 1);
    expect(remote.releases, 1, reason: 'genau eine Freigabe, erteilt vom Server');
    final QueuedEvent e = (await store.all()).single;
    expect(e.deviceEventId, c.event.deviceEventId);
    expect(offlineInspectionStatus(e), OfflineInspectionStatus.synced, reason: 'ohne Serverstand RELEASED kein „freigegeben“');
    expect((await engine.sync()).applied + (await engine.sync()).duplicate, 0, reason: 'nichts mehr zu senden');
  });

  test('freigegeben_erst_mit_serverstand_released', () async {
    await bundles.save(bundle());
    await completeOffline();
    await engine.sync();
    final QueuedEvent e = (await store.all()).single;
    final ReleaseGateView notYet = const ReleaseGateView(releaseState: 'NOT_RELEASED', missing: <MissingRequirement>[MissingRequirement(code: 'DEFECT_BLOCK')]);
    final ReleaseGateView released = const ReleaseGateView(releaseState: 'RELEASED', missing: <MissingRequirement>[]);
    expect(offlineInspectionStatus(e, serverGate: notYet), OfflineInspectionStatus.synced);
    expect(offlineInspectionStatus(e, serverGate: released), OfflineInspectionStatus.released);
    expect(offlineInspectionStatusKey(OfflineInspectionStatus.released), 'inspection.status.released');
  });

  test('sperre_zwischenzeitlich_ergibt_synchronisiert_aber_nie_freigegeben', () async {
    await bundles.save(bundle());
    await completeOffline();
    remote.blockedMeanwhile = true;
    await engine.sync();
    final QueuedEvent e = (await store.all()).single;
    final ReleaseGateView gate = const ReleaseGateView(releaseState: 'BLOCKED', missing: <MissingRequirement>[MissingRequirement(code: 'DEFECT_BLOCK')]);
    expect(offlineInspectionStatus(e, serverGate: gate), OfflineInspectionStatus.synced);
    expect(remote.releases, 0);
  });

  test('kritisches_nein_weist_sofort_an_nicht_zu_fahren', () async {
    await bundles.save(bundle());
    final OfflineCompletion c = await completeOffline(s1: 'DEFECT');
    expect(c.doNotDrive, isTrue);
    expect(c.criticalDefectStepCodes, <String>['S1']);
    expect(c.instructionKey, 'inspection.offline.criticalNo');
    expect(offlineInspectionStatus(c.event), OfflineInspectionStatus.localCompleted, reason: 'die Sperre entsteht erst beim Sync am Server');
  });

  test('ohne_lokale_vorlage_oder_altschadenliste_beginnt_offline_keine_pruefung', () async {
    final OfflineStartDecision none = await OfflineInspectionGate(bundles).evaluate('v-1');
    expect(none.allowed, isFalse);
    expect(none.textKey, 'inspection.offline.noTemplate');
    await bundles.save(bundle(withDefects: false));
    final OfflineStartDecision noDefects = await OfflineInspectionGate(bundles).evaluate('v-1');
    expect(noDefects.textKey, 'inspection.offline.noKnownDefects');
    await expectLater(completeOffline(), throwsA(isA<OfflineCompletionRejected>()));
    expect((await store.all()), isEmpty);
    await bundles.save(bundle());
    expect((await OfflineInspectionGate(bundles).evaluate('v-1')).allowed, isTrue);
  });

  test('pflichtschritt_fehlt_oder_mangel_ohne_text_wird_lokal_abgewiesen', () async {
    await bundles.save(bundle());
    await expectLater(
      recorder.complete(shiftId: 's-1', vehicleId: 'v-1', startedAtUtc: DateTime.utc(2026, 11, 11, 8), answers: answers().take(2).toList()),
      throwsA(isA<OfflineCompletionRejected>()),
    );
    await expectLater(
      recorder.complete(shiftId: 's-1', vehicleId: 'v-1', startedAtUtc: DateTime.utc(2026, 11, 11, 8), answers: <OfflineInspectionAnswer>[
        OfflineInspectionAnswer(stepCode: 'S1', answer: 'DEFECT', answeredAtUtc: DateTime.utc(2026, 11, 11, 8, 1)),
        ...answers().skip(1),
      ]),
      throwsA(isA<OfflineCompletionRejected>()),
    );
  });
}
