import type { Plugin } from 'vite';

/**
 * Content-Security-Policy der Web-Apps (WP-M1-38, SEC-009): nur eigene Skripte und Stile, keine Inline-Skripte, keine Plugins,
 * kein Framing durch andere. Wird beim Bauen als `<meta>` in `index.html` eingefügt (im Dev-Server nicht: Vite und React Refresh
 * brauchen dort Inline-Skripte). Als HTTP-Header am Hosting zusätzlich `frame-ancestors 'none'` setzen (Meta kann das nicht).
 * `VITE_API_ORIGIN` erlaubt genau die Herkunft der API für `connect-src`.
 */
export function contentSecurityPolicy(apiOrigin?: string): string {
  const connect = apiOrigin ? `'self' ${apiOrigin}` : "'self'";
  return [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
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
export function withCsp(html: string, apiOrigin?: string): string {
  if (/http-equiv=["']Content-Security-Policy["']/i.test(html)) return html;
  const meta = `<meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(apiOrigin)}" />`;
  return html.replace(/<head>/i, `<head>\n    ${meta}`);
}

export function cspPlugin(): Plugin {
  return {
    name: 'taktovia-csp',
    apply: 'build',
    transformIndexHtml: {
      order: 'post',
      handler: (html) => withCsp(html, process.env.VITE_API_ORIGIN),
    },
  };
}
