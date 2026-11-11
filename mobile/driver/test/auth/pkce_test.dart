import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:taktovia_driver/auth/oidc.dart';
import 'package:taktovia_driver/auth/pkce.dart';

void main() {
  test('S256-Challenge entspricht dem Testvektor aus RFC 7636 Anhang B', () {
    expect(pkceChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  test('Verifier hat 43 Zeichen ohne Auffuellung und passt zur Challenge', () {
    final PkcePair p = createPkcePair(Random(1));
    expect(p.verifier.length, 43);
    expect(p.verifier.contains('='), isFalse);
    expect(pkceChallenge(p.verifier), p.challenge);
  });

  final OidcConfig cfg = OidcConfig(
    authorizationEndpoint: Uri.parse('https://idp.example/auth'),
    tokenEndpoint: Uri.parse('https://idp.example/token'),
    clientId: 'driver-app',
    redirectUri: Uri.parse('de.taktovia.driver:/callback'),
  );

  test('Anmeldeanfrage nutzt Code-Flow mit S256 und ohne Client-Secret', () {
    final AuthorizationRequest r = buildAuthorizationRequest(cfg);
    final Map<String, String> q = r.url.queryParameters;
    expect(q['response_type'], 'code');
    expect(q['code_challenge_method'], 'S256');
    expect(q['code_challenge'], r.pkce.challenge);
    expect(q['state'], r.state);
    expect(q.containsKey('client_secret'), isFalse);
    expect(r.url.toString().contains(r.pkce.verifier), isFalse);
  });

  test('Rueckleitung mit falschem state, Fehler oder ohne Code wird abgewiesen', () {
    expect(authorizationCodeFromCallback(Uri.parse('x:/cb?code=abc&state=s1'), expectedState: 's1'), 'abc');
    expect(() => authorizationCodeFromCallback(Uri.parse('x:/cb?code=abc&state=boese'), expectedState: 's1'), throwsA(isA<OidcException>()));
    expect(() => authorizationCodeFromCallback(Uri.parse('x:/cb?error=access_denied&state=s1'), expectedState: 's1'), throwsA(isA<OidcException>()));
    expect(() => authorizationCodeFromCallback(Uri.parse('x:/cb?state=s1'), expectedState: 's1'), throwsA(isA<OidcException>()));
  });
}
