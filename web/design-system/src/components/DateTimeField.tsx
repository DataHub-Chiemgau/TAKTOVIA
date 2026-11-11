import { useId } from 'react';
import {
  formatLocal,
  isKnownZone,
  operatingDay,
  offsetMinutesAt,
  formatOffset,
  resolveLocal,
  toRfc3339,
} from '../time';

export interface DateTimeValue {
  /** Lokale Eingabe `YYYY-MM-DDTHH:mm[:ss]` ohne Offset. */
  local: string;
  /** Gewählter UTC-Offset bei mehrdeutiger Ortszeit, sonst `null`. */
  offset: string | null;
}

export interface DateTimeFieldProps {
  label: string;
  /** IANA-Zone des Dienstes, in der die Eingabe gelesen wird. */
  zone: string;
  value: DateTimeValue;
  onChange: (value: DateTimeValue & { instant: string | null }) => void;
}

/**
 * Datum/Zeit mit Zonenanzeige und Offsetwahl (Kap. 06 §6.2, WP-M1-18): zeigt die Zone des Dienstes und den gültigen
 * UTC-Offset; bei uneindeutiger Ortszeit (Herbstumstellung) verlangt sie die Auswahl des Offsets, bei nicht existenter
 * Ortszeit (Frühjahrslücke) eine neue Eingabe. `instant` ist erst bei eindeutiger Eingabe RFC 3339 mit Offset, sonst `null`.
 */
export function DateTimeField({ label, zone, value, onChange }: DateTimeFieldProps) {
  const id = useId();
  const res = value.local ? resolveLocal(value.local, zone) : null;
  const chosen =
    res?.kind === 'ambiguous' ? res.options.find((o) => o.offset === value.offset) : undefined;
  const resolved = res?.kind === 'unique' ? res.value : chosen;
  const instant = resolved ? toRfc3339(resolved.epochMs, zone) : null;

  const emit = (local: string, offset: string | null) => {
    const r = local ? resolveLocal(local, zone) : null;
    const o =
      r?.kind === 'ambiguous'
        ? (r.options.find((x) => x.offset === offset) ?? null)
        : r?.kind === 'unique'
          ? r.value
          : null;
    onChange({
      local,
      offset: r?.kind === 'ambiguous' ? offset : null,
      instant: o ? toRfc3339(o.epochMs, zone) : null,
    });
  };

  const zoneOk = isKnownZone(zone);
  const firstOption =
    res?.kind === 'ambiguous' ? res.options[0] : res?.kind === 'unique' ? res.value : undefined;
  const secondOption = res?.kind === 'ambiguous' ? res.options[1] : undefined;
  const zoneLabel =
    zoneOk && firstOption
      ? `${zone} (UTC${formatOffset(offsetMinutesAt(firstOption.epochMs, zone))}${secondOption ? ' oder ' + secondOption.offset : ''})`
      : zone;
  const errorId = `${id}-error`;
  const invalidMessage = !zoneOk
    ? `Unbekannte Zeitzone „${zone}“.`
    : res?.kind === 'gap'
      ? 'Diese Ortszeit existiert wegen der Zeitumstellung nicht. Bitte eine neue Zeit eingeben.'
      : res?.kind === 'invalid'
        ? 'Bitte Datum und Uhrzeit vollständig eingeben.'
        : null;

  return (
    <div>
      <label htmlFor={id}>{label}</label>{' '}
      <input
        id={id}
        type="datetime-local"
        step={1}
        value={value.local}
        aria-invalid={invalidMessage ? true : undefined}
        aria-describedby={invalidMessage ? errorId : undefined}
        onChange={(e) => emit(e.target.value, null)}
      />{' '}
      <span data-testid="zone">{zoneLabel}</span>
      {invalidMessage ? (
        <p id={errorId} role="alert">
          {invalidMessage}
        </p>
      ) : null}
      {res?.kind === 'ambiguous' ? (
        <fieldset aria-describedby={`${id}-ambiguous`}>
          <legend>UTC-Offset wählen</legend>
          <p id={`${id}-ambiguous`} role="alert">
            Diese Ortszeit kommt wegen der Zeitumstellung zweimal vor. Bitte den UTC-Offset wählen.
          </p>
          {res.options.map((o) => (
            <label key={o.offset}>
              <input
                type="radio"
                name={`${id}-offset`}
                value={o.offset}
                checked={value.offset === o.offset}
                onChange={() => emit(value.local, o.offset)}
              />{' '}
              {o.offset} ({formatLocal(o.epochMs, zone)};{' '}
              {new Date(o.epochMs).toISOString().slice(11, 16)} UTC)
            </label>
          ))}
        </fieldset>
      ) : null}
      {instant && resolved ? (
        <p>
          <time dateTime={instant}>{instant}</time>, Betriebstag{' '}
          <span data-testid="operating-day">{operatingDay(resolved.epochMs, zone)}</span>
        </p>
      ) : null}
    </div>
  );
}
