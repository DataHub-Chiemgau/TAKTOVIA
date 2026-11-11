import 'package:flutter/material.dart';

void main() {
  runApp(const DriverApp());
}

/// Geruest der Fahrer-App. Keine Fahrerbildschirme (UI-09 ff. ab M3).
class DriverApp extends StatelessWidget {
  const DriverApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      title: 'TAKTOVIA Fahrer',
      home: Scaffold(
        body: Center(child: Text('TAKTOVIA Fahrer – Geruest')),
      ),
    );
  }
}
