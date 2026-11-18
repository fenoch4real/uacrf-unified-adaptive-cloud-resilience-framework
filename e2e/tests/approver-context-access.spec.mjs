import { expect, test } from '@playwright/test';
import {
  expectCurrentAccess,
  expectPrincipalDirectory,
  rejectInvalidCredentials,
  selectLocalContext,
  signIn,
} from './helpers/auth.mjs';

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

test('approver sees a read-only principal directory and cannot admit a principal', async ({ page }) => {
  await signIn(page, 'local-approver');
  await selectLocalContext(page);
  await expectPrincipalDirectory(page);
  await expect(page.getByRole('heading', { name: 'Admit trusted directory principal' }))
    .toHaveCount(0);

  await page.goto('/');
  const csrf = await page.locator('input[name="_csrf"]').inputValue();
  const response = await page.request.post('/principals', {
    form: {
      id: 'principal-forbidden',
      providerId: 'keycloak-local',
      directorySubjectRef: '00000000-0000-4000-8000-000000000003',
      reason: 'Read-only admission denial test.',
      idempotencyKey: '00000000-0000-4000-8000-000000000209',
      _csrf: csrf,
    },
  });
  expect(response.status()).toBe(403);
  expect(await response.text()).toContain('S01 did not confirm principal admission (HTTP 403).');
});
