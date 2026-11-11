import { rightsOf, useSession } from '@taktovia/api-client';
import { AppFrame, ErrorState, ScreenFrame, StateView, visibleNav } from '@taktovia/design-system';
import type { NavLinkProps } from '@taktovia/design-system';
import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { DatenschutzScreen } from './datenschutz/DatenschutzScreen';
import { MembershipAdmin } from './identity/MembershipAdmin';
import { OPERATOR_NAV } from './navigation/navigation';
import { PlaceholderScreen, RequireRight } from './Screen';
import { useScreenContext } from './ScreenContext';

function RouterLink({ href, children, ...rest }: NavLinkProps) {
  return (
    <Link to={href} {...rest}>
      {children}
    </Link>
  );
}

function Start() {
  const context = useScreenContext({ tone: 'ok', label: 'Sitzung aktiv' });
  if (!context) return null;
  return (
    <ScreenFrame title="Start" context={context}>
      <p>
        Wählen Sie links einen Bereich. Es erscheinen nur Bereiche, für die Ihre Sitzung berechtigt
        ist.
      </p>
    </ScreenFrame>
  );
}

function AdminIndex() {
  const session = useSession();
  const context = useScreenContext({ tone: 'ok', label: 'Sitzung aktiv' });
  if (session.status !== 'ready' || !context) return null;
  const areas = visibleNav(
    OPERATOR_NAV.filter((e) => e.href.startsWith('/admin/')),
    rightsOf(session.me),
  );
  if (areas.length === 0) return <StateView kind="forbidden" level={1} />;
  return (
    <ScreenFrame title="Administration" context={context}>
      <ul>
        {areas.map((a) => (
          <li key={a.id}>
            <Link to={a.href}>{a.label}</Link>
          </li>
        ))}
      </ul>
    </ScreenFrame>
  );
}

/** Operator-Rahmenanwendung: Routing, rollenbezogene Navigation, Fehlerseiten, Admin-Grundgerüst (WP-M1-43). */
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

  const nav = visibleNav(OPERATOR_NAV, rightsOf(session.me));
  return (
    <AppFrame
      brand="TAKTOVIA Operator"
      nav={nav}
      currentHref={location.pathname}
      linkAs={RouterLink}
      headerEnd={<span>{session.me.tenant_name}</span>}
    >
      <Routes>
        <Route path="/" element={<Start />} />
        <Route
          path="/callcenter"
          element={
            <RequireRight right="callcenter">
              <PlaceholderScreen
                title="Callcenter"
                emptyTitle="Noch keine Inhalte"
                hint="Das Callcenter (UI-06) folgt in M2."
              />
            </RequireRight>
          }
        />
        <Route
          path="/finanzen"
          element={
            <RequireRight right="finance">
              <PlaceholderScreen
                title="Finanzen"
                emptyTitle="Noch keine Inhalte"
                hint="Belege und Clearing (UI-14, UI-16) folgen in M4 und M6."
              />
            </RequireRight>
          }
        />
        <Route
          path="/personal"
          element={
            <RequireRight right="staff">
              <PlaceholderScreen
                title="Personal"
                emptyTitle="Noch keine Inhalte"
                hint="Dienstplan und Zeiten (UI-12, UI-13) folgen in M4."
              />
            </RequireRight>
          }
        />
        <Route path="/admin" element={<AdminIndex />} />
        <Route
          path="/admin/gebiete"
          element={
            <RequireRight right="admin.ui17">
              <PlaceholderScreen
                title="Gebiete, Stops, Tarife"
                emptyTitle="Noch keine Gebiete, Stops oder Tarife"
                hint="UI-17: Revisionen anlegen und freigeben folgt mit den Konfigurations- und Geodaten-Arbeitspaketen."
              />
            </RequireRight>
          }
        />
        <Route
          path="/admin/kooperation"
          element={
            <RequireRight right="admin.ui18">
              <MembershipAdmin />
            </RequireRight>
          }
        />
        <Route
          path="/admin/datenschutz"
          element={
            <RequireRight right="admin.ui20">
              <DatenschutzScreen />
            </RequireRight>
          }
        />
        <Route path="*" element={<StateView kind="notFound" level={1} />} />
      </Routes>
    </AppFrame>
  );
}
