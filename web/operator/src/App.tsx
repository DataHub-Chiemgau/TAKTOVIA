import { Button, PageShell } from '@taktovia/design-system';
import { Link, Route, Routes } from 'react-router-dom';

const areas = [
  { path: 'callcenter', title: 'Callcenter' },
  { path: 'finanzen', title: 'Finanzen' },
  { path: 'personal', title: 'Personal' },
  { path: 'admin', title: 'Admin' },
] as const;

/** Operator-Shell mit Routen-Platzhaltern (keine UI-IDs aus Kap. 06 umgesetzt). */
export function App() {
  return (
    <Routes>
      <Route
        path="/"
        element={
          <PageShell title="TAKTOVIA Operator">
            <nav aria-label="Bereiche">
              <ul>
                {areas.map((a) => (
                  <li key={a.path}>
                    <Link to={`/${a.path}`}>{a.title}</Link>
                  </li>
                ))}
              </ul>
            </nav>
            <Button>Leitstelle oeffnen</Button>
          </PageShell>
        }
      />
      {areas.map((a) => (
        <Route
          key={a.path}
          path={a.path}
          element={
            <PageShell title={a.title}>
              <p>Platzhalter, Inhalt folgt.</p>
              <Link to="/">Zurueck</Link>
            </PageShell>
          }
        />
      ))}
    </Routes>
  );
}
