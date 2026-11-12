/// HTTP-Adapter der Belegerfassung (WP-M4-25/-26) für `/v1/expenses/receipts`. Die Client-UUID geht als `client_uuid` hinaus; jede Anfrage trägt einen frischen Idempotency-Key
/// (die fachliche Idempotenz liegt an der Client-UUID, nicht am Header). Statuscodes laut `api/receipts.yaml`: 201 neu, 200 vorhanden, 409 Hash-Konflikt, 403 Vorgang nicht zugewiesen.
/// Logik mit `flutter test` ausgeführt (Attrappen); die Anbindung an Gerät und echten Server ist nicht ausgeführt (siehe README).
library;

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import '../offline/offline_queue.dart' show generateUuidV4;
import 'receipt_draft.dart';
import 'receipt_ports.dart';

class ReceiptHttpReply {
  const ReceiptHttpReply(this.status, this.body, {this.headers = const <String, String>{}});
  final int status;
  final String body;
  final Map<String, String> headers;
}

abstract interface class ReceiptTransport {
  /// Wirft [SocketException] oder [HttpException] bei Netzfehlern.
  Future<ReceiptHttpReply> send(String method, Uri url, {required Map<String, String> headers, List<int>? body});
}

class IoReceiptTransport implements ReceiptTransport {
  IoReceiptTransport({HttpClient? client}) : _client = client ?? HttpClient();
  final HttpClient _client;

  @override
  Future<ReceiptHttpReply> send(String method, Uri url, {required Map<String, String> headers, List<int>? body}) async {
    final HttpClientRequest req = await _client.openUrl(method, url);
    headers.forEach(req.headers.set);
    if (body != null) req.add(body);
    final HttpClientResponse res = await req.close();
    final String text = await res.transform(utf8.decoder).join();
    final Map<String, String> h = <String, String>{};
    res.headers.forEach((String k, List<String> v) => h[k.toLowerCase()] = v.join(','));
    return ReceiptHttpReply(res.statusCode, text, headers: h);
  }
}

class HttpReceiptApi implements ReceiptApi {
  HttpReceiptApi({required this.transport, required this.baseUri, required this.accessToken, required this.legalEntityId});

  final ReceiptTransport transport;
  final Uri baseUri;
  final Future<String?> Function() accessToken;
  final String legalEntityId;

  Future<Map<String, String>> _headers({String? ifMatch, bool json = true}) async {
    final String? token = await accessToken();
    if (token == null) throw const ReceiptApiError(401, code: 'auth');
    return <String, String>{
      'Authorization': 'Bearer $token',
      if (json) 'Content-Type': 'application/json',
      'Idempotency-Key': generateUuidV4(),
      if (ifMatch != null) 'If-Match': '"$ifMatch"',
    };
  }

  Future<ReceiptHttpReply> _send(String method, Uri url, Map<String, String> headers, {List<int>? body}) async {
    try {
      return await transport.send(method, url, headers: headers, body: body);
    } on SocketException {
      throw const NetworkUnavailable();
    } on HttpException {
      throw const NetworkUnavailable();
    }
  }

  Map<String, Object?> _json(String body) => (jsonDecode(body) as Map<Object?, Object?>).cast<String, Object?>();

  @override
  Future<ReceiptTicket> register(ReceiptDraft draft) async {
    final ReceiptHttpReply r = await _send('POST', baseUri.resolve('/v1/expenses/receipts'), await _headers(), body: utf8.encode(jsonEncode(draft.toTicketBody(legalEntityId: legalEntityId))));
    if (r.status == 200 || r.status == 201) {
      final Map<String, Object?> j = _json(r.body);
      final Map<String, Object?>? up = (j['upload'] as Map<Object?, Object?>?)?.cast<String, Object?>();
      return ReceiptTicket(
        ticketId: j['ticket_id']! as String,
        revision: j['revision']! as int,
        uploadKey: j['upload_key']! as String,
        uploadUrl: up?['url'] as String?,
        uploadHeaders: ((up?['headers'] as Map<Object?, Object?>?) ?? <Object?, Object?>{}).cast<String, String>(),
        documentId: j['document_id'] as String?,
      );
    }
    throw _error(r);
  }

  @override
  Future<void> upload(ReceiptTicket ticket, Uint8List bytes, String mime) async {
    final ReceiptHttpReply r = await _send('PUT', Uri.parse(ticket.uploadUrl!), <String, String>{...ticket.uploadHeaders, 'Content-Type': mime}, body: bytes);
    if (r.status >= 500 || r.status == 429) throw NetworkUnavailable('HTTP ${r.status}');
    if (r.status < 200 || r.status > 299) throw ReceiptApiError(r.status, code: 'other');
  }

  @override
  Future<ReceiptCompletion> complete(ReceiptTicket ticket) async {
    final ReceiptHttpReply r = await _send('POST', baseUri.resolve('/v1/expenses/receipts/${ticket.ticketId}/complete'), await _headers(ifMatch: '${ticket.revision}', json: false));
    if (r.status == 200 || r.status == 201) {
      final Map<String, Object?> j = _json(r.body);
      return ReceiptCompletion(documentId: j['document_id']! as String, revision: j['revision']! as int, sha256B64: j['sha256']! as String, created: r.status == 201);
    }
    throw _error(r);
  }

  @override
  Future<int> attachEditedView(String documentId, int expectedRevision, Uint8List bytes, String mime) async {
    final ReceiptHttpReply r = await _send('PUT', baseUri.resolve('/v1/expenses/receipts/$documentId/edited-view'), await _headers(ifMatch: '$expectedRevision'),
        body: utf8.encode(jsonEncode(<String, Object?>{'mime': mime, 'content_base64': base64.encode(bytes)})));
    if (r.status == 200) return _json(r.body)['revision']! as int;
    throw _error(r);
  }

  /// Statuscode auf die fachlichen Hinweise abbilden; 5xx und 429 sind vorübergehend.
  Object _error(ReceiptHttpReply r) {
    if (r.status >= 500 || r.status == 429) return NetworkUnavailable('HTTP ${r.status}');
    final String code = switch (r.status) {
      403 => 'caseNotAssigned',
      409 => r.body.contains('läuft bereits') ? 'inProgress' : 'hashConflict',
      422 => 'transferCorrupted',
      _ => 'other',
    };
    return ReceiptApiError(r.status, code: code);
  }
}
