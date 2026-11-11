import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ACCESS_PURPOSES, AccessPurposeSelect } from './AccessPurposeSelect';
import { SUPPORT_NEED_CODES, SupportNeedsSelect } from './SupportNeedsSelect';
import type { SupportNeedCode } from './SupportNeedsSelect';
import { formatViolations, runAxe } from '../testing/axe';

const API = readFileSync(resolve(__dirname, '../../../../api/passengers.yaml'), 'utf8');

/** Liest die Aufzählung `- CODE` der ersten `enum:`-Liste nach [marker] (kein YAML-Parser im Workspace nötig). */
function enumAfter(marker: string): string[] {
  const from = API.indexOf(marker);
  expect(from).toBeGreaterThan(-1);
  const rest = API.slice(API.indexOf('enum:', from)).split('\n').slice(1);
  const out: string[] = [];
  for (const line of rest) {
    const m = /^\s+- ([A-Z_]+)\s*$/.exec(line);
    if (!m) break;
    out.push(m[1] as string);
  }
  return out;
}

describe('UI-05 Basis: Unterstützungsbedarf (WP-M1-31)', () => {
  it('bietet nur die Katalogauswahl und keine Freitext- oder Diagnoseabfrage', () => {
    const { container } = render(<SupportNeedsSelect value={[]} onChange={() => undefined} />);
    expect(screen.getAllByRole('checkbox')).toHaveLength(SUPPORT_NEED_CODES.length);
    expect(container.querySelectorAll('input:not([type=checkbox]), textarea, select')).toHaveLength(
      0,
    );
    expect(container.textContent).not.toMatch(
      /Diagnose(?!n sind)|Krankheit(?!en oder)|Behinderungsgrad|Arzt/i,
    );
    expect(screen.getByText(/nicht abgefragt/)).toBeInTheDocument();
  });

  it('der Katalog entspricht dem API-Vertrag PassengerSupportNeeds', () => {
    expect([...SUPPORT_NEED_CODES]).toEqual(enumAfter('    PassengerSupportNeeds:'));
  });

  it('meldet Auswahl und Abwahl', () => {
    const onChange = vi.fn();
    const { rerender } = render(<SupportNeedsSelect value={[]} onChange={onChange} />);
    fireEvent.click(screen.getByRole('checkbox', { name: /Niederflur/ }));
    expect(onChange).toHaveBeenLastCalledWith(['LOW_FLOOR']);
    rerender(<SupportNeedsSelect value={['LOW_FLOOR'] as SupportNeedCode[]} onChange={onChange} />);
    fireEvent.click(screen.getByRole('checkbox', { name: /Niederflur/ }));
    expect(onChange).toHaveBeenLastCalledWith([]);
  });

  it('ist ohne axe-Verstöße', async () => {
    const { container } = render(
      <SupportNeedsSelect value={['LOW_FLOOR']} onChange={() => undefined} />,
    );
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});

describe('UI-20 Basis: Zugriff mit Zweckangabe (WP-M1-31)', () => {
  it('die Zwecke entsprechen dem Header X-Access-Purpose des Vertrags', () => {
    expect([...ACCESS_PURPOSES]).toEqual(enumAfter('name: X-Access-Purpose'));
    expect(API).toMatch(/name: X-Access-Purpose\n\s+in: header\n\s+required: true/);
  });

  it('ohne Auswahl bleibt der Zweck leer und die Pflichtmeldung erscheint', () => {
    render(<AccessPurposeSelect value="" onChange={() => undefined} missing />);
    expect(screen.getByRole('combobox', { name: 'Zweck des Zugriffs' })).toHaveValue('');
    expect(screen.getByRole('alert')).toHaveTextContent('Ohne Zweckangabe');
  });

  it('meldet den gewählten Zweck', () => {
    const onChange = vi.fn();
    render(<AccessPurposeSelect value="" onChange={onChange} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'SUPPORT_CASE' } });
    expect(onChange).toHaveBeenCalledWith('SUPPORT_CASE');
  });

  it('ist ohne axe-Verstöße', async () => {
    const { container } = render(
      <AccessPurposeSelect value="SUPPORT_CASE" onChange={() => undefined} />,
    );
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
