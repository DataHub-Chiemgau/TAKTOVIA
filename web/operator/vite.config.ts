import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { cspPlugin } from '../csp';

export default defineConfig({
  plugins: [react(), cspPlugin()],
  // Die Navigationstests lesen die Rechtematrix des Servers (backend/…/rechtematrix.csv) als Quelle; sie liegt außerhalb von web/.
  server: { fs: { allow: ['../..'] } },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['../vitest.setup.ts'],
  },
});
