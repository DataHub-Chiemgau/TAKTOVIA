import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { contentSecurityPolicy, withCsp } from '../../csp';

const WEB = resolve(__dirname, '../../');

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (name === 'node_modules' || name === 'dist') return [];
    return statSync(p).isDirectory()
      ? sources(p)
      : /\.(ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name)
        ? [p]
        : [];
  });
}

describe('Eingabehärtung im Web-Rahmen (SEC-009)', () => {
  it('die Content-Security-Policy verbietet Inline-Skripte, Plugins und fremdes Framing', () => {
    const csp = contentSecurityPolicy();
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("base-uri 'self'");
    expect(csp).not.toMatch(/unsafe-inline|unsafe-eval|\*/);
    expect(contentSecurityPolicy('https://api.taktovia.example')).toContain(
      "connect-src 'self' https://api.taktovia.example",
    );
  });

  it('withCsp fügt die Policy genau einmal ein', () => {
    const html = '<!doctype html><html><head><title>x</title></head><body></body></html>';
    const once = withCsp(html);
    expect(once).toContain('http-equiv="Content-Security-Policy"');
    expect(withCsp(once)).toBe(once);
  });

  it.each(['passenger', 'operator'])(
    'index.html von %s enthält weder Inline-Skripte noch Ereignis-Attribute noch javascript:-URLs',
    (app) => {
      const html = readFileSync(join(WEB, app, 'index.html'), 'utf8');
      const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)];
      expect(scripts.length).toBeGreaterThan(0);
      for (const [, attrs, body] of scripts) {
        expect(attrs).toMatch(/\bsrc=/);
        expect((body ?? '').trim()).toBe('');
      }
      expect(html).not.toMatch(/\son[a-z]+\s*=/i);
      expect(html).not.toMatch(/javascript:/i);
    },
  );

  it.each(['passenger', 'operator'])(
    'der Build von %s trägt die Policy und bleibt frei von Inline-Skripten',
    async (app) => {
      const { build } = await import('vite');
      const { mkdtempSync, readFileSync: read } = await import('node:fs');
      const { tmpdir } = await import('node:os');
      const outDir = mkdtempSync(join(tmpdir(), `csp-${app}-`));
      await build({
        root: join(WEB, app),
        logLevel: 'silent',
        build: { outDir, emptyOutDir: true },
      });
      const html = read(join(outDir, 'index.html'), 'utf8');
      expect(html).toContain('Content-Security-Policy');
      expect(html).toContain("script-src 'self'");
      for (const [, attrs, body] of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
        expect(attrs).toMatch(/\bsrc=/);
        expect((body ?? '').trim()).toBe('');
      }
    },
    60_000,
  );

  it('im Quelltext liegen keine Tokens oder Anmeldedaten in Web-Speicher oder Cookies (Token nur im Arbeitsspeicher, WP-M1-43)', () => {
    const offenders: string[] = [];
    for (const app of ['design-system', 'passenger', 'operator']) {
      for (const file of sources(join(WEB, app, 'src'))) {
        const text = readFileSync(file, 'utf8');
        if (/\b(localStorage|sessionStorage)\b|document\.cookie/.test(text)) offenders.push(file);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('unsicheres HTML wird im Quelltext nicht erzeugt (dangerouslySetInnerHTML, innerHTML, eval)', () => {
    const offenders: string[] = [];
    for (const app of ['design-system', 'passenger', 'operator']) {
      for (const file of sources(join(WEB, app, 'src'))) {
        if (
          /dangerouslySetInnerHTML|\.innerHTML\s*=|\beval\(|new Function\(/.test(
            readFileSync(file, 'utf8'),
          )
        )
          offenders.push(file);
      }
    }
    expect(offenders).toEqual([]);
  });
});
