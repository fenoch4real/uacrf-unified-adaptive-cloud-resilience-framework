import { randomUUID } from 'node:crypto';
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

test('administrator opens the principal directory', async ({ page }) => {
  await signIn(page, 'local-admin');
  await selectLocalContext(page);
  await expectPrincipalDirectory(page);
});

test('principal admission stays separate from approval-gated membership creation', async ({ page, browser }) => {
  await signIn(page, 'local-admin');
  await selectLocalContext(page);
  await expectPrincipalDirectory(page);

  await expect(
    page.getByRole('heading', { name: 'Admit trusted directory principal' }),
  ).toBeVisible();
  await page.getByLabel('Trusted identity provider').selectOption('keycloak-local');
  await page
    .getByLabel('Directory subject reference')
    .fill('00000000-0000-4000-8000-000000000003');
  await page.getByLabel('Reason for admission').fill('Automated browser admission test.');
  await page.getByRole('button', { name: 'Admit principal' }).click();

  await expect(page.getByRole('status')).toContainText(
    /Principal [0-9a-f-]{36} was admitted from the trusted directory/,
  );
  await expect(page.getByRole('status')).toContainText('added no memberships or capabilities');
  await expect(page.getByRole('row').filter({ hasText: 'Local Member' })).toHaveCount(0);
  const admissionMessage = await page.getByRole('status').innerText();
  const principalId = admissionMessage.match(/Principal ([0-9a-f-]{36}) was admitted/)[1];

  await page.goto('/memberships');
  await expect(page.getByRole('heading', { name: 'Apply an approved membership' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Record an independent grant decision' }))
    .toHaveCount(0);

  const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  const adminCsrf = await page.locator('input[name="_csrf"]').first().inputValue();
  const unauthorizedDecision = await page.request.post('/memberships/grant-decisions', {
    form: {
      id: `admin-decision-${randomUUID()}`,
      principalId,
      capability: 'catalog.journey.read',
      serviceScope: '*',
      expiresAt,
      reason: 'Administrator must not approve grants.',
      _csrf: adminCsrf,
    },
  });
  expect(unauthorizedDecision.status()).toBe(403);

  const approverContext = await browser.newContext();
  try {
    const approverPage = await approverContext.newPage();
    await signIn(approverPage, 'local-approver');
    await selectLocalContext(approverPage);
    await approverPage.goto('/memberships');
    await expect(
      approverPage.getByRole('heading', { name: 'Record an independent grant decision' }),
    ).toBeVisible();
    await expect(
      approverPage.getByRole('heading', { name: 'Apply an approved membership' }),
    ).toHaveCount(0);

    const approverCsrf = await approverPage.locator('input[name="_csrf"]').first().inputValue();
    const unauthorizedMembership = await approverPage.request.post('/memberships', {
      form: {
        id: randomUUID(),
        idempotencyKey: randomUUID(),
        principalId,
        capability: 'catalog.journey.read',
        serviceScope: '*',
        expiresAt,
        reason: 'Approver must not create memberships.',
        authorizationRef: 'unused-decision',
        _csrf: approverCsrf,
      },
    });
    expect(unauthorizedMembership.status()).toBe(403);

    await approverPage.getByLabel('Target principal ID (required)').fill(principalId);
    await approverPage.getByLabel('Capability (required)').fill('catalog.journey.read');
    await approverPage.getByLabel('Service scope (required)').fill('*');
    await approverPage
      .getByLabel('Membership expiry (UTC, ISO-8601; required)')
      .fill(expiresAt);
    await approverPage
      .getByLabel('Approval reason (required)')
      .fill('Approved read-only journey access.');
    await approverPage.getByRole('button', { name: 'Record approval decision' }).click();
    await expect(approverPage.locator('#grant-decision-reference')).toBeVisible();
    const authorizationRef = await approverPage.locator('#grant-decision-reference').innerText();

    await page.goto('/memberships');
    await page.getByLabel('Target principal ID (required)').fill(principalId);
    await page.getByLabel('Capability (required)').fill('catalog.journey.read');
    await page.getByLabel('Service scope (required)').fill('*');
    await page
      .getByLabel('Approved membership expiry (UTC, ISO-8601; required)')
      .fill(expiresAt);
    await page
      .getByLabel('Membership reason (required)')
      .fill('Approved read-only journey access.');
    await page
      .getByLabel('Authorization reference (required)')
      .fill(authorizationRef);
    await page.getByRole('button', { name: 'Apply approved membership' }).click();

    await expect(page.getByRole('status')).toContainText(
      'S01 created membership',
    );
    await expect(page.getByRole('listitem').filter({ hasText: principalId })).toContainText(
      'catalog.journey.read',
    );
    await approverPage.goto('/principals');
    await expect(approverPage.getByRole('row').filter({ hasText: principalId })).toContainText(
      'Enabled',
    );
    await expect(approverPage.getByText('Disable principal', { exact: true })).toHaveCount(0);
    await approverPage.goto('/memberships');
    const principalCsrf = await approverPage.locator('input[name="_csrf"]').first().inputValue();
    const unauthorizedDisable = await approverPage.request.post(
      `/principals/${principalId}:disable`,
      {
        form: {
          expectedRevision: '1',
          reason: 'Approver must not disable principals.',
          idempotencyKey: randomUUID(),
          _csrf: principalCsrf,
        },
      },
    );
    expect(unauthorizedDisable.status()).toBe(403);

    await page.goto('/principals');
    const principalRow = page.getByRole('row').filter({ hasText: principalId });
    await expect(principalRow).toContainText('Enabled');
    await principalRow.getByText('Disable principal', { exact: true }).click();
    await principalRow
      .getByLabel('Reason for disabling this principal')
      .fill('Complete the principal lifecycle browser test.');
    await principalRow.getByRole('button', { name: 'Disable this principal' }).click();
    await expect(page.getByRole('status')).toContainText(
      'No memberships were removed; re-enabling requires trusted-source verification',
    );
    await expect(page.getByRole('row').filter({ hasText: principalId })).toContainText(
      'Disabled',
    );
    await page.goto('/memberships');
    await expect(page.getByRole('listitem').filter({ hasText: principalId })).toContainText(
      'catalog.journey.read',
    );
  } finally {
    await approverContext.close();
  }
});

test('administrator without catalog permission cannot create journeys or transfer ownership', async ({ page }) => {
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

  const transferPage = await page.goto('/ownership-transfers/JOURNEY/known-journey');
  expect(transferPage.status()).toBe(403);
  await expect(page.getByRole('button', { name: 'Transfer owner' })).toHaveCount(0);
  await expect(page.getByRole('status')).toContainText(
    'S01 or S02 did not authorize reading the target (HTTP 403).',
  );

  await page.goto('/principals');
  const transferCsrf = await page.locator('input[name="_csrf"]').first().inputValue();
  const unauthorizedTransfer = await page.request.post(
    '/ownership-transfers/JOURNEY/known-journey',
    {
      form: {
        ownerId: 'local-approver',
        expectedRevision: '1',
        reason: 'Must not bypass S01 catalog authorization.',
        idempotencyKey: randomUUID(),
        ownerCursor: '',
        _csrf: transferCsrf,
      },
    },
  );
  expect(unauthorizedTransfer.status()).toBe(403);
  expect(await unauthorizedTransfer.text()).toContain(
    'S01 or S02 did not authorize reading the target (HTTP 403).',
  );
});
