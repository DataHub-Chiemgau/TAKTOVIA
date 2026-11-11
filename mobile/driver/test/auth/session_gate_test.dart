import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/auth/session_gate.dart';

class FakeWiper implements LocalDataWiper {
  int orderWipes = 0;
  int fullWipes = 0;
  int unsynced = 0;
  @override
  Future<void> wipeOrderData() async => orderWipes++;
  @override
  Future<void> wipeAll() async => fullWipes++;
  @override
  Future<int> unsyncedCount() async => unsynced;
}

void main() {
  test('ohne aktive Sitzung keine Auftragsdaten', () {
    final SessionGate g = SessionGate(wiper: FakeWiper());
    expect(g.mayShowOrderData, isFalse);
    g.signedIn();
    expect(g.mayShowOrderData, isTrue);
  });

  test('401 mit "session revoked" beendet die Nutzung und entfernt Auftragsdaten', () async {
    final FakeWiper w = FakeWiper();
    final SessionGate g = SessionGate(wiper: w)..signedIn();
    await g.onServerStatus(401, wwwAuthenticate: 'Bearer error="invalid_token", error_description="session revoked"');
    expect(g.state, SessionState.revoked);
    expect(g.mayShowOrderData, isFalse);
    expect(w.orderWipes, 1);
  });

  test('401 ohne Sperrhinweis ist abgelaufen; 403 und 503 aendern die Sitzung nicht', () async {
    final FakeWiper w = FakeWiper();
    final SessionGate g = SessionGate(wiper: w)..signedIn();
    await g.onServerStatus(403);
    await g.onServerStatus(503);
    expect(g.state, SessionState.active);
    await g.onServerStatus(401);
    expect(g.state, SessionState.expired);
  });

  test('Abmeldung entfernt alle lokalen Daten, bei offenen Ereignissen nur mit force', () async {
    final FakeWiper w = FakeWiper()..unsynced = 3;
    final SessionGate g = SessionGate(wiper: w)..signedIn();
    await expectLater(g.signOut(), throwsA(isA<SignOutBlockedException>()));
    expect(w.fullWipes, 0);
    expect(g.state, SessionState.active);
    await g.signOut(force: true);
    expect(w.fullWipes, 1);
    expect(g.state, SessionState.signedOut);
  });
}
