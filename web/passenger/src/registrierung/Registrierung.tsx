import { useState } from 'react';
import { ApiError, useApi } from '@taktovia/api-client';
import { BusyButton, Field, Form } from '@taktovia/design-system';

type Step = 'form' | 'verify';

/**
 * UI-05 Basis: Registrierung als Fahrgast und Kontakt verifizieren (WP-M1-28). Das Konto beim Identitätsanbieter besteht bereits; hier entsteht das
 * Fahrgastkonto im Vertriebskontext des Clients. Der Kontakt gilt erst nach Eingabe des gesendeten Codes als verifiziert („Verifikation fehlt“).
 */
export function Registrierung({ onDone }: { onDone: () => void }) {
  const api = useApi();
  const [step, setStep] = useState<Step>('form');
  const [channel, setChannel] = useState<'EMAIL' | 'PHONE'>('EMAIL');
  const [value, setValue] = useState('');
  const [locale, setLocale] = useState<'de-DE' | 'en-GB'>('de-DE');
  const [code, setCode] = useState('');
  const [problem, setProblem] = useState<ApiError['problem']>(undefined);
  const [message, setMessage] = useState<string | undefined>(undefined);

  const register = async () => {
    setProblem(undefined);
    setMessage(undefined);
    try {
      await api.call('registerPassenger', {
        body: { contact: { channel, value: value.trim() }, locale },
      });
      setStep('verify');
    } catch (e) {
      if (e instanceof ApiError && e.status === 422) setProblem(e.problem);
      else if (e instanceof ApiError && e.status === 409)
        setMessage('Die Registrierung ist mit diesen Angaben nicht möglich.');
      else setMessage('Die Registrierung ist fehlgeschlagen. Bitte später erneut versuchen.');
    }
  };

  const confirm = async () => {
    setMessage(undefined);
    try {
      await api.call('confirmContactVerification', { body: { channel, code: code.trim() } });
      onDone();
    } catch (e) {
      setMessage(
        e instanceof ApiError && e.status === 429
          ? 'Zu viele Fehlversuche, bitte später erneut versuchen.'
          : 'Der Code ist falsch oder abgelaufen.',
      );
    }
  };

  if (step === 'verify')
    return (
      <section aria-labelledby="verify-h">
        <h2 id="verify-h">Kontakt verifizieren</h2>
        <p role="status">
          Verifikation fehlt: Wir haben Ihnen einen Code gesendet. Geben Sie ihn hier ein.
        </p>
        <label>
          Bestätigungscode
          <input
            inputMode="numeric"
            autoComplete="one-time-code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
          />
        </label>
        <BusyButton onAction={confirm}>Kontakt bestätigen</BusyButton>
        <button type="button" onClick={onDone}>
          Später bestätigen
        </button>
        {message ? <p role="alert">{message}</p> : null}
      </section>
    );

  return (
    <section aria-labelledby="reg-h">
      <h2 id="reg-h">Als Fahrgast registrieren</h2>
      <Form onSubmit={register} submitLabel="Registrieren" problem={problem}>
        <fieldset>
          <legend>Kontaktweg</legend>
          <label>
            <input
              type="radio"
              name="channel"
              checked={channel === 'EMAIL'}
              onChange={() => setChannel('EMAIL')}
            />{' '}
            E-Mail
          </label>
          <label>
            <input
              type="radio"
              name="channel"
              checked={channel === 'PHONE'}
              onChange={() => setChannel('PHONE')}
            />{' '}
            Telefon
          </label>
        </fieldset>
        <Field
          pointer="/contact/value"
          label={channel === 'EMAIL' ? 'E-Mail-Adresse' : 'Telefonnummer (mit Landesvorwahl)'}
          type={channel === 'EMAIL' ? 'email' : 'tel'}
          value={value}
          onChange={(e) => setValue(e.target.value)}
        />
        <label>
          Sprache
          <select value={locale} onChange={(e) => setLocale(e.target.value as 'de-DE' | 'en-GB')}>
            <option value="de-DE">Deutsch</option>
            <option value="en-GB">English</option>
          </select>
        </label>
      </Form>
      {message ? <p role="alert">{message}</p> : null}
    </section>
  );
}
