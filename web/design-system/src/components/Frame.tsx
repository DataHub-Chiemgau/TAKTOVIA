import { useId, useState } from 'react';
import type { ComponentType, KeyboardEvent, ReactNode } from 'react';
import { useI18n } from '../i18n';
import { StatusBadge } from './StatusBadge';
import type { StatusTone } from './StatusBadge';
import { ZonedTime } from './ZonedTime';

export interface NavEntry<R extends string = string> {
  id: string;
  label: string;
  href: string;
  /** Recht, das `/v1/me` liefern muss, damit der Eintrag sichtbar ist; fehlt es, ist der Eintrag immer sichtbar. */
  requires?: R;
}

/** Nur Einträge, deren Recht vorhanden ist. Die Anzeige ist keine Rechtequelle; der Server prüft jede Anfrage erneut. */
export function visibleNav<R extends string>(
  entries: readonly NavEntry<R>[],
  rights: ReadonlySet<R>,
): NavEntry<R>[] {
  return entries.filter((e) => e.requires === undefined || rights.has(e.requires));
}

export interface NavLinkProps {
  href: string;
  className?: string;
  'aria-current'?: 'page';
  children: ReactNode;
}

function DefaultLink({ href, children, ...rest }: NavLinkProps) {
  return (
    <a href={href} {...rest}>
      {children}
    </a>
  );
}

export interface AppFrameProps {
  brand: string;
  nav: readonly NavEntry[];
  currentHref: string;
  /** Link-Komponente des Routers (clientseitige Navigation); Standard ist ein normaler Anker. */
  linkAs?: ComponentType<NavLinkProps>;
  /** Rechts im Kopf: Nutzer- oder Mandantenhinweis. */
  headerEnd?: ReactNode;
  children: ReactNode;
}

/**
 * Anwendungsrahmen: Desktop mit Navigation links, Hauptbereich und optionalem Detailpanel; schmal mit ausklappbarer Navigation
 * und einem Arbeitsschritt je Seite. Genau ein `main`; Sprunglink zum Hauptinhalt.
 */
export function AppFrame({
  brand,
  nav,
  currentHref,
  linkAs: Link = DefaultLink,
  headerEnd,
  children,
}: AppFrameProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const navId = useId();
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape') setOpen(false);
  };
  return (
    <div className="tk-app" onKeyDown={onKeyDown}>
      <a className="tk-skip-link" href="#main">
        {t('common.skipToMain')}
      </a>
      <header className="tk-app-header">
        <span className="tk-app-brand">{brand}</span>
        {headerEnd}
        <button
          type="button"
          className="tk-menu-toggle"
          aria-expanded={open}
          aria-controls={navId}
          onClick={() => setOpen((o) => !o)}
        >
          {t('common.menu')}
        </button>
      </header>
      <nav id={navId} className="tk-nav" aria-label={t('common.mainNav')} data-open={open}>
        <ul>
          {nav.map((entry) => (
            <li key={entry.id}>
              <Link
                href={entry.href}
                {...(entry.href === currentHref ? { 'aria-current': 'page' as const } : {})}
              >
                {entry.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      <main id="main" className="tk-main" tabIndex={-1}>
        {children}
      </main>
    </div>
  );
}

export interface ScreenContext {
  tenant: string;
  /** Dienst oder Dienstumfang, z. B. „Alle Dienste“. */
  service: string;
  /** Fachlicher Status des Bildschirms (nicht der technische Ladezustand). */
  status: { tone: StatusTone; label: string };
  /** Zeitpunkt des angezeigten Datenstands (RFC 3339 mit Offset). */
  dataAsOf: string;
  /** IANA-Zone des Dienstes für die Anzeige des Datenstands. */
  zone: string;
}

export function ContextBar({ context }: { context: ScreenContext }) {
  const { t } = useI18n();
  return (
    <section aria-label={t('common.context')} className="tk-context">
      <dl className="tk-context-list">
        <div>
          <dt>{t('common.tenant')}</dt>
          <dd>{context.tenant}</dd>
        </div>
        <div>
          <dt>{t('common.service')}</dt>
          <dd>{context.service}</dd>
        </div>
        <div>
          <dt>{t('common.status')}</dt>
          <dd>
            <StatusBadge tone={context.status.tone}>{context.status.label}</StatusBadge>
          </dd>
        </div>
        <div>
          <dt>{t('common.dataAsOf')}</dt>
          <dd>
            <ZonedTime instant={context.dataAsOf} zone={context.zone} />
          </dd>
        </div>
      </dl>
    </section>
  );
}

export interface ScreenFrameProps {
  title: string;
  /** Pflicht: jeder Bildschirm benennt Mandant/Dienst, fachlichen Status und Datenstand (Kap. 06 §6.1). */
  context: ScreenContext;
  detail?: ReactNode;
  children: ReactNode;
}

/** Bildschirmrahmen: Überschrift, Arbeitskontext oben, Hauptbereich und optionales Detailpanel. */
export function ScreenFrame({ title, context, detail, children }: ScreenFrameProps) {
  const { t } = useI18n();
  return (
    <>
      <h1>{title}</h1>
      <ContextBar context={context} />
      <div className="tk-screen" data-detail={detail ? 'true' : 'false'}>
        <div>{children}</div>
        {detail ? (
          <aside className="tk-screen-detail" aria-label={t('common.details')}>
            {detail}
          </aside>
        ) : null}
      </div>
    </>
  );
}
