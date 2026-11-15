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
