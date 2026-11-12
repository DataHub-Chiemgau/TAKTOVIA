/// Ports der Belegerfassung (WP-M4-25/-26): Kamera, Bildbearbeitung, Fahrbewegung, lokale Ablage und Server-Gegenstelle. Austauschbar; die Tests nutzen Attrappen.
/// Logik mit `flutter test` ausgeführt (Attrappen); die Anbindung an Gerät und echten Server ist nicht ausgeführt (siehe README).
library;

import 'dart:typed_data';

import 'receipt_draft.dart';
import 'receipt_quality.dart';

/// Kamera verweigert (Berechtigung nicht erteilt, dauerhaft gesperrt oder kein Gerät): die Oberfläche zeigt einen verständlichen Hinweis, kein Absturz.
class CameraPermissionDenied implements Exception {
  const CameraPermissionDenied({this.permanent = false});

  /// Dauerhaft verweigert: der Hinweis führt in die Einstellungen.
  final bool permanent;
}

/// Ein aufgenommenes Foto: JPEG-Bytes und ein Graustufenbild für die Qualitätsprüfung.
class CapturedPhoto {
  const CapturedPhoto({required this.bytes, required this.mime, required this.gray});
  final Uint8List bytes;
  final String mime;
  final GrayImage gray;
}

abstract interface class CameraPort {
  /// Nimmt ein Foto auf. Wirft [CameraPermissionDenied].
  Future<CapturedPhoto> capture();
}

/// Zuschnitt und Drehung erzeugen eine **neue** Datei; die Eingabe wird nie verändert.
abstract interface class ImageEditorPort {
  /// Rechteck relativ (0..1) im Bild, danach Drehung in 90-Grad-Schritten.
  Future<Uint8List> edit(Uint8List source, {required double left, required double top, required double right, required double bottom, required int quarterTurns});
}

/// Fahrbewegung laut Fahrzeug-/Positionsdaten: während der Fahrt wird kein Erfassungsdialog geöffnet (Kap. 06 §6.1/§6.7).
abstract interface class MovementPort {
  bool get vehicleMoving;
}

/// Dem Fahrer zugewiesene Vorgänge (Fahrzeug einer eigenen Schicht, eigene Schicht) aus dem Offline-Lesestand.
abstract interface class AssignedCasesPort {
  Future<List<ReceiptCaseRef>> assignedCases();
}

/// Lokale, verschlüsselte Ablage der Entwürfe (WP-M3-24); ein Entwurf überlebt App-Neustart und fehlendes Netz.
abstract interface class ReceiptDraftStore {
  /// Legt den Entwurf an oder aktualisiert ihn; atomar (Rohbild, bearbeitete Ansicht und Metadaten gehören zusammen).
  Future<void> save(ReceiptDraft draft);
  Future<ReceiptDraft?> load(String clientUuid);

  /// Alle noch nicht synchronisierten Entwürfe (lokal oder Konflikt), in Erfassungsreihenfolge.
  Future<List<ReceiptDraft>> unsynced();
  Future<List<ReceiptDraft>> all();
}

/// Ticket des Servers (`POST /v1/expenses/receipts`).
class ReceiptTicket {
  const ReceiptTicket({required this.ticketId, required this.revision, required this.uploadKey, this.uploadUrl, this.uploadHeaders = const <String, String>{}, this.documentId});
  final String ticketId;
  final int revision;
  final String uploadKey;

  /// Vorsignierte Ziel-URL; `null`, solange am Server kein Objektspeicher angebunden ist.
  final String? uploadUrl;
  final Map<String, String> uploadHeaders;

  /// Gesetzt, wenn der Beleg zur Client-UUID schon angelegt ist (nichts mehr hochzuladen).
  final String? documentId;
}

/// Ergebnis des Abschlusses.
class ReceiptCompletion {
  const ReceiptCompletion({required this.documentId, required this.revision, required this.sha256B64, required this.created});
  final String documentId;
  final int revision;

  /// Serverseitig berechneter Hash des Originals (base64url ohne Auffüllung).
  final String sha256B64;

  /// `false`: der Beleg war schon vorhanden (200), kein zweiter wurde angelegt.
  final bool created;
}

class NetworkUnavailable implements Exception {
  const NetworkUnavailable([this.detail]);
  final String? detail;
}

/// Der Server hat eine endgültige Antwort gegeben, die kein Wiederholen mit demselben Inhalt erlaubt.
class ReceiptApiError implements Exception {
  const ReceiptApiError(this.status, {this.code});
  final int status;

  /// Fachlicher Hinweis: `hashConflict` (409), `caseNotAssigned` (403), `transferCorrupted` (422 Hash/Größe), `inProgress` (409 beim parallelen Abschluss), `other`.
  final String? code;
}

/// Server-Gegenstelle der Belegerfassung (HTTP-Adapter: `HttpReceiptApi`). Wirft [NetworkUnavailable] oder [ReceiptApiError].
abstract interface class ReceiptApi {
  /// Idempotente Anlage über die Client-UUID: neues Ticket (201), vorhandenes Ticket (200, Wiederaufnahme) oder Ticket des schon angelegten Belegs (`documentId` gesetzt).
  Future<ReceiptTicket> register(ReceiptDraft draft);

  /// Lädt die Rohbild-Bytes in die Quarantäne (vorsigniertes Ziel). Wirft [NetworkUnavailable] bei Abbruch.
  Future<void> upload(ReceiptTicket ticket, Uint8List bytes, String mime);

  Future<ReceiptCompletion> complete(ReceiptTicket ticket);

  /// Legt die bearbeitete Ansicht ab (`PUT …/edited-view`, `If-Match` = Belegrevision); liefert die neue Revision.
  Future<int> attachEditedView(String documentId, int expectedRevision, Uint8List bytes, String mime);
}
