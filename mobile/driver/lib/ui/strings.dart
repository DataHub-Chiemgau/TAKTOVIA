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
    'defect.report': 'Mangel melden',
    'defect.reportHint': 'Einen Schaden oder Mangel melden, auch ohne aktive Fahrt.',
    'action.unavailableOffline': 'Ohne Verbindung nicht verfuegbar. Die Freigabe erteilt nur der Server.',
  });

  String of(String key, [Map<String, String> args = const <String, String>{}]) {
    String s = _t[key] ?? key;
    args.forEach((String k, String v) => s = s.replaceAll('{$k}', v));
    return s;
  }
}
