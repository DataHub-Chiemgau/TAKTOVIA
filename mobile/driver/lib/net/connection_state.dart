import '../offline/models.dart';
import '../offline/offline_actions.dart';
import '../ui/strings.dart';

/// Verbindungszustand der Driver-App (WP-M5-09, LH-6.19-NFR-009). Reine Logik ohne Oberflaeche, gleicher Automat wie in den Web-Anwendungen
/// (`web/design-system/src/network/connection.ts`). Nichts in dieser Datei wurde ausgefuehrt (kein Flutter in der Umgebung).
///
/// Zustaende: online, eingeschraenkt, offline, Synchronisation laeuft, Synchronisationskonflikt. Nach einer Wiederverbindung gelten die Offline-Einschraenkungen,
/// bis der Serverstand geladen ist; kurze Aussetzer (unter [offlineDelay]) aendern weder Anzeige noch Ansage.
enum DriverConnection { online, limited, offline, syncing, conflict }

class AnnouncedChange {
  const AnnouncedChange({required this.state, required this.textKey});
  final DriverConnection state;
  final String textKey;
}

class ConnectionTracker {
  ConnectionTracker({this.offlineDelay = const Duration(seconds: 3), bool linkUp = true}) : _linkUp = linkUp, _awaiting = !linkUp;

  final Duration offlineDelay;
  bool _linkUp;
  bool _awaiting;
  bool _limited = false;
  int _pending = 0;
  int _conflicts = 0;
  DateTime? _lastServerStateAt;
  DateTime? _downSince;
  DriverConnection _announced = DriverConnection.online;
  final List<AnnouncedChange> announcements = <AnnouncedChange>[];

  /// Geraeteverbindung hat gewechselt (Plattformereignis). Ein Verlust gilt erst nach [offlineDelay] als offline ([tick]).
  void linkChanged(bool up, DateTime at) {
    if (up) {
      if (_downSince != null && _linkUp) {
        _downSince = null; // kurzer Aussetzer: nichts hat sich geaendert
      } else if (!_linkUp) {
        _linkUp = true;
        _awaiting = true; // Serverstand muss erst geladen werden
      }
    } else if (_linkUp && _downSince == null) {
      _downSince = at.toUtc();
    }
    _announce();
  }

  /// Zeitfortschritt: committet einen Verbindungsverlust nach [offlineDelay].
  void tick(DateTime now) {
    final DateTime? since = _downSince;
    if (since != null && _linkUp && now.toUtc().difference(since) >= offlineDelay) {
      _linkUp = false;
      _awaiting = true;
      _downSince = null;
    }
    _announce();
  }

  void requestFailed() {
    _limited = true;
    _announce();
  }

  /// Serverstand wurde geladen: beendet nach einer Wiederverbindung den Offlinezustand.
  void serverStateLoaded(DateTime at) {
    _awaiting = false;
    _limited = false;
    _lastServerStateAt = at.toUtc();
    _announce();
  }

  void setPending(int count) {
    _pending = count < 0 ? 0 : count;
    _announce();
  }

  void setConflicts(int count) {
    _conflicts = count < 0 ? 0 : count;
    _announce();
  }

  DateTime? get lastServerStateAt => _lastServerStateAt;
  int get pending => _pending;

  DriverConnection get state {
    if (!_linkUp) return DriverConnection.offline;
    if (_awaiting) return DriverConnection.syncing;
    if (_conflicts > 0) return DriverConnection.conflict;
    if (_pending > 0) return DriverConnection.syncing;
    if (_limited) return DriverConnection.limited;
    return DriverConnection.online;
  }

  /// Serveraktionen sind gesperrt, solange offline oder der Serverstand nach der Rueckkehr noch nicht geladen ist.
  bool get serverRestricted => !_linkUp || _awaiting;

  void _announce() {
    final DriverConnection s = state;
    if (s == _announced) return;
    _announced = s;
    announcements.add(AnnouncedChange(state: s, textKey: 'connection.${s.name}'));
  }
}

/// Anzeigemodell des Offlinebanners (UI-09/UI-10): Text und Symbol (nie nur Farbe), Live-Region-Text, Datenstand des Plans; keine ETA und keine Live-Kennzeichnung ausser online.
class ConnectionView {
  const ConnectionView({required this.state, required this.bannerText, required this.icon, required this.liveRegionText, required this.showEta, required this.showLiveBadge, this.noLiveText});
  final DriverConnection state;
  final String bannerText;
  final String icon;

  /// Text fuer die Ansage durch den Screenreader (`Semantics(liveRegion: true)`).
  final String liveRegionText;
  final bool showEta;
  final bool showLiveBadge;
  final String? noLiveText;
}

const Map<DriverConnection, String> kConnectionIcons = <DriverConnection, String>{
  DriverConnection.online: 'check',
  DriverConnection.limited: 'warning',
  DriverConnection.offline: 'cloud_off',
  DriverConnection.syncing: 'sync',
  DriverConnection.conflict: 'priority_high',
};

ConnectionView buildConnectionView(ConnectionTracker t, {DutySnapshot? snapshot, Strings strings = Strings.de}) {
  final DriverConnection s = t.state;
  String text = strings.of('connection.${s.name}');
  if (t.pending > 0 && s != DriverConnection.offline) text += ' ${strings.of('connection.pending', <String, String>{'count': '${t.pending}'})}';
  final bool live = s == DriverConnection.online;
  final DateTime? stand = snapshot?.asOfUtc ?? t.lastServerStateAt;
  String? noLive;
  if (!live && stand != null) {
    String two(int v) => v.toString().padLeft(2, '0');
    final DateTime u = stand.toUtc();
    noLive = strings.of('connection.noLive', <String, String>{'time': '${two(u.hour)}:${two(u.minute)} UTC'});
  }
  return ConnectionView(
    state: s,
    bannerText: text,
    icon: kConnectionIcons[s]!,
    liveRegionText: text,
    showEta: live,
    showLiveBadge: live,
    noLiveText: noLive,
  );
}

class ActionGate {
  const ActionGate({required this.allowed, this.reasonKey, this.syncingPrefix = false});
  final bool allowed;

  /// Text mit Grund und naechstem Schritt (ein Schluessel in [Strings]).
  final String? reasonKey;
  final bool syncingPrefix;

  String? text(Strings strings) => reasonKey == null ? null : (syncingPrefix ? strings.of('action.blocked.syncingPrefix') : '') + strings.of(reasonKey!);
}

/// Verfuegbarkeit einer Aktion: serverseitige Aktionen (Einsatzfreigabe, Sperraufhebung, verbindliche Buchung) sind offline und bis zum geladenen Serverstand gesperrt,
/// mit Grund und naechstem Schritt statt stiller Deaktivierung (Kap. 01 §1.5).
ActionGate gateAction(DriverAction a, ConnectionTracker t) {
  if (!OfflinePolicy.serverOnly.contains(a) || !t.serverRestricted) return const ActionGate(allowed: true);
  final String key = switch (a) {
    DriverAction.liftVehicleBlock => 'action.blocked.liftBlock',
    DriverAction.bindBooking => 'action.blocked.bindBooking',
    _ => 'action.blocked.releaseVehicle',
  };
  return ActionGate(allowed: false, reasonKey: key, syncingPrefix: t.state == DriverConnection.syncing && !_isOffline(t));
}

bool _isOffline(ConnectionTracker t) => t.state == DriverConnection.offline;

/// Klaerfaelle der Outbox, die dem Fahrer angezeigt werden: offline erfasster Check-in trifft auf Storno (Hinweis lokal oder Antwort `REVIEW_REQUIRED`).
List<String> conflictNoticeKeys(List<QueuedEvent> events) {
  final List<String> out = <String>[];
  for (final QueuedEvent e in events) {
    final bool cancelClash = e.conflictHint || (e.state == EventState.reviewRequired && e.reviewReason == ReviewReason.checkInVsCancellation);
    if (cancelClash && e.type == 'checkIn') out.add('connection.conflict.checkInVsCancel');
  }
  return out;
}
