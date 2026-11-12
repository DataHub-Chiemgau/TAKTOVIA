/// Synchronisation der Belegentwürfe (WP-M4-26, LH-I.1-DOC-013): lokal zwischengespeicherte Belege werden nach der Erfassung genau einmal am Server angelegt.
/// Genau einmal entsteht aus: die Client-UUID steht vor dem Upload fest und ändert sich nie; der Server legt über `(Mandant, Client-UUID)` idempotent an (201 neu, 200 vorhanden);
/// der Entwurf bleibt in der lokalen Ablage, bis der Server den Beleg bestätigt hat. Abbruch und Wiederholung (auch nach App-Neustart) führen auf dasselbe Ticket bzw. denselben Beleg.
/// Logik mit `flutter test` ausgeführt (Attrappen); die Anbindung an Gerät und echten Server ist nicht ausgeführt (siehe README).
library;

import 'dart:convert';

import 'receipt_draft.dart';
import 'receipt_ports.dart';

class ReceiptSyncResult {
  const ReceiptSyncResult({required this.synced, required this.stillLocal, required this.conflicts});
  final int synced;
  final int stillLocal;
  final int conflicts;
}

class ReceiptSyncEngine {
  ReceiptSyncEngine({required this.store, required this.api, this.maxCompleteRetries = 5});

  final ReceiptDraftStore store;
  final ReceiptApi api;

  /// Wiederholungen, wenn ein paralleler Lauf den Abschluss gerade ausführt (409).
  final int maxCompleteRetries;

  final Map<String, Future<ReceiptDraft>> _running = <String, Future<ReceiptDraft>>{};

  /// Überträgt alle noch nicht synchronisierten Entwürfe in Erfassungsreihenfolge. Ein Netzfehler beendet den Lauf, die Entwürfe bleiben lokal.
  Future<ReceiptSyncResult> syncAll() async {
    int synced = 0, local = 0, conflicts = 0;
    for (final ReceiptDraft d in await store.unsynced()) {
      final ReceiptDraft r = await syncOne(d);
      switch (r.status) {
        case ReceiptSyncStatus.synced:
          synced++;
        case ReceiptSyncStatus.conflict:
          conflicts++;
        case ReceiptSyncStatus.local:
          local++;
      }
    }
    return ReceiptSyncResult(synced: synced, stillLocal: local, conflicts: conflicts);
  }

  /// Überträgt einen Entwurf. Ein zweiter Aufruf für dieselbe Client-UUID während eines laufenden Laufs teilt sich dessen Ergebnis (kein paralleler zweiter Upload in dieser App).
  Future<ReceiptDraft> syncOne(ReceiptDraft draft) => _running[draft.clientUuid] ??= _sync(draft).whenComplete(() {
        _running.remove(draft.clientUuid); // Block statt Ausdruck: ein zurückgegebenes Future würde auf sich selbst warten
      });

  Future<ReceiptDraft> _sync(ReceiptDraft draft) async {
    if (draft.status == ReceiptSyncStatus.synced) return draft;
    try {
      final ReceiptTicket ticket = await api.register(draft);
      String? documentId = ticket.documentId;
      if (documentId == null) {
        if (ticket.uploadUrl == null) return await _mark(draft, ReceiptSyncStatus.local, ReceiptSyncIssue.uploadTargetUnavailable);
        await api.upload(ticket, draft.rawBytes, draft.mime);
        final ReceiptCompletion c = await _complete(ticket);
        documentId = c.documentId;
        // Der Server hat den Hash aus den übertragenen Bytes berechnet: er muss dem lokal berechneten entsprechen, sonst ist es kein unverändertes Original.
        if (c.sha256B64 != _b64(draft.rawSha256)) return await _mark(draft, ReceiptSyncStatus.conflict, ReceiptSyncIssue.hashConflict);
        draft.documentId = documentId;
        draft.status = ReceiptSyncStatus.synced;
        draft.issue = ReceiptSyncIssue.none;
        await store.save(draft);
        await _attachEditedView(draft, c.revision);
        return draft;
      }
      draft.documentId = documentId;
      draft.status = ReceiptSyncStatus.synced;
      draft.issue = ReceiptSyncIssue.none;
      await store.save(draft);
      return draft;
    } on NetworkUnavailable {
      // Ohne Netz oder bei Abbruch (z. B. nach 50 %): Entwurf bleibt lokal, kein Fehlerdialog; die nächste Synchronisation setzt mit derselben UUID fort.
      return _mark(draft, ReceiptSyncStatus.local, draft.documentId == null ? ReceiptSyncIssue.offline : ReceiptSyncIssue.uploadAborted);
    } on ReceiptApiError catch (e) {
      switch (e.code) {
        case 'hashConflict':
          return _mark(draft, ReceiptSyncStatus.conflict, ReceiptSyncIssue.hashConflict);
        case 'caseNotAssigned':
          return _mark(draft, ReceiptSyncStatus.local, ReceiptSyncIssue.caseNotAssigned);
        case 'transferCorrupted':
          return _mark(draft, ReceiptSyncStatus.local, ReceiptSyncIssue.transferCorrupted);   // nächster Lauf: neues Ticket derselben UUID
        default:
          return _mark(draft, ReceiptSyncStatus.local, e.status >= 500 || e.status == 429 ? ReceiptSyncIssue.offline : ReceiptSyncIssue.rejected);
      }
    }
  }

  Future<ReceiptCompletion> _complete(ReceiptTicket ticket) async {
    for (int attempt = 0;; attempt++) {
      try {
        return await api.complete(ticket);
      } on ReceiptApiError catch (e) {
        if (e.code != 'inProgress' || attempt >= maxCompleteRetries) rethrow;
      }
    }
  }

  /// Die bearbeitete Ansicht folgt dem Beleg; scheitert sie, bleibt der Beleg synchronisiert und die Ansicht wird beim nächsten Lauf nachgeholt.
  Future<void> _attachEditedView(ReceiptDraft draft, int revision) async {
    final edited = draft.editedBytes;
    if (edited == null) return;
    try {
      await api.attachEditedView(draft.documentId!, revision, edited, 'image/jpeg');
    } on NetworkUnavailable {
      // wird nachgeholt
    } on ReceiptApiError {
      // wird nachgeholt (z. B. Beleg noch in Quarantäne)
    }
  }

  Future<ReceiptDraft> _mark(ReceiptDraft d, ReceiptSyncStatus status, ReceiptSyncIssue issue) async {
    d.status = status;
    d.issue = issue;
    await store.save(d);
    return d;
  }

  /// Hash base64url ohne Auffüllung (wie am Server) aus der Hexdarstellung.
  String _b64(String hex) => base64Url.encode(<int>[for (int i = 0; i < hex.length; i += 2) int.parse(hex.substring(i, i + 2), radix: 16)]).replaceAll('=', '');
}
