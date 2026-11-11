/**
 * Routenkatalog der Passenger-PWA (Vertrag web-passenger-pwa.md). `public` = ohne Anmeldung erreichbar; alle anderen Routen zeigen vor
 * der Anmeldung nur den Anmeldezustand ohne Daten. Die Fachinhalte liefern M2-WP-36 bis M2-WP-46.
 */
export interface Route {
  readonly id: string;
  readonly path: string;
  readonly screen: 'UI-01' | 'UI-02' | 'UI-03' | 'UI-04' | 'UI-05' | 'Hilfe';
  readonly public: boolean;
  readonly right?: 'passenger.app';
}

export const ROUTES: readonly Route[] = [
  { id: 'start', path: '/', screen: 'UI-01', public: false },
  { id: 'angebote', path: '/angebote', screen: 'UI-02', public: false, right: 'passenger.app' },
  {
    id: 'checkout',
    path: '/checkout/:offerId',
    screen: 'UI-03',
    public: false,
    right: 'passenger.app',
  },
  { id: 'reisen', path: '/reisen', screen: 'UI-04', public: false, right: 'passenger.app' },
  {
    id: 'reise',
    path: '/reisen/:bookingId',
    screen: 'UI-04',
    public: false,
    right: 'passenger.app',
  },
  { id: 'konto', path: '/konto', screen: 'UI-05', public: false, right: 'passenger.app' },
  { id: 'hilfe', path: '/hilfe', screen: 'Hilfe', public: true },
];

export function isPublicPath(pathname: string): boolean {
  return ROUTES.some((r) => r.public && r.path === pathname);
}
