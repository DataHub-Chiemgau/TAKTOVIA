import { useEffect, useId, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { useI18n } from '../i18n';

export interface TenantSwitcherProps {
  tenants: readonly { id: string; name: string }[];
  activeId: string;
  /** Wechselt den Mandanten; `false`: nicht gewechselt. */
  onSwitch: (tenantId: string) => Promise<boolean> | boolean;
  /** Beschriftungen der Formulare mit ungespeicherten Eingaben im aktiven Mandanten. */
  dirtyForms?: readonly string[];
  pending?: boolean;
  failed?: boolean;
}

/**
 * Mandantenwähler (UI-Kontext, Kap. 06 §6.1): zeigt den aktiven Mandanten als **Anzeige** der autorisierten Sitzung, nicht als Rechtequelle.
 * Mit nur einem Mandanten erscheint der Name ohne Auswahl. Der Wechsel ist bewusst (Auswahl plus Schaltfläche); gibt es ungespeicherte
 * Eingaben, fragt ein Dialog zuerst: sie werden nicht in den neuen Mandanten übernommen, aber für die Rückkehr aufbewahrt.
 */
export function TenantSwitcher({
  tenants,
  activeId,
  onSwitch,
  dirtyForms = [],
  pending = false,
  failed = false,
}: TenantSwitcherProps) {
  const { t } = useI18n();
  const id = useId();
  const [chosen, setChosen] = useState<string | undefined>(undefined);
  const target = chosen !== undefined && tenants.some((x) => x.id === chosen) ? chosen : activeId;
  const [confirming, setConfirming] = useState(false);
  const dialog = useRef<HTMLDivElement>(null);
  const active = tenants.find((x) => x.id === activeId);

  useEffect(() => {
    if (confirming) dialog.current?.querySelector<HTMLElement>('button')?.focus();
  }, [confirming]);

  if (tenants.length < 2) return <span data-testid="tenant-name">{active?.name ?? ''}</span>;

  const go = () => {
    if (target === activeId || pending) return;
    if (dirtyForms.length > 0) setConfirming(true);
    else void onSwitch(target);
  };
  const keys = (e: KeyboardEvent) => {
    if (e.key === 'Escape') setConfirming(false);
  };

  return (
    <div className="tk-tenant-switcher">
      <label htmlFor={`${id}-sel`}>{t('tenant.label')}</label>{' '}
      <select
        id={`${id}-sel`}
        value={target}
        onChange={(e) => setChosen(e.target.value)}
        disabled={pending}
      >
        {tenants.map((x) => (
          <option key={x.id} value={x.id}>
            {x.name}
          </option>
        ))}
      </select>{' '}
      <button
        type="button"
        onClick={go}
        aria-disabled={pending || target === activeId ? 'true' : undefined}
      >
        {t('tenant.switch')}
      </button>
      {pending ? <span role="status">{t('tenant.switching')}</span> : null}
      {failed ? <span role="alert">{t('tenant.error')}</span> : null}
      {confirming ? (
        <div className="tk-dialog-backdrop">
          <div
            ref={dialog}
            role="alertdialog"
            aria-modal="true"
            aria-labelledby={`${id}-t`}
            aria-describedby={`${id}-b`}
            className="tk-dialog"
            onKeyDown={keys}
          >
            <h2 id={`${id}-t`}>{t('tenant.dirty.title')}</h2>
            <p id={`${id}-b`}>
              {t('tenant.dirty.body', { tenant: active?.name ?? '', forms: dirtyForms.join(', ') })}
            </p>
            <div className="tk-dialog-actions">
              <button
                type="button"
                onClick={() => {
                  setConfirming(false);
                  void onSwitch(target);
                }}
              >
                {t('tenant.dirty.confirm')}
              </button>
              <button
                type="button"
                className="tk-button-secondary"
                onClick={() => setConfirming(false)}
              >
                {t('tenant.dirty.stay')}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
