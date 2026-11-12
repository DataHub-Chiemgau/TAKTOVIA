import 'package:flutter/material.dart';

import 'auth/session_gate.dart';
import 'offline/models.dart';
import 'ui/accessibility.dart';
import 'ui/offline_banner.dart';
import 'ui/strings.dart';

void main() {
  runApp(const DriverApp());
}

/// Rahmen der Fahrer-App: Anmeldepforte, Navigation, Offlinebanner. Ohne aktive Sitzung zeigt die App keine Auftragsdaten.
/// Die Verdrahtung mit OIDC, Speicher und Sync (Hauptprogramm) folgt beim ersten Flutter-Lauf; hier ist der Rahmen testbar.
class DriverApp extends StatelessWidget {
  const DriverApp({
    super.key,
    this.state = SessionState.signedOut,
    this.online = true,
    this.snapshot,
    this.pendingEvents = 0,
    this.onSignIn,
    this.onReportDefect,
    this.strings = Strings.de,
  });

  final SessionState state;
  final bool online;
  final DutySnapshot? snapshot;
  final int pendingEvents;
  final VoidCallback? onSignIn;
  final VoidCallback? onReportDefect;
  final Strings strings;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: strings.of('app.title'),
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: state == SessionState.active ? _RunScreen(strings: strings, online: online, snapshot: snapshot, pendingEvents: pendingEvents, onReportDefect: onReportDefect) : _LoginScreen(strings: strings, state: state, onSignIn: onSignIn),
    );
  }
}

class _LoginScreen extends StatelessWidget {
  const _LoginScreen({required this.strings, required this.state, required this.onSignIn});
  final Strings strings;
  final SessionState state;
  final VoidCallback? onSignIn;

  @override
  Widget build(BuildContext context) {
    final String message = switch (state) {
      SessionState.expired => strings.of('session.expired'),
      SessionState.revoked => strings.of('session.revoked'),
      _ => strings.of('login.required'),
    };
    return Scaffold(
      appBar: AppBar(title: Text(strings.of('app.title'))),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: onSignIn,
              style: FilledButton.styleFrom(minimumSize: const Size(kMinTouchTarget, kMinTouchTarget)),
              child: Text(strings.of('login.button')),
            ),
          ],
        ),
      ),
    );
  }
}

class _RunScreen extends StatelessWidget {
  const _RunScreen({required this.strings, required this.online, required this.snapshot, required this.pendingEvents, this.onReportDefect});
  final Strings strings;
  final bool online;
  final DutySnapshot? snapshot;
  final int pendingEvents;
  final VoidCallback? onReportDefect;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(strings.of('nav.run'))),
      body: Column(
        children: <Widget>[
          OfflineBanner(online: online, planAsOfUtc: snapshot?.asOfUtc, pendingEvents: pendingEvents, strings: strings),
          // Einstieg „Mangel melden“ ohne aktive Fahrt (WP-M3-43/-44): die Meldung verlangt weder Fahrt noch Buchung noch laufende Prüfung
          Padding(
            padding: const EdgeInsets.all(16),
            child: FilledButton(
              onPressed: onReportDefect,
              style: FilledButton.styleFrom(minimumSize: const Size(kMinTouchTarget, kMinTouchTarget)),
              child: Text(strings.of('defect.report')),
            ),
          ),
          Expanded(
            child: snapshot == null
                ? const SizedBox.shrink()
                : ListView(
                    children: <Widget>[
                      Padding(padding: const EdgeInsets.all(16), child: Text(snapshot!.standLabel)),
                      for (final StopEntry s in snapshot!.stops)
                        Semantics(label: 'Stopp ${s.sequence}: ${s.label}, ${s.status}', child: ListTile(title: Text('${s.sequence}. ${s.label}'), subtitle: Text(s.status))),
                    ],
                  ),
          ),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        destinations: <NavigationDestination>[
          NavigationDestination(icon: const Icon(Icons.route), label: strings.of('nav.run')),
          NavigationDestination(icon: const Icon(Icons.fact_check), label: strings.of('nav.inspection')),
          NavigationDestination(icon: const Icon(Icons.report_problem), label: strings.of('nav.defect')),
          NavigationDestination(icon: const Icon(Icons.settings), label: strings.of('nav.settings')),
        ],
        selectedIndex: 0,
      ),
    );
  }
}
