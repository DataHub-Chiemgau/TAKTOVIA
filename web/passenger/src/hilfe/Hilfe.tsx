import { tx } from '../texte';

/** Öffentliche Hilfeseite (ohne Anmeldung erreichbar, enthält keine personenbezogenen Daten). */
export function Hilfe({ headingLevel = 1 }: { headingLevel?: 1 | 2 }) {
  const Heading = headingLevel === 1 ? 'h1' : 'h2';
  return (
    <section aria-labelledby="hilfe-titel">
      <Heading id="hilfe-titel">{tx('hilfe.title')}</Heading>
      <p>{tx('hilfe.intro')}</p>
      <p>{tx('hilfe.access')}</p>
    </section>
  );
}
