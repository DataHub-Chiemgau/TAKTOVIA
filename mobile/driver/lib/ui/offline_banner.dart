import 'package:flutter/material.dart';

import '../net/connection_state.dart';
import 'accessibility.dart';
import 'strings.dart';

/// Offlinebanner mit der Zeit des letzten bestaetigten Plans (WP-M3-24). Text und Symbol, nicht nur Farbe.
class OfflineBanner extends StatelessWidget {
  const OfflineBanner({super.key, required this.online, required this.planAsOfUtc, this.pendingEvents = 0, this.strings = Strings.de});
  final bool online;
  final DateTime? planAsOfUtc;
  final int pendingEvents;
  final Strings strings;

  static String formatTime(DateTime utc) {
    String two(int v) => v.toString().padLeft(2, '0');
    final DateTime t = utc.toUtc();
    return '${t.year}-${two(t.month)}-${two(t.day)} ${two(t.hour)}:${two(t.minute)} UTC';
  }

  @override
  Widget build(BuildContext context) {
    if (online && pendingEvents == 0) return const SizedBox.shrink();
    final String text = online
        ? strings.of('online.pending', <String, String>{'count': '$pendingEvents'})
        : (planAsOfUtc == null ? strings.of('offline.noPlan') : strings.of('offline.banner', <String, String>{'time': formatTime(planAsOfUtc!)}));
    return Material(
      color: Theme.of(context).colorScheme.secondaryContainer,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: kMinTouchTarget),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: StatusChip(icon: online ? Icons.sync : Icons.cloud_off, text: text, color: Theme.of(context).colorScheme.onSecondaryContainer),
        ),
      ),
    );
  }
}

/// Verbindungszustand als Banner (WP-M5-09, UI-09/UI-10): Symbol und Text (nie nur Farbe), als Live-Region fuer Screenreader, Datenstand statt Echtzeit.
/// Nicht ausgefuehrt (kein Flutter in der Umgebung); die Logik steht in `lib/net/connection_state.dart`.
class ConnectionBannerView extends StatelessWidget {
  const ConnectionBannerView({super.key, required this.view});
  final ConnectionView view;

  static const Map<String, IconData> _icons = <String, IconData>{
    'check': Icons.check_circle,
    'warning': Icons.warning_amber,
    'cloud_off': Icons.cloud_off,
    'sync': Icons.sync,
    'priority_high': Icons.priority_high,
  };

  @override
  Widget build(BuildContext context) {
    if (view.state == DriverConnection.online) return const SizedBox.shrink();
    final String text = view.noLiveText == null ? view.bannerText : '${view.bannerText} ${view.noLiveText}';
    return Semantics(
      liveRegion: true,
      container: true,
      label: text,
      child: Material(
        color: Theme.of(context).colorScheme.secondaryContainer,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: kMinTouchTarget),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: StatusChip(icon: _icons[view.icon] ?? Icons.info, text: text, color: Theme.of(context).colorScheme.onSecondaryContainer),
          ),
        ),
      ),
    );
  }
}
