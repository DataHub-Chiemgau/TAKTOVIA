/**
 * Auswertung von `application/problem+json` (Kap. 05): Zustand und Feldzuordnung hängen nur an Status, `code` und
 * `violations[].pointer`/`code`, nie am freien Text (`title`, `detail`, `message`).
 */

/** Katalog der Feldverletzungscodes (common.yaml `ViolationCode`). Ein Test hält ihn mit dem deutschen Textkatalog synchron. */
export const ViolationCode = [
  'REQUIRED',
  'UNKNOWN_FIELD',
  'TYPE_MISMATCH',
  'TOO_SHORT',
  'TOO_LONG',
  'BELOW_MINIMUM',
  'ABOVE_MAXIMUM',
  'PATTERN_MISMATCH',
  'INVALID_FORMAT',
  'NOT_IN_ENUM',
  'TOO_FEW_ITEMS',
  'TOO_MANY_ITEMS',
  'NOT_UNIQUE',
  'INVALID_VALUE',
  'NESTING_TOO_DEEP',
  'MONEY_NOT_INTEGER',
  'MONEY_OUT_OF_RANGE',
  'MONEY_NEGATIVE',
  'CURRENCY_UNKNOWN',
  'EXPONENT_MISMATCH',
  'OFFSET_REQUIRED',
  'LOCAL_TIME_NONEXISTENT',
  'LOCAL_TIME_AMBIGUOUS',
  'ZONE_UNKNOWN',
  'ZONE_OFFSET_MISMATCH',
  'INTERVAL_INVALID',
  'TARGET_NOT_ALLOWED',
] as const;

export interface ProblemLike {
  type?: string;
  title?: string;
  status: number;
  code: string;
  detail?: string;
  correlation_id?: string;
  violations?: { pointer: string; code: string; message?: string }[];
}

/** Fehler einer API-Antwort in der Form, die `@taktovia/api-client` als `ApiError` liefert (strukturell, ohne Abhängigkeit). */
export interface ErrorLike {
  status: number;
  problem?: ProblemLike | undefined;
  retryAfterSeconds?: number | undefined;
}

/** pointer -> Codes in Reihenfolge des Eintreffens. Der Freitext `message` wird nicht übernommen. */
export function fieldErrorsFromProblem(problem: ProblemLike | undefined): Map<string, string[]> {
  const map = new Map<string, string[]>();
  for (const v of problem?.violations ?? []) {
    const list = map.get(v.pointer) ?? [];
    list.push(v.code);
    map.set(v.pointer, list);
  }
  return map;
}

export type StateKind =
  | 'loading'
  | 'empty'
  | 'error'
  | 'unauthenticated'
  | 'forbidden'
  | 'notFound'
  | 'conflict'
  | 'preconditionRequired'
  | 'rateLimited'
  | 'unavailable'
  | 'offline';

export function stateKindForStatus(status: number): StateKind {
  switch (status) {
    case 0:
      return 'offline';
    case 401:
      return 'unauthenticated';
    case 403:
      return 'forbidden';
    case 404:
      return 'notFound';
    case 409:
    case 412:
      return 'conflict';
    case 428:
      return 'preconditionRequired';
    case 429:
      return 'rateLimited';
    case 502:
    case 503:
    case 504:
      return 'unavailable';
    default:
      return 'error';
  }
}
