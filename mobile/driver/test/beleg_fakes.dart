// Attrappen für die Belegtests (WP-M4-25/-26); ausgeführt mit Flutter 3.47.9.
import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:taktovia_driver/beleg/receipt_draft.dart';
import 'package:taktovia_driver/beleg/receipt_ports.dart';
import 'package:taktovia_driver/beleg/receipt_quality.dart';

/// Scharfes Testbild: Schachbrettmuster mit harten Kanten; unscharf: sanfter Verlauf ohne Kanten.
GrayImage sharpImage({int w = 64, int h = 64, int sourceW = 3000, int sourceH = 4000}) =>
    GrayImage(width: w, height: h, luma: <int>[for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) ((x ~/ 2 + y ~/ 2) % 2 == 0) ? 255 : 0], sourceWidth: sourceW, sourceHeight: sourceH);

GrayImage blurryImage({int w = 64, int h = 64, int sourceW = 3000, int sourceH = 4000}) =>
    GrayImage(width: w, height: h, luma: <int>[for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) 100 + (x + y) ~/ 4], sourceWidth: sourceW, sourceHeight: sourceH);

Uint8List jpegBytes(int seed, [int n = 4096]) {
  final Uint8List b = Uint8List.fromList(List<int>.generate(n, (int i) => (i * 31 + seed) & 0xff));
  b[0] = 0xff;
  b[1] = 0xd8;
  b[2] = 0xff;
  return b;
}

CapturedPhoto photo(int seed, {GrayImage? gray}) => CapturedPhoto(bytes: jpegBytes(seed), mime: 'image/jpeg', gray: gray ?? sharpImage());

class FakeCamera implements CameraPort {
  FakeCamera(this.queue);
  final List<Object> queue; // CapturedPhoto oder CameraPermissionDenied
  int calls = 0;

  @override
  Future<CapturedPhoto> capture() async {
    calls++;
    final Object next = queue.removeAt(0);
    if (next is CameraPermissionDenied) throw next;
    return next as CapturedPhoto;
  }
}

class FakeEditor implements ImageEditorPort {
  int calls = 0;

  @override
  Future<Uint8List> edit(Uint8List source, {required double left, required double top, required double right, required double bottom, required int quarterTurns}) async {
    calls++;
    return Uint8List.fromList(source.reversed.toList().sublist(0, source.length ~/ 2)); // neue, andere Datei; `source` bleibt unberührt
  }
}

class FakeMovement implements MovementPort {
  FakeMovement(this.vehicleMoving);
  @override
  bool vehicleMoving;
}

class FakeCases implements AssignedCasesPort {
  FakeCases(this.assigned);
  final List<ReceiptCaseRef> assigned;
  @override
  Future<List<ReceiptCaseRef>> assignedCases() async => assigned;
}

const ReceiptCaseRef ownVehicle = ReceiptCaseRef(ReceiptCaseKind.vehicle, '00000000-0000-4000-8000-0000000000a1', 'B-TX 101');
const ReceiptCaseRef foreignVehicle = ReceiptCaseRef(ReceiptCaseKind.vehicle, '00000000-0000-4000-8000-0000000000b2', 'B-TX 202');

String b64Hash(List<int> bytes) => base64Url.encode(sha256.convert(bytes).bytes).replaceAll('=', '');

/// Server-Attrappe mit dem Verhalten von `api/receipts.yaml`: Anlage idempotent über die Client-UUID (201/200), Hash-Konflikt 409, fremder Vorgang 403, Abschluss prüft den Hash der übertragenen Bytes.
class FakeReceiptServer implements ReceiptApi {
  FakeReceiptServer({this.assigned = const <String>{}});
  final Set<String> assigned;
  final Map<String, Map<String, Object?>> documents = <String, Map<String, Object?>>{}; // client_uuid -> Beleg
  final Map<String, Map<String, Object?>> tickets = <String, Map<String, Object?>>{};
  final Map<String, Uint8List> uploaded = <String, Uint8List>{};
  final Map<String, Uint8List> editedViews = <String, Uint8List>{};
  bool online = true;

  /// Nach so vielen Upload-Aufrufen bricht der nächste ab (Netz weg nach 50 %): ohne Wirkung am Server.
  int abortUploadsRemaining = 0;
  bool corruptNextUpload = false;
  int registerCalls = 0;
  int _n = 0;

  void _net() {
    if (!online) throw const NetworkUnavailable();
  }

  @override
  Future<ReceiptTicket> register(ReceiptDraft d) async {
    _net();
    registerCalls++;
    final Map<String, Object?> body = d.toTicketBody(legalEntityId: 'le');
    final Map<String, Object?> c = body['case_ref']! as Map<String, Object?>;
    if (!assigned.contains(c['id'])) throw const ReceiptApiError(403, code: 'caseNotAssigned');
    final Map<String, Object?>? doc = documents[d.clientUuid];
    if (doc != null) {
      if (doc['sha256'] != body['sha256']) throw const ReceiptApiError(409, code: 'hashConflict');
      return ReceiptTicket(ticketId: doc['ticket']! as String, revision: 2, uploadKey: 'k', documentId: doc['id']! as String);
    }
    final Map<String, Object?>? open = tickets[d.clientUuid];
    if (open != null) {
      if (open['sha256'] != body['sha256']) throw const ReceiptApiError(409, code: 'hashConflict');
      return ReceiptTicket(ticketId: open['id']! as String, revision: 1, uploadKey: 'k', uploadUrl: 'https://objects.test/${open['id']}');
    }
    final String id = 'ticket-${++_n}';
    tickets[d.clientUuid] = <String, Object?>{'id': id, 'sha256': body['sha256'], 'client': d.clientUuid, 'captured_at': body['captured_at']};
    return ReceiptTicket(ticketId: id, revision: 1, uploadKey: 'k', uploadUrl: 'https://objects.test/$id');
  }

  @override
  Future<void> upload(ReceiptTicket t, Uint8List bytes, String mime) async {
    _net();
    if (abortUploadsRemaining > 0) {
      abortUploadsRemaining--;
      throw const NetworkUnavailable('abgebrochen nach 50 %');
    }
    uploaded[t.ticketId] = corruptNextUpload ? (Uint8List.fromList(bytes)..[bytes.length ~/ 2] ^= 0x01) : Uint8List.fromList(bytes);
    corruptNextUpload = false;
  }

  @override
  Future<ReceiptCompletion> complete(ReceiptTicket t) async {
    _net();
    final Map<String, Object?> ticket = tickets.values.firstWhere((Map<String, Object?> x) => x['id'] == t.ticketId);
    final String client = ticket['client']! as String;
    final Map<String, Object?>? existing = documents[client];
    if (existing != null) return ReceiptCompletion(documentId: existing['id']! as String, revision: 2, sha256B64: existing['sha256']! as String, created: false);
    final Uint8List? bytes = uploaded[t.ticketId];
    if (bytes == null || b64Hash(bytes) != ticket['sha256']) {
      tickets.remove(client); // Ticket verbraucht, nichts angelegt; die Wiederholung bekommt ein neues Ticket
      throw const ReceiptApiError(422, code: 'transferCorrupted');
    }
    final String docId = 'doc-${++_n}';
    documents[client] = <String, Object?>{'id': docId, 'ticket': t.ticketId, 'sha256': ticket['sha256'], 'captured_at': ticket['captured_at'], 'received_at': DateTime.now().toUtc().toIso8601String()};
    return ReceiptCompletion(documentId: docId, revision: 1, sha256B64: ticket['sha256']! as String, created: true);
  }

  @override
  Future<int> attachEditedView(String documentId, int expectedRevision, Uint8List bytes, String mime) async {
    _net();
    editedViews[documentId] = Uint8List.fromList(bytes);
    return expectedRevision + 1;
  }
}
