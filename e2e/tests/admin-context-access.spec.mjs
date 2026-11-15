import { expect, test } from '@playwright/test';
import {
  expectCurrentAccess,
  expectPrincipalDirectory,
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

test('administrator sees the read-only principal directory', async ({ page }) => {
  await signIn(page, 'local-admin');
  await selectLocalContext(page);
  await expectPrincipalDirectory(page);
});

test('administrator without catalog permission cannot create a journey', async ({ page }) => {
  await signIn(page, 'local-admin');
  await selectLocalContext(page);

  const formResponse = await page.goto('/journeys/new');
  expect(formResponse.status()).toBe(403);
  await expect(page.getByRole('status')).toHaveText(
    'S01 did not authorize owner selection (HTTP 403).',
  );
  await expect(page.getByRole('button', { name: 'Create draft journey' })).toHaveCount(0);

  const csrf = await page.locator('input[name="_csrf"]').inputValue();
  const idempotencyKey = await page.locator('input[name="idempotencyKey"]').inputValue();
  const submission = await page.request.post('/journeys', {
    form: {
      name: 'Unauthorized test journey',
      purpose: 'Verify that missing catalog authority blocks creation.',
      ownerId: 'local-admin',
      idempotencyKey,
      _csrf: csrf,
    },
  });
  expect(submission.status()).toBe(403);
  expect(await submission.text()).toContain('S01 did not authorize owner selection (HTTP 403).');
});
