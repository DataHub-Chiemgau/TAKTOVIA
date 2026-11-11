import { Button, PageShell } from '@taktovia/design-system';

/** Leere Startseite der Passenger-PWA (Platzhalter, keine UI-IDs aus Kap. 06 umgesetzt). */
export function App() {
  return (
    <PageShell title="TAKTOVIA Fahrgast">
      <p>Platzhalter: Buchung folgt ab M2.</p>
      <Button>Fahrt suchen</Button>
    </PageShell>
  );
}
