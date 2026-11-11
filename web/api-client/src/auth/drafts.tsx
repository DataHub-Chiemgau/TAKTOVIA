import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useSession } from '../session';

interface DraftEntry {
  label: string;
  value: unknown;
}

interface DraftStore {
  /** Aufbewahrte Entwürfe je Mandant und Formular (nur Arbeitsspeicher dieses Tabs; Personendaten gehören nicht in Web-Speicher). */
  saved: Map<string, DraftEntry>;
  /** Derzeit ungespeicherte Eingaben der angezeigten Formulare. */
  live: Map<string, { label: string }>;
  listeners: Set<() => void>;
}

const Ctx = createContext<DraftStore | null>(null);

/**
 * Hält ungespeicherte Eingaben getrennt je Mandant. Beim Mandantenwechsel wird der Bildschirmbaum neu aufgebaut; Entwürfe des alten Mandanten
 * werden **nicht** in den neuen übernommen, sondern unter dem alten Mandanten aufbewahrt und beim Zurückwechseln angeboten (Rückkehrmöglichkeit).
 */
export function DraftProvider({ children }: { children: ReactNode }) {
  const store = useMemo<DraftStore>(
    () => ({ saved: new Map(), live: new Map(), listeners: new Set() }),
    [],
  );
  return <Ctx.Provider value={store}>{children}</Ctx.Provider>;
}

function useStore(): DraftStore | null {
  return useContext(Ctx);
}

/**
 * Formularzustand mit Entwurfsspeicher: wie `useState`, meldet aber ungespeicherte Eingaben (`isDirty`) an, bewahrt sie beim Abbau (z. B. durch
 * einen Mandantenwechsel) unter dem **aktuellen** Mandanten auf und stellt sie beim erneuten Aufbau im selben Mandanten wieder her
 * (`restored`). Ein anderer Mandant sieht nie den Entwurf eines fremden.
 */
export function useDraftState<T>(
  formId: string,
  label: string,
  empty: T,
  isDirty: (value: T) => boolean,
): [T, (value: T) => void, { restored: boolean }] {
  const store = useStore();
  const session = useSession();
  const tenant = session.status === 'ready' ? session.me.tenant_id : '';
  const key = `${tenant}:${formId}`;
  const [initial] = useState(() => {
    const saved = store?.saved.get(key);
    return saved ? { value: saved.value as T, restored: true } : { value: empty, restored: false };
  });
  const [value, setValue] = useState<T>(initial.value);
  const dirty = isDirty(value);
  const latest = useRef({ value, dirty });
  useEffect(() => {
    latest.current = { value, dirty };
    if (!store) return;
    if (dirty) store.live.set(key, { label });
    else store.live.delete(key);
    store.listeners.forEach((l) => l());
  }, [store, key, label, value, dirty]);
  useEffect(
    () => () => {
      if (!store) return;
      store.live.delete(key);
      if (latest.current.dirty) store.saved.set(key, { label, value: latest.current.value });
      else store.saved.delete(key);
      store.listeners.forEach((l) => l());
    },
    [store, key, label],
  );
  return [value, setValue, { restored: initial.restored }];
}

/** Beschriftungen der Formulare des aktiven Mandanten mit ungespeicherten Eingaben (für die Rückfrage vor dem Wechsel). */
export function useDirtyForms(): string[] {
  const store = useStore();
  const session = useSession();
  const tenant = session.status === 'ready' ? session.me.tenant_id : '';
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!store) return;
    const l = () => setTick((n) => n + 1);
    store.listeners.add(l);
    return () => void store.listeners.delete(l);
  }, [store]);
  if (!store) return [];
  return [...store.live.entries()]
    .filter(([k]) => k.startsWith(`${tenant}:`))
    .map(([, v]) => v.label);
}
