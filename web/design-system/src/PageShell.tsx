import type { ReactNode } from 'react';

export interface PageShellProps {
  title: string;
  children?: ReactNode;
}

/** Seitenrahmen mit Landmarken (main) und Hauptueberschrift. */
export function PageShell({ title, children }: PageShellProps) {
  return (
    <main>
      <h1>{title}</h1>
      {children}
    </main>
  );
}
