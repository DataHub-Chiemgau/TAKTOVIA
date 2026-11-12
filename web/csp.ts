import type { Plugin } from 'vite';

/**
 * Content-Security-Policy der Web-Apps (WP-M1-38, SEC-009): nur eigene Skripte und Stile, keine Inline-Skripte, keine Plugins,
 * kein Framing durch andere. Wird beim Bauen als `<meta>` in `index.html` eingefügt (im Dev-Server nicht: Vite und React Refresh
 * brauchen dort Inline-Skripte). Als HTTP-Header am Hosting zusätzlich `frame-ancestors 'none'` setzen (Meta kann das nicht).
 * `VITE_TILE_URL_TEMPLATE` erlaubt genau die Herkunft der Kartenkacheln für `img-src` (WP-M2-40, Passenger-PWA).
 * `VITE_API_ORIGIN` erlaubt genau die Herkunft der API für `connect-src`, `VITE_OIDC_ISSUER` die des Anmeldedienstes (Token-Endpunkt, WP-M1-44).
 */
export function contentSecurityPolicy(
  apiOrigin?: string,
  idpOrigin?: string,
  tileOrigin?: string,
): string {
  const connect = ["'self'", apiOrigin, idpOrigin]
    .filter((x) => x !== undefined && x !== '')
    .join(' ');
  return [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    ['img-src', "'self'", 'data:', tileOrigin].filter((x) => x !== undefined && x !== '').join(' '),
    "font-src 'self'",
    `connect-src ${connect}`,
    "manifest-src 'self'",
    "worker-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ');
}

/** Fügt die Policy als erstes Element in `<head>` ein. Eine vorhandene Policy wird nicht dupliziert. */
export function withCsp(
  html: string,
  apiOrigin?: string,
  idpOrigin?: string,
  tileOrigin?: string,
): string {
  if (/http-equiv=["']Content-Security-Policy["']/i.test(html)) return html;
  const meta = `<meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(apiOrigin, idpOrigin, tileOrigin)}" />`;
  return html.replace(/<head>/i, `<head>\n    ${meta}`);
}

/** Herkunft der Realm-URL des OIDC-Anbieters für `connect-src` (Token-Endpunkt); ungültige Werte ergeben keine Freigabe. */
function originOf(url?: string): string | undefined {
  try {
    return url ? new URL(url).origin : undefined;
  } catch {
    return undefined;
  }
}

export function cspPlugin(): Plugin {
  return {
    name: 'taktovia-csp',
    apply: 'build',
    transformIndexHtml: {
      order: 'post',
      handler: (html) =>
        withCsp(
          html,
          process.env.VITE_API_ORIGIN,
          originOf(process.env.VITE_OIDC_ISSUER),
          originOf(process.env.VITE_TILE_URL_TEMPLATE?.replace(/\{[zxy]\}/g, '0')),
        ),
    },
  };
}
