import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/auth/session_gate.dart';
import 'package:taktovia_driver/main.dart';
import 'package:taktovia_driver/offline/models.dart';
import 'package:taktovia_driver/ui/accessibility.dart';

DutySnapshot snap() => DutySnapshot(
      dutyId: 'd1',
      planRevision: 4,
      asOfUtc: DateTime.utc(2026, 11, 12, 8, 5),
      stops: const <StopEntry>[StopEntry(stopId: 's1', sequence: 1, label: 'Markt', status: 'offen')],
    );

void main() {
  testWidgets('ohne Sitzung keine Auftragsdaten, nur Anmeldung', (WidgetTester tester) async {
    await tester.pumpWidget(DriverApp(state: SessionState.signedOut, snapshot: snap()));
    expect(find.text('Anmelden'), findsOneWidget);
    expect(find.text('1. Markt'), findsNothing);
    expect(find.textContaining('Stand von'), findsNothing);
  });

  testWidgets('abgelaufene und gesperrte Sitzung zeigen keine Auftragsdaten', (WidgetTester tester) async {
    for (final SessionState s in <SessionState>[SessionState.expired, SessionState.revoked]) {
      await tester.pumpWidget(DriverApp(state: s, snapshot: snap()));
      expect(find.text('1. Markt'), findsNothing, reason: '$s');
      expect(find.text('Anmelden'), findsOneWidget);
    }
  });

  testWidgets('aktive Sitzung offline zeigt Stoppliste und Banner mit Planzeit', (WidgetTester tester) async {
    await tester.pumpWidget(DriverApp(state: SessionState.active, online: false, snapshot: snap()));
    expect(find.text('1. Markt'), findsOneWidget);
    expect(find.text('Offline. Letzter Plan: 2026-11-12 08:05 UTC'), findsOneWidget);
    expect(find.textContaining('Stand von 2026-11-12 08:05 UTC'), findsOneWidget);
  });

  testWidgets('Anmeldeschaltflaeche hat mindestens 44x44', (WidgetTester tester) async {
    await tester.pumpWidget(const DriverApp());
    final Size size = tester.getSize(find.byType(FilledButton));
    expect(size.width, greaterThanOrEqualTo(kMinTouchTarget));
    expect(size.height, greaterThanOrEqualTo(kMinTouchTarget));
  });
}
