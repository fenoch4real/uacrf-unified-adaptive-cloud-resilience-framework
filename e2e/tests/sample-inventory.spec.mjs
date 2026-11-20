import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { expectCurrentAccess, selectLocalContext, signIn } from './helpers/auth.mjs';

test.use({ actionTimeout: 10_000, navigationTimeout: 30_000 });

async function fillCaseForm(page, workloadId) {
  await page.getByLabel('Workload ID').fill(workloadId);
  await page.getByLabel('Purpose').fill('Synthetic assessment draft');
  await page.getByLabel('Metric').fill('sample.latency');
  await page.getByLabel('Threshold').fill('10');
  await page.getByLabel('Unit', { exact: true }).fill('ms');
  await page.getByLabel('Window (seconds)').fill('60');
  await page.getByLabel('Required source kinds, one per line').fill('measurement.latency');
  await page.getByLabel('Objective ID').fill('sample-objective');
  await page.getByLabel('Objective revision').fill('1');
  await page.getByLabel('Objective digest').fill('sample');
  await page.getByLabel('Graph ID').fill('sample-graph');
  await page.getByLabel('Graph revision').fill('1');
  await page.getByLabel('Graph digest').fill('sample');
}

async function transferOwner(page, objectKind, objectId, currentOwner, nextOwner) {
  await page.goto(`/ownership-transfers/${objectKind}/${objectId}`);
  await expect(page.locator('dl dd').nth(2)).toHaveText(currentOwner);
  await page.getByLabel('New owner (required)').selectOption(nextOwner);
  await page.getByLabel('Transfer reason (required)').fill('Validate operator-authorized ownership transfer.');
  await page.getByRole('button', { name: 'Transfer owner' }).click();
  await expect(page).toHaveURL(
    new RegExp(`/ownership-transfers/${objectKind}/${objectId}\\?updated=true$`),
  );
  await expect(page.getByRole('status')).toHaveText('Ownership was updated successfully.');
  await expect(page.locator('dl dd').nth(2)).toHaveText(nextOwner);
}

async function verifySystemClientDelegation({
  clientId,
  clientSecret,
  principalId,
  capabilityIds,
  delegatedCapabilityIds,
  audience,
}) {
  expect(clientSecret).toBeTruthy();
  const tokenResponse = await fetch(
    'http://localhost:8180/realms/uacrf/protocol/openid-connect/token',
    {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'client_credentials',
        client_id: clientId,
        client_secret: clientSecret,
      }),
    },
  );
  expect(tokenResponse.status).toBe(200);
  const identityToken = (await tokenResponse.json()).access_token;
  expect(identityToken).toBeTruthy();

  const currentAccessResponse = await fetch(
    'http://localhost:8080/v1/me/access?tenant_id=local-dev&environment_id=local',
    { headers: { authorization: `Bearer ${identityToken}` } },
  );
  expect(currentAccessResponse.status).toBe(200);
  expect(await currentAccessResponse.json()).toMatchObject({
    principal_id: principalId,
    kind: 'SYSTEM',
    capability_ids: capabilityIds,
  });

  const delegationRequest = {
    audience,
    capability_ids: delegatedCapabilityIds,
    tenant_id: 'local-dev',
    environment_id: 'local',
  };
  const delegationResponse = await fetch('http://localhost:8082/internal/v1/delegations', {
    method: 'POST',
    headers: {
      authorization: `Bearer ${identityToken}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify(delegationRequest),
  });
  expect(delegationResponse.status).toBe(200);
  const delegation = await delegationResponse.json();
  expect(delegation.expires_in).toBe(30);
  const claims = JSON.parse(
    Buffer.from(delegation.access_token.split('.')[1], 'base64url').toString('utf8'),
  );
  expect(claims.sub).toBe(principalId);
  expect(claims.aud).toBe(audience);
  expect(claims.capabilities).toEqual(delegationRequest.capability_ids);
  expect(claims.tenant_id).toBe('local-dev');
  expect(claims.environment_id).toBe('local');

  const excessiveDelegationResponse = await fetch(
    'http://localhost:8082/internal/v1/delegations',
    {
      method: 'POST',
      headers: {
        authorization: `Bearer ${identityToken}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        ...delegationRequest,
        capability_ids: ['catalog.service.write'],
      }),
    },
  );
  expect(excessiveDelegationResponse.status).toBe(403);
}

test('authorized user imports and replays synthetic inventory through S03', async ({ browser, page }) => {
  test.setTimeout(180_000);
  const username = `sample-${randomUUID()}`;
  const password = randomBytes(24).toString('hex');
  const transfereeUsername = `transferee-${randomUUID()}`;
  const transfereePassword = randomBytes(24).toString('hex');
  const fixtureServiceId = `fixture-service-${randomUUID()}`;
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
  const evidenceClientId = `evidence-fixture-${randomUUID()}`;
  const evidenceClientSecret = randomBytes(24).toString('hex');
  const evidenceClient = await fetch('http://localhost:8180/admin/realms/uacrf/clients', {
    method: 'POST', headers,
    body: JSON.stringify({
      clientId: evidenceClientId, enabled: true, publicClient: false,
      standardFlowEnabled: false, directAccessGrantsEnabled: false,
      serviceAccountsEnabled: true, fullScopeAllowed: false, secret: evidenceClientSecret,
    }),
  });
  expect(evidenceClient.status).toBe(201);
  const clientUuid = evidenceClient.headers.get('location')?.split('/').pop();
  expect(clientUuid).toBeTruthy();
  const scopesResponse = await fetch('http://localhost:8180/admin/realms/uacrf/client-scopes', { headers });
  expect(scopesResponse.status).toBe(200);
  const contextScope = (await scopesResponse.json()).find((scope) => scope.name === 'uacrf-s01-context');
  expect(contextScope?.id).toBeTruthy();
  const attachScope = await fetch(
    `http://localhost:8180/admin/realms/uacrf/clients/${clientUuid}/default-client-scopes/${contextScope.id}`,
    { method: 'PUT', headers },
  );
  expect(attachScope.status).toBe(204);
  const evidenceAccountLookup = await fetch(
    `http://localhost:8180/admin/realms/uacrf/users?${new URLSearchParams({
      username: `service-account-${evidenceClientId}`, exact: 'true',
    })}`, { headers },
  );
  expect(evidenceAccountLookup.status).toBe(200);
  const evidenceAccounts = await evidenceAccountLookup.json();
  expect(evidenceAccounts).toHaveLength(1);
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

  const transfereeResponse = await fetch('http://localhost:8180/admin/realms/uacrf/users', {
    method: 'POST', headers,
    body: JSON.stringify({
      username: transfereeUsername, enabled: true, firstName: 'Browser', lastName: 'Transferee',
      email: `${transfereeUsername}@example.test`, emailVerified: true,
      credentials: [{ type: 'password', value: transfereePassword, temporary: false }],
    }),
  });
  expect(transfereeResponse.status).toBe(201);
  const transfereeLookup = await fetch(
    `http://localhost:8180/admin/realms/uacrf/users?${new URLSearchParams({
      username: transfereeUsername, exact: 'true',
    })}`,
    { headers },
  );
  expect(transfereeLookup.status).toBe(200);
  const transfereeUsers = await transfereeLookup.json();
  expect(transfereeUsers).toHaveLength(1);

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

  await page.goto('/principals');
  await page.getByLabel('Trusted identity provider').selectOption('keycloak-local');
  await page.getByLabel('Directory subject reference').fill(transfereeUsers[0].id);
  await page.getByLabel('Reason for admission').fill('Isolated ownership-transfer transferee.');
  await page.getByRole('button', { name: 'Admit principal' }).click();
  const transfereeAdmission = await page.getByRole('status').innerText();
  const transfereePrincipalId =
    transfereeAdmission.match(/Principal ([0-9a-f-]{36}) was admitted/)?.[1];
  expect(transfereePrincipalId).toBeTruthy();

  const runtimeLookup = await fetch(
    `http://localhost:8180/admin/realms/uacrf/users?${new URLSearchParams({
      username: 'service-account-uacrf-s03-publisher',
      exact: 'true',
    })}`,
    { headers },
  );
  expect(runtimeLookup.status).toBe(200);
  const runtimeUsers = await runtimeLookup.json();
  expect(runtimeUsers).toHaveLength(1);
  await page.goto('/principals');
  await page.getByLabel('Trusted identity provider').selectOption('keycloak-local');
  await page.getByLabel('Directory subject reference').fill(runtimeUsers[0].id);
  await page.getByLabel('Reason for admission').fill('Isolated agent workload runtime principal.');
  await page.getByRole('button', { name: 'Admit principal' }).click();
  const runtimeAdmission = await page.getByRole('status').innerText();
  const runtimePrincipalId =
    runtimeAdmission.match(/Principal ([0-9a-f-]{36}) was admitted/)?.[1];
  expect(runtimePrincipalId).toBeTruthy();

  const collectorLookup = await fetch(
    `http://localhost:8180/admin/realms/uacrf/users?${new URLSearchParams({
      username: 'service-account-uacrf-s03-collector',
      exact: 'true',
    })}`,
    { headers },
  );
  expect(collectorLookup.status).toBe(200);
  const collectorUsers = await collectorLookup.json();
  expect(collectorUsers).toHaveLength(1);
  await page.goto('/principals');
  await page.getByLabel('Trusted identity provider').selectOption('keycloak-local');
  await page.getByLabel('Directory subject reference').fill(collectorUsers[0].id);
  await page.getByLabel('Reason for admission').fill('Isolated S03 collection runner principal.');
  await page.getByRole('button', { name: 'Admit principal' }).click();
  const collectorAdmission = await page.getByRole('status').innerText();
  const collectorPrincipalId =
    collectorAdmission.match(/Principal ([0-9a-f-]{36}) was admitted/)?.[1];
  expect(collectorPrincipalId).toBeTruthy();

  await page.goto('/principals');
  await page.getByLabel('Trusted identity provider').selectOption('keycloak-local');
  await page.getByLabel('Directory subject reference').fill(evidenceAccounts[0].id);
  await page.getByLabel('Reason for admission').fill('Isolated synthetic evidence fixture writer.');
  await page.getByRole('button', { name: 'Admit principal' }).click();
  const evidenceAdmission = await page.getByRole('status').innerText();
  const evidencePrincipalId = evidenceAdmission.match(/Principal ([0-9a-f-]{36}) was admitted/)?.[1];
  expect(evidencePrincipalId).toBeTruthy();

  const approverContext = await browser.newContext();
  try {
    const approver = await approverContext.newPage();
    await signIn(approver, 'local-approver');
    await selectLocalContext(approver);
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const grants = [
      ...[
      'owner.eligible', 'identity.owner.select', 'connections.connection.read',
      'connections.connection.write', 'connections.observation.write', 'evidence.event.read',
      'objectives.objective.read', 'assessment.case.read', 'assessment.case.write', 'assessment.evaluation.read',
      'assessment.evaluation.run',
      'assessment.recommendation.read',
      'catalog.journey.read', 'catalog.journey.write', 'catalog.service.read',
      'catalog.service.write', 'catalog.agent.read', 'catalog.agent.write',
      ].map((capability) => ({ principalId, capability })),
      { principalId: transfereePrincipalId, capability: 'owner.eligible' },
      { principalId: transfereePrincipalId, capability: 'evidence.event.read', scope: fixtureServiceId },
      { principalId: transfereePrincipalId, capability: 'objectives.objective.read', scope: 'browser-objective-workload' },
      { principalId: runtimePrincipalId, capability: 'runtime.principal.use' },
      { principalId: runtimePrincipalId, capability: 'evidence.inbox.write' },
      { principalId: collectorPrincipalId, capability: 'connections.observation.write' },
      { principalId: evidencePrincipalId, capability: 'evidence.event.write' },
    ];
    for (const { principalId: targetPrincipalId, capability, scope = '*' } of grants) {
      await approver.goto('/memberships');
      await approver.getByLabel('Target principal ID (required)').fill(targetPrincipalId);
      await approver.getByLabel('Capability (required)').fill(capability);
      await approver.getByLabel('Service scope (required)').fill(scope);
      await approver.getByLabel('Membership expiry (UTC, ISO-8601; required)').fill(expiresAt);
      await approver.getByLabel('Approval reason (required)').fill('Approve isolated sample test.');
      await approver.getByRole('button', { name: 'Record approval decision' }).click();
      const authorizationRef = await approver.locator('#grant-decision-reference').innerText();

      await page.goto('/memberships');
      await page.getByLabel('Target principal ID (required)').fill(targetPrincipalId);
      await page.getByLabel('Capability (required)').fill(capability);
      await page.getByLabel('Service scope (required)').fill(scope);
      await page.getByLabel('Approved membership expiry (UTC, ISO-8601; required)').fill(expiresAt);
      await page.getByLabel('Membership reason (required)').fill('Approve isolated sample test.');
      await page.getByLabel('Authorization reference (required)').fill(authorizationRef);
      await page.getByRole('button', { name: 'Apply approved membership' }).click();
      await expect(page.getByRole('status')).toContainText('S01 created membership');
    }
  } finally {
    await approverContext.close();
  }

  await verifySystemClientDelegation({
    clientId: 'uacrf-s03-publisher',
    clientSecret: process.env.KEYCLOAK_S03_PUBLISHER_CLIENT_SECRET,
    principalId: runtimePrincipalId,
    capabilityIds: ['evidence.inbox.write', 'runtime.principal.use'],
    delegatedCapabilityIds: ['evidence.inbox.write'],
    audience: 'S04',
  });
  await verifySystemClientDelegation({
    clientId: 'uacrf-s03-collector',
    clientSecret: process.env.KEYCLOAK_S03_COLLECTION_CLIENT_SECRET,
    principalId: collectorPrincipalId,
    capabilityIds: ['connections.observation.write'],
    delegatedCapabilityIds: ['connections.observation.write'],
    audience: 'S03',
  });

  const fixtureTokenResponse = await fetch(
    'http://localhost:8180/realms/uacrf/protocol/openid-connect/token',
    {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'client_credentials', client_id: evidenceClientId,
        client_secret: evidenceClientSecret,
      }),
    },
  );
  expect(fixtureTokenResponse.status).toBe(200);
  const fixtureIdentityToken = (await fixtureTokenResponse.json()).access_token;
  const fixtureDelegationResponse = await fetch('http://localhost:8082/internal/v1/delegations', {
    method: 'POST',
    headers: { authorization: `Bearer ${fixtureIdentityToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      audience: 'S04', capability_ids: ['evidence.event.write'],
      tenant_id: 'local-dev', environment_id: 'local',
    }),
  });
  expect(fixtureDelegationResponse.status).toBe(200);
  const fixtureDelegation = (await fixtureDelegationResponse.json()).access_token;
  const fixturePayload = '{"value":42}';
  const fixtureId = `browser-evidence-${randomUUID()}`;
  const submission = await fetch('http://localhost:8086/internal/v1/observations', {
    method: 'POST',
    headers: { authorization: `Bearer ${fixtureDelegation}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      id: fixtureId, source_class: 'SAMPLE_LOCAL', source_ref: 'sample:browser-evidence',
      sequence: 1, observed_at: new Date().toISOString(), kind: 'sample.metric',
      service_id: fixtureServiceId,
      payload: fixturePayload, payload_digest: createHash('sha256').update(fixturePayload).digest('hex'),
    }),
  });
  expect(submission.status).toBe(202);
  expect(await submission.json()).toMatchObject({ qualification_state: 'SAMPLE_ONLY' });

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
    await sample.goto('/objectives');
    await expect(sample.getByRole('listitem').filter({ hasText: 'browser-objective-a' }))
      .toContainText('Synthetic browser objective');
    await expect(sample.getByRole('listitem').filter({ hasText: 'browser-objective-b' }))
      .toContainText('DRAFT');
    await sample.getByLabel('Workload ID (optional)').fill('browser-objective-workload');
    await sample.getByRole('button', { name: 'Show objectives' }).click();
    await expect(sample.getByRole('listitem').filter({ hasText: 'browser-objective-a' }))
      .toBeVisible();
    await expect(sample.getByText('browser-objective-b', { exact: true })).toHaveCount(0);
    await sample.goto('/assessment-cases');
    await expect(sample.getByRole('listitem').filter({ hasText: 'browser-case-a' }))
      .toContainText('Synthetic browser assessment');
    await expect(sample.getByRole('listitem').filter({ hasText: 'browser-case-b' }))
      .toContainText('DRAFT');
    await sample.getByRole('link', { name: 'View evaluations for browser-case-a' }).click();
    const evaluation = sample.getByRole('listitem').filter({ hasText: 'browser-evaluation-a' });
    await expect(evaluation).toContainText('INSUFFICIENT_EVIDENCE');
    await expect(evaluation).toContainText('FAILED');
    await expect(evaluation).toContainText('OWNER_UNAVAILABLE');
    await sample.getByRole('link', { name: 'Back to assessment cases' }).click();
    await sample.getByRole('link', { name: 'View evaluations for browser-case-b' }).click();
    await expect(sample.getByText('No evaluations are available for this case.')).toBeVisible();
    await sample.getByRole('link', { name: 'Back to assessment cases' }).click();
    await sample.getByRole('link', { name: 'View recommendations for browser-case-workload' }).click();
    const recommendation = sample.getByRole('listitem').filter({ hasText: 'browser-recommendation-a' });
    await expect(recommendation).toContainText('INVESTIGATE_ONLY');
    await expect(recommendation).toContainText('browser-evaluation-b');
    await expect(sample.getByText('Retained recommendations are investigation-only', { exact: false }))
      .toBeVisible();
    await sample.getByRole('link', { name: 'Back to assessment cases' }).click();
    await sample.getByRole('link', { name: 'View recommendations for other-case-workload' }).click();
    await expect(sample.getByText('No recommendations are available for this workload.')).toBeVisible();
    await sample.getByRole('link', { name: 'Back to assessment cases' }).click();
    await sample.getByLabel('Workload ID (optional)').fill('browser-case-workload');
    await sample.getByRole('button', { name: 'Show cases' }).click();
    await expect(sample.getByRole('listitem').filter({ hasText: 'browser-case-a' })).toBeVisible();
    await expect(sample.getByText('browser-case-b', { exact: true })).toHaveCount(0);
    await sample.getByRole('link', { name: 'Create a draft assessment case' }).click();
    const createdWorkload = `browser-created-${randomUUID()}`;
    await fillCaseForm(sample, createdWorkload);
    const [createRequest] = await Promise.all([
      sample.waitForRequest((request) => request.method() === 'POST'
        && new URL(request.url()).pathname === '/assessment-cases'),
      sample.getByRole('button', { name: 'Create draft case' }).click(),
    ]);
    await expect(sample.getByRole('heading', { name: 'Assessment case receipt' })).toBeVisible();
    const createdId = await sample.locator('main p').filter({ hasText: 'Case ID:' }).locator('strong').innerText();
    await expect(sample.getByText('Current state: DRAFT')).toBeVisible();
    const replay = await sample.request.post('/assessment-cases', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: createRequest.postData(),
    });
    expect(replay.status()).toBe(200);
    expect(await replay.text()).toContain(createdId);
    await sample.getByRole('link', { name: 'View cases for this workload' }).click();
    await expect(sample.getByRole('listitem').filter({ hasText: createdId })).toHaveCount(1);
    await expect(sample.getByRole('listitem').filter({ hasText: createdId })).toContainText('DRAFT');
    await sample.getByRole('link', { name: `Review draft ${createdId} for sealing` }).click();
    await expect(sample.getByText('Sealing makes this case immutable.', { exact: false })).toBeVisible();
    await expect(sample.locator('main')).toContainText('sample-objective');
    await expect(sample.locator('main')).toContainText('sample-graph');
    const [sealRequest] = await Promise.all([
      sample.waitForRequest((request) => request.method() === 'POST'
        && new URL(request.url()).pathname === `/assessment-cases/${createdId}/seal`),
      sample.getByRole('button', { name: 'Seal this draft' }).click(),
    ]);
    await expect(sample.getByRole('heading', { name: 'Sealed case' })).toBeVisible();
    const sealReplay = await sample.request.post(`/assessment-cases/${createdId}/seal`, {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: sealRequest.postData(),
    });
    expect(sealReplay.status()).toBe(409);
    expect(await sealReplay.text()).toContain('Review the current case state before retrying');
    const createdReplay = await sample.request.post('/assessment-cases', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: createRequest.postData(),
    });
    expect(createdReplay.status()).toBe(200);
    expect(await createdReplay.text()).toContain('SEALED');
    await sample.getByRole('link', { name: 'Review current case state' }).click();
    await expect(sample.getByText('This case is already sealed', { exact: false })).toBeVisible();
    await expect(sample.getByRole('button', { name: 'Seal this draft' })).toHaveCount(0);
    await sample.goto(`/assessment-cases/${createdId}/evaluate`);
    await sample.getByLabel('Observation cutoff (required)').fill('2026-01-01T12:00:00Z');
    const [evaluationRequest] = await Promise.all([
      sample.waitForRequest((request) => request.method() === 'POST'
        && new URL(request.url()).pathname === `/assessment-cases/${createdId}/evaluate`),
      sample.getByRole('button', { name: 'Run evaluation' }).click(),
    ]);
    await expect(sample.getByRole('heading', { name: 'Assessment evaluation receipt' })).toBeVisible();
    await expect(sample.locator('main')).toContainText('INSUFFICIENT_EVIDENCE');
    await expect(sample.locator('main')).toContainText('SKIPPED');
    const evaluationId = await sample.locator('dt').filter({ hasText: 'Evaluation ID' })
      .locator('+ dd').innerText();
    const evaluationReplay = await sample.request.post(`/assessment-cases/${createdId}/evaluate`, {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: evaluationRequest.postData(),
    });
    expect(evaluationReplay.status()).toBe(200);
    expect(await evaluationReplay.text()).toContain(evaluationId);
    await sample.getByRole('link', { name: 'View evaluation history' }).click();
    await expect(sample.getByRole('listitem').filter({ hasText: evaluationId })).toHaveCount(1);
    await sample.goto('/evidence');
    const evidenceRow = sample.getByRole('listitem').filter({ hasText: fixtureId });
    await expect(evidenceRow).toContainText('Synthetic sample; never qualified evidence.');
    await expect(evidenceRow).toContainText('SAMPLE_ONLY');
    await expect(evidenceRow).toContainText('sample:browser-evidence');
    await expect(sample.locator('main')).toContainText('S03 connection observations do not automatically');
    await expect(evidenceRow).not.toContainText(fixturePayload);
    await sample.goto('/connections');
    await sample.getByRole('link', { name: 'Import synthetic estate' }).click();
    await sample.getByLabel('Eligible human owner').selectOption(principalId);
    await sample.getByRole('button', { name: 'Import synthetic estate' }).click();
    await expect(sample.getByRole('status')).toContainText('122 observations confirmed');
    await sample.getByRole('button', { name: 'Import synthetic estate' }).click();
    await expect(sample.getByRole('status')).toContainText('122 already accepted on a prior attempt');
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

    await sample.getByRole('link', { name: 'View all retained sample observations' }).click();
    await expect(sample.getByText('Synthetic sample only: these retained payloads are not live provider data or qualified evidence.'))
      .toBeVisible();
    let observationCount = 0;
    const observationKinds = new Set();
    for (let page = 0; page < 10; page++) {
      const observationRows = sample.locator('ul.observation-list > li');
      observationCount += await observationRows.count();
      for (const kind of await observationRows.locator('h3').allTextContents()) {
        observationKinds.add(kind);
      }
      const next = sample.getByRole('link', { name: 'Next observations' });
      if (await next.count() === 0) {
        break;
      }
      await next.click();
    }
    expect(observationCount).toBe(122);
    expect([...observationKinds]).toEqual(expect.arrayContaining([
      'aws.cloudwatch.metrics',
      'azure.monitor.metrics',
      'gcp.monitoring.timeseries',
    ]));

    const journeyName = `Browser journey ${randomUUID()}`;
    await sample.goto('/journeys/new');
    await sample.getByLabel('Name').fill(journeyName);
    await sample.getByLabel('Purpose').fill('Validate authorized owner selection and S02 persistence.');
    await sample.getByLabel('Eligible human owner').selectOption(principalId);
    await sample.getByRole('button', { name: 'Create draft journey' }).click();
    await expect(sample.getByRole('heading', { name: journeyName })).toBeVisible();
    await expect(sample.getByText(principalId, { exact: true })).toBeVisible();
    const journeyId =
      new URL(sample.url()).pathname.match(/\/journeys\/([^/]+)$/)?.[1];
    expect(journeyId).toBeTruthy();

    await sample.getByRole('link', { name: 'Register and link a service' }).click();
    const serviceName = `Browser service ${randomUUID()}`;
    await sample.getByLabel('Name (required)').fill(serviceName);
    await sample.getByLabel('Registration reason (required)').fill('Validate authorized catalog registration.');
    await sample.getByLabel('Eligible human owner (required)').selectOption(principalId);
    await sample.locator('input[name="criticality"][value="3"]').check();
    await sample.getByRole('button', { name: 'Register service' }).click();
    await expect(sample.getByRole('heading', { name: /Link service to/ })).toBeVisible();
    const serviceId = new URL(sample.url()).pathname.match(/\/services\/([^/]+)\/link$/)?.[1];
    expect(serviceId).toBeTruthy();
    await sample.getByRole('radio', { name: 'Required' }).check();
    await sample.getByRole('button', { name: 'Link service' }).click();
    await expect(sample.getByRole('heading', { name: journeyName })).toBeVisible();
    await expect(sample.getByText(serviceId, { exact: true })).toBeVisible();

    await sample.goto('/journeys');
    await expect(sample.getByRole('link', { name: journeyName })).toBeVisible();

    await transferOwner(page, 'JOURNEY', journeyId, principalId, transfereePrincipalId);
    await transferOwner(page, 'SERVICE', serviceId, principalId, transfereePrincipalId);

    await sample.goto('/agents/new');
    await expect(sample.getByText('This agent will be standalone, without a service anchor.'))
      .toBeVisible();
    const standaloneName = `Standalone agent ${randomUUID()}`;
    await sample.getByLabel('Name (required)').fill(standaloneName);
    await sample.locator('input[name="kind"][value="SINGLE_AGENT"]').check();
    await sample.getByLabel('Eligible human owner (required)').selectOption(principalId);
    await sample.getByLabel('Runtime principal ID (required)').fill(runtimePrincipalId);
    await sample.getByLabel('Purpose (required)').fill('Validate authorized standalone workload registration.');
    await sample.getByRole('button', { name: 'Register agent workload' }).click();
    const standaloneReceipt = await sample.getByRole('status').innerText();
    expect(standaloneReceipt).toContain('was created. No runtime action was performed.');
    const standaloneId = standaloneReceipt.match(/Agent workload (\S+) was created\./)?.[1];
    expect(standaloneId).toBeTruthy();

    await sample.goto(`/services/${serviceId}/agents/new`);
    await expect(sample.locator('main')).toContainText(`Service-bound agent for ${serviceId}`);
    const serviceBoundName = `Service-bound agent ${randomUUID()}`;
    await sample.getByLabel('Name (required)').fill(serviceBoundName);
    await sample.locator('input[name="kind"][value="MULTI_AGENT"]').check();
    await sample.getByLabel('Eligible human owner (required)').selectOption(principalId);
    await sample.getByLabel('Runtime principal ID (required)').fill(runtimePrincipalId);
    await sample.getByLabel('Purpose (required)').fill('Validate authorized service-bound workload registration.');
    await sample.getByRole('button', { name: 'Register agent workload' }).click();
    const serviceBoundReceipt = await sample.getByRole('status').innerText();
    expect(serviceBoundReceipt).toContain('was created. No runtime action was performed.');
    const serviceBoundId = serviceBoundReceipt.match(/Agent workload (\S+) was created\./)?.[1];
    expect(serviceBoundId).toBeTruthy();

    await sample.goto('/agents');
    await expect(sample.getByRole('heading', { name: 'Agent workloads' })).toBeVisible();
    const standaloneRecord = sample.getByRole('listitem').filter({ hasText: standaloneId });
    await expect(standaloneRecord).toContainText(standaloneName);
    await expect(standaloneRecord).toContainText('SINGLE_AGENT');
    await expect(standaloneRecord).toContainText(principalId);
    await expect(standaloneRecord).toContainText(runtimePrincipalId);
    await expect(standaloneRecord.locator('dd').nth(2)).toHaveText('Standalone');
    await expect(standaloneRecord.locator('dd').last()).toHaveText('1');
    const serviceBoundRecord = sample.getByRole('listitem').filter({ hasText: serviceBoundId });
    await expect(serviceBoundRecord).toContainText(serviceBoundName);
    await expect(serviceBoundRecord).toContainText('MULTI_AGENT');
    await expect(serviceBoundRecord).toContainText(serviceId);
    await expect(serviceBoundRecord).toContainText(principalId);
    await expect(serviceBoundRecord).toContainText(runtimePrincipalId);
    await expect(serviceBoundRecord.locator('dd').last()).toHaveText('1');

    const restrictedContext = await browser.newContext();
    try {
      const restricted = await restrictedContext.newPage();
      await restricted.goto('/contexts');
      await restricted.locator('#username').fill(transfereeUsername);
      await restricted.locator('#password').fill(transfereePassword);
      await restricted.locator('#kc-login').click();
      await expect(
        restricted.getByRole('heading', { name: 'Unified Adaptive Cloud Resilience Framework' }),
      ).toBeVisible();
      await selectLocalContext(restricted);
      await expectCurrentAccess(restricted, 'Browser Transferee', ['owner.eligible']);

      const globalObjectives = await restricted.goto('/objectives');
      expect(globalObjectives.status()).toBe(403);
      const deniedCases = await restricted.goto('/assessment-cases');
      expect(deniedCases.status()).toBe(403);
      await expect(restricted.getByText('browser-case-a', { exact: true })).toHaveCount(0);
      await restricted.goto('/assessment-cases/new');
      await fillCaseForm(restricted, `denied-${randomUUID()}`);
      const [deniedCreation] = await Promise.all([
        restricted.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/assessment-cases'),
        restricted.getByRole('button', { name: 'Create draft case' }).click(),
      ]);
      expect(deniedCreation.status()).toBe(403);
      await expect(restricted.getByRole('alert')).toContainText('not authorized');
      const csrfToken = await restricted.locator('input[name="_csrf"]').inputValue();
      const deniedSeal = await restricted.request.post('/assessment-cases/browser-case-b/seal', {
        form: { _csrf: csrfToken, expectedRevision: '1', tenantId: 'local-dev', environmentId: 'local' },
      });
      expect(deniedSeal.status()).toBe(403);
      expect(await deniedSeal.text()).toContain('Sealing was not authorized');
      const deniedEvaluation = await restricted.request.post(`/assessment-cases/${createdId}/evaluate`, {
        form: { _csrf: csrfToken, tenantId: 'local-dev', environmentId: 'local',
          idempotencyKey: randomUUID(), observationCutoff: '2026-01-01T12:00:00Z',
          mode: 'SHADOW', measurementsJson: '[]' },
      });
      expect(deniedEvaluation.status()).toBe(403);
      expect(await deniedEvaluation.text()).toContain('Evaluation was not authorized');
      await sample.goto('/assessment-cases/browser-case-b/seal');
      await expect(sample.getByRole('button', { name: 'Seal this draft' })).toBeVisible();
      const deniedEvaluations = await restricted.goto('/assessment-cases/browser-case-a/evaluations');
      expect(deniedEvaluations.status()).toBe(403);
      await expect(restricted.getByText('browser-evaluation-a', { exact: true })).toHaveCount(0);
      const deniedRecommendations = await restricted.goto(
        '/assessment-recommendations?workloadId=browser-case-workload');
      expect(deniedRecommendations.status()).toBe(403);
      await expect(restricted.getByText('browser-recommendation-a', { exact: true })).toHaveCount(0);
      await restricted.goto('/objectives');
      await restricted.getByLabel('Workload ID (optional)').fill('browser-objective-workload');
      const [scopedObjectives] = await Promise.all([
        restricted.waitForResponse((response) =>
          response.url().includes('/objectives?') && response.request().isNavigationRequest()),
        restricted.getByRole('button', { name: 'Show objectives' }).click(),
      ]);
      expect(scopedObjectives.status()).toBe(403);
      await expect(restricted.getByText('browser-objective-a', { exact: true })).toHaveCount(0);
      await expect(restricted.getByText('browser-objective-b', { exact: true })).toHaveCount(0);
      await restricted.getByLabel('Workload ID (optional)').fill('other-objective-workload');
      const [deniedObjectives] = await Promise.all([
        restricted.waitForResponse((response) =>
          response.url().includes('/objectives?') && response.request().isNavigationRequest()),
        restricted.getByRole('button', { name: 'Show objectives' }).click(),
      ]);
      expect(deniedObjectives.status()).toBe(403);
      await expect(restricted.getByText('browser-objective-b', { exact: true })).toHaveCount(0);

      const globalEvidence = await restricted.goto('/evidence');
      expect(globalEvidence.status()).toBe(403);
      await restricted.getByLabel('Service ID (optional)').fill(fixtureServiceId);
      await restricted.getByRole('button', { name: 'Show evidence' }).click();
      await expect(restricted.getByRole('listitem').filter({ hasText: fixtureId }))
        .toContainText('SAMPLE_ONLY');
      await restricted.getByLabel('Service ID (optional)').fill('other-service');
      const [deniedEvidence] = await Promise.all([
        restricted.waitForResponse((response) =>
          response.url().includes('/evidence?') && response.request().isNavigationRequest()),
        restricted.getByRole('button', { name: 'Show evidence' }).click(),
      ]);
      expect(deniedEvidence.status()).toBe(403);
      await expect(restricted.getByText(fixtureId, { exact: true })).toHaveCount(0);

      const deniedResponse = await restricted.goto('/agents');
      expect(deniedResponse.status()).toBe(403);
      await expect(restricted.getByRole('status')).toContainText(
        'S01 did not authorize agent workload access (HTTP 403).',
      );
      await expect(restricted.getByText(standaloneName, { exact: true })).toHaveCount(0);
      await expect(restricted.getByText(serviceBoundName, { exact: true })).toHaveCount(0);
    } finally {
      await restrictedContext.close();
    }
  } finally {
    await sampleContext.close();
  }
});
