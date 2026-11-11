/// Zustand der Sitzung der App (Anmeldung und Serverstatus).
enum SessionState {
  /// Nicht angemeldet.
  signedOut,

  /// Gueltige Sitzung.
  active,

  /// Token abgelaufen und nicht erneuerbar; erneute Anmeldung noetig.
  expired,

  /// Serverseitig gesperrt oder beendet (401 mit `session revoked`).
  revoked,
}

/// Raeumt lokale Daten ab (Port; Umsetzung loescht Datei und Schluessel).
abstract interface class LocalDataWiper {
  /// Entfernt Auftragsdaten (Lesestand mit Stoppliste); die verschluesselte Outbox bleibt fuer die erneute Anmeldung desselben Kontos.
  Future<void> wipeOrderData();

  /// Entfernt alles: Datenbankdatei, Datenbankschluessel, Token. Danach ist nichts mehr lesbar.
  Future<void> wipeAll();

  /// Anzahl noch nicht bestaetigter Outbox-Ereignisse.
  Future<int> unsyncedCount();
}

/// Abmeldung ist blockiert, weil noch nicht uebertragene Ereignisse existieren.
class SignOutBlockedException implements Exception {
  const SignOutBlockedException(this.unsynced);
  final int unsynced;
  @override
  String toString() => 'SignOutBlockedException: $unsynced Ereignisse nicht uebertragen';
}

/// Pforte vor allen Auftragsdaten (WP-M3-24 AK 1): ohne [SessionState.active] liefert [mayShowOrderData] false, die Oberflaeche
/// zeigt nichts davon. Eine Sperre des Servers wirkt nach Wiederverbindung mit der ersten abgewiesenen Anfrage ([onServerStatus]).
class SessionGate {
  SessionGate({required this.wiper, SessionState initial = SessionState.signedOut}) : _state = initial;

  final LocalDataWiper wiper;
  SessionState _state;
  final List<void Function(SessionState)> _listeners = <void Function(SessionState)>[];

  SessionState get state => _state;
  bool get mayShowOrderData => _state == SessionState.active;

  void addListener(void Function(SessionState) l) => _listeners.add(l);

  void _set(SessionState s) {
    if (s == _state) return;
    _state = s;
    for (final void Function(SessionState) l in List<void Function(SessionState)>.of(_listeners)) {
      l(s);
    }
  }

  void signedIn() => _set(SessionState.active);

  /// Wertet die Antwort des Servers aus. 401 beendet die Nutzung (Auftragsdaten werden entfernt); 403 und andere Codes aendern die Sitzung nicht.
  Future<void> onServerStatus(int httpStatus, {String? wwwAuthenticate}) async {
    if (httpStatus != 401) return;
    final bool revoked = (wwwAuthenticate ?? '').contains('session revoked');
    _set(revoked ? SessionState.revoked : SessionState.expired);
    await wiper.wipeOrderData();
  }

  /// Abmeldung durch den Fahrer. Mit offenen Ereignissen nur mit [force] (dann gehen sie verloren).
  Future<void> signOut({bool force = false}) async {
    final int open = await wiper.unsyncedCount();
    if (open > 0 && !force) throw SignOutBlockedException(open);
    _set(SessionState.signedOut);
    await wiper.wipeAll();
  }
}
