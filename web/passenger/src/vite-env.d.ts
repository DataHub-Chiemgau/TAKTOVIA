/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Realm-URL des OIDC-Anbieters (Keycloak), z. B. `https://idp.example/realms/taktovia`. */
  readonly VITE_OIDC_ISSUER?: string;
  /** Client der Anwendung im Realm (Standard `passenger-web`). */
  readonly VITE_OIDC_CLIENT_ID?: string;
  readonly VITE_API_ORIGIN?: string;
}
