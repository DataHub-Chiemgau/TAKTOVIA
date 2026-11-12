// WP-M4-26 (LH-I.1-DOC-013): Offline-Belegerfassung mit einmaliger Synchronisation. Ausgeführt mit Flutter 3.47.9 (flutter test), Server als Attrappe
// ; die Serverseite ist durch `OfflineReceiptSyncTest`, `ReceiptCaptureApiTest` und `db/tests/test_expense_client_uuid.py` belegt.
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/beleg/receipt_capture_flow.dart';
import 'package:taktovia_driver/beleg/receipt_draft.dart';
import 'package:taktovia_driver/beleg/receipt_memory_store.dart';
import 'package:taktovia_driver/beleg/receipt_sync.dart';

import 'beleg_fakes.dart';

Future<ReceiptDraft> capture(InMemoryReceiptDraftStore store, {int seed = 1, String uuid = '00000000-0000-4000-8000-0000000000c1', bool edited = false}) async {
  final ReceiptCaptureFlow f = ReceiptCaptureFlow(
    camera: FakeCamera(<Object>[photo(seed)]),
    editor: FakeEditor(),
    movement: FakeMovement(false),
    cases: FakeCases(const <ReceiptCaseRef>[ownVehicle]),
    store: store,
    idGenerator: () => uuid,
  );
  await f.takePhoto();
  await f.confirmEdit(crop: edited ? (left: 0.0, top: 0.0, right: 0.5, bottom: 1.0) : null);
  await f.chooseCase(ownVehicle);
  return f.save();
}

void main() {
  FakeReceiptServer server() => FakeReceiptServer(assigned: <String>{ownVehicle.id});

  test('app_neustart_vor_sync_behaelt_entwurf_mit_gleicher_uuid_und_status_lokal', () async {
    final InMemoryReceiptDraftStore store = InMemoryReceiptDraftStore(); // verschlüsselte Ablage: überlebt den Neustart
    final FakeReceiptServer s = server()..online = false; // ohne Netz erfasst
    final ReceiptDraft d = await capture(store);
    expect(d.status, ReceiptSyncStatus.local);
    final ReceiptSyncResult offline = await ReceiptSyncEngine(store: store, api: s).syncAll(); // ohne Netz: kein Fehlerdialog, Status bleibt „lokal“
    expect(offline.stillLocal, 1);
    expect((await store.load(d.clientUuid))!.status, ReceiptSyncStatus.local);
    expect((await store.load(d.clientUuid))!.issueLabel, contains('Kein Netz'));
    // „App-Neustart“: neue Engine auf derselben Ablage; Entwurf, UUID und Rohbild-Hash sind unverändert
    final List<ReceiptDraft> after = await store.unsynced();
    expect(after, hasLength(1));
    expect(after.single.clientUuid, d.clientUuid);
    expect(after.single.rawSha256, d.rawSha256);
    s.online = true;
    final ReceiptSyncResult online = await ReceiptSyncEngine(store: store, api: s).syncAll();
    expect(online.synced, 1);
    expect(s.documents, hasLength(1));
    expect(s.documents[d.clientUuid]!['sha256'], b64Hash(jpegBytes(1)), reason: 'Server-Hash entspricht dem lokal erfassten Bild');
    expect(s.documents[d.clientUuid]!['captured_at'], d.capturedAtUtc.toUtc().toIso8601String(), reason: 'Erfassungszeit des Geräts bleibt unverändert');
    // wiederholter Sync (zweiter Lauf, zweite Engine): kein zweiter Beleg
    await ReceiptSyncEngine(store: store, api: s).syncAll();
    expect(s.documents, hasLength(1));
  });

  test('hash_konflikt_und_upload_abbruch_zeigen_fehlerzustand_mit_wiederaufnahme', () async {
    final InMemoryReceiptDraftStore store = InMemoryReceiptDraftStore();
    final FakeReceiptServer s = server()..abortUploadsRemaining = 1; // Abbruch nach 50 %
    final ReceiptDraft d = await capture(store, edited: true);
    final ReceiptSyncEngine engine = ReceiptSyncEngine(store: store, api: s);
    await engine.syncOne((await store.load(d.clientUuid))!);
    ReceiptDraft now = (await store.load(d.clientUuid))!;
    expect(now.status, ReceiptSyncStatus.local);
    expect(now.issue, ReceiptSyncIssue.offline);
    expect(s.documents, isEmpty);
    // Wiederaufnahme: dasselbe Ticket (keine zweite Anlage), danach genau ein Beleg und die bearbeitete Ansicht folgt
    await engine.syncOne(now);
    now = (await store.load(d.clientUuid))!;
    expect(now.status, ReceiptSyncStatus.synced);
    expect(s.documents, hasLength(1));
    expect(s.tickets, hasLength(1));
    expect(s.editedViews, hasLength(1));
    // Übertragungsfehler (Bytes beschädigt): nichts angelegt, Wiederholung mit neuem Ticket gelingt
    final FakeReceiptServer s2 = server()..corruptNextUpload = true;
    final InMemoryReceiptDraftStore store2 = InMemoryReceiptDraftStore();
    final ReceiptDraft d2 = await capture(store2, seed: 2, uuid: '00000000-0000-4000-8000-0000000000c2');
    final ReceiptSyncEngine e2 = ReceiptSyncEngine(store: store2, api: s2);
    await e2.syncOne(d2);
    expect((await store2.load(d2.clientUuid))!.issue, ReceiptSyncIssue.transferCorrupted);
    expect(s2.documents, isEmpty);
    await e2.syncOne((await store2.load(d2.clientUuid))!);
    expect((await store2.load(d2.clientUuid))!.status, ReceiptSyncStatus.synced);
    expect(s2.documents, hasLength(1));
    // Hash-Konflikt: dieselbe Kennung, anderer Inhalt am Server -> Fehlerzustand, nichts überschrieben, Entwurf bleibt
    final FakeReceiptServer s3 = server();
    final InMemoryReceiptDraftStore store3 = InMemoryReceiptDraftStore();
    final ReceiptDraft first = await capture(store3, seed: 3, uuid: '00000000-0000-4000-8000-0000000000c3');
    await ReceiptSyncEngine(store: store3, api: s3).syncOne(first);
    final InMemoryReceiptDraftStore other = InMemoryReceiptDraftStore();
    final ReceiptDraft clash = await capture(other, seed: 4, uuid: '00000000-0000-4000-8000-0000000000c3');
    await ReceiptSyncEngine(store: other, api: s3).syncOne(clash);
    final ReceiptDraft c = (await other.load(clash.clientUuid))!;
    expect(c.status, ReceiptSyncStatus.conflict);
    expect(c.issueLabel, contains('nichts überschrieben'));
    expect(s3.documents[first.clientUuid]!['sha256'], b64Hash(jpegBytes(3)));
    expect(c.rawBytes, jpegBytes(4));
  });

  test('derselbe_entwurf_parallel_von_zwei_sync_laeufen_ergibt_einen_beleg', () async {
    final InMemoryReceiptDraftStore store = InMemoryReceiptDraftStore();
    final FakeReceiptServer s = server();
    final ReceiptDraft d = await capture(store);
    final ReceiptSyncEngine e1 = ReceiptSyncEngine(store: store, api: s);
    final ReceiptSyncEngine e2 = ReceiptSyncEngine(store: store, api: s); // zwei Prozesse/Engines
    final List<ReceiptDraft> r = await Future.wait(<Future<ReceiptDraft>>[e1.syncOne(d), e2.syncOne((await store.load(d.clientUuid))!)]);
    expect(r.every((ReceiptDraft x) => x.status == ReceiptSyncStatus.synced), isTrue);
    expect(s.documents, hasLength(1));
  });

  test('fremder_vorgang_wird_serverseitig_mit_403_abgewiesen_der_entwurf_bleibt', () async {
    final InMemoryReceiptDraftStore store = InMemoryReceiptDraftStore();
    final FakeReceiptServer s = FakeReceiptServer(assigned: <String>{}); // Zuweisung am Server entzogen
    final ReceiptDraft d = await capture(store);
    await ReceiptSyncEngine(store: store, api: s).syncOne(d);
    final ReceiptDraft now = (await store.load(d.clientUuid))!;
    expect(now.status, ReceiptSyncStatus.local);
    expect(now.issue, ReceiptSyncIssue.caseNotAssigned);
    expect(s.documents, isEmpty);
  });
}
