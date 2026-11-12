import { fireEvent, screen } from '@testing-library/react';

/** Wählt Start und Ziel über die Ortssuche (Textsuche, erster Treffer). */
export async function waehleOrte(start = 'dresden', ziel = 'dresden') {
  fireEvent.change(await screen.findByRole('combobox', { name: 'Start' }), {
    target: { value: start },
  });
  fireEvent.click(await screen.findByRole('option', { name: /Dresden|Berlin/ }));
  fireEvent.change(screen.getByRole('combobox', { name: 'Ziel' }), { target: { value: ziel } });
  fireEvent.click(await screen.findByRole('option', { name: /Dresden|Berlin/ }));
}
