import 'package:flutter/material.dart';

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
