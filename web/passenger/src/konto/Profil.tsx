import { useCallback, useEffect, useState } from 'react';
import { ApiError, useApi } from '@taktovia/api-client';
import type { NotificationChannel, Profile, ProfileUpdate } from '@taktovia/api-client';
import {
  BusyButton,
  ErrorState,
  Field,
  Form,
  StateView,
  SupportNeedsSelect,
} from '@taktovia/design-system';
import type { ProblemLike, SupportNeedCode } from '@taktovia/design-system';
import {
  FavoritenEditor,
  favoritesToDraft,
  favoritesToInput,
  validateFavorites,
} from './Favoriten';
import type { FavoriteDraft } from './Favoriten';

const LOCALES: readonly { value: Profile['locale']; label: string }[] = [
  { value: 'de-DE', label: 'Deutsch' },
  { value: 'en-GB', label: 'English' },
];
const CHANNELS: readonly { value: NotificationChannel; label: string }[] = [
  { value: 'PUSH', label: 'Push-Mitteilung' },
  { value: 'EMAIL', label: 'E-Mail' },
  { value: 'SMS', label: 'SMS' },
];

type Load =
  | { status: 'loading' }
  | { status: 'error'; error: ApiError }
  | { status: 'ready'; profile: Profile };

interface Draft {
  locale: Profile['locale'];
  email: string;
  phone: string;
  favorites: FavoriteDraft[];
  stops: string[];
  channels: NotificationChannel[];
}

function toDraft(p: Profile): Draft {
  return {
    locale: p.locale,
    email: p.contact.email?.value ?? '',
    phone: p.contact.phone?.value ?? '',
    favorites: favoritesToDraft(p.favorites),
    stops: [...p.preferred_stops],
    channels: [...p.notification_channels],
  };
}

/**
 * Die Haltestellenauswahl eines Favoriten ist kein Textfeld des Formulars: ein Fehler an `/favorites/{i}/stop_id` wird an der Koordinate des Favoriten gezeigt
 * (der Haltepunkt bestimmt dort den Ort), damit die Zusammenfassung ein Feld mit Namen nennt.
 */
function favoriteStopPointers(problem: ProblemLike | undefined): ProblemLike | undefined {
  if (!problem?.violations) return problem;
  return {
    ...problem,
    violations: problem.violations.map((v) => ({
      ...v,
      pointer: v.pointer.replace(/^(\/favorites\/\d+)\/stop_id$/, '$1/lat'),
    })),
  };
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

/** Nur die geänderten Bereiche; Listen ersetzen den Bereich vollständig (API `ProfileUpdate`). */
function diff(p: Profile, d: Draft): ProfileUpdate {
  const base = toDraft(p);
  const out: ProfileUpdate = {};
  if (d.locale !== base.locale) out.locale = d.locale;
  const contact: { email?: string; phone?: string } = {};
  if (d.email.trim() !== base.email && d.email.trim() !== '') contact.email = d.email.trim();
  if (d.phone.trim() !== base.phone && d.phone.trim() !== '') contact.phone = d.phone.trim();
  if (Object.keys(contact).length > 0) out.contact = contact;
  if (!same(d.favorites, base.favorites)) out.favorites = favoritesToInput(d.favorites);
  if (!same(d.stops, base.stops)) out.preferred_stops = d.stops;
  if (!same(d.channels, base.channels)) out.notification_channels = d.channels;
  return out;
}

function ChannelState({ view }: { view: Profile['contact']['email'] }) {
  if (!view) return null;
  return view.status === 'VERIFIED' ? (
    <p>Kontakt verifiziert.</p>
  ) : (
    <p role="status">
      Verifikation fehlt: Dieser Kontakt ist nicht für die Kontowiederherstellung nutzbar.
    </p>
  );
}

/** Bestätigungscode anfordern und einlösen (WP-M1-28). Der Code wird nur gesendet, nie angezeigt. */
function Verifikation({
  channel,
  onVerified,
}: {
  channel: 'EMAIL' | 'PHONE';
  onVerified: () => void;
}) {
  const api = useApi();
  const [code, setCode] = useState('');
  const [message, setMessage] = useState<string | undefined>(undefined);
  const name = channel === 'EMAIL' ? 'E-Mail' : 'Telefon';
  const start = async () => {
    try {
      await api.call('startContactVerification', { body: { channel } });
      setMessage('Der Code wurde gesendet.');
    } catch (e) {
      setMessage(
        e instanceof ApiError && e.status === 429
          ? 'Zu viele Versuche, bitte später erneut versuchen.'
          : 'Der Code konnte nicht gesendet werden.',
      );
    }
  };
  const confirm = async () => {
    try {
      await api.call('confirmContactVerification', { body: { channel, code: code.trim() } });
      setCode('');
      setMessage(undefined);
      onVerified();
    } catch (e) {
      setMessage(
        e instanceof ApiError && e.status === 429
          ? 'Zu viele Fehlversuche, bitte später erneut versuchen.'
          : 'Der Code ist falsch oder abgelaufen.',
      );
    }
  };
  return (
    <div role="group" aria-label={`${name} verifizieren`}>
      <BusyButton onAction={start}>{`Code für ${name} anfordern`}</BusyButton>
      <label>
        {`Code für ${name}`}
        <input
          inputMode="numeric"
          autoComplete="one-time-code"
          value={code}
          onChange={(e) => setCode(e.target.value)}
        />
      </label>
      <BusyButton onAction={confirm}>{`${name} bestätigen`}</BusyButton>
      {message ? <p role="status">{message}</p> : null}
    </div>
  );
}

/** Unterstützungsbedarf: eigene Auswahl aus dem Katalog (WP-M1-31), eingebettet in die Kontoseite. */
function Unterstuetzung() {
  const api = useApi();
  const [codes, setCodes] = useState<readonly SupportNeedCode[]>([]);
  const [message, setMessage] = useState<string | undefined>(undefined);
  useEffect(() => {
    let live = true;
    api.call('getMyTransportSupport', {}).then(
      (r) => live && setCodes(r.codes as SupportNeedCode[]),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [api]);
  const save = async () => {
    try {
      await api.call('replaceMyTransportSupport', { body: { codes: [...codes] } });
      setMessage('Gespeichert.');
    } catch {
      setMessage('Die Auswahl konnte nicht gespeichert werden.');
    }
  };
  return (
    <section aria-labelledby="unterstuetzung-h">
      <h2 id="unterstuetzung-h">Unterstützung</h2>
      <SupportNeedsSelect value={codes} onChange={setCodes} />
      <BusyButton onAction={save}>Auswahl speichern</BusyButton>
      {message ? <p role="status">{message}</p> : null}
    </section>
  );
}

/**
 * UI-05 Basis: Profil des Fahrgasts (WP-M1-29): Kontakt mit Verifikationsstatus, Sprache, Favoriten, bevorzugte Haltestellen, Benachrichtigungskanäle.
 * Änderungen laufen mit `If-Match`; eine zwischenzeitliche Änderung (412) überschreibt nichts, der Fahrgast lädt den aktuellen Stand.
 * Favoriten (WP-M2-36) tragen eine bestätigte Koordinate und optional einen Haltepunkt aus der Haltestellenliste; bevorzugte Haltestellen werden weiter als Kennung erfasst.
 * Die gespeicherte Sprache bestimmt `lang` des Dokuments (Screenreader, Silbentrennung); die Nachrichten nutzen sie serverseitig (WP-M2-33).
 */
export function Profil() {
  const api = useApi();
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const [draft, setDraft] = useState<Draft | undefined>(undefined);
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);

  const fetchProfile = useCallback(() => {
    api.call('getMyProfile', {}).then(
      (profile) => {
        setLoad({ status: 'ready', profile });
        setDraft(toDraft(profile));
      },
      (e: unknown) =>
        setLoad({ status: 'error', error: e instanceof ApiError ? e : new ApiError(0) }),
    );
  }, [api]);
  useEffect(() => fetchProfile(), [fetchProfile]);
  const savedLocale = load.status === 'ready' ? load.profile.locale : undefined;
  useEffect(() => {
    if (savedLocale) document.documentElement.lang = savedLocale;
  }, [savedLocale]);
  const reload = () => {
    setLoad({ status: 'loading' });
    fetchProfile();
  };

  if (load.status === 'loading' || !draft)
    return load.status === 'error' ? (
      <ErrorState error={load.error} />
    ) : (
      <StateView kind="loading" />
    );
  if (load.status === 'error') return <ErrorState error={load.error} />;
  const profile = load.profile;
  const set = (patch: Partial<Draft>) => setDraft({ ...draft, ...patch });

  const save = async () => {
    setProblem(undefined);
    setNotice(undefined);
    const invalid = validateFavorites(draft.favorites);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    const body = diff(profile, draft);
    if (Object.keys(body).length === 0) {
      setNotice('Keine Änderungen.');
      return;
    }
    try {
      const next = await api.call('updateMyProfile', { body, revision: profile.revision });
      setLoad({ status: 'ready', profile: next });
      setDraft(toDraft(next));
      setNotice('Gespeichert.');
    } catch (e) {
      if (e instanceof ApiError && e.status === 412) {
        setNotice(
          'Das Profil wurde zwischenzeitlich geändert (anderes Gerät). Es wurde nichts überschrieben; bitte den aktuellen Stand laden.',
        );
      } else if (e instanceof ApiError && (e.status === 422 || e.status === 409)) {
        setProblem(favoriteStopPointers(e.problem));
        if (e.status === 409) setNotice('Dieser Kontakt kann nicht verwendet werden.');
      } else setNotice('Das Profil konnte nicht gespeichert werden.');
    }
  };

  return (
    <section aria-labelledby="profil-h">
      <h2 id="profil-h">Profil</h2>
      {profile.status === 'PENDING_VERIFICATION' ? (
        <p role="status">
          Verifikation fehlt: Bitte bestätigen Sie Ihren Kontakt mit dem gesendeten Code.
        </p>
      ) : null}
      <Form onSubmit={save} submitLabel="Profil speichern" problem={problem}>
        <fieldset>
          <legend>Sprache</legend>
          <label>
            Sprache
            <select
              value={draft.locale}
              onChange={(e) => set({ locale: e.target.value as Profile['locale'] })}
            >
              {LOCALES.map((l) => (
                <option key={l.value} value={l.value}>
                  {l.label}
                </option>
              ))}
            </select>
          </label>
        </fieldset>
        <fieldset>
          <legend>Kontakt</legend>
          <Field
            pointer="/contact/email"
            label="E-Mail-Adresse"
            type="email"
            value={draft.email}
            onChange={(e) => set({ email: e.target.value })}
          />
          <ChannelState view={profile.contact.email} />
          {profile.contact.email && profile.contact.email.status === 'UNVERIFIED' ? (
            <Verifikation channel="EMAIL" onVerified={reload} />
          ) : null}
          <Field
            pointer="/contact/phone"
            label="Telefonnummer"
            type="tel"
            value={draft.phone}
            onChange={(e) => set({ phone: e.target.value })}
          />
          <ChannelState view={profile.contact.phone} />
          {profile.contact.phone && profile.contact.phone.status === 'UNVERIFIED' ? (
            <Verifikation channel="PHONE" onVerified={reload} />
          ) : null}
        </fieldset>
        <fieldset>
          <legend>Benachrichtigungskanäle</legend>
          {CHANNELS.map((c) => (
            <label key={c.value} style={{ display: 'block' }}>
              <input
                type="checkbox"
                checked={draft.channels.includes(c.value)}
                onChange={() =>
                  set({
                    channels: draft.channels.includes(c.value)
                      ? draft.channels.filter((x) => x !== c.value)
                      : [...draft.channels, c.value],
                  })
                }
              />{' '}
              {c.label}
            </label>
          ))}
        </fieldset>
        <FavoritenEditor value={draft.favorites} onChange={(favorites) => set({ favorites })} />
        <fieldset>
          <legend>Bevorzugte Haltestellen</legend>
          {draft.stops.map((s, i) => (
            <div key={i} role="group" aria-label={`Haltestelle ${i + 1}`}>
              <Field
                pointer={`/preferred_stops/${i}`}
                label={`Bevorzugter Haltepunkt ${i + 1}`}
                value={s}
                onChange={(e) =>
                  set({ stops: draft.stops.map((x, j) => (j === i ? e.target.value : x)) })
                }
              />
              <button
                type="button"
                onClick={() => set({ stops: draft.stops.filter((_, j) => j !== i) })}
              >{`Haltestelle ${i + 1} entfernen`}</button>
            </div>
          ))}
          <button type="button" onClick={() => set({ stops: [...draft.stops, ''] })}>
            Haltestelle hinzufügen
          </button>
        </fieldset>
      </Form>
      {notice ? <p role="status">{notice}</p> : null}
      <button type="button" onClick={reload}>
        Aktuellen Stand laden
      </button>
      <Unterstuetzung />
    </section>
  );
}
