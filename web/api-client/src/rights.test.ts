import { describe, expect, it } from 'vitest';
import { hasRight, rightsOf } from './rights';
import type { Me } from './generated';

const me = (roles: string[]): Me => ({
  subject: 's',
  issuer: 'i',
  tenant_id: '00000000-0000-4000-8000-000000000001',
  tenant_name: 'A',
  roles,
  service_scope: null,
  session_id: '00000000-0000-4000-8000-000000000002',
  mfa: true,
  tenants: [],
});

describe('Rechte aus /v1/me', () => {
  it('liefert Rechte nur für bekannte Rollen; unbekannte Rollen geben keine Rechte', () => {
    expect(rightsOf(me([])).size).toBe(0);
    expect(rightsOf(me(['gibt-es-nicht'])).size).toBe(0);
    expect(hasRight(me(['ADMIN']), 'admin.ui17')).toBe(true);
    expect(hasRight(me(['PASSENGER']), 'admin.ui17')).toBe(false);
  });

  it('Administrator erhält nicht automatisch Personal- oder Finanzrechte (Kap. 01 §1.7)', () => {
    expect(hasRight(me(['ADMIN']), 'finance')).toBe(false);
    expect(hasRight(me(['ADMIN']), 'staff')).toBe(false);
    expect(hasRight(me(['ADMIN', 'FINANCE']), 'finance')).toBe(true);
  });

  it('ein lokal manipulierter Mandant oder eine Rolle außerhalb von /v1/me erzeugt keine Rechte', () => {
    const tampered = { ...me(['PASSENGER']), tenant_id: 'fremd' };
    expect(hasRight(tampered, 'admin.ui20')).toBe(false);
  });
});
