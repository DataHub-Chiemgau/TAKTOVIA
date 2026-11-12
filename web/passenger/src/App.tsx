import type { ReactNode } from 'react';
import {
  rightsOf,
  useAuthOptional,
  useMySessions,
  useSession,
  useSignOut,
  useTenantSwitcherData,
} from '@taktovia/api-client';
import {
  AppFrame,
  AuthStateView,
  BusyButton,
  ErrorState,
  ScreenFrame,
  SessionList,
  StateView,
  TenantSwitcher,
  ResourceView,
  visibleNav,
  zeit,
} from '@taktovia/design-system';
import type { NavEntry, NavLinkProps, ScreenContext } from '@taktovia/design-system';
import type { Right, SessionResource } from '@taktovia/api-client';
import type { ResourceState } from '@taktovia/design-system';
import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { Fragment } from 'react';
import { Profil } from './konto/Profil';
import { PrivacySettings } from './privacy/PrivacySettings';
import { Registrierung } from './registrierung/Registrierung';
import { Hilfe } from './hilfe/Hilfe';
import { Start } from './booking/Start';
import { OfflineBanner } from './netz/Offline';
import { useOnline } from './netz/useOnline';
import { tx } from './texte';
import type { TextKey } from './texte';

const ZONE = 'Europe/Berlin';

/** Hauptnavigation „Meine Reisen | Hilfe“ (Kap. 06 §6.2); Start ist die Marke, Konto der Link im Kopfbereich. */
const NAV: readonly NavEntry<Right>[] = [
  { id: 'reisen', label: tx('nav.reisen'), href: '/reisen', requires: 'passenger.app' },
  { id: 'hilfe', label: tx('nav.hilfe'), href: '/hilfe' },
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

/** Platzhalter eines Fachbildschirms (UI-01 bis UI-04) im Seitenrahmen; offline ohne jede Buchungsmöglichkeit. */
function Placeholder({ screen }: { screen: 'ui01' | 'ui02' | 'ui03' | 'ui04' }) {
  const online = useOnline();
  const context = useScreenContext(
    online
      ? { tone: 'info', label: tx('shell.status.shell') }
      : { tone: 'warning', label: 'Offline' },
  );
  if (!context) return null;
  const title = tx(`${screen}.title` as TextKey);
  return (
    <ScreenFrame title={title} context={context}>
      {online ? (
        <ResourceView
          state={{ status: 'ready', data: [] as string[] }}
          isEmpty={(d) => d.length === 0}
          empty={{
            title: tx(`${screen}.empty.title` as TextKey),
            hint: tx(`${screen}.empty.hint` as TextKey),
          }}
        >
          {() => null}
        </ResourceView>
      ) : (
        <StateView kind="offline" title={tx('offline.title')} hint={tx('offline.hint')} />
      )}
    </ScreenFrame>
  );
}

/** Konto ohne aktive Mitgliedschaft: verständlicher Zustand ohne Datenzugriff (WP-M1-44 AK 1). */
function NoMembership({ onRegistered }: { onRegistered: () => void }) {
  const auth = useAuthOptional();
  return (
    <>
      <AuthStateView
        kind="noMembership"
        action={
          auth ? (
            <button type="button" onClick={() => auth.signOut()}>
              Abmelden
            </button>
          ) : undefined
        }
      />
      {/* WP-M1-28: ein Konto ohne Mitgliedschaft kann sich hier als Fahrgast registrieren */}
      <Registrierung onDone={onRegistered} />
    </>
  );
}

/** UI-05 Basis (Konto): eigene Sitzungen, einzelne Geräte und alle Geräte abmelden (WP-M1-10, WP-M1-44). */
function Konto() {
  const sessions = useMySessions();
  const context = useScreenContext({ tone: 'info', label: 'Basis: Sitzungen' });
  if (!context) return null;
  const state: ResourceState<SessionResource[]> =
    sessions.state.status === 'ready'
      ? { status: 'ready', data: sessions.state.items }
      : sessions.state.status === 'error'
        ? { status: 'error', error: sessions.state.error }
        : { status: 'loading' };
  return (
    <ScreenFrame title="Konto" context={context}>
      {sessions.notice ? <p role="status">{sessions.notice}</p> : null}
      <ResourceView
        state={state}
        isEmpty={(d) => d.length === 0}
        empty={{ title: 'Keine Sitzungen' }}
        errorAction={
          <button type="button" onClick={sessions.reload}>
            Erneut versuchen
          </button>
        }
      >
        {(items) => (
          <SessionList
            sessions={items}
            currentId={sessions.currentId}
            formatTime={(iso) => zeit.toRfc3339(Date.parse(iso), ZONE)}
            onRevoke={sessions.revoke}
            onRevokeAll={sessions.revokeAll}
          />
        )}
      </ResourceView>
      <Profil />
      <PrivacySettings />
      <p>Vertreter (UI-05 im Vollausbau) folgen mit M7.</p>
    </ScreenFrame>
  );
}

/** Kopfbereich: Mandantenwähler (nur bei mehreren Mandanten), Abmelden. */
function HeaderEnd() {
  const data = useTenantSwitcherData();
  const signOut = useSignOut();
  const auth = useAuthOptional();
  return (
    <>
      <Link to="/">{tx('nav.start')}</Link>
      <Link to="/konto">{tx('nav.konto')}</Link>
      {data ? <TenantSwitcher {...data} /> : null}
      {auth ? <BusyButton onAction={signOut}>Abmelden</BusyButton> : null}
    </>
  );
}

function RequirePassenger({ children }: { children: ReactNode }) {
  const session = useSession();
  if (session.status !== 'ready' || !rightsOf(session.me).has('passenger.app'))
    return <StateView kind="forbidden" level={1} />;
  return <>{children}</>;
}

/** Passenger-Rahmenanwendung: Routing UI-01 bis UI-05 und Hilfe, Navigation nach Recht, Fehler- und Offlinezustände. Fachinhalte folgen mit WP-M2-36 ff. */
export function App() {
  const session = useSession();
  const location = useLocation();
  if (session.status === 'loading')
    return (
      <main>
        <StateView kind="loading" level={1} />
      </main>
    );
  if (session.status === 'error' && session.error.status === 403)
    return <NoMembership onRegistered={session.reload} />;
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
      brand={tx('shell.brand')}
      nav={visibleNav(NAV, rightsOf(session.me))}
      currentHref={location.pathname}
      linkAs={RouterLink}
      headerEnd={<HeaderEnd />}
    >
      <Fragment key={session.me.tenant_id}>
        <OfflineBanner />
        <Routes>
          <Route path="/" element={<Start />} />
          <Route
            path="/angebote"
            element={
              <RequirePassenger>
                <Placeholder screen="ui02" />
              </RequirePassenger>
            }
          />
          <Route
            path="/checkout/:offerId"
            element={
              <RequirePassenger>
                <Placeholder screen="ui03" />
              </RequirePassenger>
            }
          />
          <Route
            path="/reisen"
            element={
              <RequirePassenger>
                <Placeholder screen="ui04" />
              </RequirePassenger>
            }
          />
          <Route
            path="/reisen/:bookingId"
            element={
              <RequirePassenger>
                <Placeholder screen="ui04" />
              </RequirePassenger>
            }
          />
          <Route
            path="/konto"
            element={
              <RequirePassenger>
                <Konto />
              </RequirePassenger>
            }
          />
          <Route path="/hilfe" element={<Hilfe />} />
          <Route path="*" element={<StateView kind="notFound" level={1} />} />
        </Routes>
      </Fragment>
    </AppFrame>
  );
}
