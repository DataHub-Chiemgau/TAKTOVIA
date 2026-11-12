import 'driver_run.dart';

/// Ziel der Navigation (WP-M3-28): Koordinate, Haltepunktname und die Revisionen, auf die es sich bezieht. Das Ziel ist immer der naechste disponierte Stopp der gueltigen Stoppliste.
class NavigationTarget {
  const NavigationTarget({required this.routeStopId, required this.stopRevisionId, required this.name, required this.latitude, required this.longitude, required this.planRevision});
  final String routeStopId;
  final String? stopRevisionId;
  final String name;
  final double latitude;
  final double longitude;
  final int? planRevision;

  /// Gleiches Ziel? (Haltepunkt, Revision des Haltepunkts und Koordinate); die Planrevision allein macht kein neues Ziel.
  bool sameDestination(NavigationTarget o) => routeStopId == o.routeStopId && stopRevisionId == o.stopRevisionId && latitude == o.latitude && longitude == o.longitude;
}

/// Navigationsuebergabe-Schnittstelle (Port): ein Anbieterwechsel (Geraete-Navigation, andere App, spaeter eingebettete Abbiegenavigation, OP-19) aendert die
/// Stopplistenlogik nicht. Liefert false, wenn der Anbieter das Ziel nicht uebernehmen konnte.
abstract interface class NavigationProvider {
  String get id;
  Future<bool> navigateTo(NavigationTarget target);
}

/// Anbieter ueber eine `geo:`-URI der Geraete-Navigation (Standard bei Uebergabe, OP-19, D-09 blockiert nur die Produktivschaltung).
class GeoUriNavigationProvider implements NavigationProvider {
  GeoUriNavigationProvider(this.launch);

  /// Oeffnet die URI mit der Geraete-Navigation (Port, z. B. `url_launcher`).
  final Future<bool> Function(Uri uri) launch;

  @override
  String get id => 'geo-uri';

  /// `geo:<lat>,<lon>?q=<lat>,<lon>(<Name>)`: Koordinaten mit fester Notation, Name URL-kodiert.
  static Uri uriFor(NavigationTarget t) => Uri.parse('geo:${t.latitude.toStringAsFixed(6)},${t.longitude.toStringAsFixed(6)}?q=${t.latitude.toStringAsFixed(6)},${t.longitude.toStringAsFixed(6)}(${Uri.encodeComponent(t.name)})');

  @override
  Future<bool> navigateTo(NavigationTarget target) => launch(uriFor(target));
}

/// Stopplistenlogik der Navigation. Das Ziel wird nur aus der gueltigen Stoppliste gebildet; ohne sie ist die Aktion nicht verfuegbar. Nach einer Planaenderung des naechsten
/// Stopps wird das alte Ziel nicht weiterverwendet: die App weist hin und uebergibt das neue Ziel. Es wird nie ein Ziel einer veralteten Planrevision uebergeben.
class NavigationHandover {
  NavigationHandover({required this.provider, required this.currentRun});
  final NavigationProvider provider;

  /// Aktuelle gueltige Projektion (immer die jeweils neueste; fragt der Aufrufer erst unmittelbar vor der Uebergabe ab).
  final DriverRun? Function() currentRun;

  NavigationTarget? _handedOver;
  bool changedHint = false;

  /// Ziel = erster Stopp der Stoppliste, der noch nicht abgeschlossen ist.
  static NavigationTarget? targetFor(DriverRun? run, {Set<String> completed = const <String>{}}) {
    if (run == null || !run.hasValidList) return null;
    for (final RunStop s in run.stops) {
      if (completed.contains(s.routeStopId)) continue;
      return NavigationTarget(routeStopId: s.routeStopId, stopRevisionId: s.stopRevisionId, name: s.stopName, latitude: s.latitude, longitude: s.longitude, planRevision: run.planRevision);
    }
    return null;
  }

  /// Aktion „Navigation starten“ verfuegbar? Nein ohne gueltige Stoppliste (keine Schicht, kein Stopp, alles abgeschlossen).
  bool available({Set<String> completed = const <String>{}}) => targetFor(currentRun(), completed: completed) != null;

  /// Uebergibt das Ziel des naechsten Stopps. Vor der Uebergabe wird die Planrevision erneut gelesen: ist sie inzwischen neuer, gilt das neue Ziel. Trifft waehrend der Uebergabe
  /// eine neue Revision mit anderem Ziel ein, wird [changedHint] gesetzt und das neue Ziel uebergeben (hoechstens [maxRounds] Runden).
  Future<NavigationTarget?> start({Set<String> completed = const <String>{}, int maxRounds = 3}) async {
    NavigationTarget? handed;
    for (int round = 0; round < maxRounds; round++) {
      final NavigationTarget? target = targetFor(currentRun(), completed: completed);
      if (target == null) return handed;
      if (handed != null && target.sameDestination(handed) && target.planRevision == handed.planRevision) return handed;
      final bool ok = await provider.navigateTo(target);
      if (!ok) return handed;
      handed = target;
      _handedOver = target;
      final NavigationTarget? after = targetFor(currentRun(), completed: completed);
      if (after == null || (after.sameDestination(target))) return handed;   // keine Aenderung des Ziels waehrend der Uebergabe
      changedHint = true;
    }
    return handed;
  }

  /// Planaenderung eingetroffen: aendert sich das Ziel gegenueber dem uebergebenen, wird hingewiesen und das neue Ziel uebergeben. Das alte Ziel wird nie weiterverwendet.
  Future<NavigationTarget?> onRunUpdated({Set<String> completed = const <String>{}}) async {
    final NavigationTarget? previous = _handedOver;
    if (previous == null) return null;
    final NavigationTarget? now = targetFor(currentRun(), completed: completed);
    if (now == null || now.sameDestination(previous)) return null;
    changedHint = true;
    final bool ok = await provider.navigateTo(now);
    if (!ok) return null;
    _handedOver = now;
    return now;
  }

  /// Das zuletzt uebergebene Ziel (null: noch keines uebergeben).
  NavigationTarget? get handedOver => _handedOver;
}
