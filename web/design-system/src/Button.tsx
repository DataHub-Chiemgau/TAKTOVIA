import type { ButtonHTMLAttributes } from 'react';

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement>;

/** Platzhalter-Schaltflaeche; Inhalt des Designsystems folgt ab M1. */
export function Button({ type = 'button', ...rest }: ButtonProps) {
  return <button type={type} {...rest} />;
}
