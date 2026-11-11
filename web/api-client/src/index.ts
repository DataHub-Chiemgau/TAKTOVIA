export * from './generated';
export { ApiError, createApiClient } from './runtime';
export type { ApiClient, ApiClientOptions, CallArgs } from './runtime';
export { PROVISIONAL_ROLE_RIGHTS, SCOPE_RIGHTS, hasRight, rightsOf } from './rights';
export type { Right } from './rights';
export { SessionProvider, useApi, useSession } from './session';
export type { Session } from './session';
export { AuthProvider, useAuth, useAuthOptional } from './auth/AuthProvider';
export type { AuthApi, AuthConfig, AuthState, EndReason } from './auth/AuthProvider';
export { DraftProvider, useDirtyForms, useDraftState } from './auth/drafts';
export { useMySessions, useSignOut, useTenantSwitch, useTenantSwitcherData } from './auth/hooks';
export type {
  MySessions,
  MySessionsState,
  TenantSwitchState,
  TenantSwitcherData,
} from './auth/hooks';
export {
  OidcError,
  buildAuthorizationUrl,
  completeLogin,
  endSessionUrl,
  isCallback,
  keycloakConfig,
  refreshTokens,
} from './auth/oidc';
export type { OidcConfig, PendingStore, TokenSet } from './auth/oidc';
export { challengeS256, randomString } from './auth/pkce';
/** Sitzungsressource der API (`Session` des Vertrags); `Session` heißt hier der Zustand der geladenen Anmeldung. */
export type { Session as SessionResource } from './generated';
