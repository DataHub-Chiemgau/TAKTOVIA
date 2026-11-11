import { describe, expect, it } from 'vitest';
import { fieldErrorsFromProblem, stateKindForStatus } from './problem';

const base = {
  type: 'https://api.taktovia.example/problems/validation-failed',
  title: 'Validierung fehlgeschlagen',
  status: 422,
  code: 'VALIDATION_FAILED',
  correlation_id: '00000000-0000-4000-8000-000000000070',
};

describe('Problem-Auswertung', () => {
  it('ordnet Verletzungen nach pointer und code zu, ohne message auszuwerten', () => {
    const errors = fieldErrorsFromProblem({
      ...base,
      violations: [
        { pointer: '/legal_name', code: 'REQUIRED', message: 'Irgendein Freitext' },
        { pointer: '/legal_name', code: 'TOO_LONG', message: 'noch ein Freitext' },
        { pointer: '/address/street', code: 'PATTERN_MISMATCH', message: 'x' },
      ],
    });
    expect(errors.get('/legal_name')).toEqual(['REQUIRED', 'TOO_LONG']);
    expect(errors.get('/address/street')).toEqual(['PATTERN_MISMATCH']);
    expect(JSON.stringify([...errors])).not.toContain('Freitext');
  });

  it('ohne Verletzungen oder ohne Problem ist die Zuordnung leer', () => {
    expect(fieldErrorsFromProblem(undefined).size).toBe(0);
    expect(fieldErrorsFromProblem(base).size).toBe(0);
  });

  it.each([
    [401, 'unauthenticated'],
    [403, 'forbidden'],
    [404, 'notFound'],
    [409, 'conflict'],
    [412, 'conflict'],
    [428, 'preconditionRequired'],
    [429, 'rateLimited'],
    [503, 'unavailable'],
    [500, 'error'],
    [0, 'offline'],
  ])('Status %i ergibt den Zustand %s', (status, kind) => {
    expect(stateKindForStatus(status)).toBe(kind);
  });
});
