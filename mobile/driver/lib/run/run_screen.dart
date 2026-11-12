import 'package:flutter/material.dart';

import '../ui/strings.dart';
import 'driver_run.dart';
import 'run_list.dart';

/// UI-10 „Driver Fahrt“, Stoppliste (WP-M3-26, WP-M3-28, WP-M3-32): Plan-Stand, naechster Stopp, Liste mit Soll- und Prognosezeit („keine Prognose“ statt erfundener Zeit),
/// Personenzahl und notwendiger Unterstuetzung; Leer-, Fehler- und Offline-Zustand sind unterscheidbar. NICHT AUSGEFUEHRT (Flutter war in der Erstellungsumgebung nicht verfuegbar).
/// Hinweise zur Unterstuetzung werden nur angezeigt, wenn der Server sie liefert; ihre Werte stehen nie in Logs.
class RunListScreen extends StatelessWidget {
  const RunListScreen({super.key, required this.state, required this.formatTime, this.onStartNavigation});

  final RunListState state;
  final String Function(DateTime) formatTime;

  /// Aktion „Navigation starten“; null, wenn ohne gueltige Stoppliste nicht verfuegbar.
  final VoidCallback? onStartNavigation;

  static const Strings _s = Strings.de;

  @override
  Widget build(BuildContext context) {
    final List<Widget> children = <Widget>[];
    final String? stand = state.fetchedAt == null ? null : formatTime(state.fetchedAt!);
    final String revision = state.planRevision?.toString() ?? '-';
    switch (state.view) {
      case RunListView.loading:
        children.add(const LinearProgressIndicator());
      case RunListView.empty:
        children.add(Text(_s.of('run.empty'), key: const Key('run.empty')));
      case RunListView.error:
        children.add(Text(stand == null ? _s.of('run.errorNoPlan') : _s.of('run.error', <String, String>{'time': stand, 'revision': revision}), key: const Key('run.error')));
        children.addAll(_stops());
      case RunListView.offline:
        children.add(Text(stand == null ? _s.of('offline.noPlan') : _s.of('run.offline', <String, String>{'time': stand, 'revision': revision}), key: const Key('run.offline')));
        children.addAll(_stops());
      case RunListView.list:
        if (stand != null) children.add(Text(_s.of('run.planStand', <String, String>{'revision': revision, 'time': stand}), key: const Key('run.stand')));
        children.addAll(_stops());
    }
    return ListView(padding: const EdgeInsets.all(16), children: children);
  }

  List<Widget> _stops() {
    final DriverRun? run = state.run;
    if (run == null) return const <Widget>[];
    final List<Widget> out = <Widget>[];
    final RunStop? next = state.nextStop;
    if (next != null) {
      out.add(Semantics(
        header: true,
        child: Text('${_s.of('run.next')}: ${next.stopName}', key: const Key('run.next')),
      ));
      out.add(ElevatedButton(
        key: const Key('nav.start'),
        onPressed: onStartNavigation,
        child: Text(onStartNavigation == null ? _s.of('nav.unavailable') : _s.of('nav.start')),
      ));
    }
    for (final RunStop s in run.stops) {
      out.add(ListTile(
        key: Key('stop.${s.routeStopId}'),
        title: Text('${s.sequence}. ${s.stopName}'),
        subtitle: Text(<String>[
          _s.of('run.stopTime', <String, String>{'planned': formatTime(s.plannedAt), 'forecast': RunListState.forecastText(s, _s.of('run.noForecast'), formatTime)}),
          _s.of('run.persons', <String, String>{'count': s.passengerCount.toString()}),
          if (s.assistance != null) _assistanceText(s.assistance!),
        ].join('\n')),
      ));
    }
    return out;
  }

  String _assistanceText(StopAssistance a) {
    final List<String> parts = <String>[
      if (a.wheelchairRequired) _s.of('run.assistance.wheelchair'),
      if (a.boardingAssist) _s.of('run.assistance.boarding'),
      if (a.alightingAssist) _s.of('run.assistance.alighting'),
    ];
    return '${_s.of('run.assistance')}: ${parts.join(', ')}';
  }
}
