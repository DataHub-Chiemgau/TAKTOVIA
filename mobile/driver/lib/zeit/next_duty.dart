import 'dart:convert';

import '../shift/shift_start.dart' show JsonTransport;

/// Eigene veroeffentlichte Schicht (`GET /v1/rosters/own-shifts`, WP-M4-14, UI-09 „Naechster Dienst“ und UI-13 „Eigene Zeit“).
/// Die Antwort enthaelt ausschliesslich Schichten der aktuellen veroeffentlichten Revision; Entwuerfe gibt es in dieser Sicht nie.
/// NICHT AUSGEFUEHRT: Flutter/Dart ist in dieser Umgebung nicht lauffaehig (siehe README), weder Analyse noch Test lagen vor.
class OwnShift {
  const OwnShift({
    required this.shiftId,
    required this.rosterRevision,
    required this.operatingDay,
    required this.startsAt,
    required this.endsAt,
    required this.timezone,
    required this.breakMinutes,
  });

  final String shiftId;
  final int rosterRevision;
  final String operatingDay;
  final DateTime startsAt;
  final DateTime endsAt;
  final String timezone;
  final int breakMinutes;

  factory OwnShift.fromJson(Map<String, Object?> j) => OwnShift(
        shiftId: j['shift_id']! as String,
        rosterRevision: j['roster_revision']! as int,
        operatingDay: j['operating_day']! as String,
        startsAt: DateTime.parse(j['starts_at']! as String).toUtc(),
        endsAt: DateTime.parse(j['ends_at']! as String).toUtc(),
        timezone: j['timezone']! as String,
        breakMinutes: j['break_minutes']! as int,
      );
}

/// Naechster Dienst: die erste veroeffentlichte Schicht, die noch nicht beendet ist (Ende nach [now]); leer, wenn es keinen gibt (Leerzustand
/// „kein veroeffentlichter Dienst“). Die App rechnet nie selbst Planzeiten in Istzeiten um; die Gerätezeit dient nur der Auswahl der Anzeige.
OwnShift? nextDuty(List<OwnShift> shifts, DateTime now) {
  final List<OwnShift> open = shifts.where((OwnShift s) => s.endsAt.isAfter(now.toUtc())).toList()
    ..sort((OwnShift a, OwnShift b) => a.startsAt.compareTo(b.startsAt));
  return open.isEmpty ? null : open.first;
}

/// Liest die eigenen veroeffentlichten Schichten der Betriebstage `[from, to]` (`yyyy-mm-dd`); die Person ist immer der angemeldete Akteur.
Future<List<OwnShift>> fetchOwnShifts(JsonTransport transport, {required String from, required String to}) async {
  final ({int status, String body}) r = await transport.get('/v1/rosters/own-shifts?from=$from&to=$to');
  if (r.status != 200) throw StateError('own-shifts: HTTP ${r.status}');
  final Map<String, Object?> body = jsonDecode(r.body) as Map<String, Object?>;
  return (body['items']! as List<Object?>).map((Object? e) => OwnShift.fromJson(e! as Map<String, Object?>)).toList();
}
