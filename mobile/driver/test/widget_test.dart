import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/main.dart';

void main() {
  testWidgets('Geruest zeigt den Titel', (WidgetTester tester) async {
    await tester.pumpWidget(const DriverApp());
    expect(find.text('TAKTOVIA Fahrer – Geruest'), findsOneWidget);
  });
}
