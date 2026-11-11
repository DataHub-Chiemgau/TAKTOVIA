import 'pkce.dart';

/// Konfiguration des OIDC-Clients (Authorization Code + PKCE, kein Client-Secret in der App).
class OidcConfig {
  const OidcConfig({
    required this.authorizationEndpoint,
    required this.tokenEndpoint,
    required this.clientId,
    required this.redirectUri,
    this.scopes = const <String>['openid', 'driver:events', 'driver:run', 'driver:devices', 'driver:positions', 'inspections:write', 'defects:write'],
  });

  final Uri authorizationEndpoint;
  final Uri tokenEndpoint;
  final String clientId;
  final Uri redirectUri;
  final List<String> scopes;
}

/// Vorbereitete Anmeldeanfrage: URL fuer den Systembrowser plus die lokal zu haltenden Werte.
class AuthorizationRequest {
  const AuthorizationRequest({required this.url, required this.state, required this.nonce, required this.pkce});
  final Uri url;
  final String state;
  final String nonce;
  final PkcePair pkce;
}

AuthorizationRequest buildAuthorizationRequest(OidcConfig c, {PkcePair? pkce, String? state, String? nonce}) {
  final PkcePair p = pkce ?? createPkcePair();
  final String s = state ?? randomToken();
  final String n = nonce ?? randomToken();
  final Uri url = c.authorizationEndpoint.replace(queryParameters: <String, String>{
    ...c.authorizationEndpoint.queryParameters,
    'response_type': 'code',
    'client_id': c.clientId,
    'redirect_uri': c.redirectUri.toString(),
    'scope': c.scopes.join(' '),
    'state': s,
    'nonce': n,
    'code_challenge': p.challenge,
    'code_challenge_method': 'S256',
  });
  return AuthorizationRequest(url: url, state: s, nonce: n, pkce: p);
}

/// Anmeldung fehlgeschlagen oder manipuliert (falscher `state`, Fehlerantwort des IdP).
class OidcException implements Exception {
  const OidcException(this.message);
  final String message;
  @override
  String toString() => 'OidcException: $message';
}

/// Prueft die Rueckleitung des Systembrowsers und liefert den Autorisierungscode.
/// Ein abweichender `state` (CSRF/Replay) oder ein `error` ist ein Fehler, nie ein Code.
String authorizationCodeFromCallback(Uri callback, {required String expectedState}) {
  final Map<String, String> q = callback.queryParameters;
  if (q.containsKey('error')) throw OidcException('IdP-Fehler: ${q['error']}');
  if (q['state'] != expectedState) throw const OidcException('state stimmt nicht');
  final String? code = q['code'];
  if (code == null || code.isEmpty) throw const OidcException('Autorisierungscode fehlt');
  return code;
}

/// Token-Satz. Die Zugriffstoken-Laufzeit steuert der IdP; die App liest sie nur.
class TokenSet {
  const TokenSet({required this.accessToken, required this.refreshToken, required this.expiresAtUtc, required this.subject});
  final String accessToken;
  final String? refreshToken;
  final DateTime expiresAtUtc;

  /// Subject der Anmeldung (Konto), aus dem ID-Token; trennt Daten verschiedener Fahrer auf demselben Geraet.
  final String subject;

  bool isExpired(DateTime nowUtc) => !nowUtc.isBefore(expiresAtUtc);
}

/// Systembrowser (Port). Umsetzung mit einem AppAuth-Paket, Version beim ersten Flutter-Lauf festlegen.
abstract interface class OidcBrowser {
  /// Oeffnet [url] im Systembrowser und liefert die Rueckleitungs-URL.
  Future<Uri> authorize(Uri url, {required String callbackScheme});
}

/// Tokenendpunkt (Port): Code gegen Token tauschen, Refresh.
abstract interface class OidcTokenEndpoint {
  Future<TokenSet> exchangeCode({required String code, required String codeVerifier, required Uri redirectUri, required String clientId});
  Future<TokenSet?> refresh(String refreshToken, {required String clientId});
}

/// Sicherer Ablageort fuer Token und lokalen Datenbankschluessel (Android Keystore / iOS Keychain, Port).
abstract interface class SecretVault {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// Anmeldeablauf: Authorization Code + PKCE.
class OidcSignIn {
  OidcSignIn({required this.config, required this.browser, required this.tokens});
  final OidcConfig config;
  final OidcBrowser browser;
  final OidcTokenEndpoint tokens;

  Future<TokenSet> signIn() async {
    final AuthorizationRequest req = buildAuthorizationRequest(config);
    final Uri callback = await browser.authorize(req.url, callbackScheme: config.redirectUri.scheme);
    final String code = authorizationCodeFromCallback(callback, expectedState: req.state);
    return tokens.exchangeCode(code: code, codeVerifier: req.pkce.verifier, redirectUri: config.redirectUri, clientId: config.clientId);
  }
}
