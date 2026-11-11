import type { ReactNode } from 'react';
import { useI18n } from '../i18n';
import type { MessageKey } from '../i18n';
import { stateKindForStatus } from '../problem';
import type { ErrorLike, StateKind } from '../problem';

export interface StateViewProps {
  kind: StateKind;
  /** Überschreibt Titel und Text nur für `empty` (dort ist der Grund fachlich); Fehlertexte kommen immer aus dem Katalog. */
  title?: string;
  hint?: string;
  action?: ReactNode;
  retryAfterSeconds?: number | undefined;
  correlationId?: string | undefined;
  /** Überschriftenebene: 1, wenn der Zustand die ganze Seite ersetzt (genau eine h1 je Seite), sonst 2. */
  level?: 1 | 2;
}

const ALERT: ReadonlySet<StateKind> = new Set([
  'error',
  'unavailable',
  'offline',
  'forbidden',
  'unauthenticated',
  'conflict',
  'preconditionRequired',
  'rateLimited',
  'notFound',
]);

/**
 * Einheitliche Zustandsanzeige: laden, leer, Fehler, keine Berechtigung (Kap. 06 §6.9). Texte stammen aus dem Katalog,
 * nie aus dem freien Fehlertext des Servers; bei 403 werden keine Einzelheiten gezeigt.
 */
export function StateView({
  kind,
  title,
  hint,
  action,
  retryAfterSeconds,
  correlationId,
  level = 2,
}: StateViewProps) {
  const Heading = level === 1 ? 'h1' : 'h2';
  const { t } = useI18n();
  const titleKey = `state.${kind}.title` as MessageKey;
  const bodyKey = `state.${kind}.body` as MessageKey;
  const heading = title ?? t(titleKey);
  const body = hint ?? (kind === 'loading' || kind === 'empty' ? undefined : t(bodyKey));
  return (
    <section
      className="tk-state"
      data-testid="state-view"
      data-kind={kind}
      role={ALERT.has(kind) ? 'alert' : undefined}
      aria-busy={kind === 'loading' ? 'true' : undefined}
    >
      <Heading>{heading}</Heading>
      {body ? <p>{body}</p> : null}
      {kind === 'rateLimited' && retryAfterSeconds ? (
        <p>{t('state.rateLimited.retry', { seconds: retryAfterSeconds })}</p>
      ) : null}
      {correlationId ? <p>{t('common.correlation', { id: correlationId })}</p> : null}
      {action}
    </section>
  );
}

export function EmptyState({
  title,
  hint,
  action,
}: {
  title: string;
  hint?: string;
  action?: ReactNode;
}) {
  return (
    <StateView
      kind="empty"
      title={title}
      {...(hint !== undefined ? { hint } : {})}
      {...(action !== undefined ? { action } : {})}
    />
  );
}

/** Zeigt den Zustand zu einem API-Fehler (401/403/404/412/428/429/…). Die Zuordnung hängt nur am Statuscode. */
export function ErrorState({
  error,
  action,
  level,
}: {
  error: ErrorLike;
  action?: ReactNode;
  level?: 1 | 2;
}) {
  const kind = stateKindForStatus(error.status);
  return (
    <StateView
      kind={kind}
      {...(level !== undefined ? { level } : {})}
      retryAfterSeconds={error.retryAfterSeconds}
      correlationId={kind === 'forbidden' ? undefined : error.problem?.correlation_id}
      {...(action !== undefined ? { action } : {})}
    />
  );
}
