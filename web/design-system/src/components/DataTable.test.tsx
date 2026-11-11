import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DataTable } from '../index';
import { formatViolations, runAxe } from '../testing/axe';

interface Row {
  id: string;
  name: string;
}
const columns = [{ key: 'name', header: 'Name', render: (r: Row) => r.name }];
const rows: Row[] = [
  { id: '1', name: 'Region Nord' },
  { id: '2', name: 'Region Süd' },
];

describe('DataTable mit Cursor-Paging', () => {
  it('rendert Beschriftung, Spaltenköpfe und Zeilen als echte Tabelle', async () => {
    const { container } = render(
      <DataTable caption="Regionen" columns={columns} rows={rows} rowKey={(r) => r.id} />,
    );
    expect(screen.getByRole('table', { name: 'Regionen' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Name' })).toHaveAttribute('scope', 'col');
    expect(screen.getAllByRole('row')).toHaveLength(3);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('zeigt „Weitere laden“ nur bei vorhandenem next_cursor und übergibt den Cursor unverändert', () => {
    const onLoadMore = vi.fn();
    const { rerender } = render(
      <DataTable
        caption="R"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        page={{ nextCursor: 'abc.def', onLoadMore }}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Weitere laden' }));
    expect(onLoadMore).toHaveBeenCalledWith('abc.def');
    rerender(
      <DataTable
        caption="R"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        page={{ nextCursor: null, onLoadMore }}
      />,
    );
    expect(screen.queryByRole('button', { name: 'Weitere laden' })).toBeNull();
  });

  it('sperrt „Weitere laden“ während des Ladens (kein doppelter Cursor-Abruf)', () => {
    const onLoadMore = vi.fn();
    render(
      <DataTable
        caption="R"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        page={{ nextCursor: 'x', loading: true, onLoadMore }}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Weitere laden' }));
    expect(onLoadMore).not.toHaveBeenCalled();
  });

  it('ohne Zeilen erscheint der Leerzustand statt einer leeren Tabelle', () => {
    render(<DataTable caption="R" columns={columns} rows={[]} rowKey={(r: Row) => r.id} />);
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByText('Keine Einträge vorhanden.')).toBeInTheDocument();
  });
});
