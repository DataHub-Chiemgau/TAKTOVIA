// NICHT AUSGEFÜHRT: Flutter ist in der Erstellungsumgebung nicht verfügbar (mobile/driver/README.md).
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/auth/session_gate.dart';
import 'package:taktovia_driver/main.dart';

void main() {
  testWidgets('einstieg_mangel_melden_ohne_aktive_fahrt', (WidgetTester tester) async {
    bool opened = false;
    // aktive Sitzung, kein Dienst und keine Fahrt geladen: der Einstieg „Mangel melden“ ist trotzdem da
    await tester.pumpWidget(DriverApp(state: SessionState.active, snapshot: null, onReportDefect: () => opened = true));
    expect(find.text('Mangel melden'), findsOneWidget);
    await tester.tap(find.text('Mangel melden'));
    expect(opened, isTrue);
    // ohne Sitzung kein Einstieg
    await tester.pumpWidget(const DriverApp(state: SessionState.signedOut));
    expect(find.text('Mangel melden'), findsNothing);
  });
}
