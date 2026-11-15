import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  testMatch: '**/*.spec.mjs',
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  globalSetup: './global-setup.mjs',
  use: {
    baseURL: 'http://localhost:8084',
    headless: true,
    trace: 'retain-on-failure',
  },
});
