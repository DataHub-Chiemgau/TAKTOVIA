/** PKCE (RFC 7636) mit S256: Verifier und Challenge aus dem Web-Crypto-Zufall; nichts davon verlässt den Browser außer der Challenge. */

function base64Url(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Zufallswert aus `crypto.getRandomValues` (base64url, ohne Padding); 32 Byte ergeben 43 Zeichen (Mindestlänge des Verifiers). */
export function randomString(bytes = 32): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(bytes)));
}

export async function challengeS256(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}
