import { ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useEffect, useState } from 'react';
import { AuditList } from '../audit/AuditList';
import { useScreenContext } from '../ScreenContext';
import { DateninventarExport } from './DateninventarExport';
import type { Dateninventar } from './DateninventarExport';

/** UI-20 Basis (Datenschutz, Audit, Support): Export des Dateninventars und lesende Auditliste mit Zweckangabe (WP-M1-12); Fälle folgen mit WP-M1-33/-42. */
export function DatenschutzScreen() {
  const context = useScreenContext({
    tone: 'info',
    label: 'Basis: lesender Inventar-Export und Auditliste',
  });
  const [state, setState] = useState<ResourceState<Dateninventar>>({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;
    import('./dateninventar.json')
      .then((m) => {
        if (!cancelled) setState({ status: 'ready', data: m.default as unknown as Dateninventar });
      })
      .catch(() => {
        if (!cancelled) setState({ status: 'error', error: { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!context) return null;
  return (
    <ScreenFrame title="Datenschutz, Audit, Support" context={context}>
      <ResourceView
        state={state}
        isEmpty={(d) => d.tabellen.length === 0}
        empty={{
          title: 'Das Dateninventar ist leer',
          hint: 'Das Inventar wird aus db/dateninventar.d erzeugt (python db/dateninventar.py export --write).',
        }}
      >
        {(d) => <DateninventarExport inventar={d} />}
      </ResourceView>
      <AuditList />
    </ScreenFrame>
  );
}
