import type { OperationId, Operations, Problem } from './generated';
import { operationTable } from './generated';

/** Fehler einer API-Antwort. `status` 0 bedeutet: keine Antwort erhalten (Netzwerk). */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | undefined;
  readonly retryAfterSeconds: number | undefined;

  constructor(status: number, problem?: Problem, retryAfterSeconds?: number) {
    super(problem?.code ?? `HTTP_${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export interface ApiClientOptions {
  baseUrl: string;
  fetch?: typeof fetch;
  /** Liefert das Zugriffstoken aus dem Arbeitsspeicher; es wird nie in Web-Speicher oder Cookies abgelegt. */
  getToken?: () => string | undefined;
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

  return {
    async call(id, args) {
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
      const headers = new Headers({ accept: 'application/json, application/problem+json' });
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
      const init: RequestInit = { method: info.method, headers, credentials: 'omit' };
      if (info.hasBody && args.body !== undefined) {
        headers.set('content-type', 'application/json');
        init.body = JSON.stringify(args.body);
      }
      if (args.signal) init.signal = args.signal;

      let response: Response;
      try {
        response = await doFetch(`${options.baseUrl}${path}${qs ? `?${qs}` : ''}`, init);
      } catch {
        throw new ApiError(0);
      }
      const text = await response.text();
      if (!response.ok) {
        const retry = Number(response.headers.get('retry-after'));
        throw new ApiError(
          response.status,
          parseProblem(text),
          Number.isFinite(retry) && retry > 0 ? retry : undefined,
        );
      }
      return (text ? JSON.parse(text) : undefined) as never;
    },
  };
}
