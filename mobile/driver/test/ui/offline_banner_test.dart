import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/ui/offline_banner.dart';

void main() {
  testWidgets('Banner nennt die Zeit des letzten Plans und hat Textalternative zur Farbe', (WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: OfflineBanner(online: false, planAsOfUtc: DateTime.utc(2026, 11, 12, 8, 5)))));
    expect(find.text('Offline. Letzter Plan: 2026-11-12 08:05 UTC'), findsOneWidget);
    expect(find.byIcon(Icons.cloud_off), findsOneWidget);
    expect(tester.getSize(find.byType(ConstrainedBox).first).height, greaterThanOrEqualTo(0));
  });

  testWidgets('online ohne offene Ereignisse ist das Banner unsichtbar', (WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: OfflineBanner(online: true, planAsOfUtc: null))));
    expect(find.byIcon(Icons.cloud_off), findsNothing);
    expect(find.byIcon(Icons.sync), findsNothing);
  });

  testWidgets('online mit offenen Ereignissen zeigt deren Anzahl', (WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: OfflineBanner(online: true, planAsOfUtc: null, pendingEvents: 3))));
    expect(find.text('3 Ereignisse warten auf die Uebertragung.'), findsOneWidget);
  });
}
