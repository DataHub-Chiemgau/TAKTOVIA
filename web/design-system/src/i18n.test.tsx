import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { I18nProvider, de, translate, useI18n } from './i18n';

function Probe({ id }: { id: Parameters<ReturnType<typeof useI18n>['t']>[0] }) {
  const { t } = useI18n();
  return <p>{t(id, { seconds: 12 })}</p>;
}

describe('Internationalisierung', () => {
  it('Deutsch ist die erste Sprache und der Standard ohne Provider', () => {
    render(<Probe id="state.rateLimited.title" />);
    expect(screen.getByText(de['state.rateLimited.title'])).toBeInTheDocument();
  });

  it('setzt Parameter ein', () => {
    expect(translate(de, 'state.rateLimited.retry', { seconds: 12 })).toContain('12');
  });

  it('ein zweiter Katalog überschreibt Texte, fehlende Schlüssel fallen auf Deutsch zurück', () => {
    render(
      <I18nProvider locale="en" messages={{ 'state.rateLimited.title': 'Too many requests' }}>
        <Probe id="state.rateLimited.title" />
        <Probe id="state.notFound.title" />
      </I18nProvider>,
    );
    expect(screen.getByText('Too many requests')).toBeInTheDocument();
    expect(screen.getByText(de['state.notFound.title'])).toBeInTheDocument();
  });

  it('jede Verletzungscode-Meldung des API-Katalogs hat einen deutschen Text (kein freier Servertext nötig)', async () => {
    const { ViolationCode } = await import('./problem');
    for (const code of ViolationCode) expect(de[`violation.${code}`]).toBeTruthy();
  });
});
