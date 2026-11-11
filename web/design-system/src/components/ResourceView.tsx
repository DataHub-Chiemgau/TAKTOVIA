import type { ReactNode } from 'react';
import type { ErrorLike } from '../problem';
import { EmptyState, ErrorState, StateView } from './StateView';

export type ResourceState<T> =
  { status: 'loading' } | { status: 'error'; error: ErrorLike } | { status: 'ready'; data: T };

export interface ResourceViewProps<T> {
  state: ResourceState<T>;
  isEmpty: (data: T) => boolean;
  /** Fachlicher Leerzustand: Grund und nächste Handlung (Kap. 06 §6.2, §6.9). */
  empty: { title: string; hint?: string; action?: ReactNode };
  /** Aktion im Fehlerzustand, z. B. „Erneut versuchen“. */
  errorAction?: ReactNode;
  children: (data: T) => ReactNode;
}

/**
 * Wiederverwendbarer Zustandsrahmen für Bildschirme (UI-17, UI-18, UI-20 und alle folgenden): laden, Fehler und fehlende Rechte
 * (aus dem Statuscode), leer, sonst die Daten. Bei Fehlern werden nie Daten gerendert.
 */
export function ResourceView<T>({
  state,
  isEmpty,
  empty,
  errorAction,
  children,
}: ResourceViewProps<T>) {
  if (state.status === 'loading') return <StateView kind="loading" />;
  if (state.status === 'error')
    return (
      <ErrorState
        error={state.error}
        {...(errorAction !== undefined ? { action: errorAction } : {})}
      />
    );
  if (isEmpty(state.data))
    return (
      <EmptyState
        title={empty.title}
        {...(empty.hint !== undefined ? { hint: empty.hint } : {})}
        {...(empty.action !== undefined ? { action: empty.action } : {})}
      />
    );
  return <>{children(state.data)}</>;
}
