import { hasRight, useSession } from '@taktovia/api-client';
import type { Right } from '@taktovia/api-client';
import { ResourceView, ScreenFrame, StateView } from '@taktovia/design-system';
import type { ReactNode } from 'react';
import { useScreenContext } from './ScreenContext';

/** Zeigt den Bildschirm nur mit Recht aus `/v1/me`, sonst den Rechtezustand ohne Daten. */
export function RequireRight({ right, children }: { right: Right; children: ReactNode }) {
  const session = useSession();
  if (session.status !== 'ready' || !hasRight(session.me, right))
    return <StateView kind="forbidden" level={1} />;
  return <>{children}</>;
}

/** Grundgerüst eines noch leeren Bereichs: Bildschirmrahmen mit Kontext und fachlichem Leerzustand. */
export function PlaceholderScreen({
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
