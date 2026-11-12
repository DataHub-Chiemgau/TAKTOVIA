/// Geführte Belegerfassung (WP-M4-25, LH-I.1-DOC-001, UI-10): Foto, Qualitätsprüfung, Zuschnitt/Drehung, Vorgang wählen, als Entwurf speichern, in höchstens fünf Eingabeschritten.
/// Das Rohbild wird vor jeder Bearbeitung gesichert und nie verändert (Hash lokal berechnet); Zuschnitt und Drehung erzeugen eine eigene Datei. Ein Abbruch speichert nichts
/// (kein halber Beleg); erst „Speichern“ legt den Entwurf lokal ab, die Übertragung folgt getrennt (`ReceiptSyncEngine`, WP-M4-26).
/// Logik mit `flutter test` ausgeführt (Attrappen); die Anbindung an Gerät und echten Server ist nicht ausgeführt (siehe README).
library;

import 'dart:typed_data';

import '../offline/offline_queue.dart' show generateUuidV4;
import 'receipt_draft.dart';
import 'receipt_ports.dart';
import 'receipt_quality.dart';

/// Schritte in fester Reihenfolge; jeder Wechsel durch eine Eingabe des Fahrers zählt (Foto aufnehmen, Bearbeitung bestätigen, Vorgang wählen, Speichern).
enum ReceiptStep { photo, edit, chooseCase, save, done }

/// Ergebnis von [ReceiptCaptureFlow.start]: während der Fahrbewegung wird kein Dialog geöffnet.
enum StartResult { started, blockedWhileMoving }

/// Hinweis nach der Aufnahme (die Oberfläche übersetzt ihn in einen Text und bietet „Neu aufnehmen“ an).
enum CaptureOutcome { accepted, tooSmall, blurry, tooSmallAndBlurry, cameraDenied, cameraDeniedPermanently }

class ReceiptCaptureFlow {
  ReceiptCaptureFlow({
    required this.camera,
    required this.editor,
    required this.movement,
    required this.cases,
    required this.store,
    this.policy = const ReceiptQualityPolicy(),
    DateTime Function()? clock,
    String Function()? idGenerator,
    this.kind = ReceiptKind.fuelReceipt,
  })  : _clock = clock ?? (() => DateTime.now().toUtc()),
        _newId = idGenerator ?? generateUuidV4;

  final CameraPort camera;
  final ImageEditorPort editor;
  final MovementPort movement;
  final AssignedCasesPort cases;
  final ReceiptDraftStore store;
  final ReceiptQualityPolicy policy;
  final ReceiptKind kind;
  final DateTime Function() _clock;
  final String Function() _newId;

  ReceiptStep step = ReceiptStep.photo;
  int inputSteps = 0;
  CapturedPhoto? _photo;
  DateTime? _capturedAt;
  Uint8List? _edited;
  ReceiptCaseRef? _case;
  ReceiptDraft? saved;

  /// Zuletzt gemeldete Qualität (für die Anzeige der Hinweise).
  QualityReport? lastQuality;

  StartResult start() => movement.vehicleMoving ? StartResult.blockedWhileMoving : StartResult.started;

  /// Schritt 1: Foto aufnehmen. Bei unzureichender Qualität bleibt der Schritt offen; der Fahrer kann neu aufnehmen (derselbe Aufruf). Ohne Kameraberechtigung kommt ein Hinweis, kein Absturz.
  Future<CaptureOutcome> takePhoto() async {
    _assertStep(ReceiptStep.photo);
    final CapturedPhoto p;
    try {
      p = await camera.capture();
    } on CameraPermissionDenied catch (e) {
      return e.permanent ? CaptureOutcome.cameraDeniedPermanently : CaptureOutcome.cameraDenied;
    }
    inputSteps++;
    final QualityReport q = assessReceiptQuality(p.gray, policy: policy);
    lastQuality = q;
    if (!q.ok) return q.issues.length == 2 ? CaptureOutcome.tooSmallAndBlurry : (q.issues.single == QualityIssue.tooSmall ? CaptureOutcome.tooSmall : CaptureOutcome.blurry);
    _photo = p;
    _capturedAt = _clock();
    step = ReceiptStep.edit;
    return CaptureOutcome.accepted;
  }

  /// Schritt 2: Zuschnitt und Drehung bestätigen (ein Schritt, auch mit mehreren Handgriffen; ohne Änderung `crop: null`). Das Rohbild bleibt unverändert.
  Future<void> confirmEdit({({double left, double top, double right, double bottom})? crop, int quarterTurns = 0}) async {
    _assertStep(ReceiptStep.edit);
    final CapturedPhoto p = _photo!;
    if (crop != null || quarterTurns % 4 != 0) {
      final c = crop ?? (left: 0.0, top: 0.0, right: 1.0, bottom: 1.0);
      _edited = await editor.edit(Uint8List.fromList(p.bytes), left: c.left, top: c.top, right: c.right, bottom: c.bottom, quarterTurns: quarterTurns % 4);
    }
    inputSteps++;
    step = ReceiptStep.chooseCase;
  }

  /// Schritt 3: Vorgang wählen. Nur zugewiesene Vorgänge sind wählbar; ein anderer wird abgelehnt (der Server prüft es zusätzlich, 403).
  Future<void> chooseCase(ReceiptCaseRef ref) async {
    _assertStep(ReceiptStep.chooseCase);
    final List<ReceiptCaseRef> assigned = await cases.assignedCases();
    if (!assigned.any((ReceiptCaseRef a) => a.kind == ref.kind && a.id == ref.id)) throw const CaseNotAssigned();
    _case = ref;
    inputSteps++;
    step = ReceiptStep.save;
  }

  /// Schritt 4: als Entwurf speichern. Erzeugt die Client-UUID, berechnet den Hash des Rohbilds und legt alles atomar in der verschlüsselten Ablage ab. Netz ist dafür nicht nötig.
  Future<ReceiptDraft> save() async {
    _assertStep(ReceiptStep.save);
    final CapturedPhoto p = _photo!;
    final ReceiptDraft d = ReceiptDraft(clientUuid: _newId(), capturedAtUtc: _capturedAt!, rawBytes: p.bytes, mime: p.mime, kind: kind, caseRef: _case, editedBytes: _edited);
    await store.save(d);
    inputSteps++;
    step = ReceiptStep.done;
    saved = d;
    return d;
  }

  /// Abbruch in jedem Schritt: nichts wird gespeichert, es entsteht kein halber Beleg.
  void cancel() {
    _photo = null;
    _edited = null;
    _case = null;
    step = ReceiptStep.photo;
    inputSteps = 0;
  }

  void _assertStep(ReceiptStep expected) {
    if (step != expected) throw StateError('Schritt $expected erwartet, aktuell $step');
  }
}

class CaseNotAssigned implements Exception {
  const CaseNotAssigned();
}
