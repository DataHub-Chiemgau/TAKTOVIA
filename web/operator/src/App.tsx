import {
  rightsOf,
  useAuthOptional,
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
  StateView,
  TenantSwitcher,
  visibleNav,
} from '@taktovia/design-system';
import type { NavLinkProps } from '@taktovia/design-system';
import { Fragment } from 'react';
import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { Konto } from './Konto';
import { ConfigEditor } from './config/ConfigEditor';
import { ConfigRevisions } from './config/ConfigRevisions';
import { DienstKonfiguration } from './config/DienstKonfiguration';
import { DatenschutzScreen } from './datenschutz/DatenschutzScreen';
import { CompanyProfileForm } from './identity/CompanyProfileForm';
import { MembershipAdmin } from './identity/MembershipAdmin';
import { OrganizationAdmin } from './identity/OrganizationAdmin';
import { Fahrerfreigabe } from './personal/Fahrerfreigabe';
import { Geodaten } from './geo/Geodaten';
import { Organisationsstruktur } from './organisation/Organisationsstruktur';
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

/** Konto ohne aktive Mitgliedschaft: verständlicher Zustand ohne Datenzugriff (WP-M1-44 AK 1). */
function NoMembership() {
  const auth = useAuthOptional();
  return (
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
  );
}

/** Kopfbereich: Mandantenwähler (Anzeige der autorisierten Sitzung), Konto (UI-05 Basis) und Abmelden. */
function HeaderEnd() {
  const data = useTenantSwitcherData();
  const signOut = useSignOut();
  const auth = useAuthOptional();
  if (!data) return null;
  return (
    <>
      <TenantSwitcher {...data} />
      <Link to="/konto">Konto</Link>
      {auth ? <BusyButton onAction={signOut}>Abmelden</BusyButton> : null}
    </>
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
  if (session.status === 'error' && session.error.status === 403) return <NoMembership />;
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
      headerEnd={<HeaderEnd />}
    >
      <Fragment key={session.me.tenant_id}>
        <Routes>
          <Route path="/" element={<Start />} />
          <Route path="/konto" element={<Konto />} />
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
          <Route
            path="/personal/fahrerfreigabe"
            element={
              <RequireRight right="staff">
                <Fahrerfreigabe />
              </RequireRight>
            }
          />
          <Route path="/admin" element={<AdminIndex />} />
          <Route
            path="/admin/gebiete"
            element={
              <RequireRight right="admin.ui17">
                <ConfigRevisions />
                <p>
                  <Link to="/admin/struktur">Zur Organisationsstruktur (Regionen, Dienste)</Link>
                  {' · '}
                  <Link to="/admin/geodaten">Zu Gebieten, Haltepunkten und Betriebszeiten</Link>
                  {' · '}
                  <Link to="/admin/konfiguration">Zur wirksamen Konfiguration</Link>
                </p>
              </RequireRight>
            }
          />
          <Route
            path="/admin/geodaten"
            element={
              <RequireRight right="admin.ui17">
                <Geodaten />
              </RequireRight>
            }
          />
          <Route
            path="/admin/konfiguration"
            element={
              <RequireRight right="admin.ui17">
                <ConfigEditor />
              </RequireRight>
            }
          />
          <Route
            path="/admin/dienstkonfiguration"
            element={
              <RequireRight right="admin.ui17">
                <DienstKonfiguration />
              </RequireRight>
            }
          />
          <Route
            path="/admin/struktur"
            element={
              <RequireRight right="admin.ui17">
                <Organisationsstruktur />
              </RequireRight>
            }
          />
          <Route
            path="/admin/unternehmensprofil"
            element={
              <RequireRight right="admin.ui18">
                <CompanyProfileForm />
              </RequireRight>
            }
          />
          <Route
            path="/admin/organisationen"
            element={
              <RequireRight right="admin.ui18">
                <OrganizationAdmin />
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
      </Fragment>
    </AppFrame>
  );
}
