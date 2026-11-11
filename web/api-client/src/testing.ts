import type { Me, OperationId, Operations } from './generated';
import { ApiError } from './runtime';
import type { ApiClient } from './runtime';

type Handler<K extends OperationId> = () => Operations[K]['response'] | ApiError;

/** Test-Client mit festen Antworten je operationId; Fehler werden als `ApiError` geliefert. Nicht für Produktionscode. */
export function fakeApiClient(handlers: { [K in OperationId]?: Handler<K> }): ApiClient {
  return {
    call(id) {
      const handler = handlers[id] as (() => unknown) | undefined;
      if (!handler) return Promise.reject(new ApiError(404));
      const result = handler();
      return result instanceof ApiError ? Promise.reject(result) : Promise.resolve(result as never);
    },
  };
}

export function sampleMe(overrides: Partial<Me> = {}): Me {
  return {
    subject: 'user-1',
    issuer: 'https://idp.example/realms/test',
    tenant_id: '00000000-0000-4000-8000-000000000001',
    tenant_name: 'Verkehrsbetrieb Nord',
    roles: [],
    service_scope: null,
    session_id: '00000000-0000-4000-8000-000000000002',
    mfa: true,
    tenants: [],
    ...overrides,
  };
}
