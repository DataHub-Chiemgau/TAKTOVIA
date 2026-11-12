import 'dart:typed_data';

import 'receipt_draft.dart';
import 'receipt_ports.dart';

/// Ablage im Arbeitsspeicher; Referenzverhalten des Ports, nur für Tests. Speichert wie die verschlüsselte Ablage Metadaten und Bilder getrennt und liefert bei jedem Laden eine neue Instanz
/// (ein „App-Neustart“ im Test = neue Engine/Flow auf demselben Speicher).
class InMemoryReceiptDraftStore implements ReceiptDraftStore {
  final Map<String, Map<String, Object?>> _meta = <String, Map<String, Object?>>{};
  final Map<String, Uint8List> _raw = <String, Uint8List>{};
  final Map<String, Uint8List> _edited = <String, Uint8List>{};
  final List<String> _order = <String>[];

  @override
  Future<void> save(ReceiptDraft d) async {
    if (!_meta.containsKey(d.clientUuid)) _order.add(d.clientUuid);
    _meta[d.clientUuid] = d.toStoredJson();
    _raw[d.clientUuid] = d.rawBytes;
    final Uint8List? e = d.editedBytes;
    if (e != null) _edited[d.clientUuid] = Uint8List.fromList(e);
  }

  ReceiptDraft _build(String id) => ReceiptDraft.fromStored(_meta[id]!, raw: _raw[id]!, edited: _edited[id]);

  @override
  Future<ReceiptDraft?> load(String clientUuid) async => _meta.containsKey(clientUuid) ? _build(clientUuid) : null;

  @override
  Future<List<ReceiptDraft>> all() async => <ReceiptDraft>[for (final String id in _order) _build(id)];

  @override
  Future<List<ReceiptDraft>> unsynced() async => (await all()).where((ReceiptDraft d) => d.status != ReceiptSyncStatus.synced).toList();
}
