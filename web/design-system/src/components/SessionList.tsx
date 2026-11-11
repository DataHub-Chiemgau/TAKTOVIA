import { useI18n } from '../i18n';
import { BusyButton } from './BusyButton';
import { DataTable } from './DataTable';
import { StatusBadge } from './StatusBadge';

export interface SessionRow {
  id: string;
  created_at: string;
  last_seen_at: string;
  revoked_at: string | null;
}

export interface SessionListProps<T extends SessionRow> {
  sessions: readonly T[];
  currentId: string | undefined;
  /** Formatiert einen Zeitpunkt in der Anzeigezone (Aufrufer, damit die Zone aus dem Dienstkontext stammt). */
  formatTime: (iso: string) => string;
  onRevoke: (s: T) => unknown;
  onRevokeAll: () => unknown;
}

/** UI-05 Basis: Sitzungen des Kontos mit Abmelden je Gerät und „Alle Geräte abmelden“. Aktionen sind gegen Doppelklick gesperrt. */
export function SessionList<T extends SessionRow>({
  sessions,
  currentId,
  formatTime,
  onRevoke,
  onRevokeAll,
}: SessionListProps<T>) {
  const { t } = useI18n();
  return (
    <div>
      <DataTable
        caption={t('sessions.caption')}
        emptyTitle={t('sessions.empty')}
        rows={[...sessions]}
        rowKey={(s) => s.id}
        columns={[
          {
            key: 'created',
            header: t('sessions.created'),
            render: (s) => (
              <>
                {formatTime(s.created_at)}
                {s.id === currentId ? ` (${t('sessions.thisDevice')})` : ''}
              </>
            ),
          },
          {
            key: 'seen',
            header: t('sessions.lastSeen'),
            render: (s) => formatTime(s.last_seen_at),
          },
          {
            key: 'state',
            header: t('sessions.state'),
            render: (s) =>
              s.revoked_at ? (
                <StatusBadge tone="neutral">{t('sessions.revokedState')}</StatusBadge>
              ) : (
                <StatusBadge tone="ok">{t('sessions.active')}</StatusBadge>
              ),
          },
          {
            key: 'action',
            header: t('sessions.action'),
            render: (s) =>
              s.revoked_at ? null : (
                <BusyButton onAction={() => onRevoke(s)}>
                  {t('sessions.revoke')} ({formatTime(s.created_at)})
                </BusyButton>
              ),
          },
        ]}
      />
      <p>
        <BusyButton onAction={onRevokeAll}>{t('sessions.revokeAll')}</BusyButton>
      </p>
    </div>
  );
}
