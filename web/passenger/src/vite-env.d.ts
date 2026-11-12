/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Realm-URL des OIDC-Anbieters (Keycloak), z. B. `https://idp.example/realms/taktovia`. */
  readonly VITE_OIDC_ISSUER?: string;
  /** Client der Anwendung im Realm (Standard `passenger-web`). */
  readonly VITE_OIDC_CLIENT_ID?: string;
  readonly VITE_API_ORIGIN?: string;
  /** URL-Vorlage der Kartenkacheln mit `{z}`, `{x}`, `{y}` (https); ohne Angabe zeigt die Karte keinen Hintergrund (Entscheidung D-09). */
  readonly VITE_TILE_URL_TEMPLATE?: string;
  /** Pflichtangabe (Urheber/Lizenz) der Kachelquelle. */
  readonly VITE_TILE_ATTRIBUTION?: string;
}
