import 'driver_run.dart';
import 'run_list.dart';

/// Anzeigezustand einer Planrevision aus Sicht der App (WP-M3-27, LH-6.6-DRV-004; Zustandsautomat `plan_revision_fahrer.md`). Die ersten vier Werte liefert der Server
/// (`revision_state`); [past] bildet nur die App: die zwischengespeicherte Revision ist aelter als die des Servers (offline oder Weckruf verloren), der angezeigte Plan ist dann
/// keine vermeintlich aktuelle Route, sondern ausdruecklich „Stand <Zeit>, nicht aktuell“.
enum PlanRevisionView { current, newUnconfirmed, autoAdopted, confirmed, past }

/// Bewegungszustand des Fahrzeugs aus den Positionsmessungen (Planungsannahme, Kap. 06 §6.7 legt keine Erkennung fest): in Bewegung ab [MotionDetector.movingThresholdMetersPerSecond]
/// in einer Messung, die nicht aelter als [MotionDetector.freshness] ist. Ohne frische Messung gilt „unbekannt“ und wird wie Bewegung behandelt (nie ein Dialog im Zweifel).
enum Motion { moving, standing, unknown }

class MotionDetector {
  MotionDetector({this.movingThresholdMetersPerSecond = 1.5, this.freshness = const Duration(seconds: 30)});
  final double movingThresholdMetersPerSecond;
  final Duration freshness;

  double? _speed;
  DateTime? _at;

  void onPosition({required double speedMetersPerSecond, required DateTime at}) {
    if (_at != null && at.isBefore(_at!)) return; // aeltere Messung ueberschreibt nie eine neuere
    _speed = speedMetersPerSecond;
    _at = at;
  }

  Motion motion(DateTime now) {
    if (_at == null || now.difference(_at!) > freshness) return Motion.unknown;
    return _speed! >= movingThresholdMetersPerSecond ? Motion.moving : Motion.standing;
  }
}

/// Was die Aenderungsanzeige zeigt: neue, entfallene und verschobene Stopps gegenueber der Vergleichsbasis.
class PlanChangeNotice {
  const PlanChangeNotice({required this.added, required this.moved, required this.removed, required this.acknowledgementRequired});
  final List<RunStop> added;
  final List<RunStop> moved;
  final List<RemovedStop> removed;
  final bool acknowledgementRequired;
  bool get isEmpty => added.isEmpty && moved.isEmpty && removed.isEmpty;
}

/// Ob und wann die Bestaetigung abgefragt wird (Kap. 06 §6.7): nie waehrend der Fahrbewegung; dann nur Hinweis/Ansage, die Bestaetigung wird beim naechsten Halt verlangt.
enum AckPrompt { none, deferredUntilStop, showNow }

class PlanChangePresenter {
  PlanChangePresenter({required this.motion, required this.now});
  final MotionDetector motion;
  final DateTime Function() now;

  /// Darstellungszustand: [serverRevision] ist die zuletzt vom Server gemeldete Revision (Live-Kanal, Weckruf, Abruf); ist sie groesser als die des gezeigten Plans, ist dieser „vergangen“.
  PlanRevisionView view(DriverRun shown, {int? serverRevision}) {
    final int? shownRevision = shown.planRevision;
    if (serverRevision != null && shownRevision != null && serverRevision > shownRevision) return PlanRevisionView.past;
    switch (shown.revisionState) {
      case 'NEW_UNCONFIRMED':
        return PlanRevisionView.newUnconfirmed;
      case 'AUTO_ADOPTED':
        return PlanRevisionView.autoAdopted;
      case 'CONFIRMED':
        return PlanRevisionView.confirmed;
      default:
        return PlanRevisionView.current;
    }
  }

  PlanChangeNotice notice(DriverRun run) => PlanChangeNotice(
        added: run.stops.where((RunStop s) => s.change == 'NEW').toList(growable: false),
        moved: run.stops.where((RunStop s) => s.change == 'MOVED').toList(growable: false),
        removed: run.removedStops,
        acknowledgementRequired: run.acknowledgementRequired,
      );

  AckPrompt prompt(DriverRun run) {
    if (!run.acknowledgementRequired) return AckPrompt.none;
    // nur im sicher erkannten Stand duerfen Dialoge erscheinen; Bewegung und „unbekannt“ schieben die Bestaetigung auf den naechsten Halt
    return motion.motion(now()) == Motion.standing ? AckPrompt.showNow : AckPrompt.deferredUntilStop;
  }

  /// Kurze Ansage der Aenderung (Sprachausgabe oder Banner) fuer die Fahrt; enthaelt nur Haltepunktnamen und Zahlen, keine Fahrgastdaten.
  String announcement(DriverRun run) {
    final PlanChangeNotice n = notice(run);
    final List<String> parts = <String>[
      if (n.added.isNotEmpty) '${n.added.length} neu',
      if (n.removed.isNotEmpty) '${n.removed.length} entfallen',
      if (n.moved.isNotEmpty) '${n.moved.length} verschoben',
    ];
    return parts.isEmpty ? 'Plan aktualisiert' : 'Planaenderung: ${parts.join(', ')}';
  }
}

/// Stand der Anzeige offline (Kap. 06, DRV-004): der letzte Plan wird mit Stand gezeigt und als „nicht aktuell“ gekennzeichnet, nie als aktuelle Route.
class OfflinePlanText {
  static String of(RunListState s, String Function(DateTime) format) {
    if (s.view != RunListView.offline || s.run == null) return '';
    final DateTime at = s.fetchedAt ?? s.run!.planAsOf;
    return 'Offline: Plan Stand ${format(at)}, nicht aktuell';
  }
}
