import { useEffect, useState } from 'react';
import { tx } from '../texte';

/**
 * Countdown der bestätigten Restfrist des Holds (Kap. 06 §6.3): die Frist ist `hold.expires_at` des Servers. Die Anzeige ist ein `timer` ohne
 * Live-Ansage (kein sekündliches Vorlesen); den Ablauf meldet die umgebende Ansicht.
 */
export function Countdown({
  until,
  now = Date.now,
}: {
  until: string;
  now?: (() => number) | undefined;
}) {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  void tick;
  const rest = Math.max(0, Math.ceil((Date.parse(until) - now()) / 1000));
  const mm = String(Math.floor(rest / 60)).padStart(2, '0');
  const ss = String(rest % 60).padStart(2, '0');
  return (
    <p role="timer" aria-live="off" data-testid="countdown" data-rest={rest}>
      {tx('checkout.restfrist')}: <strong>{`${mm}:${ss}`}</strong>
    </p>
  );
}

export function restSekunden(until: string, now: number): number {
  return Math.ceil((Date.parse(until) - now) / 1000);
}
