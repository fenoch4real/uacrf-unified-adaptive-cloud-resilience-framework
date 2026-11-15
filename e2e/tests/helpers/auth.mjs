import { expect } from '@playwright/test';

export async function signIn(page, username) {
  const passwordVariable =
    username === 'local-admin' ? 'UACRF_E2E_ADMIN_PASSWORD' : 'UACRF_E2E_APPROVER_PASSWORD';
  const password = process.env[passwordVariable];
  if (!password) {
    throw new Error(`Missing disposable test password in ${passwordVariable}.`);
  }

  await page.goto('/contexts');
  await expect(page.locator('#username')).toBeVisible();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(
    page.getByRole('heading', { name: 'Unified Adaptive Cloud Resilience Framework' }),
  ).toBeVisible();
}

export async function rejectInvalidCredentials(page, username) {
  await page.goto('/access');
  await expect(page.locator('#username')).toBeVisible();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill('invalid-e2e-password');
  await page.locator('#kc-login').click();
  await expect(page.locator('#input-error, .kc-feedback-text')).toBeVisible();
}

export async function selectLocalContext(page) {
  await page.goto('/contexts');
  await expect(page.getByRole('heading', { name: 'Select a tenant and environment' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Local Development' })).toBeVisible();
  await page.getByRole('button', { name: 'Use this environment' }).click();
  await expect(page.getByRole('heading', { name: 'Current access' })).toBeVisible();
}

export async function expectCurrentAccess(page, displayName, capabilities) {
  await expect(page.getByText(`Access was evaluated by S01 for ${displayName}.`)).toBeVisible();
  await expect(page.getByText('Local Development', { exact: true })).toBeVisible();
  await expect(page.getByText('Local', { exact: true })).toBeVisible();

  const actualCapabilities = await page
    .getByRole('heading', { name: 'Capabilities' })
    .locator('xpath=following-sibling::ul[1]')
    .getByRole('listitem')
    .allTextContents();
  expect(actualCapabilities.sort()).toEqual([...capabilities].sort());
}

export async function expectPrincipalDirectory(page) {
  const response = await page.goto('/principals');
  expect(response.status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Principal directory' })).toBeVisible();
  const table = page.getByRole('table', {
    name: 'Principals visible in the selected context',
  });
  for (const [id, displayName] of [
    ['local-admin', 'Local Administrator'],
    ['local-approver', 'Local Approver'],
  ]) {
    const row = table.getByRole('row').filter({ has: page.getByRole('rowheader', { name: id }) });
    await expect(row.getByRole('cell', { name: displayName, exact: true })).toBeVisible();
    await expect(row.getByRole('cell', { name: 'HUMAN', exact: true })).toBeVisible();
    await expect(row.getByRole('cell', { name: 'Enabled', exact: true })).toBeVisible();
    await expect(row.getByRole('cell', { name: 'No', exact: true })).toBeVisible();
  }
  await expect(page.getByRole('button', { name: /admit|create|disable/i })).toHaveCount(0);
}
