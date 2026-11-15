import { test } from '@playwright/test';
import { expectCurrentAccess, rejectInvalidCredentials, selectLocalContext, signIn } from './helpers/auth.mjs';

const approverCapabilities = ['identity.directory.read', 'identity.grant.approve'];

test('approver receives only the separately seeded S01 capabilities', async ({ page }) => {
  await signIn(page, 'local-approver');
  await selectLocalContext(page);
  await expectCurrentAccess(page, 'Local Approver', approverCapabilities);

  await page.reload();
  await expectCurrentAccess(page, 'Local Approver', approverCapabilities);
});

test('approver cannot start a session with an invalid password', async ({ page }) => {
  await rejectInvalidCredentials(page, 'local-approver');
});
