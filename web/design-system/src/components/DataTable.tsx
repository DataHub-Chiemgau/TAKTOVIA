import type { ReactNode } from 'react';
import { useI18n } from '../i18n';
import { ScrollRegion } from './ScrollRegion';
import { EmptyState } from './StateView';

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
}

export interface CursorPage {
  /** `next_cursor` der letzten Antwort; `null` oder fehlend: keine weitere Seite. Der Cursor ist opak und wird nie verändert. */
  nextCursor: string | null | undefined;
  loading?: boolean;
  onLoadMore: (cursor: string) => void;
}

export interface DataTableProps<T> {
  caption: string;
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  page?: CursorPage;
  emptyTitle?: string;
}

/** Tabelle mit Cursor-Paging (Kap. 05 §5.1: opaker `next_cursor`). Ohne Zeilen erscheint ein Leerzustand. */
export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  page,
  emptyTitle,
}: DataTableProps<T>) {
  const { t } = useI18n();
  if (rows.length === 0) return <EmptyState title={emptyTitle ?? t('common.noEntries')} />;
  return (
    <div>
      <ScrollRegion label={caption}>
        <table className="tk-table">
          <caption>{caption}</caption>
          <thead>
            <tr>
              {columns.map((c) => (
                <th key={c.key} scope="col">
                  {c.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={rowKey(row)}>
                {columns.map((c) => (
                  <td key={c.key}>{c.render(row)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </ScrollRegion>
      {page?.nextCursor ? (
        <button
          type="button"
          aria-disabled={page.loading ? 'true' : undefined}
          aria-busy={page.loading ? 'true' : undefined}
          onClick={() => {
            if (!page.loading && page.nextCursor) page.onLoadMore(page.nextCursor);
          }}
        >
          {t('common.loadMore')}
        </button>
      ) : null}
    </div>
  );
}
