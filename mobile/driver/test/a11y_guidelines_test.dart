// NICHT AUSGEFUEHRT: in der Erstellungsumgebung war kein Dart/Flutter-SDK ausfuehrbar (WP-M5-18). Automatisierte Barrierefreiheitspruefung des Mobile-Builds (Flutter-Leitlinien:
// beschriftete Tippziele, Mindestgroesse Android 48 dp bzw. iOS 44 pt, Textkontrast) fuer die Banner der P1-Oberflaechen; laeuft als Teil von `flutter test` im CI-Job `mobile`.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/net/connection_state.dart';
import 'package:taktovia_driver/ui/offline_banner.dart';

Widget host(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  testWidgets('verbindungsbanner_erfuellt_flutter_barrierefreiheitsleitlinien', (WidgetTester tester) async {
    final SemanticsHandle handle = tester.ensureSemantics();
    final ConnectionTracker t = ConnectionTracker();
    t.linkChanged(false, DateTime.utc(2026, 11, 11, 8));
    t.tick(DateTime.utc(2026, 11, 11, 8, 0, 10));
    await tester.pumpWidget(host(ConnectionBannerView(view: buildConnectionView(t))));
    await expectLater(tester, meetsGuideline(textContrastGuideline));
    await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
    await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
    await expectLater(tester, meetsGuideline(iOSTapTargetGuideline));
    expect(tester.getSemantics(find.byType(ConnectionBannerView)).hasFlag(SemanticsFlag.isLiveRegion), isTrue);
    handle.dispose();
  });

  testWidgets('offlinebanner_hat_text_und_symbol_nicht_nur_farbe', (WidgetTester tester) async {
    await tester.pumpWidget(host(const OfflineBanner(online: false, planAsOfUtc: null)));
    expect(find.byType(Icon), findsOneWidget);
    expect(find.textContaining('Offline'), findsOneWidget);
  });
}
