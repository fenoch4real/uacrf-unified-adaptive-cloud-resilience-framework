import { test, expect } from '@playwright/test';
import {
  expectCurrentAccess,
  rejectInvalidCredentials,
  selectLocalContext,
  signIn,
} from './helpers/auth.mjs';

const adminCapabilities = [
  'identity.directory.read',
  'identity.principal.manage',
  'identity.membership.read',
  'identity.membership.manage',
  'identity.owner.select',
  'ownership.transfer',
  'identity.provider.manage',
];

test('protected access requires authentication and local logout clears the UX01 session', async ({ page }) => {
  await rejectInvalidCredentials(page, 'local-admin');

  await signIn(page, 'local-admin');
  await page.goto('/access');
  await expect(page).toHaveURL(/\/contexts$/);
  await selectLocalContext(page);
  await expectCurrentAccess(page, 'Local Administrator', adminCapabilities);

  await page.getByRole('link', { name: 'Back to home' }).click();
  await page.getByRole('button', { name: 'Sign out of UACRF' }).click();
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign out of UACRF' })).toHaveCount(0);
});

test('context selection and logout reject missing or invalid CSRF tokens', async ({ page }) => {
  await signIn(page, 'local-admin');
  await page.goto('/contexts');

  for (const token of [undefined, 'invalid-csrf-token']) {
    const form = { tenantId: 'local-dev', environmentId: 'local' };
    if (token) form._csrf = token;
    const response = await page.request.post('/contexts', { form });
    expect(response.status()).toBe(403);
  }

  const accessBeforeSelection = await page.request.get('/access', { maxRedirects: 0 });
  expect(accessBeforeSelection.status()).toBe(302);
  expect(accessBeforeSelection.headers().location).toMatch(/\/contexts$/);

  await selectLocalContext(page);
  await expectCurrentAccess(page, 'Local Administrator', adminCapabilities);

  for (const token of [undefined, 'invalid-csrf-token']) {
    const form = token ? { _csrf: token } : {};
    const response = await page.request.post('/logout', { form });
    expect(response.status()).toBe(403);
    await page.goto('/access');
    await expectCurrentAccess(page, 'Local Administrator', adminCapabilities);
  }

  await page.goto('/');
  await page.getByRole('button', { name: 'Sign out of UACRF' }).click();
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
});
