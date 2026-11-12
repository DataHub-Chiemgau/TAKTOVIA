import type { OperationId, Operations, Problem } from './generated';
import { operationTable } from './generated';

/** Fehler einer API-Antwort. `status` 0 bedeutet: keine Antwort erhalten (Netzwerk). */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | undefined;
  readonly retryAfterSeconds: number | undefined;
  /** `WWW-Authenticate` der Antwort (z. B. `insufficient_user_authentication` für den Step-up zum zweiten Faktor). */
  readonly wwwAuthenticate: string | undefined;

  constructor(
    status: number,
    problem?: Problem,
    retryAfterSeconds?: number,
    wwwAuthenticate?: string,
  ) {
    super(problem?.code ?? `HTTP_${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.retryAfterSeconds = retryAfterSeconds;
    this.wwwAuthenticate = wwwAuthenticate;
  }
}

export interface ApiClientOptions {
  baseUrl: string;
  fetch?: typeof fetch;
  /** Liefert das Zugriffstoken aus dem Arbeitsspeicher; es wird nie in Web-Speicher oder Cookies abgelegt. */
  getToken?: () => string | undefined;
  /**
   * Mandantenbindung dieses Tabs (`X-Taktovia-Context` aus dem Tenantwechsel). Sie verleiht keine Rechte: der Server prüft sie bei jeder Anfrage
   * gegen die Mitgliedschaft; ohne Bindung gilt der Standardmandant.
   */
  getContext?: () => string | undefined;
  /** Erzeugt Idempotency-Keys; Standard `crypto.randomUUID`. */
  newKey?: () => string;
}

export type CallArgs<K extends OperationId> = {
  path?: Partial<Operations[K]['path']>;
  query?: Partial<Operations[K]['query']>;
  headers?: Partial<Operations[K]['headers']>;
  body?: Operations[K]['body'];
  /** Revision aus der gelesenen Ressource; wird als starker ETag in `If-Match` gesendet. */
  revision?: number | string;
  signal?: AbortSignal;
};

export interface ApiClient {
  call<K extends OperationId>(id: K, args: CallArgs<K>): Promise<Operations[K]['response']>;
  /**
   * Lädt eine binäre Antwort (z. B. das Belegoriginal) mit demselben Zugriffstoken und denselben Fehlern wie [call]; die Bytes bleiben im Arbeitsspeicher (kein Web-Speicher, keine Adresse mit Token).
   * Fehlt die Methode (Testdoubles), zeigt die Oberfläche den Zustand „Original nicht ladbar“.
   */
  callBlob?<K extends OperationId>(id: K, args: CallArgs<K>): Promise<{ blob: Blob; mime: string }>;
  /**
   * Öffnet einen Ereignisstrom (`text/event-stream`, z. B. den Live-Kanal, WP-M3-35) mit demselben Zugriffstoken und denselben Fehlern wie [call]. Das Token geht im Header mit
   * (nie in der Adresse); deshalb liest der Client den Strom über `fetch`, nicht über `EventSource`. Fehlt die Methode (Testdoubles), gilt der Kanal als nicht verfügbar.
   */
  openStream?<K extends OperationId>(id: K, args: CallArgs<K>): Promise<Response>;
}

function parseProblem(text: string): Problem | undefined {
  try {
    const v: unknown = JSON.parse(text);
    if (v && typeof v === 'object' && 'code' in v && 'status' in v) return v as Problem;
  } catch {
    /* kein Problem-JSON */
  }
  return undefined;
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const doFetch = options.fetch ?? ((...a: Parameters<typeof fetch>) => fetch(...a));
  const newKey = options.newKey ?? (() => crypto.randomUUID());

  function prepare(
    id: OperationId,
    args: CallArgs<OperationId>,
    accept: string,
  ): { url: string; init: RequestInit } {
    const info = operationTable[id];
    let path: string = info.path;
    for (const name of info.pathParams) {
      const value = (args.path as Record<string, unknown> | undefined)?.[name];
      if (value === undefined) throw new TypeError(`Pfadparameter ${name} fehlt für ${id}`);
      path = path.replace(`{${name}}`, encodeURIComponent(String(value)));
    }
    const query = new URLSearchParams();
    for (const name of info.query) {
      const value = (args.query as Record<string, unknown> | undefined)?.[name];
      if (value !== undefined && value !== null) query.set(name, String(value));
    }
    const qs = query.toString();
    const headers = new Headers({ accept });
    const given = (args.headers ?? {}) as Record<string, string | undefined>;
    for (const name of info.headers) {
      const v = given[name];
      if (v !== undefined) headers.set(name, v);
    }
    if (info.headers.includes('Idempotency-Key') && !headers.has('Idempotency-Key'))
      headers.set('Idempotency-Key', newKey());
    if (
      info.headers.includes('If-Match') &&
      !headers.has('If-Match') &&
      args.revision !== undefined
    )
      headers.set('If-Match', `"${String(args.revision)}"`);
    const token = options.getToken?.();
    if (token) headers.set('authorization', `Bearer ${token}`);
    const context = options.getContext?.();
    if (context) headers.set('x-taktovia-context', context);
    const init: RequestInit = { method: info.method, headers, credentials: 'omit' };
    if (info.hasBody && args.body !== undefined) {
      headers.set('content-type', 'application/json');
      init.body = JSON.stringify(args.body);
    }
    if (args.signal) init.signal = args.signal;
    return { url: `${options.baseUrl}${path}${qs ? `?${qs}` : ''}`, init };
  }

  async function fail(response: Response): Promise<never> {
    const text = await response.text();
    const retry = Number(response.headers.get('retry-after'));
    throw new ApiError(
      response.status,
      parseProblem(text),
      Number.isFinite(retry) && retry > 0 ? retry : undefined,
      response.headers.get('www-authenticate') ?? undefined,
    );
  }

  return {
    async call(id, args) {
      const { url, init } = prepare(
        id,
        args as CallArgs<OperationId>,
        'application/json, application/problem+json',
      );
      let response: Response;
      try {
        response = await doFetch(url, init);
      } catch {
        throw new ApiError(0);
      }
      if (!response.ok) return fail(response);
      const text = await response.text();
      return (text ? JSON.parse(text) : undefined) as never;
    },
    async callBlob(id, args) {
      const { url, init } = prepare(id, args as CallArgs<OperationId>, '*/*');
      let response: Response;
      try {
        response = await doFetch(url, init);
      } catch {
        throw new ApiError(0);
      }
      if (!response.ok) return fail(response);
      const blob = await response.blob();
      return { blob, mime: response.headers.get('content-type') ?? blob.type };
    },
    async openStream(id, args) {
      const { url, init } = prepare(
        id,
        args as CallArgs<OperationId>,
        'text/event-stream, application/problem+json',
      );
      let response: Response;
      try {
        response = await doFetch(url, init);
      } catch {
        throw new ApiError(0);
      }
      if (!response.ok) return fail(response);
      return response;
    },
  };
}
