import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';

const Context = createContext<(message: string) => void>(() => undefined);

/**
 * Zurückhaltende Live-Region für asynchrone Meldungen (Kap. 06 §6.1): `role="status"`, höflich, unsichtbar, springt nie im Layout.
 * Die Region ist von Anfang an im DOM, damit Screenreader spätere Änderungen sicher melden.
 */
export function LiveRegionProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState('');
  const announce = useCallback((text: string) => {
    // Zurücksetzen erzwingt, dass eine identische Folgemeldung erneut vorgelesen wird.
    setMessage('');
    queueMicrotask(() => setMessage(text));
  }, []);
  const value = useMemo(() => announce, [announce]);
  return (
    <Context.Provider value={value}>
      {children}
      <div role="status" aria-live="polite" aria-atomic="true" className="tk-sr-only">
        {message}
      </div>
    </Context.Provider>
  );
}

export function useAnnounce(): (message: string) => void {
  return useContext(Context);
}
