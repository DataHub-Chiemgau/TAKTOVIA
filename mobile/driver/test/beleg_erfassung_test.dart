// WP-M4-25 (LH-I.1-DOC-001): Belegerfassung per Kamera. Ausgeführt mit Flutter 3.47.9 (flutter test); Kamera, Bildbearbeitung und Fahrbewegung sind Attrappen (beleg_fakes.dart).
// Die Serverseite (Rohbild und bearbeitete Ansicht, Zuordnung, Idempotenz) ist durch `ReceiptCaptureTest` und `ReceiptCaptureApiTest` im Backend belegt.
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/beleg/receipt_capture_flow.dart';
import 'package:taktovia_driver/beleg/receipt_draft.dart';
import 'package:taktovia_driver/beleg/receipt_memory_store.dart';
import 'package:taktovia_driver/beleg/receipt_capture_screen.dart';
import 'package:taktovia_driver/beleg/receipt_ports.dart';

import 'beleg_fakes.dart';

ReceiptCaptureFlow flow(FakeCamera cam, InMemoryReceiptDraftStore store, {FakeEditor? editor, bool moving = false}) => ReceiptCaptureFlow(
      camera: cam,
      editor: editor ?? FakeEditor(),
      movement: FakeMovement(moving),
      cases: FakeCases(const <ReceiptCaseRef>[ownVehicle]),
      store: store,
      idGenerator: () => '00000000-0000-4000-8000-0000000000c1',
      clock: () => DateTime.utc(2026, 12, 1, 7, 15),
    );

void main() {
  test('tankbeleg_in_hoechstens_fuenf_schritten_als_entwurf', () async {
    final InMemoryReceiptDraftStore store = InMemoryReceiptDraftStore();
    final FakeEditor editor = FakeEditor();
    final ReceiptCaptureFlow f = flow(FakeCamera(<Object>[photo(1)]), store, editor: editor);
    expect(f.start(), StartResult.started);
    expect(await f.takePhoto(), CaptureOutcome.accepted); // 1
    await f.confirmEdit(crop: (left: 0.1, top: 0.1, right: 0.9, bottom: 0.9), quarterTurns: 1); // 2: Zuschnitt und Drehung
    await f.chooseCase(ownVehicle); // 3
    final ReceiptDraft d = await f.save(); // 4
    expect(f.inputSteps, lessThanOrEqualTo(kMaxReceiptCaptureSteps));
    expect(f.step, ReceiptStep.done);
    // als Entwurf lokal gespeichert, Tankbeleg, Vorgang gesetzt, Rohbild und bearbeitete Ansicht getrennt
    final ReceiptDraft? stored = await store.load(d.clientUuid);
    expect(stored, isNotNull);
    expect(stored!.status, ReceiptSyncStatus.local);
    expect(stored.kind, ReceiptKind.fuelReceipt);
    expect(stored.caseRef!.id, ownVehicle.id);
    expect(stored.editedBytes, isNotNull);
    expect(stored.rawBytes, isNot(equals(stored.editedBytes)));
    // Zuschnitt und Drehung verändern das Rohbild nicht (Hash gleich dem der Aufnahme)
    expect(stored.rawSha256, sha256Hex(jpegBytes(1)));
    expect(stored.rawBytes, jpegBytes(1));
    expect(editor.calls, 1);
    expect(stored.toTicketBody(legalEntityId: 'le')['sha256'], b64Hash(jpegBytes(1)));
    expect(stored.syncLabel, 'Lokal (nur auf diesem Gerät)');
    expect(stored.approvalLabel, 'Nicht freigegeben'); // „synchronisiert“ und „freigegeben“ sind getrennte Zustände
  });

  test('unscharfes_bild_hinweis_und_neu_aufnahme', () async {
    final InMemoryReceiptDraftStore store = InMemoryReceiptDraftStore();
    final FakeCamera cam = FakeCamera(<Object>[photo(1, gray: blurryImage()), photo(2, gray: sharpImage(sourceW: 600, sourceH: 800)), photo(3)]);
    final ReceiptCaptureFlow f = flow(cam, store);
    expect(await f.takePhoto(), CaptureOutcome.blurry); // Hinweis, Schritt bleibt offen
    expect(f.step, ReceiptStep.photo);
    expect(await f.takePhoto(), CaptureOutcome.tooSmall); // zu geringe Auflösung
    expect(f.step, ReceiptStep.photo);
    expect(await f.takePhoto(), CaptureOutcome.accepted); // Neuaufnahme gelingt
    expect(f.step, ReceiptStep.edit);
    expect(cam.calls, 3);
    expect(await store.all(), isEmpty); // bis zum Speichern entsteht nichts
  });

  test('kameraberechtigung_verweigert_verstaendlicher_hinweis', () async {
    final InMemoryReceiptDraftStore store = InMemoryReceiptDraftStore();
    final ReceiptCaptureFlow f = flow(FakeCamera(<Object>[const CameraPermissionDenied(), const CameraPermissionDenied(permanent: true), photo(1)]), store);
    expect(await f.takePhoto(), CaptureOutcome.cameraDenied); // kein Absturz, Hinweis
    expect(await f.takePhoto(), CaptureOutcome.cameraDeniedPermanently);
    expect(f.step, ReceiptStep.photo);
    expect(await f.takePhoto(), CaptureOutcome.accepted); // nach Freigabe geht es weiter
  });

  test('abbruch_hinterlaesst_keinen_halben_beleg', () async {
    final InMemoryReceiptDraftStore store = InMemoryReceiptDraftStore();
    final ReceiptCaptureFlow f = flow(FakeCamera(<Object>[photo(1)]), store);
    await f.takePhoto();
    await f.confirmEdit(crop: null);
    f.cancel(); // Abbruch vor dem Speichern
    expect(await store.all(), isEmpty);
    expect(f.step, ReceiptStep.photo);
    expect(f.inputSteps, 0);
  });

  test('fremdes_fahrzeug_wird_abgewiesen_und_der_ablauf_bleibt_offen', () async {
    final InMemoryReceiptDraftStore store = InMemoryReceiptDraftStore();
    final ReceiptCaptureFlow f = flow(FakeCamera(<Object>[photo(1)]), store);
    await f.takePhoto();
    await f.confirmEdit(crop: null);
    await expectLater(f.chooseCase(foreignVehicle), throwsA(isA<CaseNotAssigned>()));
    expect(f.step, ReceiptStep.chooseCase); // Foto bleibt erhalten, der Fahrer wählt einen eigenen Vorgang
    await f.chooseCase(ownVehicle);
    expect((await f.save()).caseRef!.id, ownVehicle.id);
  });

  test('waehrend_der_fahrbewegung_oeffnet_sich_kein_erfassungsdialog', () async {
    final ReceiptCaptureFlow f = flow(FakeCamera(<Object>[photo(1)]), InMemoryReceiptDraftStore(), moving: true);
    expect(f.start(), StartResult.blockedWhileMoving);
  });

  test('rohbild_ist_nach_dem_speichern_nicht_veraenderbar', () {
    final Uint8List raw = jpegBytes(7);
    final ReceiptDraft d = ReceiptDraft(clientUuid: 'u', capturedAtUtc: DateTime.utc(2026), rawBytes: raw, mime: 'image/jpeg');
    raw[10] ^= 0xff; // Änderung der Eingabe wirkt nicht auf den Entwurf
    d.rawBytes[11] ^= 0xff; // Änderung an einer Kopie auch nicht
    expect(sha256Hex(d.rawBytes), d.rawSha256);
  });

  testWidgets('bildschirm_zeigt_schritte_und_hinweise_als_text_und_sperrt_waehrend_der_fahrt', (WidgetTester tester) async {
    final ReceiptCaptureFlow f = flow(FakeCamera(<Object>[photo(1, gray: blurryImage()), const CameraPermissionDenied()]), InMemoryReceiptDraftStore());
    await tester.pumpWidget(MaterialApp(home: ReceiptCaptureScreen(flow: f)));
    expect(find.text('Beleg fotografieren'), findsOneWidget);
    await tester.tap(find.text('Foto aufnehmen'));
    await tester.pump();
    expect(find.textContaining('unscharf'), findsOneWidget); // Hinweis als Text (mit Symbol), nicht nur Farbe
    expect(find.text('Neu aufnehmen'), findsOneWidget); // Neuaufnahme wird angeboten
    await tester.tap(find.text('Neu aufnehmen'));
    await tester.pump();
    expect(find.textContaining('Kamera ist nicht freigegeben'), findsOneWidget); // Berechtigung verweigert: verständlicher Hinweis, kein Absturz
    // Fahrbewegung: kein Erfassungsdialog
    final ReceiptCaptureFlow moving = flow(FakeCamera(<Object>[]), InMemoryReceiptDraftStore(), moving: true);
    await tester.pumpWidget(MaterialApp(home: ReceiptCaptureScreen(flow: moving)));
    expect(find.textContaining('Während der Fahrt'), findsOneWidget);
    expect(find.text('Foto aufnehmen'), findsNothing);
  });

  testWidgets('status_lokal_synchronisiert_und_freigegeben_getrennt_angezeigt', (WidgetTester tester) async {
    final ReceiptDraft d = ReceiptDraft(clientUuid: 'u', capturedAtUtc: DateTime.utc(2026, 12, 1, 7, 15), rawBytes: jpegBytes(1), mime: 'image/jpeg');
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ReceiptStatusView(draft: d))));
    expect(find.text('Lokal (nur auf diesem Gerät)'), findsOneWidget);
    expect(find.text('Nicht freigegeben'), findsOneWidget);
    d.status = ReceiptSyncStatus.synced;
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ReceiptStatusView(draft: d))));
    expect(find.text('Synchronisiert (an den Server übertragen)'), findsOneWidget);
    expect(find.text('Nicht freigegeben'), findsOneWidget); // synchronisiert heißt nicht freigegeben
    d.approvalState = 'APPROVED';
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ReceiptStatusView(draft: d))));
    expect(find.text('Freigegeben'), findsOneWidget);
  });
}
