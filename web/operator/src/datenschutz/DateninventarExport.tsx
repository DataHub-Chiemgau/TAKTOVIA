import { hasRight, useSession } from '@taktovia/api-client';
import { ScrollRegion, StateView, StatusBadge } from '@taktovia/design-system';
import { useMemo, useState } from 'react';

export interface InventarSpalte {
  spalte: string;
  personenbezogen: 'ja' | 'nein';
  zweck: string;
  rechtsgrundlage: string;
  schutzklasse: string;
  loeschklasse: string;
  bemerkung: string;
}

export interface Dateninventar {
  format: 'dateninventar.v1';
  freigegeben: boolean;
  hinweis: string;
  kataloge: {
    zwecke: Record<string, string>;
    rechtsgrundlagen: Record<string, string>;
    schutzklassen: Record<string, string>;
    loeschklassen: Record<string, string>;
  };
  tabellen: {
    tabelle: string;
    aggregat: string;
    loeschklasse: string;
    bemerkung: string;
    spalten: InventarSpalte[];
  }[];
}

const CSV_HEADER = [
  'tabelle',
  'aggregat_loeschklasse',
  'spalte',
  'personenbezogen',
  'zweck',
  'rechtsgrundlage',
  'schutzklasse',
  'loeschklasse',
  'bemerkung',
];

function csvCell(value: string): string {
  return /[;"\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
}

/** Lesbarer Export als CSV (Semikolon, wie die Quelldateien des Inventars). */
export function inventarAlsCsv(doc: Dateninventar): string {
  const lines = [CSV_HEADER.join(';')];
  for (const t of doc.tabellen) {
    for (const s of t.spalten) {
      lines.push(
        [
          t.tabelle,
          t.loeschklasse,
          s.spalte,
          s.personenbezogen,
          s.zweck,
          s.rechtsgrundlage,
          s.schutzklasse,
          s.loeschklasse,
          s.bemerkung,
        ]
          .map(csvCell)
          .join(';'),
      );
    }
  }
  return `${lines.join('\n')}\n`;
}

/** Katalogwert mit Klartext: `CODE` und, falls vorhanden, die Beschreibung aus dem Katalog. */
function Eintrag({ code, katalog }: { code: string; katalog: Record<string, string> }) {
  if (!code) return <span aria-label="nicht zutreffend">–</span>;
  const text = katalog[code];
  return (
    <>
      <code>{code}</code>
      {text ? <div>{text}</div> : null}
    </>
  );
}

/**
 * UI-20 Basis: lesbarer Export des Dateninventars für den Datenschutz (WP-M1-32, SEC-004). Nur Lesen; sichtbar nur mit dem
 * Recht `admin.ui20` aus `/v1/me`. Das Inventar ist mandantenunabhängige Schemainformation und wird aus dem Repository
 * erzeugt (`python db/dateninventar.py export --write`), nicht über die API geladen.
 */
export function DateninventarExport({ inventar }: { inventar: Dateninventar }) {
  const session = useSession();
  const [nurPersonen, setNurPersonen] = useState(false);
  const tabellen = useMemo(
    () =>
      inventar.tabellen
        .map((t) => ({
          ...t,
          spalten: nurPersonen ? t.spalten.filter((s) => s.personenbezogen === 'ja') : t.spalten,
        }))
        .filter((t) => t.spalten.length > 0),
    [inventar, nurPersonen],
  );

  if (session.status !== 'ready' || !hasRight(session.me, 'admin.ui20'))
    return <StateView kind="forbidden" />;

  const download = () => {
    const blob = new Blob([inventarAlsCsv(inventar)], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'dateninventar.csv';
    a.click();
    URL.revokeObjectURL(url);
  };

  const k = inventar.kataloge;
  return (
    <section aria-labelledby="inventar-titel">
      <h2 id="inventar-titel">Dateninventar</h2>
      <p>
        {inventar.freigegeben ? (
          <StatusBadge tone="ok">Katalog freigegeben</StatusBadge>
        ) : (
          <StatusBadge tone="warning">Katalog nicht freigegeben (OP-24)</StatusBadge>
        )}
      </p>
      <p>{inventar.hinweis}</p>
      <p>
        <label>
          <input
            type="checkbox"
            checked={nurPersonen}
            onChange={(e) => setNurPersonen(e.target.checked)}
          />{' '}
          Nur personenbezogene Felder
        </label>
      </p>
      <p>
        <button type="button" onClick={download}>
          Als CSV herunterladen
        </button>
      </p>
      {tabellen.map((t) => (
        <ScrollRegion key={t.tabelle} label={`${t.tabelle} (Tabelle)`}>
          <table className="tk-table">
            <caption>
              {t.tabelle} ({t.aggregat}), Löschklasse {t.loeschklasse}
            </caption>
            <thead>
              <tr>
                <th scope="col">Feld</th>
                <th scope="col">Personenbezug</th>
                <th scope="col">Zweck</th>
                <th scope="col">Rechtsgrundlage</th>
                <th scope="col">Schutzklasse</th>
                <th scope="col">Löschklasse</th>
                <th scope="col">Bemerkung</th>
              </tr>
            </thead>
            <tbody>
              {t.spalten.map((s) => (
                <tr key={s.spalte}>
                  <th scope="row">{s.spalte}</th>
                  <td>{s.personenbezogen}</td>
                  <td>
                    <Eintrag code={s.zweck} katalog={k.zwecke} />
                  </td>
                  <td>
                    <Eintrag code={s.rechtsgrundlage} katalog={k.rechtsgrundlagen} />
                  </td>
                  <td>
                    <Eintrag code={s.schutzklasse} katalog={k.schutzklassen} />
                  </td>
                  <td>
                    <Eintrag code={s.loeschklasse} katalog={k.loeschklassen} />
                  </td>
                  <td>{s.bemerkung || '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      ))}
    </section>
  );
}
