import 'dart:io';
import 'dart:math';

import '../auth/oidc.dart';
import '../auth/session_gate.dart';
import 'models.dart';
import 'ports.dart';

/// Name des Eintrags im Schluesselbund (Android Keystore / iOS Keychain).
const String dbKeyName = 'outbox.db.key.v1';
const String integrityKeyName = 'outbox.integrity.key.v1';

String _hex(List<int> bytes) => bytes.map((int b) => b.toRadixString(16).padLeft(2, '0')).join();

/// Erzeugt beim ersten Start je 256-Bit-Schluessel fuer Datenbank und Zeilenintegritaet und haelt sie ausschliesslich im Schluesselbund.
/// Die Schluessel verlassen das Geraet nie und stehen nie im Repository oder in der Datenbankdatei.
class LocalKeys {
  LocalKeys._(this.dbHexKey, this.integrityKey);
  final String dbHexKey;
  final List<int> integrityKey;

  static Future<LocalKeys> loadOrCreate(SecretVault vault, [Random? random]) async {
    final Random r = random ?? Random.secure();
    Future<String> keyFor(String name) async {
      final String? existing = await vault.read(name);
      if (existing != null) return existing;
      final String created = _hex(List<int>.generate(32, (_) => r.nextInt(256)));
      await vault.write(name, created);
      return created;
    }

    final String db = await keyFor(dbKeyName);
    final String integrity = await keyFor(integrityKeyName);
    return LocalKeys._(db, List<int>.generate(32, (int i) => int.parse(integrity.substring(i * 2, i * 2 + 2), radix: 16)));
  }
}

/// Raeumt lokale Daten ab: Auftragsdaten (Lesestand) bei beendeter Sitzung, alles bei Abmeldung.
/// Nach [wipeAll] fehlt der Schluessel; eine zurueckgebliebene Datei ist ohne ihn nicht lesbar und wird zusaetzlich geloescht.
class FileLocalDataWiper implements LocalDataWiper {
  FileLocalDataWiper({required this.dbPath, required this.vault, required this.outbox, required this.clearSnapshot});

  final String dbPath;
  final SecretVault vault;
  final OutboxStore outbox;

  /// Entfernt den Offline-Lesestand (Dienst und Stoppliste); die Umsetzung des Snapshot-Speichers stellt sie bereit.
  final Future<void> Function() clearSnapshot;

  @override
  Future<void> wipeOrderData() => clearSnapshot();

  @override
  Future<void> wipeAll() async {
    await vault.delete(dbKeyName);
    await vault.delete(integrityKeyName);
    for (final String suffix in <String>['', '-wal', '-shm', '-journal']) {
      final File f = File('$dbPath$suffix');
      if (await f.exists()) await f.delete();
    }
  }

  @override
  Future<int> unsyncedCount() async => (await outbox.all()).where((QueuedEvent e) => e.state != EventState.synced).length;
}

/// Zugriffstoken-Quelle fuer den HTTP-Adapter: liefert null, wenn die Sitzung nicht aktiv ist (dann bleibt alles in der Outbox).
Future<String?> Function() accessTokenFor(SessionGate gate, TokenSet? Function() current, DateTime Function() now) {
  return () async {
    final TokenSet? t = current();
    if (!gate.mayShowOrderData || t == null || t.isExpired(now())) return null;
    return t.accessToken;
  };
}
