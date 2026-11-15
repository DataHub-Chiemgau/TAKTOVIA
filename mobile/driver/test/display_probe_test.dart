import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/run/display_probe.dart';

/// WP-M5-13 / LH-6.19-NFR-004: Messpunkt „Revision tatsaechlich dargestellt“. NICHT AUSGEFUEHRT (Flutter war in der Erstellungsumgebung nicht verfuegbar);
/// die Messkette gegen die echte Anwendung steht in tests/performance/test_planrevision_p95.py (Gerätesimulation statt dieses Geraets).
void main() {
  test('meldet_nur_hoehere_revisionen_und_nur_im_testmodus', () {
    final List<(int, DateTime)> got = <(int, DateTime)>[];
    DateTime t = DateTime.utc(2026, 12, 1, 7);
    final DisplayProbe p = DisplayProbe(enabled: true, now: () => t, sink: (int r, DateTime at) => got.add((r, at)));
    p.onFrameShown(null);
    p.onFrameShown(3);
    t = t.add(const Duration(seconds: 2));
    p.onFrameShown(4);
    p.onFrameShown(3); // aeltere Revision ueberschreibt nie eine neuere
    p.onFrameShown(4); // dieselbe Revision wird nicht doppelt gemeldet
    expect(got.map(((int, DateTime) e) => e.$1).toList(), <int>[3, 4]);
    expect(got.last.$2, DateTime.utc(2026, 12, 1, 7, 0, 2));
    expect(p.lastReportedRevision, 4);

    final List<int> off = <int>[];
    DisplayProbe(enabled: false, now: DateTime.now, sink: (int r, DateTime _) => off.add(r)).onFrameShown(5);
    DisplayProbe.disabled().onFrameShown(5);
    expect(off, isEmpty);
  });
}
