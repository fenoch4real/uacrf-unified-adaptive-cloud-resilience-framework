import { test } from '@playwright/test';
import { expectCurrentAccess, rejectInvalidCredentials, selectLocalContext, signIn } from './helpers/auth.mjs';

const adminCapabilities = [
  'identity.directory.read',
  'identity.principal.manage',
  'identity.membership.read',
  'identity.membership.manage',
  'identity.owner.select',
  'ownership.transfer',
  'identity.provider.manage',
];

test('administrator selects a context and reloads current S01 access', async ({ page }) => {
  await signIn(page, 'local-admin');
  await selectLocalContext(page);
  await expectCurrentAccess(page, 'Local Administrator', adminCapabilities);

  await page.reload();
  await expectCurrentAccess(page, 'Local Administrator', adminCapabilities);
});

test('administrator cannot start a session with an invalid password', async ({ page }) => {
  await rejectInvalidCredentials(page, 'local-admin');
});
