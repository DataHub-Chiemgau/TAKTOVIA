import type { ReactNode } from 'react';
import { rightsOf, useSession } from '@taktovia/api-client';
import {
  AppFrame,
  ErrorState,
  ScreenFrame,
  StateView,
  ResourceView,
  visibleNav,
  zeit,
} from '@taktovia/design-system';
import type { NavEntry, NavLinkProps, ScreenContext } from '@taktovia/design-system';
import type { Right } from '@taktovia/api-client';
import { Link, Route, Routes, useLocation } from 'react-router-dom';

const ZONE = 'Europe/Berlin';

const NAV: readonly NavEntry<Right>[] = [
  { id: 'start', label: 'Start', href: '/' },
  { id: 'reisen', label: 'Meine Reisen', href: '/reisen', requires: 'passenger.app' },
  { id: 'konto', label: 'Konto', href: '/konto', requires: 'passenger.app' },
];

function RouterLink({ href, children, ...rest }: NavLinkProps) {
  return (
    <Link to={href} {...rest}>
      {children}
    </Link>
  );
}

function useScreenContext(status: ScreenContext['status']): ScreenContext | null {
  const session = useSession();
  if (session.status !== 'ready') return null;
  return {
    tenant: session.me.tenant_name,
    service:
      session.me.service_scope === null
        ? 'Alle Dienste'
        : `${session.me.service_scope.length} Dienste`,
    status,
    dataAsOf: zeit.toRfc3339(session.loadedAt, ZONE),
    zone: ZONE,
  };
}

function Placeholder({
  title,
  emptyTitle,
  hint,
}: {
  title: string;
  emptyTitle: string;
  hint: string;
}) {
  const context = useScreenContext({ tone: 'info', label: 'Grundgerüst, noch keine Inhalte' });
  if (!context) return null;
  return (
    <ScreenFrame title={title} context={context}>
      <ResourceView
        state={{ status: 'ready', data: [] as string[] }}
        isEmpty={(d) => d.length === 0}
        empty={{ title: emptyTitle, hint }}
      >
        {() => null}
      </ResourceView>
    </ScreenFrame>
  );
}

function RequirePassenger({ children }: { children: ReactNode }) {
  const session = useSession();
  if (session.status !== 'ready' || !rightsOf(session.me).has('passenger.app'))
    return <StateView kind="forbidden" level={1} />;
  return <>{children}</>;
}

/** Passenger-Rahmenanwendung: Routing, Navigation nach Recht, Fehlerseiten. Buchung (UI-01 bis UI-04) folgt ab M2. */
export function App() {
  const session = useSession();
  const location = useLocation();
  if (session.status === 'loading')
    return (
      <main>
        <StateView kind="loading" level={1} />
      </main>
    );
  if (session.status === 'error')
    return (
      <main>
        <ErrorState
          level={1}
          error={session.error}
          action={
            <button type="button" onClick={session.reload}>
              Erneut versuchen
            </button>
          }
        />
      </main>
    );
  return (
    <AppFrame
      brand="TAKTOVIA Fahrgast"
      nav={visibleNav(NAV, rightsOf(session.me))}
      currentHref={location.pathname}
      linkAs={RouterLink}
    >
      <Routes>
        <Route
          path="/"
          element={
            <Placeholder
              title="Start"
              emptyTitle="Noch keine Fahrtsuche"
              hint="Fahrten suchen und buchen (UI-01 bis UI-03) folgt ab M2."
            />
          }
        />
        <Route
          path="/reisen"
          element={
            <RequirePassenger>
              <Placeholder
                title="Meine Reisen"
                emptyTitle="Keine Reisen"
                hint="Aktive und vergangene Reisen (UI-04) folgen ab M2."
              />
            </RequirePassenger>
          }
        />
        <Route
          path="/konto"
          element={
            <RequirePassenger>
              <Placeholder
                title="Konto"
                emptyTitle="Noch keine Kontodaten"
                hint="UI-05 Basis: Kontakt, Präferenzen und Vertreter folgen mit der Registrierung."
              />
            </RequirePassenger>
          }
        />
        <Route path="*" element={<StateView kind="notFound" level={1} />} />
      </Routes>
    </AppFrame>
  );
}
