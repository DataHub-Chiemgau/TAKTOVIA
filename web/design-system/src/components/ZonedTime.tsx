import { formatLocal, isKnownZone, operatingDay } from '../time';

export interface ZonedTimeProps {
  /** Zeitpunkt als RFC 3339 mit Offset (so liefert ihn die API). */
  instant: string;
  /** IANA-Zone des Dienstes, in der angezeigt wird. */
  zone: string;
  /** Beginn des Betriebstags in Minuten nach Mitternacht Ortszeit (Standard 0). */
  dayStartMinutes?: number;
}

/** Zeigt einen Zeitpunkt in der Zone des Dienstes mit UTC-Offset und lokalem Betriebstag (WP-M1-18 AK 4 und 5, PH-AT-018). */
export function ZonedTime({ instant, zone, dayStartMinutes = 0 }: ZonedTimeProps) {
  const ms = Date.parse(instant);
  if (Number.isNaN(ms) || !isKnownZone(zone))
    return <span role="alert">Zeitpunkt oder Zeitzone ungültig.</span>;
  return (
    <span>
      <time dateTime={instant}>{formatLocal(ms, zone)}</time> · {zone} · Betriebstag{' '}
      <span data-testid="operating-day">{operatingDay(ms, zone, dayStartMinutes)}</span>
    </span>
  );
}
