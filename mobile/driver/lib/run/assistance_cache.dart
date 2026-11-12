import 'run_list.dart';

/// Raeumt lokale Unterstuetzungshinweise (WP-M3-32): mit Fahrtabschluss und Schichtende sind sie weder abrufbar noch im lokalen Cache. Die Hinweise werden nie getrennt vom Plan
/// gespeichert; es genuegt, den Plan-Stand der Schicht zu loeschen. Zusaetzlich entfernt [purgeAll] jeden Eintrag, der noch Merkmale traegt (Sicherheitsnetz nach Absturz).
class AssistanceCache {
  AssistanceCache(this.store);
  final RunSnapshotStore store;

  /// Schichtende oder Fahrtabschluss der Schicht [shiftId]: Plan-Stand und Merkmale loeschen.
  Future<void> onShiftEnded(String shiftId) => store.delete(shiftId);

  /// Loescht alle Eintraege mit Unterstuetzungsmerkmalen (z. B. beim Start der App oder nach Abmeldung); liefert die Zahl der entfernten Eintraege.
  Future<int> purgeAll() async {
    int removed = 0;
    for (final String key in await store.keys()) {
      final String? json = await store.read(key);
      if (json != null && json.contains('"assistance"')) {
        await store.delete(key);
        removed++;
      }
    }
    return removed;
  }
}
