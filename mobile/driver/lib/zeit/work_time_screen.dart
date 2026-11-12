import 'package:flutter/material.dart';

import '../ui/accessibility.dart';
import '../ui/strings.dart';
import 'work_time.dart';

/// Fahrbewegung laut Fahrzeug-/Positionsdaten: waehrend der Fahrt oeffnet sich kein Erfassungsdialog (Kap. 06 §6.1/§6.7).
abstract interface class WorkTimeMovement {
  bool get vehicleMoving;
}

/// Bereich Arbeitszeit im Dienststart (UI-09, „Arbeitszeit: gestartet“): Kommen, Pause, Gehen. Arbeitszeitbeginn und technische Einsatzfreigabe sind getrennt sichtbar
/// ([releaseGranted] kommt vom Server nach bestaetigter Pruefung und aendert sich durch Stempeln nie). Status immer als Symbol und Text, nie nur als Farbe.
/// NICHT AUSGEFUEHRT: Flutter/Dart ist in dieser Umgebung nicht lauffaehig.
class WorkTimeScreen extends StatefulWidget {
  const WorkTimeScreen({
    super.key,
    required this.controller,
    required this.movement,
    required this.releaseGranted,
    this.shiftId,
    this.strings = Strings.de,
    this.formatTime,
  });

  final WorkTimeController controller;
  final WorkTimeMovement movement;
  final bool releaseGranted;
  final String? shiftId;
  final Strings strings;
  final String Function(DateTime)? formatTime;

  @override
  State<WorkTimeScreen> createState() => _WorkTimeScreenState();
}

class _WorkTimeScreenState extends State<WorkTimeScreen> {
  WorkTimeStatus? _status;
  String? _hint;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    final WorkTimeStatus s = await widget.controller.status();
    if (mounted) setState(() => _status = s);
  }

  String _time(DateTime? t) => t == null ? '' : (widget.formatTime ?? (DateTime d) => '${d.toLocal().hour.toString().padLeft(2, '0')}:${d.toLocal().minute.toString().padLeft(2, '0')}')(t);

  Future<void> _stamp(StampKind kind) async {
    final Strings t = widget.strings;
    final bool needsConfirm = kind == StampKind.workEnd;
    if (widget.movement.vehicleMoving) {
      // Waehrend der Fahrbewegung nie ein Dialog: nur ein Hinweis im Bildschirm, der Stempel wird nicht erfasst.
      setState(() => _hint = t.of('worktime.moving'));
      return;
    }
    if (needsConfirm) {
      final bool? ok = await showDialog<bool>(
        context: context,
        builder: (BuildContext c) => AlertDialog(
          content: Text(t.of('worktime.confirmOut')),
          actions: <Widget>[
            TextButton(onPressed: () => Navigator.of(c).pop(false), child: Text(t.of('worktime.confirmNo'))),
            TextButton(onPressed: () => Navigator.of(c).pop(true), child: Text(t.of('worktime.confirmYes'))),
          ],
        ),
      );
      if (ok != true) return;
    }
    try {
      await widget.controller.stamp(kind, shiftId: widget.shiftId);
      _hint = null;
    } on StampNotAllowed catch (e) {
      _hint = e.phase == WorkPhase.working && kind == StampKind.workStart ? t.of('worktime.alreadyRunning') : t.of('worktime.notAllowed');
    }
    await _reload();
  }

  @override
  Widget build(BuildContext context) {
    final Strings t = widget.strings;
    final WorkTimeStatus? s = _status;
    if (s == null) return const SizedBox.shrink();
    final (IconData, String) status = switch (s.phase) {
      WorkPhase.idle => (Icons.stop_circle_outlined, t.of('worktime.idle')),
      WorkPhase.working => (Icons.play_circle_outline, t.of('worktime.working', <String, String>{'time': _time(s.since)})),
      WorkPhase.onBreak => (Icons.pause_circle_outline, t.of('worktime.onBreak', <String, String>{'time': _time(s.since)})),
    };
    final String release = t.of('worktime.release', <String, String>{'state': t.of(widget.releaseGranted ? 'worktime.release.granted' : 'worktime.release.pending')});
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Semantics(header: true, child: Text(t.of('worktime.title'))),
        StatusChip(key: const ValueKey<String>('worktime-status'), icon: status.$1, text: status.$2, color: Theme.of(context).colorScheme.primary),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 8, children: <Widget>[
          if (s.phase == WorkPhase.idle) _button('worktime.clockIn', StampKind.workStart),
          if (s.phase == WorkPhase.working) _button('worktime.breakStart', StampKind.breakStart),
          if (s.phase == WorkPhase.onBreak) _button('worktime.breakEnd', StampKind.breakEnd),
          if (s.phase != WorkPhase.idle) _button('worktime.clockOut', StampKind.workEnd),
        ]),
        if (_hint != null) Semantics(liveRegion: true, child: Text(_hint!, key: const ValueKey<String>('worktime-hint'))),
        if (s.unsynced > 0) Text(t.of('worktime.unsynced', <String, String>{'count': '${s.unsynced}'}), key: const ValueKey<String>('worktime-unsynced')),
        if (s.needsReview > 0) Text(t.of('worktime.review', <String, String>{'count': '${s.needsReview}'}), key: const ValueKey<String>('worktime-review')),
        // Einsatzfreigabe getrennt vom Arbeitszeitbeginn
        Text(release, key: const ValueKey<String>('worktime-release')),
      ],
    );
  }

  Widget _button(String key, StampKind kind) => AccessibleTap(
        label: widget.strings.of(key),
        onTap: () => _stamp(kind),
        child: Padding(padding: const EdgeInsets.symmetric(horizontal: 16), child: Text(widget.strings.of(key))),
      );
}
