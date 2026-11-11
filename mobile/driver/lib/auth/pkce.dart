import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';

/// PKCE-Paar nach RFC 7636 (Methode S256). Der Verifier verlaesst das Geraet nie.
class PkcePair {
  const PkcePair(this.verifier, this.challenge);
  final String verifier;
  final String challenge;
}

/// Base64url ohne Auffuellzeichen (RFC 7636 Anhang A).
String base64UrlNoPad(List<int> bytes) => base64Url.encode(bytes).replaceAll('=', '');

/// S256-Challenge zu einem Verifier.
String pkceChallenge(String verifier) => base64UrlNoPad(sha256.convert(ascii.encode(verifier)).bytes);

/// Erzeugt einen Verifier aus 32 Zufallsbytes (43 Zeichen) und seine Challenge.
PkcePair createPkcePair([Random? random]) {
  final Random r = random ?? Random.secure();
  final String verifier = base64UrlNoPad(List<int>.generate(32, (_) => r.nextInt(256)));
  return PkcePair(verifier, pkceChallenge(verifier));
}

/// Zufallswert fuer `state` und `nonce`.
String randomToken([Random? random]) {
  final Random r = random ?? Random.secure();
  return base64UrlNoPad(List<int>.generate(24, (_) => r.nextInt(256)));
}
