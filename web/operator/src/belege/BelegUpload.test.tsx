import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { BelegUpload, sha256Base64Url } from './BelegUpload';

const LE = '00000000-0000-4000-8000-0000000000a1';

function setup(
  handlers: Partial<Record<OperationId, (args: Record<string, unknown>) => unknown>>,
  canWrite = true,
) {
  const calls: { id: OperationId; args: Record<string, unknown> }[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe')
        return Promise.resolve(sampleMe({ roles: ['finance'], scopes: ['receipts:write'] }));
      const h = handlers[id];
      return h ? Promise.resolve(h(args)) : Promise.reject(new Error('unerwartet ' + id));
    }),
  } as unknown as ApiClient;
  const created = vi.fn();
  const view = render(
    <SessionProvider client={client}>
      <BelegUpload legalEntityId={LE} canWrite={canWrite} onCreated={created} />
    </SessionProvider>,
  );
  return { ...view, calls, created };
}

const file = (name: string, type: string, content = 'beleg') => new File([content], name, { type });

describe('UI-14 Belegerfassung per Web-Upload', () => {
  it('sha256_als_base64url_ohne_auffuellung', async () => {
    // SHA-256 von "abc"
    expect(await sha256Base64Url(new TextEncoder().encode('abc').buffer)).toBe(
      'ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0',
    );
  });

  it('ohne_uploadziel_klarer_hinweis_und_kein_vorgetaeuschter_beleg', async () => {
    const { calls, created, container } = setup({
      requestReceiptUpload: () => ({
        ticket_id: '00000000-0000-4000-8000-0000000000b1',
        upload: null,
        revision: 1,
      }),
    });
    fireEvent.change(await screen.findByLabelText(/Datei \(JPEG/), {
      target: { files: [file('bon.jpg', 'image/jpeg')] },
    });
    fireEvent.change(screen.getByLabelText('Belegart'), { target: { value: 'FUEL_RECEIPT' } });
    fireEvent.click(screen.getByRole('button', { name: 'Hochladen' }));
    expect(await screen.findByText(/kein Uploadziel konfiguriert/)).toBeInTheDocument();
    const req = calls.find((c) => c.id === 'requestReceiptUpload');
    expect(req?.args).toMatchObject({
      body: {
        legal_entity_id: LE,
        purpose: 'RECEIPT',
        mime: 'image/jpeg',
        size_bytes: 5,
        document_kind: 'FUEL_RECEIPT',
      },
    });
    expect(calls.some((c) => c.id === 'completeReceiptUpload')).toBe(false);
    expect(created).not.toHaveBeenCalled();
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('ungueltige_dateien_ohne_recht_und_abschluss_mit_vorsigniertem_ziel', async () => {
    const view = setup({}, false);
    expect(
      await screen.findByText('Ohne das Recht zum Erfassen ist der Upload gesperrt.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hochladen' })).toBeDisabled();
    view.unmount();

    const ok = setup({
      requestReceiptUpload: () => ({
        ticket_id: '00000000-0000-4000-8000-0000000000b1',
        revision: 1,
        upload: {
          method: 'PUT',
          url: 'https://objects.test/up',
          headers: { 'content-type': 'image/png' },
        },
      }),
      completeReceiptUpload: () => ({ document_id: '00000000-0000-4000-8000-0000000000e1' }),
      getExpense: () => ({ document_id: '00000000-0000-4000-8000-0000000000e1' }),
    });
    fireEvent.change(await screen.findByLabelText(/Datei \(JPEG/), {
      target: { files: [file('x.exe', 'application/x-msdownload')] },
    });
    expect(screen.getByRole('alert')).toHaveTextContent('Erlaubt sind JPEG, PNG und PDF.');
    expect(screen.getByRole('button', { name: 'Hochladen' })).toBeDisabled();
    const put = vi.fn(() => Promise.resolve(new Response(null, { status: 200 })));
    vi.stubGlobal('fetch', put);
    fireEvent.change(screen.getByLabelText(/Datei \(JPEG/), {
      target: { files: [file('ok.png', 'image/png')] },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Hochladen' }));
    await waitFor(() => expect(ok.created).toHaveBeenCalled());
    expect(put).toHaveBeenCalledWith(
      'https://objects.test/up',
      expect.objectContaining({ method: 'PUT', credentials: 'omit' }),
    );
    expect(ok.calls.map((c) => c.id)).toEqual(
      expect.arrayContaining(['requestReceiptUpload', 'completeReceiptUpload', 'getExpense']),
    );
    vi.unstubAllGlobals();
  });
});
