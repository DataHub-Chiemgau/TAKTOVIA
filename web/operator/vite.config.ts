import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { cspPlugin } from '../csp';

export default defineConfig({
  plugins: [react(), cspPlugin()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['../vitest.setup.ts'],
  },
});
