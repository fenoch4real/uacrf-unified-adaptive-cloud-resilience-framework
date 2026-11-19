import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { selectLocalContext, signIn } from './helpers/auth.mjs';

test.use({ actionTimeout: 10_000, navigationTimeout: 30_000 });

test('authorized user imports and replays synthetic inventory through S03', async ({ browser, page }) => {
  test.setTimeout(180_000);
  const username = `sample-${randomUUID()}`;
  const password = randomBytes(24).toString('hex');
  const tokenResponse = await fetch('http://localhost:8180/realms/master/protocol/openid-connect/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password', client_id: 'admin-cli',
      username: process.env.KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME,
      password: process.env.KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD,
    }),
  });
  expect(tokenResponse.status).toBe(200);
  const { access_token: token } = await tokenResponse.json();
  const headers = { authorization: `Bearer ${token}`, 'content-type': 'application/json' };
  const userResponse = await fetch('http://localhost:8180/admin/realms/uacrf/users', {
    method: 'POST', headers,
    body: JSON.stringify({
      username, enabled: true, firstName: 'Sample', lastName: 'Importer',
      email: `${username}@example.test`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    }),
  });
  expect(userResponse.status).toBe(201);
  const lookup = await fetch(
    `http://localhost:8180/admin/realms/uacrf/users?${new URLSearchParams({ username, exact: 'true' })}`,
    { headers },
  );
  expect(lookup.status).toBe(200);
  const users = await lookup.json();
  expect(users).toHaveLength(1);
  const subject = users[0].id;

  await signIn(page, 'local-admin');
  await selectLocalContext(page);
  await page.goto('/principals');
  await page.getByLabel('Trusted identity provider').selectOption('keycloak-local');
  await page.getByLabel('Directory subject reference').fill(subject);
  await page.getByLabel('Reason for admission').fill('Isolated synthetic inventory test.');
  await page.getByRole('button', { name: 'Admit principal' }).click();
  const admission = await page.getByRole('status').innerText();
  const principalId = admission.match(/Principal ([0-9a-f-]{36}) was admitted/)?.[1];
  expect(principalId).toBeTruthy();

  const approverContext = await browser.newContext();
  try {
    const approver = await approverContext.newPage();
    await signIn(approver, 'local-approver');
    await selectLocalContext(approver);
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    for (const capability of [
      'owner.eligible', 'identity.owner.select', 'connections.connection.read',
      'connections.connection.write', 'connections.observation.write',
    ]) {
      await approver.goto('/memberships');
      await approver.getByLabel('Target principal ID (required)').fill(principalId);
      await approver.getByLabel('Capability (required)').fill(capability);
      await approver.getByLabel('Service scope (required)').fill('*');
      await approver.getByLabel('Membership expiry (UTC, ISO-8601; required)').fill(expiresAt);
      await approver.getByLabel('Approval reason (required)').fill('Approve isolated sample test.');
      await approver.getByRole('button', { name: 'Record approval decision' }).click();
      const authorizationRef = await approver.locator('#grant-decision-reference').innerText();

      await page.goto('/memberships');
      await page.getByLabel('Target principal ID (required)').fill(principalId);
      await page.getByLabel('Capability (required)').fill(capability);
      await page.getByLabel('Service scope (required)').fill('*');
      await page.getByLabel('Approved membership expiry (UTC, ISO-8601; required)').fill(expiresAt);
      await page.getByLabel('Membership reason (required)').fill('Approve isolated sample test.');
      await page.getByLabel('Authorization reference (required)').fill(authorizationRef);
      await page.getByRole('button', { name: 'Apply approved membership' }).click();
      await expect(page.getByRole('status')).toContainText('S01 created membership');
    }
  } finally {
    await approverContext.close();
  }

  const sampleContext = await browser.newContext();
  try {
    const sample = await sampleContext.newPage();
    await sample.goto('/contexts');
    await sample.locator('#username').fill(username);
    await sample.locator('#password').fill(password);
    await sample.locator('#kc-login').click();
    await expect(sample.getByRole('heading', { name: 'Unified Adaptive Cloud Resilience Framework' }))
      .toBeVisible();
    await selectLocalContext(sample);
    await sample.goto('/connections');
    await sample.getByRole('link', { name: 'Import synthetic inventory' }).click();
    await sample.getByLabel('Eligible human owner').selectOption(principalId);
    await sample.getByRole('button', { name: 'Import synthetic inventory' }).click();
    await expect(sample.getByRole('status')).toContainText('42 observations confirmed');
    await sample.getByRole('button', { name: 'Import synthetic inventory' }).click();
    await expect(sample.getByRole('status')).toContainText('42 already accepted on a prior attempt');
    await sample.getByRole('link', { name: 'View sample inventory' }).click();
    await expect(sample.getByText('Synthetic sample: these observations are not live provider data or qualified evidence.'))
      .toBeVisible();
    await expect(sample.getByRole('heading', { name: 'Source collection status' })).toBeVisible();
    await expect(sample.getByText('NEVER_RUN', { exact: true }).first()).toBeVisible();
    await expect(sample.getByText('Collection has not run yet.', { exact: true }).first()).toBeVisible();
    await expect(sample.getByText('Not recorded', { exact: true }).first()).toBeVisible();
    await expect(sample.getByText(/timestamps do not indicate freshness or staleness/)).toBeVisible();
    await expect(sample.locator('ul.inventory-list > li')).toHaveCount(25);
    await expect(sample.getByText('AWS', { exact: true }).first()).toBeVisible();
    await expect(sample.getByText('AZURE', { exact: true }).first()).toBeVisible();
    await sample.getByRole('link', { name: 'Next observations' }).click();
    await expect(sample.locator('ul.inventory-list > li')).toHaveCount(17);
    await expect(sample.getByText('GCP', { exact: true }).first()).toBeVisible();
    await expect(sample.getByText('Unknown', { exact: true }).first()).toBeVisible();
  } finally {
    await sampleContext.close();
  }
});
