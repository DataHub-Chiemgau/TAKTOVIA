// NICHT AUSGEFUEHRT: in der Entwicklungsumgebung ohne Flutter/Dart-SDK geschrieben (WP-M3-23). Der Server-Teil ist durch InspectionStatusProjectionTest und InspectionStatusDriverHttpTest nachgewiesen.
import 'package:flutter/material.dart';

/// Die sechs Pruefstatuswerte der Fahrer-App (UI-09, WP-M3-23, LH-6.21-CHK-016), identisch zur Leitstelle. Die Ableitung steht allein im Server; die App zeigt nur an.
enum Pruefstatus {
  pending('PENDING', 'Ausstehend', Icons.radio_button_unchecked),
  inProgress('IN_PROGRESS', 'In Bearbeitung', Icons.timelapse),
  passed('PASSED', 'Bestanden', Icons.check_circle),
  restricted('RESTRICTED', 'Eingeschraenkt', Icons.warning_amber),
  blocked('BLOCKED', 'Gesperrt', Icons.block),
  unclear('UNCLEAR', 'Technisch ungeklaert', Icons.help_outline);

  const Pruefstatus(this.code, this.label, this.icon);
  final String code;
  final String label;
  final IconData icon;

  /// Unbekannte Werte werden nie geraten: sie gelten als `PENDING` (die Anzeige verspricht nichts).
  static Pruefstatus fromCode(String code) => values.firstWhere((Pruefstatus s) => s.code == code, orElse: () => pending);
}

/// Angezeigter Stand: Status mit Revision und Datenstand. [adopt] uebernimmt nur einen Stand mit hoeherer oder gleicher Revision (bei zwei Wechseln gilt der juengere, nie ein aelterer).
class PruefstatusStand {
  const PruefstatusStand({required this.status, required this.revision, required this.asOfUtc});
  final Pruefstatus status;
  final int revision;
  final DateTime asOfUtc;

  PruefstatusStand adopt(PruefstatusStand incoming) => incoming.revision >= revision ? incoming : this;
}

/// Statusanzeige: Text und Symbol (nie nur Farbe), Datenstand und bei unterbrochenem Kanal die Kennzeichnung „moeglicherweise veraltet“; Semantik fuer Screenreader (Live-Region).
class PruefstatusAnzeige extends StatelessWidget {
  const PruefstatusAnzeige({super.key, required this.stand, this.moeglicherweiseVeraltet = false});
  final PruefstatusStand stand;
  final bool moeglicherweiseVeraltet;

  static String _uhr(DateTime t) => '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')} UTC';

  @override
  Widget build(BuildContext context) {
    final String text = 'Pruefstatus: ${stand.status.label}';
    final String standText = 'Stand ${_uhr(stand.asOfUtc)}${moeglicherweiseVeraltet ? ', moeglicherweise veraltet' : ''}';
    return Semantics(
      liveRegion: true,
      label: '$text. $standText',
      child: ExcludeSemantics(
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 44, minWidth: 44),
          child: Row(children: <Widget>[
            Icon(stand.status.icon),
            const SizedBox(width: 8),
            Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[Text(text), Text(standText)]),
          ]),
        ),
      ),
    );
  }
}
