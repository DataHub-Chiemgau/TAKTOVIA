import { ApiError, useApi } from '@taktovia/api-client';
import type { Expense } from '@taktovia/api-client';
import { StatusBadge } from '@taktovia/design-system';
import { useState } from 'react';

const MAX_BYTES = 10 * 1024 * 1024;
const MIME = ['image/jpeg', 'image/png', 'application/pdf'];

/** SHA-256 im Browser (Web Crypto) als base64url ohne Auffüllung, wie `/v1/expenses/receipts` ihn verlangt. */
export async function sha256Base64Url(bytes: ArrayBuffer): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  let binary = '';
  digest.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * UI-14 Belegerfassung per Web-Upload für Leitstelle und Finanzen (WP-M4-38): Datei wählen, Hash berechnen, Uploadticket anfordern, Datei an das vorsignierte Ziel senden und den Upload abschließen
 * (Quarantäne und Malware-Prüfung folgen serverseitig, der Beleg erscheint danach in der Arbeitsliste). Ohne Uploadziel (kein Objektspeicher-Adapter angebunden, WP-M0-08 nicht ausgeführt) endet der Ablauf
 * mit einem klaren Hinweis, ohne einen Beleg vorzutäuschen. Aktiv nur mit Scope `receipts:write`.
 */
export function BelegUpload({
  legalEntityId,
  canWrite,
  onCreated,
}: {
  legalEntityId: string;
  canWrite: boolean;
  onCreated: (e: Expense | undefined) => void;
}) {
  const api = useApi();
  const [file, setFile] = useState<File | undefined>();
  const [kind, setKind] = useState<'RECEIPT' | 'FUEL_RECEIPT' | 'INVOICE'>('RECEIPT');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<
    { tone: 'ok' | 'error' | 'info'; text: string } | undefined
  >();

  const problem = file
    ? !MIME.includes(file.type)
      ? 'Erlaubt sind JPEG, PNG und PDF.'
      : file.size < 1
        ? 'Die Datei ist leer.'
        : file.size > MAX_BYTES
          ? 'Die Datei ist größer als 10 MiB.'
          : undefined
    : undefined;

  const start = async () => {
    if (!file || problem) return;
    setBusy(true);
    setNotice(undefined);
    try {
      const bytes = await file.arrayBuffer();
      const sha256 = await sha256Base64Url(bytes);
      const ticket = await api.call('requestReceiptUpload', {
        body: {
          legal_entity_id: legalEntityId,
          purpose: 'RECEIPT',
          mime: file.type as 'application/pdf',
          size_bytes: file.size,
          sha256,
          document_kind: kind,
        },
      });
      if (!ticket.upload) {
        setNotice({
          tone: 'info',
          text: 'Für diese Umgebung ist kein Uploadziel konfiguriert (Objektspeicher nicht angebunden); der Beleg wurde nicht übertragen.',
        });
        return;
      }
      const put = await fetch(ticket.upload.url, {
        method: ticket.upload.method,
        headers: ticket.upload.headers as Record<string, string>,
        body: bytes,
        credentials: 'omit',
      });
      if (!put.ok) throw new Error(`Upload ${put.status}`);
      const done = await api.call('completeReceiptUpload', {
        path: { ticket_id: ticket.ticket_id },
        revision: ticket.revision,
      });
      setNotice({ tone: 'ok', text: 'Der Beleg ist hochgeladen und wird geprüft.' });
      onCreated(
        done.document_id
          ? await api.call('getExpense', { path: { expense_id: done.document_id } })
          : undefined,
      );
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0;
      setNotice({
        tone: 'error',
        text:
          status === 422
            ? 'Die Datei wurde abgewiesen (422): Größe, Typ oder Hash stimmen nicht.'
            : status === 403
              ? 'Dafür fehlt das Recht (403).'
              : 'Der Upload ist fehlgeschlagen. Bitte erneut versuchen.',
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-label="Beleg hochladen">
      <h3>Beleg hochladen</h3>
      <div>
        <label htmlFor="beleg-datei">Datei (JPEG, PNG oder PDF, höchstens 10 MiB)</label>
        <input
          id="beleg-datei"
          type="file"
          accept={MIME.join(',')}
          disabled={!canWrite}
          onChange={(e) => setFile(e.target.files?.[0])}
        />
      </div>
      <div>
        <label htmlFor="beleg-art">Belegart</label>
        <select
          id="beleg-art"
          value={kind}
          disabled={!canWrite}
          onChange={(e) => setKind(e.target.value as typeof kind)}
        >
          <option value="RECEIPT">Quittung</option>
          <option value="FUEL_RECEIPT">Tankbeleg</option>
          <option value="INVOICE">Rechnung</option>
        </select>
      </div>
      {problem && <p role="alert">{problem}</p>}
      {!canWrite && <p>Ohne das Recht zum Erfassen ist der Upload gesperrt.</p>}
      <button
        type="button"
        disabled={!canWrite || !file || !!problem || busy}
        onClick={() => void start()}
      >
        Hochladen
      </button>
      {notice && (
        <p role={notice.tone === 'error' ? 'alert' : 'status'}>
          <StatusBadge tone={notice.tone}>
            {notice.tone === 'error' ? 'Fehler' : notice.tone === 'ok' ? 'Erledigt' : 'Hinweis'}
          </StatusBadge>{' '}
          {notice.text}
        </p>
      )}
    </section>
  );
}
