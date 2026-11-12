/// Belegentwurf der Fahrer-App (WP-M4-25/-26, LH-I.1-DOC-001/-013, UI-10): Rohbild, bearbeitete Ansicht, Vorgang, stabile Client-UUID und Anzeigezustände.
/// Logik mit `flutter test` ausgeführt (Flutter 3.47.9, Attrappen für Kamera/Server); Gerätefunktionen und Server-HTTP gegen echtes Gerät/Backend nicht ausgeführt (siehe README). Serverseite: Backend-Tests (`ReceiptCaptureTest`, `OfflineReceiptSyncTest`).
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

/// Obergrenze der Eingabeschritte bis zum gespeicherten Entwurf (Originalabnahme DOC-001: höchstens fünf).
const int kMaxReceiptCaptureSteps = 5;

/// Vorgangsarten der Zuordnung (Fahrzeug oder Schicht); nur zugewiesene Vorgänge sind wählbar, der Server weist fremde mit 403 ab.
enum ReceiptCaseKind { vehicle, shift }

class ReceiptCaseRef {
  const ReceiptCaseRef(this.kind, this.id, this.label);
  final ReceiptCaseKind kind;
  final String id;

  /// Anzeigename (Kennzeichen, Schichtzeit).
  final String label;

  Map<String, Object?> toJson() => <String, Object?>{'kind': kind == ReceiptCaseKind.vehicle ? 'VEHICLE' : 'SHIFT', 'id': id};
}

/// Belegarten der App; Tankbelege sind der Musterfall.
enum ReceiptKind { receipt, fuelReceipt }

/// Zustand des Entwurfs auf dem Gerät. „Lokal“ und „synchronisiert“ sind getrennt von „freigegeben“ (Kap. 06 §6.7, [ReceiptDraft.approvalLabel]).
enum ReceiptSyncStatus {
  /// Nur auf diesem Gerät (auch nach App-Neustart und ohne Netz).
  local,

  /// Der Server hat den Beleg angelegt (Original gespeichert, Prüfung läuft oder bestanden).
  synced,

  /// Der Server hat den Inhalt zur Client-UUID abgewiesen (anderer Hash, 409): nicht überschreibbar, braucht Klärung.
  conflict,
}

/// Fehler- oder Hinweiszustand der letzten Synchronisation (für die Anzeige; der Entwurf bleibt in jedem Fall erhalten).
enum ReceiptSyncIssue { none, offline, uploadAborted, uploadTargetUnavailable, transferCorrupted, hashConflict, caseNotAssigned, rejected }

String sha256Hex(List<int> bytes) => sha256.convert(bytes).toString();

class ReceiptDraft {
  ReceiptDraft({
    required this.clientUuid,
    required DateTime capturedAtUtc,
    required Uint8List rawBytes,
    required this.mime,
    this.kind = ReceiptKind.fuelReceipt,
    this.caseRef,
    Uint8List? editedBytes,
    this.status = ReceiptSyncStatus.local,
    this.issue = ReceiptSyncIssue.none,
    this.documentId,
    this.approvalState,
  })  : capturedAtUtc = DateTime.fromMillisecondsSinceEpoch(capturedAtUtc.millisecondsSinceEpoch, isUtc: true), // Millisekunden wie in der Ablage: vor und nach einem Neustart derselbe Wert
        _raw = Uint8List.fromList(rawBytes),
        rawSha256 = sha256Hex(rawBytes),
        editedBytes = editedBytes == null ? null : Uint8List.fromList(editedBytes);

  /// Vor dem Upload vom Gerät erzeugt und danach nie geändert: Idempotenzschlüssel gegenüber dem Server (Unique je Mandant).
  final String clientUuid;

  /// Erfassungszeit laut Geräteuhr (UTC); der Server speichert sie getrennt von der Eingangszeit und überschreibt sie nie.
  final DateTime capturedAtUtc;

  final Uint8List _raw;

  /// Hash des Rohbilds, lokal berechnet **bevor** irgendetwas übertragen wird; der Server berechnet ihn aus den Bytes nach und vergleicht.
  final String rawSha256;
  final String mime;
  ReceiptKind kind;
  ReceiptCaseRef? caseRef;

  /// Bearbeitete Ansicht (Zuschnitt, Drehung) als eigene Datei; das Rohbild bleibt davon unberührt.
  Uint8List? editedBytes;

  ReceiptSyncStatus status;
  ReceiptSyncIssue issue;
  String? documentId;

  /// Freigabezustand des Servers (`null` = noch nicht freigegeben). Wird nur vom Server gelesen, nie in der App gesetzt.
  String? approvalState;

  /// Rohbild als unveränderliche Sicht; eine Änderung der Bytes ist nicht möglich.
  Uint8List get rawBytes => Uint8List.fromList(_raw);

  bool get isComplete => caseRef != null;

  /// Rumpf von `POST /v1/expenses/receipts`: Größe, Typ und Hash des **Rohbilds** (das Original); Mandant, Erfasser und Eingangszeit setzt der Server.
  Map<String, Object?> toTicketBody({required String legalEntityId}) {
    if (!isComplete) throw StateError('Vorgang fehlt');
    return <String, Object?>{
      'legal_entity_id': legalEntityId,
      'purpose': 'RECEIPT',
      'mime': mime,
      'size_bytes': _raw.length,
      'sha256': base64Url.encode(sha256.convert(_raw).bytes).replaceAll('=', ''),
      'source_channel': 'DRIVER_APP',
      'document_kind': kind == ReceiptKind.fuelReceipt ? 'FUEL_RECEIPT' : 'RECEIPT',
      'client_uuid': clientUuid,
      'captured_at': capturedAtUtc.toUtc().toIso8601String(),
      'case_ref': caseRef!.toJson(),
    };
  }

  /// Anzeigetexte; drei getrennte Zustände („lokal“, „synchronisiert“, „freigegeben“), nie nur über Farbe (Symbol und Text gehören dazu).
  String get syncLabel => switch (status) {
        ReceiptSyncStatus.local => 'Lokal (nur auf diesem Gerät)',
        ReceiptSyncStatus.synced => 'Synchronisiert (an den Server übertragen)',
        ReceiptSyncStatus.conflict => 'Konflikt (Server hat anderen Inhalt zu dieser Kennung)',
      };

  String get approvalLabel => approvalState == 'APPROVED' || approvalState == 'POSTED' ? 'Freigegeben' : 'Nicht freigegeben';

  /// Verständlicher Hinweis zum letzten Synchronisationsfehler (leer, wenn keiner).
  String get issueLabel => switch (issue) {
        ReceiptSyncIssue.none => '',
        ReceiptSyncIssue.offline => 'Kein Netz. Der Beleg bleibt gespeichert und wird später übertragen.',
        ReceiptSyncIssue.uploadAborted => 'Die Übertragung wurde unterbrochen. Sie wird fortgesetzt, der Beleg wird nicht doppelt angelegt.',
        ReceiptSyncIssue.uploadTargetUnavailable => 'Der Upload-Dienst ist noch nicht erreichbar. Der Beleg bleibt gespeichert.',
        ReceiptSyncIssue.transferCorrupted => 'Die Datei kam beschädigt an. Sie wird erneut übertragen.',
        ReceiptSyncIssue.hashConflict => 'Zu dieser Kennung liegt am Server ein anderer Inhalt. Es wurde nichts überschrieben; bitte die Leitstelle fragen.',
        ReceiptSyncIssue.caseNotAssigned => 'Der gewählte Vorgang ist Ihnen nicht zugewiesen. Der Entwurf bleibt erhalten; bitte einen eigenen Vorgang wählen.',
        ReceiptSyncIssue.rejected => 'Der Server hat den Beleg abgelehnt.',
      };

  /// Serialisierung für die verschlüsselte lokale Ablage (Bilder getrennt als Binärdaten).
  Map<String, Object?> toStoredJson() => <String, Object?>{
        'client_uuid': clientUuid,
        'captured_at_ms': capturedAtUtc.millisecondsSinceEpoch,
        'mime': mime,
        'kind': kind.name,
        'case': caseRef == null ? null : <String, Object?>{'kind': caseRef!.kind.name, 'id': caseRef!.id, 'label': caseRef!.label},
        'status': status.name,
        'issue': issue.name,
        'document_id': documentId,
        'approval_state': approvalState,
        'raw_sha256': rawSha256,
      };

  static ReceiptDraft fromStored(Map<String, Object?> j, {required Uint8List raw, Uint8List? edited}) {
    final Map<String, Object?>? c = (j['case'] as Map<Object?, Object?>?)?.cast<String, Object?>();
    final ReceiptDraft d = ReceiptDraft(
      clientUuid: j['client_uuid']! as String,
      capturedAtUtc: DateTime.fromMillisecondsSinceEpoch(j['captured_at_ms']! as int, isUtc: true),
      rawBytes: raw,
      mime: j['mime']! as String,
      kind: ReceiptKind.values.byName(j['kind']! as String),
      caseRef: c == null ? null : ReceiptCaseRef(ReceiptCaseKind.values.byName(c['kind']! as String), c['id']! as String, c['label']! as String),
      editedBytes: edited,
      status: ReceiptSyncStatus.values.byName(j['status']! as String),
      issue: ReceiptSyncIssue.values.byName(j['issue']! as String),
      documentId: j['document_id'] as String?,
      approvalState: j['approval_state'] as String?,
    );
    if (d.rawSha256 != j['raw_sha256']) throw StateError('Rohbild passt nicht zum gespeicherten Hash (Ablage beschädigt)');
    return d;
  }
}
