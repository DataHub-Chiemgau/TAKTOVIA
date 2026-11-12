/// Deutsche Texte mit vorbereiteter Internationalisierung: alle Texte der Oberflaeche stehen hier, nie als Literal im Widget.
/// Eine weitere Sprache ergaenzt eine zweite Tabelle; die Auswahl folgt der Geraetesprache (Standard: Deutsch).
class Strings {
  const Strings._(this._t);
  final Map<String, String> _t;

  static const Strings de = Strings._(<String, String>{
    'app.title': 'TAKTOVIA Fahrer',
    'login.button': 'Anmelden',
    'login.required': 'Bitte anmelden, um Ihren Dienst zu sehen.',
    'session.expired': 'Ihre Sitzung ist abgelaufen. Bitte erneut anmelden.',
    'session.revoked': 'Ihre Sitzung wurde beendet. Bitte erneut anmelden.',
    'offline.banner': 'Offline. Letzter Plan: {time}',
    'offline.noPlan': 'Offline. Noch kein Plan geladen.',
    'online.pending': '{count} Ereignisse warten auf die Uebertragung.',
    'nav.run': 'Dienst',
    'nav.inspection': 'Pruefung',
    'nav.defect': 'Schaden',
    'nav.settings': 'Einstellungen',
    'shift.next': 'Naechster Dienst {from}-{to} · Fahrzeug {vehicle}',
    'shift.none': 'Keine gueltige Schicht. Es werden keine Auftraege angezeigt.',
    'shift.start': 'Dienst antreten',
    'shift.startTooEarly': 'Antritt ab {from} moeglich.',
    'shift.started': 'Dienst angetreten. Die Einsatzfreigabe erteilt nur der Server nach der Pruefung.',
    'shift.end': 'Dienst beenden',
    'shift.withdrawn': 'Die Zuweisung wurde entzogen. Es werden keine Auftraege angezeigt.',
    'shift.expired': 'Die Schicht ist abgelaufen. Es werden keine Auftraege angezeigt.',
    'shift.vehicleMismatch': 'Das Fahrzeug passt nicht zur Schicht.',
    'shift.offline': 'Ohne Verbindung kann der Dienst nicht angetreten werden.',
    'position.notice': 'Waehrend Ihres Dienstes wird der Fahrzeugstandort erfasst und an die Leitstelle uebermittelt. Ausserhalb des Dienstes wird nichts erfasst.',
    'position.stopped': 'Standorterfassung beendet.',
    'position.notActivated': 'Die Standorterfassung ist noch nicht aktiviert.',
    'action.unavailableOffline': 'Ohne Verbindung nicht verfuegbar. Die Freigabe erteilt nur der Server.',
  });

  String of(String key, [Map<String, String> args = const <String, String>{}]) {
    String s = _t[key] ?? key;
    args.forEach((String k, String v) => s = s.replaceAll('{$k}', v));
    return s;
  }
}
