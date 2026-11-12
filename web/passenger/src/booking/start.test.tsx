import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { Start } from './Start';

const SERVICE = '22222222-2222-4222-8222-222222222222';

function client(): ApiClient {
  const hit = (label: string, outside: boolean) => ({
    type: 'ADDRESS',
    label,
    point: { latitude: 51.05, longitude: 13.74 },
    stop_id: null,
    source: 'test',
    data_as_of: '2026-10-25T08:00:00Z',
    service_area: outside ? 'OUTSIDE' : 'INSIDE',
    nearest_stop: null,
  });
  return {
    call(id: string, args: { query?: { q?: string; service_id?: string } }) {
      if (id === 'getMe')
        return Promise.resolve({ ...sampleMe({ roles: ['PASSENGER'] }), service_scope: [SERVICE] });
      if (id === 'getMyProfile') return Promise.reject(new ApiError(404));
      if (id === 'searchPlaces')
        return Promise.resolve({
          items: [
            hit(args.query?.q === 'berlin' ? 'Berlin' : 'Dresden', args.query?.q === 'berlin'),
          ],
          notices: [],
          as_of: '2026-10-25T08:00:00Z',
        });
      return Promise.reject(new ApiError(404));
    },
  } as unknown as ApiClient;
}

describe('UI-01 Start: Ortswahl (WP-M2-38)', () => {
  afterEach(cleanup);

  it('start_und_ziel_werden_bestaetigt_und_ausserhalb_bediengebiet_blockiert', async () => {
    render(
      <MemoryRouter>
        <SessionProvider client={client()}>
          <Start />
        </SessionProvider>
      </MemoryRouter>,
    );
    await screen.findByRole('combobox', { name: 'Start' });
    fireEvent.change(screen.getByRole('combobox', { name: 'Start' }), {
      target: { value: 'dresden' },
    });
    fireEvent.click(await screen.findByRole('option', { name: /Dresden/ }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Ziel' }), {
      target: { value: 'berlin' },
    });
    fireEvent.click(await screen.findByRole('option', { name: /Berlin/ }));
    expect(screen.queryByTestId('orte-bereit')).toBeNull();
    expect(screen.getByText(/kann nicht gebucht werden/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Ziel Ändern' }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Ziel' }), {
      target: { value: 'dresden' },
    });
    fireEvent.click(await screen.findByRole('option', { name: /Dresden/ }));
    const ready = await screen.findByTestId('orte-bereit');
    expect(JSON.parse(ready.getAttribute('data-origin') ?? '{}')).toMatchObject({
      point: { latitude: 51.05, longitude: 13.74 },
      label: 'Dresden',
    });
  });
});
