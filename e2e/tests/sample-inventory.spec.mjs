import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
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
  const reviewerUsername = `knowledge-reviewer-${randomUUID()}`;
  const reviewerPassword = randomBytes(24).toString('hex');
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

  const reviewerResponse = await fetch('http://localhost:8180/admin/realms/uacrf/users', {
    method: 'POST', headers,
    body: JSON.stringify({
      username: reviewerUsername, enabled: true, firstName: 'Knowledge', lastName: 'Reviewer',
      email: `${reviewerUsername}@example.test`, emailVerified: true,
      credentials: [{ type: 'password', value: reviewerPassword, temporary: false }],
    }),
  });
  expect(reviewerResponse.status).toBe(201);
  const reviewerLookup = await fetch(
    `http://localhost:8180/admin/realms/uacrf/users?${new URLSearchParams({
      username: reviewerUsername, exact: 'true',
    })}`, { headers },
  );
  expect(reviewerLookup.status).toBe(200);
  const reviewerUsers = await reviewerLookup.json();
  expect(reviewerUsers).toHaveLength(1);

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

  await page.goto('/principals');
  await page.getByLabel('Trusted identity provider').selectOption('keycloak-local');
  await page.getByLabel('Directory subject reference').fill(reviewerUsers[0].id);
  await page.getByLabel('Reason for admission').fill('Isolated independent knowledge reviewer.');
  await page.getByRole('button', { name: 'Admit principal' }).click();
  const reviewerAdmission = await page.getByRole('status').innerText();
  const reviewerPrincipalId = reviewerAdmission.match(/Principal ([0-9a-f-]{36}) was admitted/)?.[1];
  expect(reviewerPrincipalId).toBeTruthy();

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
      'objectives.objective.read', 'objectives.objective.write', 'objectives.objective.review',
      'objectives.objective.activate', 'assessment.case.read', 'assessment.case.write', 'assessment.evaluation.read',
      'assessment.evaluation.run', 'assessment.incident.write',
      'assessment.recommendation.read', 'assessment.incident.read', 'assessment.learning.read', 'assessment.learning.write',
      'assessment.learning.review',
      'knowledge.item.read', 'knowledge.item.write', 'knowledge.item.review', 'knowledge.item.withdraw',
      'knowledge.retrieval.run', 'knowledge.retrieval.read', 'knowledge.classification.internal.read',
      'catalog.journey.read', 'catalog.journey.write', 'catalog.service.read',
      'catalog.service.write', 'catalog.agent.read', 'catalog.agent.write',
      ].map((capability) => ({ principalId, capability })),
      { principalId: transfereePrincipalId, capability: 'owner.eligible' },
      { principalId: reviewerPrincipalId, capability: 'knowledge.item.read' },
      { principalId: reviewerPrincipalId, capability: 'knowledge.item.review' },
      { principalId: reviewerPrincipalId, capability: 'objectives.objective.read' },
      { principalId: reviewerPrincipalId, capability: 'objectives.objective.review' },
      { principalId: transfereePrincipalId, capability: 'evidence.event.read', scope: fixtureServiceId },
      { principalId: transfereePrincipalId, capability: 'objectives.objective.read', scope: 'browser-objective-workload' },
      { principalId: runtimePrincipalId, capability: 'runtime.principal.use' },
      { principalId: runtimePrincipalId, capability: 'evidence.inbox.write' },
      { principalId: collectorPrincipalId, capability: 'connections.observation.write' },
      { principalId: evidencePrincipalId, capability: 'evidence.event.write' },
      { principalId, capability: 'authorization.policy.read', scope: fixtureServiceId },
      { principalId, capability: 'authorization.policy.write', scope: fixtureServiceId },
      { principalId, capability: 'authorization.policy.review', scope: fixtureServiceId },
      { principalId: reviewerPrincipalId, capability: 'authorization.policy.read', scope: fixtureServiceId },
      { principalId: reviewerPrincipalId, capability: 'authorization.policy.review', scope: fixtureServiceId },
      { principalId: evidencePrincipalId, capability: 'authorization.policy.write', scope: fixtureServiceId },
      { principalId: evidencePrincipalId, capability: 'authorization.policy.write', scope: 'browser-signed-policy-service' },
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

  const policyDelegationResponse = await fetch('http://localhost:8082/internal/v1/delegations', {
    method: 'POST',
    headers: { authorization: `Bearer ${fixtureIdentityToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      audience: 'S10', service_id: fixtureServiceId, capability_ids: ['authorization.policy.write'],
      tenant_id: 'local-dev', environment_id: 'local',
    }),
  });
  expect(policyDelegationResponse.status).toBe(200);
  const policyToken = (await policyDelegationResponse.json()).access_token;
  const policyId = `browser-policy-${randomUUID()}`;
  async function proposePolicy(id, expectedRevision) {
    const result = await fetch('http://localhost:8080/internal/decision-governance/v1/authorization-policy-bundles', {
      method: 'POST',
      headers: { authorization: `Bearer ${policyToken}`, 'content-type': 'application/json',
        'Idempotency-Key': randomUUID() },
      body: JSON.stringify({
        id, service_id: fixtureServiceId, expected_revision: expectedRevision,
        expires_at: '2035-01-01T00:00:00Z', effective_from: '2026-01-01T00:00:00Z',
        reason: 'Synthetic browser policy',
        definition: { allowed_action_kinds: ['READ'], allowed_actor_ids: ['sample-actor'],
          allowed_resource_ids: ['sample-resource'], permitted_providers: ['AZURE'],
          permitted_regions: ['sample-region'], required_approver_count: 1,
          eligible_approver_ids: ['sample-reviewer'], maximum_cost_usd: 10 },
      }),
    });
    expect(result.status).toBe(200);
  }
  await proposePolicy(policyId, 0);
  await proposePolicy(policyId, 1);
  for (let i = 0; i < 25; i++) await proposePolicy(`browser-policy-page-${i}-${randomUUID()}`, 0);

  const signedService = 'browser-signed-policy-service';
  const signedPolicyId = `browser-signed-policy-${randomUUID()}`;
  const signedDelegation = await fetch('http://localhost:8082/internal/v1/delegations', {
    method: 'POST',
    headers: { authorization: `Bearer ${fixtureIdentityToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      audience: 'S10', service_id: signedService, capability_ids: ['authorization.policy.write'],
      tenant_id: 'local-dev', environment_id: 'local',
    }),
  });
  expect(signedDelegation.status).toBe(200);
  const signedPolicyResponse = await fetch('http://localhost:8080/internal/decision-governance/v1/authorization-policy-bundles', {
    method: 'POST',
    headers: { authorization: `Bearer ${(await signedDelegation.json()).access_token}`,
      'content-type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: JSON.stringify({
      id: signedPolicyId, service_id: signedService, expected_revision: 0,
      expires_at: '2035-01-01T00:00:00Z', effective_from: '2026-01-01T00:00:00Z',
      reason: 'Synthetic signed policy',
      definition: { allowed_action_kinds: ['READ'], allowed_actor_ids: ['sample-actor'],
        allowed_resource_ids: ['sample-resource'], permitted_providers: ['AZURE'],
        permitted_regions: ['sample-region'], required_approver_count: 1,
        eligible_approver_ids: ['local-approver'], maximum_cost_usd: 10 },
    }),
  });
  expect(signedPolicyResponse.status).toBe(200);
  const signedPolicy = (await signedPolicyResponse.json()).policy;
  const signingContext = await browser.newContext();
  try {
    const signingReviewer = await signingContext.newPage();
    await signIn(signingReviewer, 'local-approver');
    await selectLocalContext(signingReviewer);
    await signingReviewer.goto(`/policies/approve?serviceId=${signedService}&id=${signedPolicyId}&revision=1`);
    await expect(signingReviewer.getByText('Canonical policy material')).toBeVisible();
    const reason = 'Independent synthetic signed approval café';
    const signed = spawnSync(process.execPath, ['../tools/sign_policy_review.mjs',
      '--key', process.env.UACRF_E2E_POLICY_REVIEW_KEY, '--kid', 'e2e-review-key',
      '--tenant', 'local-dev', '--environment', 'local', '--service', signedService,
      '--policy', signedPolicyId, '--revision', '1', '--digest', signedPolicy.digest,
      '--reviewer', 'local-approver'], { input: reason, encoding: 'utf8' });
    expect(signed.status, signed.stderr).toBe(0);
    const assertion = signed.stdout.trim();
    const parts = assertion.split('.');
    expect(parts).toHaveLength(3);
    const signingInput = `${parts[0]}.${parts[1]}`;
    const signature = Buffer.from(parts[2], 'base64url');
    const badSignature = Buffer.from(signature);
    badSignature[0] ^= 1;
    await signingReviewer.getByLabel('Reason for approval').fill(reason);
    await signingReviewer.getByLabel('Externally signed compact JWS')
      .fill(`${signingInput}.${badSignature.toString('base64url')}`);
    const [invalidApproval] = await Promise.all([
      signingReviewer.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/policies/approve'),
      signingReviewer.getByRole('button', { name: 'Submit signed approval' }).click(),
    ]);
    expect(invalidApproval.status()).toBe(422);
    await signingReviewer.goto(`/policies/detail?serviceId=${signedService}&id=${signedPolicyId}&revision=1`);
    await expect(signingReviewer.getByText('No votes recorded.')).toBeVisible();
    await signingReviewer.goto(`/policies/approve?serviceId=${signedService}&id=${signedPolicyId}&revision=1`);
    await signingReviewer.getByLabel('Reason for approval').fill(reason);
    await signingReviewer.getByLabel('Externally signed compact JWS').fill(assertion);
    const [approval] = await Promise.all([
      signingReviewer.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/policies/approve'),
      signingReviewer.getByRole('button', { name: 'Submit signed approval' }).click(),
    ]);
    expect(approval.status()).toBe(200);
    await expect(signingReviewer.getByRole('heading', { name: 'Policy approval receipt' })).toBeVisible();
    await expect(signingReviewer.getByText('APPROVED', { exact: true })).toBeVisible();
    const replayApproval = await signingReviewer.request.post('/policies/approve', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: approval.request().postData(),
    });
    expect(replayApproval.status()).toBe(200);
    const changedApproval = new URLSearchParams(approval.request().postData());
    changedApproval.set('reason', 'Changed signed approval');
    const approvalConflict = await signingReviewer.request.post('/policies/approve', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: changedApproval.toString(),
    });
    expect(approvalConflict.status()).toBe(409);
  } finally {
    await signingContext.close();
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
    const proposedPolicyId = `browser-ui-policy-${randomUUID()}`;
    await sample.goto(`/policies/new?serviceId=${fixtureServiceId}`);
    await sample.getByLabel('Policy ID').fill(proposedPolicyId);
    await sample.getByLabel('Effective from (UTC)').fill('2026-01-01T00:00:00Z');
    await sample.getByLabel('Expires at (UTC)').fill('2035-01-01T00:00:00Z');
    await sample.getByLabel('Allowed action kinds').fill('READ');
    await sample.getByLabel('Allowed actor IDs').fill('sample-actor');
    await sample.getByLabel('Allowed resource IDs').fill('sample-resource');
    await sample.getByLabel('Permitted providers').fill('AZURE');
    await sample.getByLabel('Permitted regions').fill('sample-region');
    await sample.getByLabel('Eligible approver IDs').fill(reviewerPrincipalId);
    await sample.getByLabel('Maximum cost (USD)').fill('10.000001');
    await sample.getByLabel('Proposal reason').fill('Synthetic browser proposal');
    const [policyRequest] = await Promise.all([
      sample.waitForRequest((request) => request.method() === 'POST'
        && new URL(request.url()).pathname === '/policies'),
      sample.getByRole('button', { name: 'Propose policy revision' }).click(),
    ]);
    await expect(sample.getByRole('heading', { name: 'Policy proposal receipt' })).toBeVisible();
    await expect(sample.locator('main')).toContainText(proposedPolicyId);
    await expect(sample.locator('main')).toContainText('DRAFT');
    const replayPolicy = await sample.request.post('/policies', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: policyRequest.postData(),
    });
    expect(replayPolicy.status()).toBe(200);
    const changedPolicyForm = new URLSearchParams(policyRequest.postData());
    changedPolicyForm.set('reason', 'Changed synthetic proposal');
    const changedPolicy = await sample.request.post('/policies', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: changedPolicyForm.toString(),
    });
    expect(changedPolicy.status()).toBe(409);
    await sample.getByRole('link', { name: 'Read this revision' }).click();
    await expect(sample.getByRole('heading', { name: `${proposedPolicyId} - revision 1` })).toBeVisible();
    await sample.getByRole('link', { name: 'Propose a revision based on this revision' }).click();
    await expect(sample.getByLabel('Policy ID')).toHaveValue(proposedPolicyId);
    await expect(sample.getByLabel('Expected current revision')).toHaveValue('1');
    await sample.getByLabel('Effective from (UTC)').fill('2026-01-01T00:00:00Z');
    await sample.getByLabel('Expires at (UTC)').fill('2035-01-01T00:00:00Z');
    await sample.getByLabel('Allowed action kinds').fill('READ');
    await sample.getByLabel('Allowed actor IDs').fill('sample-actor');
    await sample.getByLabel('Allowed resource IDs').fill('sample-resource');
    await sample.getByLabel('Permitted providers').fill('AZURE');
    await sample.getByLabel('Permitted regions').fill('sample-region');
    await sample.getByLabel('Eligible approver IDs').fill(reviewerPrincipalId);
    await sample.getByLabel('Maximum cost (USD)').fill('10');
    await sample.getByLabel('Proposal reason').fill('Synthetic next revision');
    await sample.getByRole('button', { name: 'Propose policy revision' }).click();
    await expect(sample.locator('dt').filter({ hasText: 'Revision' }).locator('+ dd')).toHaveText('2');
    const oldPolicyReplay = await sample.request.post('/policies', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: policyRequest.postData(),
    });
    expect(oldPolicyReplay.status()).toBe(200);
    expect(await oldPolicyReplay.text()).toContain('>1</dd>');
    await sample.goto(`/policies/reject?serviceId=${fixtureServiceId}&id=${proposedPolicyId}&revision=2`);
    await sample.getByLabel('Reason for rejection').fill('Self review must be denied');
    const [selfRejection] = await Promise.all([
      sample.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/policies/reject'),
      sample.getByRole('button', { name: 'Reject this revision' }).click(),
    ]);
    expect(selfRejection.status()).toBe(403);
    await sample.goto(`/policies?serviceId=${fixtureServiceId}`);
    await expect(sample.getByRole('heading', { name: 'Service policy bundles' })).toBeVisible();
    await expect(sample.getByText('governance records, not permits', { exact: false })).toBeVisible();
    await expect(sample.getByRole('link', { name: 'Next policies' })).toBeVisible();
    await sample.getByRole('link', { name: 'Next policies' }).click();
    await sample.getByRole('link', { name: `${policyId} - revision 2` }).click();
    await expect(sample.getByRole('heading', { name: `${policyId} - revision 2` })).toBeVisible();
    await expect(sample.getByRole('navigation', { name: 'Policy revisions' })).toBeVisible();
    await expect(sample.getByText('Synthetic browser policy')).toBeVisible();
    await expect(sample.getByText('No votes recorded.')).toBeVisible();
    await sample.getByRole('link', { name: 'Previous revision (1)' }).click();
    await expect(sample.getByRole('heading', { name: `${policyId} - revision 1` })).toBeVisible();
    await expect(sample.getByRole('link', { name: /Previous revision/ })).toHaveCount(0);
    await sample.getByRole('link', { name: 'Read current revision' }).click();
    await expect(sample.getByRole('heading', { name: `${policyId} - revision 2` })).toBeVisible();
    const deniedOtherPolicy = await sample.goto('/policies?serviceId=other-service');
    expect(deniedOtherPolicy.status()).toBe(403);
    await expect(sample.getByText(policyId, { exact: false })).toHaveCount(0);
    await sample.goto('/knowledge');
    await expect(sample.getByText('Synthetic public knowledge')).toBeVisible();
    await expect(sample.getByText('Synthetic internal knowledge')).toHaveCount(0);
    await expect(sample.getByText('Other tenant synthetic knowledge')).toHaveCount(0);
    await sample.getByLabel('Internal and public').check();
    await sample.getByLabel('Purpose', { exact: true }).fill('sample:review');
    await sample.getByRole('button', { name: 'Show metadata' }).click();
    await expect(sample.getByText('Synthetic public knowledge')).toBeVisible();
    await expect(sample.getByText('Synthetic internal knowledge')).toBeVisible();
    await expect(sample.getByText('Other tenant synthetic knowledge')).toHaveCount(0);
    await sample.getByLabel('Restricted and lower').check();
    const [deniedKnowledge] = await Promise.all([
      sample.waitForResponse((response) => new URL(response.url()).pathname === '/knowledge'
        && response.request().method() === 'GET'),
      sample.getByRole('button', { name: 'Show metadata' }).click(),
    ]);
    expect(deniedKnowledge.status()).toBe(403);
    await expect(sample.getByText('Synthetic public knowledge')).toHaveCount(0);
    const stagedItemId = `synthetic-knowledge-${randomUUID()}`;
    await sample.goto('/knowledge/new');
    await sample.getByLabel('Item ID (required)').fill(stagedItemId);
    await sample.getByLabel('Version (required)').fill('v1');
    await sample.getByLabel('Title (required)').fill('Synthetic staged browser knowledge');
    await sample.getByLabel('Source reference (optional, inert)').fill('sample:browser');
    await sample.getByLabel('Plaintext content (required)').fill('Synthetic browser knowledge content.');
    await sample.getByLabel('Allowed purposes, one per line (required)').fill('sample:review');
    await sample.getByLabel('Review deadline in UTC (required)').fill(new Date(Date.now() + 86400000).toISOString());
    await sample.getByLabel('Reason (required)').fill('Synthetic browser staging test.');
    const [stageResponse] = await Promise.all([
      sample.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/knowledge'),
      sample.getByRole('button', { name: 'Stage candidate' }).click(),
    ]);
    expect(stageResponse.status()).toBe(200);
    await expect(sample.getByRole('status')).toContainText('original receipt');
    await expect(sample.getByText(stagedItemId, { exact: true })).toBeVisible();
    const replayStage = await sample.request.post('/knowledge', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      data: stageResponse.request().postData(),
    });
    expect(replayStage.status()).toBe(200);
    expect(await replayStage.text()).toContain('original receipt');
    await sample.goto('/knowledge');
    await sample.getByLabel('Item ID', { exact: true }).fill(stagedItemId);
    await sample.getByRole('button', { name: 'Show metadata' }).click();
    await expect(sample.getByRole('listitem').filter({ hasText: stagedItemId })).toContainText('CANDIDATE');
    await expect(sample.getByRole('listitem').filter({ hasText: stagedItemId })).toHaveCount(1);
    await sample.getByRole('link', { name: `Review candidate ${stagedItemId} version v1` }).click();
    await expect(sample.getByText('Synthetic browser knowledge content.')).toBeVisible();
    await sample.getByLabel('Reject', { exact: true }).check();
    await sample.getByLabel('Reason (required)').fill('Self review must be rejected.');
    const [knowledgeSelfReview] = await Promise.all([
      sample.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname.endsWith('/review')),
      sample.getByRole('button', { name: 'Record review decision' }).click(),
    ]);
    expect(knowledgeSelfReview.status()).toBe(409);
    await expect(sample.getByRole('button', { name: 'Record review decision' })).toHaveCount(0);
    const reviewerContext = await browser.newContext();
    try {
      const reviewer = await reviewerContext.newPage();
      await reviewer.goto('/contexts');
      await reviewer.locator('#username').fill(reviewerUsername);
      await reviewer.locator('#password').fill(reviewerPassword);
      await reviewer.locator('#kc-login').click();
      await selectLocalContext(reviewer);
      await reviewer.goto(`/policies/reject?serviceId=${fixtureServiceId}&id=${proposedPolicyId}&revision=1`);
      await reviewer.getByLabel('Reason for rejection').fill('Stale revision');
      const [staleRejection] = await Promise.all([
        reviewer.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/policies/reject'),
        reviewer.getByRole('button', { name: 'Reject this revision' }).click(),
      ]);
      expect(staleRejection.status()).toBe(409);
      await reviewer.goto(`/policies/reject?serviceId=${fixtureServiceId}&id=${proposedPolicyId}&revision=2`);
      await expect(reviewer.getByText('Canonical policy material')).toBeVisible();
      await reviewer.getByLabel('Reason for rejection').fill('Independent synthetic rejection');
      const [rejection] = await Promise.all([
        reviewer.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/policies/reject'),
        reviewer.getByRole('button', { name: 'Reject this revision' }).click(),
      ]);
      expect(rejection.status()).toBe(200);
      await expect(reviewer.getByRole('heading', { name: 'Policy rejection receipt' })).toBeVisible();
      const replayRejection = await reviewer.request.post('/policies/reject', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: rejection.request().postData(),
      });
      expect(replayRejection.status()).toBe(200);
      const changedRejection = new URLSearchParams(rejection.request().postData());
      changedRejection.set('reason', 'Changed rejection');
      const rejectionConflict = await reviewer.request.post('/policies/reject', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: changedRejection.toString(),
      });
      expect(rejectionConflict.status()).toBe(409);
      await reviewer.goto('/knowledge');
      await reviewer.getByLabel('Item ID', { exact: true }).fill(stagedItemId);
      await reviewer.getByRole('button', { name: 'Show metadata' }).click();
      await reviewer.getByRole('link', { name: `Review candidate ${stagedItemId} version v1` }).click();
      await expect(reviewer.getByText('Synthetic browser knowledge content.')).toBeVisible();
      await reviewer.getByLabel('Reject', { exact: true }).check();
      await reviewer.getByLabel('Reason (required)').fill('Independent synthetic review.');
      await reviewer.getByRole('button', { name: 'Record review decision' }).click();
      await expect(reviewer.getByRole('heading', { name: 'Knowledge review receipt' })).toBeVisible();
      await expect(reviewer.getByText('REJECTED', { exact: true })).toBeVisible();
      await reviewer.getByRole('link', { name: 'Read current metadata' }).click();
      await expect(reviewer.getByRole('listitem').filter({ hasText: stagedItemId })).toContainText('REJECTED');
      await expect(reviewer.getByRole('link', { name: `Review candidate ${stagedItemId} version v1` })).toHaveCount(0);

      const approvedItemId = `synthetic-approval-${randomUUID()}`;
      await sample.goto('/knowledge/new');
      await sample.getByLabel('Item ID (required)').fill(approvedItemId);
      await sample.getByLabel('Version (required)').fill('v1');
      await sample.getByLabel('Title (required)').fill('Synthetic withdrawal candidate');
      await sample.getByLabel('Plaintext content (required)').fill('Synthetic material for withdrawal.');
      await sample.getByLabel('Allowed purposes, one per line (required)').fill('sample:review');
      await sample.getByLabel('Review deadline in UTC (required)').fill(new Date(Date.now() + 86400000).toISOString());
      await sample.getByLabel('Reason (required)').fill('Synthetic approval and withdrawal test.');
      await sample.getByRole('button', { name: 'Stage candidate' }).click();
      await expect(sample.getByRole('status')).toContainText('original receipt');
      await reviewer.goto('/knowledge');
      await reviewer.getByLabel('Item ID', { exact: true }).fill(approvedItemId);
      await reviewer.getByRole('button', { name: 'Show metadata' }).click();
      await reviewer.getByRole('link', { name: `Review candidate ${approvedItemId} version v1` }).click();
      await reviewer.getByLabel('Approve', { exact: true }).check();
      await reviewer.getByLabel('Reason (required)').fill('Independent synthetic approval.');
      await reviewer.getByRole('button', { name: 'Record review decision' }).click();
      await expect(reviewer.getByText('APPROVED', { exact: true })).toBeVisible();
      await reviewer.goto('/knowledge/retrieve');
      await reviewer.getByLabel('Purpose (required)').fill('sample:review');
      await reviewer.getByLabel('Search terms (required)').fill('withdrawal');
      const [deniedRetrieval] = await Promise.all([
        reviewer.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/knowledge/retrieve'),
        reviewer.getByRole('button', { name: 'Run retrieval' }).click(),
      ]);
      expect(deniedRetrieval.status()).toBe(403);
      await sample.goto('/knowledge/retrieve');
      await sample.getByLabel('Purpose (required)').fill('sample:review');
      await sample.getByLabel('Search terms (required)').fill('withdrawal');
      const [retrievalResponse] = await Promise.all([
        sample.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/knowledge/retrieve'),
        sample.getByRole('button', { name: 'Run retrieval' }).click(),
      ]);
      expect(retrievalResponse.status()).toBe(200);
      await expect(sample.getByText('NOT_VERIFIED', { exact: true })).toBeVisible();
      await expect(sample.getByText('Synthetic material for withdrawal.')).toBeVisible();
      const readRetrievalUrl = await sample.getByRole('link', { name: 'Recheck this retrieval' }).getAttribute('href');
      const deniedRead = await reviewer.goto(readRetrievalUrl);
      expect(deniedRead.status()).toBe(403);
      const deniedHistory = await reviewer.goto('/knowledge/retrieval-history');
      expect(deniedHistory.status()).toBe(403);
      const retrievalId = new URL(readRetrievalUrl, 'http://localhost:8084').pathname.split('/').pop();
      await sample.goto('/knowledge/retrieval-history');
      await expect(sample.getByRole('link', { name: `Retrieval ${retrievalId}` })).toBeVisible();
      await expect(sample.getByText('Synthetic material for withdrawal.')).toHaveCount(0);
      const retryRetrieval = await sample.request.post('/knowledge/retrieve', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        data: retrievalResponse.request().postData(),
      });
      expect(retryRetrieval.status()).toBe(200);
      expect(await retryRetrieval.text()).toContain('Synthetic material for withdrawal.');
      await reviewer.goto(`/knowledge?itemId=${approvedItemId}`);
      await reviewer.getByRole('link', { name: `Withdraw approved ${approvedItemId} version v1` }).click();
      await reviewer.getByLabel('Withdrawal reason (required)').fill('Ungranted withdrawal.');
      const [deniedWithdrawal] = await Promise.all([
        reviewer.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname.endsWith('/withdraw')),
        reviewer.getByRole('button', { name: 'Permanently withdraw this item' }).click(),
      ]);
      expect(deniedWithdrawal.status()).toBe(403);
      await sample.goto(`/knowledge?itemId=${approvedItemId}`);
      await sample.getByRole('link', { name: `Withdraw approved ${approvedItemId} version v1` }).click();
      await expect(sample.getByText('This action cannot be undone.', { exact: false })).toBeVisible();
      await sample.getByLabel('Withdrawal reason (required)').fill('Synthetic item is obsolete.');
      await sample.getByRole('button', { name: 'Permanently withdraw this item' }).click();
      await expect(sample.getByRole('heading', { name: 'Knowledge withdrawal receipt' })).toBeVisible();
      await expect(sample.getByText('WITHDRAWN', { exact: true })).toBeVisible();
      await sample.getByRole('link', { name: 'Read current metadata' }).click();
      await expect(sample.getByRole('listitem').filter({ hasText: approvedItemId })).toContainText('WITHDRAWN');
      await expect(sample.getByRole('link', { name: `Withdraw approved ${approvedItemId} version v1` })).toHaveCount(0);
      const concealedReplay = await sample.request.post('/knowledge/retrieve', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        data: retrievalResponse.request().postData(),
      });
      expect(concealedReplay.status()).toBe(200);
      const concealedHtml = await concealedReplay.text();
      expect(concealedHtml).not.toContain('Synthetic material for withdrawal.');
      expect(concealedHtml).toContain('Excerpt unavailable after current checks.');
      await sample.goto('/knowledge/retrieval-history');
      await sample.getByRole('link', { name: `Retrieval ${retrievalId}` }).click();
      await expect(sample.getByText('Excerpt unavailable after current checks.')).toBeVisible();
      await expect(sample.getByText('Synthetic material for withdrawal.')).toHaveCount(0);
    } finally {
      await reviewerContext.close();
    }
    await sample.goto('/objectives');
    const newObjectiveId = `browser-ui-objective-${randomUUID()}`;
    await sample.getByRole('link', { name: 'Create a local objective draft' }).click();
    await sample.getByLabel('Objective ID').fill(newObjectiveId);
    await sample.getByLabel('Workload ID').fill('browser-objective-workload');
    await sample.getByLabel('Title').fill('Synthetic local objective');
    await sample.getByLabel('Metric').fill('sample.latency');
    await sample.getByLabel('Target').fill('42.123456');
    await sample.getByLabel('Unit').fill('ms');
    await sample.getByLabel('Window in seconds').fill('60');
    const [objectiveRequest] = await Promise.all([
      sample.waitForRequest((request) => request.method() === 'POST'
        && new URL(request.url()).pathname === '/objectives'),
      sample.getByRole('button', { name: 'Create local draft' }).click(),
    ]);
    await expect(sample.getByRole('heading', { name: 'Objective receipt' })).toBeVisible();
    await expect(sample.locator('main')).toContainText(newObjectiveId);
    await expect(sample.locator('main')).toContainText('DRAFT');
    const replayObjective = await sample.request.post('/objectives', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: objectiveRequest.postData(),
    });
    expect(replayObjective.status()).toBe(200);
    const changedObjectiveForm = new URLSearchParams(objectiveRequest.postData());
    changedObjectiveForm.set('target', '43');
    const changedObjective = await sample.request.post('/objectives', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: changedObjectiveForm.toString(),
    });
    expect(changedObjective.status()).toBe(409);
    await sample.getByRole('link', { name: 'Review draft for submission' }).click();
    await expect(sample.getByRole('heading', { name: 'Review objective submission' })).toBeVisible();
    await expect(sample.locator('main')).toContainText('Synthetic local objective');
    const [submissionRequest] = await Promise.all([
      sample.waitForRequest((request) => request.method() === 'POST'
        && new URL(request.url()).pathname === '/objectives/submit'),
      sample.getByRole('button', { name: 'Submit objective for review' }).click(),
    ]);
    await expect(sample.getByRole('heading', { name: 'Objective submission receipt' })).toBeVisible();
    await expect(sample.locator('main')).toContainText('SUBMITTED');
    const replaySubmission = await sample.request.post('/objectives/submit', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: submissionRequest.postData(),
    });
    expect(replaySubmission.status()).toBe(200);
    const changedSubmission = new URLSearchParams(submissionRequest.postData());
    changedSubmission.set('contentDigest', 'a'.repeat(64));
    const conflictSubmission = await sample.request.post('/objectives/submit', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: changedSubmission.toString(),
    });
    expect(conflictSubmission.status()).toBe(409);
    await sample.goto(`/objectives/review?id=${newObjectiveId}`);
    await sample.getByLabel('Approve', { exact: true }).check();
    await sample.getByLabel('Reason (required)').fill('Self review must fail.');
    const [selfObjectiveReview] = await Promise.all([
      sample.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/objectives/review'),
      sample.getByRole('button', { name: 'Record decision' }).click(),
    ]);
    expect(selfObjectiveReview.status()).toBe(409);
    const rejectedObjectiveId = `browser-ui-objective-${randomUUID()}`;
    const secondObjective = new URLSearchParams(objectiveRequest.postData());
    secondObjective.set('id', rejectedObjectiveId);
    secondObjective.set('idempotencyKey', randomUUID());
    expect((await sample.request.post('/objectives', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: secondObjective.toString(),
    })).status()).toBe(200);
    await sample.goto(`/objectives/submit?id=${rejectedObjectiveId}`);
    await sample.getByRole('button', { name: 'Submit objective for review' }).click();
    await expect(sample.getByRole('heading', { name: 'Objective submission receipt' })).toBeVisible();
    const objectiveReviewerContext = await browser.newContext();
    try {
      const reviewer = await objectiveReviewerContext.newPage();
      await reviewer.goto('/contexts');
      await reviewer.locator('#username').fill(reviewerUsername);
      await reviewer.locator('#password').fill(reviewerPassword);
      await reviewer.locator('#kc-login').click();
      await selectLocalContext(reviewer);
      await reviewer.goto(`/objectives/review?id=${newObjectiveId}`);
      await expect(reviewer.locator('main')).toContainText('Synthetic local objective');
      await reviewer.getByLabel('Approve', { exact: true }).check();
      await reviewer.getByLabel('Reason (required)').fill('Independent synthetic approval.');
      const [reviewRequest] = await Promise.all([
        reviewer.waitForRequest((request) => request.method() === 'POST'
          && new URL(request.url()).pathname === '/objectives/review'),
        reviewer.getByRole('button', { name: 'Record decision' }).click(),
      ]);
      await expect(reviewer.getByRole('heading', { name: 'Objective review receipt' })).toBeVisible();
      await expect(reviewer.locator('main')).toContainText('APPROVED');
      expect((await reviewer.request.post('/objectives/review', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: reviewRequest.postData(),
      })).status()).toBe(200);
      const changedReview = new URLSearchParams(reviewRequest.postData());
      changedReview.set('reason', 'Different reason');
      expect((await reviewer.request.post('/objectives/review', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: changedReview.toString(),
      })).status()).toBe(409);
      await reviewer.goto(`/objectives/review?id=${rejectedObjectiveId}`);
      await reviewer.getByLabel('Reject', { exact: true }).check();
      await reviewer.getByLabel('Reason (required)').fill('Independent synthetic rejection.');
      await reviewer.getByRole('button', { name: 'Record decision' }).click();
      await expect(reviewer.getByRole('heading', { name: 'Objective review receipt' })).toBeVisible();
      await expect(reviewer.locator('main')).toContainText('REJECTED');
    } finally {
      await objectiveReviewerContext.close();
    }
    await sample.goto(`/objectives/history?id=${newObjectiveId}`);
    await expect(sample.getByRole('heading', { name: 'Recorded objective decision events' })).toBeVisible();
    await expect(sample.locator('main')).toContainText('APPROVED');
    await expect(sample.locator('main')).toContainText('not a complete audit history');
    await sample.getByRole('link', { name: 'Read retained decision records with reasons' }).click();
    await expect(sample.getByRole('heading', { name: 'Retained objective decisions' })).toBeVisible();
    await expect(sample.locator('main')).toContainText('Independent synthetic approval.');
    await expect(sample.locator('main')).toContainText('Earlier decisions may be missing');
    await sample.goto(`/objectives/activate?id=${newObjectiveId}`);
    await expect(sample.getByRole('heading', { name: 'Objective activation and current state' })).toBeVisible();
    const [activationRequest] = await Promise.all([
      sample.waitForRequest((request) => request.method() === 'POST'
        && new URL(request.url()).pathname === '/objectives/activate'),
      sample.getByRole('button', { name: 'Activate objective' }).click(),
    ]);
    await expect(sample.getByRole('heading', { name: 'Recorded activation result' })).toBeVisible();
    await expect(sample.locator('main')).toContainText('ACTIVE');
    expect((await sample.request.post('/objectives/activate', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: activationRequest.postData(),
    })).status()).toBe(200);
    const changedActivation = new URLSearchParams(activationRequest.postData());
    changedActivation.set('contentDigest', 'a'.repeat(64));
    expect((await sample.request.post('/objectives/activate', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: changedActivation.toString(),
    })).status()).toBe(409);
    await sample.getByRole('link', { name: 'Read current objective state' }).click();
    await expect(sample.locator('main')).toContainText('ACTIVE');
    await expect(sample.getByRole('button', { name: 'Activate objective' })).toHaveCount(0);
    await sample.goto('/objectives/decision-records?id=browser-objective-a');
    await expect(sample.getByRole('listitem')).toHaveCount(25);
    await expect(sample.locator('main')).toContainText('Synthetic pagination reason 27');
    await expect(sample.getByText('Synthetic pagination reason 2', { exact: true })).toHaveCount(0);
    await sample.getByRole('link', { name: 'Older decision records' }).click();
    await expect(sample.getByRole('listitem')).toHaveCount(1);
    await expect(sample.locator('main')).toContainText('Synthetic pagination reason 2');
    await expect(sample.getByRole('link', { name: 'Older decision records' })).toHaveCount(0);
    await sample.getByRole('link', { name: 'First decision records page' }).click();
    await expect(sample.getByRole('listitem')).toHaveCount(25);
    await sample.goto('/objectives/import');
    await expect(sample.locator('main')).toContainText('No worker currently resolves the connection');
    await sample.getByLabel('Connection ID (required)', { exact: true }).fill('browser-objective-source');
    await sample.getByLabel('External objective ID (required)').fill('sample:checkout-recovery');
    await sample.getByLabel('Target workload ID (required)').fill('browser-objective-workload');
    await sample.getByLabel('Mapping revision (required, at least 1)').fill('1');
    await sample.getByLabel('Expected local revision (required, 0 if none)').fill('0');
    const [importRequest] = await Promise.all([
      sample.waitForRequest((request) => request.method() === 'POST'
        && new URL(request.url()).pathname === '/objectives/import'),
      sample.getByRole('button', { name: 'Record pending preview request' }).click(),
    ]);
    await expect(sample.getByRole('heading', { name: 'Objective import request receipt' })).toBeVisible();
    await expect(sample.locator('main')).toContainText('PENDING');
    await expect(sample.locator('main')).toContainText('no source has been fetched or verified');
    expect((await sample.request.post('/objectives/import', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: importRequest.postData(),
    })).status()).toBe(200);
    const changedImport = new URLSearchParams(importRequest.postData());
    changedImport.set('externalObjectiveId', 'changed-synthetic-definition');
    expect((await sample.request.post('/objectives/import', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: changedImport.toString(),
    })).status()).toBe(409);
    const importReceiptUrl = await sample.getByRole('link', { name: 'Read current request status' }).getAttribute('href');
    await sample.getByRole('link', { name: 'Read current request status' }).click();
    await expect(sample.locator('main')).toContainText('PENDING');
    const savedReceiptId = new URL(importReceiptUrl, sample.url()).searchParams.get('id');
    let captureRequest;
    let captureReadUrl;
    let captureListUrl;
    let rehearsalUrl;
    let mappingRequest;
    let mappingReadUrl;
    let proposalListUrl;
    const [inspectionResponse] = await Promise.all([
      sample.waitForResponse((response) =>
        new URL(response.url()).pathname === '/objectives/import/receipt/sample-source'),
      sample.getByRole('link', { name: "Inspect this request's fictional sample source" }).click(),
    ]);
    if (process.env.UACRF_E2E_SAMPLE_INSPECTION === '1') {
      expect(inspectionResponse.status()).toBe(200);
      await expect(sample.locator('main')).toContainText('SYNTHETIC_UNVERIFIED');
      await expect(sample.locator('main')).toContainText('recovery.time');
      await expect(sample.locator('main')).toContainText('720');
      await expect(sample.locator('main')).toContainText('browser-objective-binding');
      await expect(sample.locator('main')).toContainText('OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1');
      await expect(sample.locator('main'))
        .toContainText('0615af06050a1ebe47c53362ebd790c68e0abd5ed9749b01605a3b006a9b7e1d');
      await expect(sample.locator('main')).toContainText('may differ from this inspection');
      const [captureResponse] = await Promise.all([
        sample.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/objectives/import/receipt/sample-capture'),
        sample.getByRole('button', { name: 'Capture current fictional source' }).click(),
      ]);
      expect(captureResponse.status()).toBe(200);
      captureRequest = captureResponse.request();
      await expect(sample.locator('main')).toContainText('SYNTHETIC_UNVERIFIED');
      const captureId = await sample.locator('dt').filter({ hasText: 'Capture ID' }).locator('+ dd').textContent();
      expect(captureId).toMatch(/^[0-9a-f-]{36}$/);
      const captureRetry = await sample.request.post('/objectives/import/receipt/sample-capture', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: captureRequest.postData(),
      });
      expect(captureRetry.status()).toBe(200);
      expect(await captureRetry.text()).toContain(captureId);
      for (let index = 0; index < 25; index++) {
        const additionalCapture = new URLSearchParams(captureRequest.postData());
        additionalCapture.set('idempotencyKey', randomUUID());
        const createdCapture = await sample.request.post('/objectives/import/receipt/sample-capture', {
          headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: additionalCapture.toString(),
        });
        expect(createdCapture.status()).toBe(200);
      }
      captureReadUrl = await sample.getByRole('link', { name: 'Read this retained capture' }).getAttribute('href');
      const [captureRead] = await Promise.all([
        sample.waitForResponse((response) => response.request().method() === 'GET'
          && new URL(response.url()).pathname === '/objectives/import/receipt/sample-capture'),
        sample.getByRole('link', { name: 'Read this retained capture' }).click(),
      ]);
      expect(captureRead.status()).toBe(200);
      expect(captureRead.headers()['cache-control']).toContain('no-store');
      await expect(sample.locator('main')).toContainText('SYNTHETIC_UNVERIFIED');
      await expect(sample.locator('main')).toContainText('not a fresh source read');
      await expect(sample.locator('pre code')).toContainText('"target":720');
      await expect(sample.locator('main'))
        .toContainText('0615af06050a1ebe47c53362ebd790c68e0abd5ed9749b01605a3b006a9b7e1d');
      await sample.getByLabel('Target unit (required)').fill('minutes');
      const [rehearsal] = await Promise.all([
        sample.waitForResponse((response) =>
          new URL(response.url()).pathname === '/objectives/import/receipt/normalization-rehearsal'),
        sample.getByRole('button', { name: 'Rehearse conversion' }).click(),
      ]);
      expect(rehearsal.status()).toBe(200);
      rehearsalUrl = sample.url();
      await expect(sample.locator('main')).toContainText('UNAPPROVED_REHEARSAL');
      await expect(sample.locator('main')).toContainText('720 seconds');
      await expect(sample.locator('main')).toContainText('12 minutes');
      await sample.getByLabel('Reason for proposal (required)').fill('Review the fictional conversion');
      const [mappingResponse] = await Promise.all([
        sample.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/objectives/import/receipt/synthetic-mapping'),
        sample.getByRole('button', { name: 'Record unapproved proposal' }).click(),
      ]);
      expect(mappingResponse.status()).toBe(200);
      mappingRequest = mappingResponse.request();
      await expect(sample.locator('main')).toContainText('UNAPPROVED');
      await expect(sample.locator('main')).toContainText('SYNTHETIC_UNVERIFIED');
      const proposalId = await sample.locator('dt').filter({ hasText: 'Proposal ID' }).locator('+ dd').textContent();
      const mappingRetry = await sample.request.post('/objectives/import/receipt/synthetic-mapping', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: mappingRequest.postData(),
      });
      expect(mappingRetry.status()).toBe(200);
      expect(await mappingRetry.text()).toContain(proposalId);
      for (let index = 0; index < 25; index++) {
        const additionalMapping = new URLSearchParams(mappingRequest.postData());
        additionalMapping.set('idempotencyKey', randomUUID());
        const createdMapping = await sample.request.post('/objectives/import/receipt/synthetic-mapping', {
          headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: additionalMapping.toString(),
        });
        expect(createdMapping.status()).toBe(200);
      }
      mappingReadUrl = await sample.getByRole('link', { name: 'Read this retained proposal' }).getAttribute('href');
      await sample.getByRole('link', { name: 'Read this retained proposal' }).click();
      await expect(sample.locator('main')).toContainText('Review the fictional conversion');
      const reviewUrl = await sample.getByRole('link', { name: 'Review this fictional proposal' }).getAttribute('href');
      await sample.getByRole('link', { name: 'Review this fictional proposal' }).click();
      await sample.getByLabel('Approve this synthetic proposal').check();
      await sample.getByLabel('Reason for review (required)').fill('Self review must be rejected');
      const [selfReview] = await Promise.all([
        sample.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/objectives/import/receipt/synthetic-mapping-review'),
        sample.getByRole('button', { name: 'Record synthetic decision' }).click(),
      ]);
      expect(selfReview.status()).toBe(409);
      const syntheticReviewerContext = await browser.newContext();
      try {
        const reviewer = await syntheticReviewerContext.newPage();
        await reviewer.goto('/contexts');
        await reviewer.locator('#username').fill(reviewerUsername);
        await reviewer.locator('#password').fill(reviewerPassword);
        await reviewer.locator('#kc-login').click();
        await selectLocalContext(reviewer);
        await reviewer.goto(new URL(reviewUrl, sample.url()).toString());
        await expect(reviewer.locator('main')).toContainText('SYNTHETIC_MAPPING_PROPOSAL_SHA256_V1');
        await reviewer.getByLabel('Approve this synthetic proposal').check();
        await reviewer.getByLabel('Reason for review (required)').fill('Independent fictional mapping review');
        const [reviewResponse] = await Promise.all([
          reviewer.waitForResponse((response) => response.request().method() === 'POST'
            && new URL(response.url()).pathname === '/objectives/import/receipt/synthetic-mapping-review'),
          reviewer.getByRole('button', { name: 'Record synthetic decision' }).click(),
        ]);
        expect(reviewResponse.status()).toBe(200);
        await expect(reviewer.locator('main')).toContainText('Independent fictional mapping review');
        await expect(reviewer.locator('main')).toContainText('SYNTHETIC_UNVERIFIED');
        expect((await reviewer.request.post('/objectives/import/receipt/synthetic-mapping-review', {
          headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: reviewResponse.request().postData(),
        })).status()).toBe(200);
        await reviewer.goto(new URL(reviewUrl, sample.url()).toString());
        await expect(reviewer.getByRole('heading', { name: 'Retained synthetic decision' })).toBeVisible();
        await expect(reviewer.getByRole('button', { name: 'Record synthetic decision' })).toHaveCount(0);
      } finally {
        await syntheticReviewerContext.close();
      }
      await sample.goto(new URL(mappingReadUrl, sample.url()).toString());
      await sample.getByRole('link', { name: 'Back to this capture' }).click();
      const [proposalList] = await Promise.all([
        sample.waitForResponse((response) =>
          new URL(response.url()).pathname === '/objectives/import/receipt/synthetic-mappings'),
        sample.getByRole('link', { name: 'View fictional mapping proposals' }).click(),
      ]);
      expect(proposalList.status()).toBe(200);
      expect(proposalList.headers()['cache-control']).toContain('no-store');
      proposalListUrl = sample.url();
      await expect(sample.locator('main')).toContainText('not a snapshot');
      await expect(sample.locator('main li')).toHaveCount(25);
      const firstPageIds = await sample.locator('main li a').allTextContents();
      await sample.getByRole('link', { name: 'More proposals' }).click();
      await expect(sample.locator('main li')).toHaveCount(1);
      const secondPageId = await sample.locator('main li a').textContent();
      expect(firstPageIds).not.toContain(secondPageId);
      expect([...firstPageIds, secondPageId]).toContain(proposalId);
      await sample.getByRole('link', { name: 'First proposals page' }).click();
      await expect(sample.locator('main li')).toHaveCount(25);
      await sample.getByRole('link', { name: 'Back to this capture' }).click();
      await sample.getByLabel('Target unit (required)').fill('unsupported');
      const [invalidRehearsal] = await Promise.all([
        sample.waitForResponse((response) =>
          new URL(response.url()).pathname === '/objectives/import/receipt/normalization-rehearsal'),
        sample.getByRole('button', { name: 'Rehearse conversion' }).click(),
      ]);
      expect(invalidRehearsal.status()).toBe(400);
      await expect(sample.getByRole('alert')).toContainText('HTTP 400');
      await sample.getByRole('link', { name: 'Back to this capture' }).click();
    } else {
      expect(inspectionResponse.status()).toBe(503);
      await expect(sample.locator('main')).toContainText('Pending sample inspection failed (HTTP 503)');
    }
    if (!captureReadUrl) await expect(sample.locator('main')).toContainText('The request remains pending.');
    await sample.getByRole('link', { name: 'Back to this request' }).click();
    await expect(sample.locator('main')).toContainText('PENDING');
    if (captureReadUrl) {
      captureListUrl = await sample.getByRole('link', { name: 'View retained fictional captures' }).getAttribute('href');
      await sample.getByRole('link', { name: 'View retained fictional captures' }).click();
      await expect(sample.locator('main')).toContainText('not a snapshot');
      await expect(sample.locator('main li')).toHaveCount(25);
      await sample.getByRole('link', { name: 'More captures' }).click();
      await expect(sample.locator('main li')).toHaveCount(1);
      await sample.getByRole('link', { name: 'First captures page' }).click();
      await expect(sample.locator('main li')).toHaveCount(25);
      await sample.getByRole('link', { name: 'Back to this request' }).click();
      await expect(sample.locator('main')).toContainText('PENDING');
    }
    await sample.getByRole('link', { name: 'Request or find an import' }).click();
    await sample.getByLabel('Sample connection ID (required)').fill('browser-objective-source');
    await sample.getByLabel('Bound workload ID (required)').fill('browser-objective-workload');
    await sample.getByRole('button', { name: 'Inspect synthetic source' }).click();
    await expect(sample.locator('main')).toContainText('SYNTHETIC_UNVERIFIED');
    await expect(sample.locator('main')).toContainText('"target":720');
    await expect(sample.locator('main')).toContainText('No import or proposal was created');
    await sample.getByRole('link', { name: 'Back to import requests' }).click();
    await sample.getByLabel('Receipt ID (required)').fill(savedReceiptId);
    await sample.getByRole('button', { name: 'Find request' }).click();
    await expect(sample.locator('main')).toContainText(savedReceiptId);
    await expect(sample.locator('main')).toContainText('PENDING');
    for (let n = 0; n < 25; n++) {
      const request = new URLSearchParams(importRequest.postData());
      request.set('idempotencyKey', randomUUID());
      request.set('externalObjectiveId', `synthetic-list-${n}`);
      expect((await sample.request.post('/objectives/import', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: request.toString(),
      })).status()).toBe(200);
    }
    await sample.getByRole('link', { name: 'Request or find an import' }).click();
    await sample.getByRole('link', { name: 'Browse pending requests' }).click();
    await expect(sample.getByRole('listitem')).toHaveCount(25);
    const firstPageIds = await sample.locator('main li a').allTextContents();
    await sample.getByRole('link', { name: 'More requests' }).click();
    await expect(sample.getByRole('listitem')).toHaveCount(1);
    const lastPageIds = await sample.locator('main li a').allTextContents();
    expect(new Set([...firstPageIds, ...lastPageIds]).size).toBe(26);
    expect([...firstPageIds, ...lastPageIds]).toContain(savedReceiptId);
    await sample.getByRole('link', { name: 'First requests page' }).click();
    await expect(sample.getByRole('listitem')).toHaveCount(25);
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
    await sample.getByRole('link', { name: 'View incidents for browser-case-a' }).click();
    await expect(sample.getByRole('listitem').filter({ hasText: 'Synthetic browser incident 1' }).first()).toBeVisible();
    await sample.getByRole('link', {
      name: 'View learning records for 00000000-0000-0000-0000-000000000001',
    }).click();
    await expect(sample.getByText('Synthetic hypothesis').first()).toBeVisible();
    await expect(sample.getByText('Investigate sample').first()).toBeVisible();
    await expect(sample.getByText('not evidence of adoption', { exact: false })).toBeVisible();
    await sample.getByRole('link', { name: 'Next learning records' }).click();
    await expect(sample.getByRole('heading', { name: '10000000-0000-0000-0000-000000000051' })).toBeVisible();
    await expect(sample.getByRole('link', { name: 'Next learning records' })).toHaveCount(0);
    await sample.getByRole('link', { name: 'Prepare a learning proposal' }).click();
    await expect(sample.getByText('Creation does not revalidate evidence.', { exact: false })).toBeVisible();
    await sample.getByLabel('Hypotheses, one per line (required)').fill('Synthetic proposal hypothesis');
    await sample.getByLabel('Proposed action items JSON (required)').fill(JSON.stringify([{
      description: 'Investigate synthetic data', owner_id: 'sample-owner',
      due_at: '2026-12-01T12:00:00Z', closure_test: 'Document findings',
    }]));
    await sample.getByLabel('Reason (required)').fill('Exercise proposal workflow');
    const [proposalRequest] = await Promise.all([
      sample.waitForRequest((request) => request.method() === 'POST'
        && new URL(request.url()).pathname === '/assessment-incidents/00000000-0000-0000-0000-000000000001/learning-records'),
      sample.getByRole('button', { name: 'Submit learning proposal' }).click(),
    ]);
    await expect(sample.getByRole('heading', { name: 'Learning proposal receipt' })).toBeVisible();
    const proposalId = await sample.locator('dt').filter({ hasText: 'Proposal ID' }).locator('+ dd').innerText();
    const proposalReplay = await sample.request.post('/assessment-incidents/00000000-0000-0000-0000-000000000001/learning-records', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: proposalRequest.postData(),
    });
    expect(proposalReplay.status()).toBe(200);
    expect(await proposalReplay.text()).toContain(proposalId);
    await sample.getByRole('link', { name: 'View learning records' }).click();
    let matchingProposals = await sample.getByRole('heading', { name: proposalId }).count();
    if (await sample.getByRole('link', { name: 'Next learning records' }).count()) {
      await sample.getByRole('link', { name: 'Next learning records' }).click();
      matchingProposals += await sample.getByRole('heading', { name: proposalId }).count();
    }
    expect(matchingProposals).toBe(1);
    await sample.goto(`/assessment-learning-records/${proposalId}/review`);
    await sample.getByLabel('Decision (required)').selectOption('REJECT');
    await sample.getByLabel('Reason (required)').fill('Self review must be denied');
    const [selfReview] = await Promise.all([
      sample.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/assessment-learning-records/${proposalId}/review`),
      sample.getByRole('button', { name: 'Record review decision' }).click(),
    ]);
    expect(selfReview.status()).toBe(409);
    await expect(sample.getByRole('alert')).toContainText('Read the retained decision');
    const fixtureLearningId = '10000000-0000-0000-0000-000000000001';
    await sample.goto(`/assessment-learning-records/${fixtureLearningId}/review`);
    await sample.getByLabel('Decision (required)').selectOption('APPROVE');
    await sample.getByLabel('Reason (required)').fill('Synthetic evidence must not be approved');
    const [approval] = await Promise.all([
      sample.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/assessment-learning-records/${fixtureLearningId}/review`),
      sample.getByRole('button', { name: 'Record review decision' }).click(),
    ]);
    expect(approval.status()).toBe(409);
    await sample.goto(`/assessment-learning-records/${fixtureLearningId}/review`);
    await sample.getByLabel('Decision (required)').selectOption('REJECT');
    await sample.getByLabel('Reason (required)').fill('Synthetic proposal is not qualified');
    const [reviewRequest] = await Promise.all([
      sample.waitForRequest((request) => request.method() === 'POST'
        && new URL(request.url()).pathname === `/assessment-learning-records/${fixtureLearningId}/review`),
      sample.getByRole('button', { name: 'Record review decision' }).click(),
    ]);
    await expect(sample.getByRole('heading', { name: 'Learning review decision' })).toBeVisible();
    await expect(sample.locator('main')).toContainText('REJECT');
    const reviewReplay = await sample.request.post(`/assessment-learning-records/${fixtureLearningId}/review`, {
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: reviewRequest.postData(),
    });
    expect(reviewReplay.status()).toBe(409);
    await sample.goto(`/assessment-learning-records/${fixtureLearningId}/review/decision`);
    await expect(sample.locator('main')).toContainText('Synthetic proposal is not qualified');
    await sample.goto('/assessment-cases/browser-case-a/incidents');
    await sample.getByRole('link', { name: 'Next incidents' }).click();
    await expect(sample.getByText('Synthetic browser incident 51', { exact: true })).toBeVisible();
    await expect(sample.getByRole('link', { name: 'Next incidents' })).toHaveCount(0);
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
    await sample.goto(`/assessment-cases/${createdId}/incidents/new`);
    await expect(sample.getByText('SKIPPED or synthetic evidence', { exact: false })).toBeVisible();
    await sample.getByLabel('Observed from (required)').fill('2026-01-01T12:00:00Z');
    await sample.getByLabel('Description (required)').fill('Synthetic incident must not be accepted');
    await sample.getByLabel('Evidence IDs, one per line (required)').fill('sample-receipt');
    await sample.getByLabel('Reason (required)').fill('Verify evidence gate');
    const incidentKey = await sample.locator('input[name="idempotencyKey"]').inputValue();
    const [incidentResponse] = await Promise.all([
      sample.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/assessment-cases/${createdId}/incidents`),
      sample.getByRole('button', { name: 'Create incident' }).click(),
    ]);
    expect(incidentResponse.status()).toBe(409);
    await expect(sample.getByRole('alert')).toContainText('Incident creation failed');
    await expect(sample.locator('input[name="idempotencyKey"]')).toHaveValue(incidentKey);
    await expect(sample.getByLabel('Evidence IDs, one per line (required)')).toHaveValue('sample-receipt');
    await sample.goto(`/assessment-cases/${createdId}/incidents`);
    await expect(sample.getByText('Synthetic incident must not be accepted')).toHaveCount(0);
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

      const deniedKnowledge = await restricted.goto('/knowledge');
      expect(deniedKnowledge.status()).toBe(403);
      await expect(restricted.getByText('Synthetic public knowledge')).toHaveCount(0);
      const deniedCandidate = await restricted.goto(
        `/knowledge/${stagedItemId}/versions/v1/review?classification=PUBLIC`,
      );
      expect(deniedCandidate.status()).toBe(403);
      await expect(restricted.getByText('Synthetic browser knowledge content.')).toHaveCount(0);
      await restricted.goto('/knowledge/new');
      await restricted.getByLabel('Item ID (required)').fill(`denied-knowledge-${randomUUID()}`);
      await restricted.getByLabel('Version (required)').fill('v1');
      await restricted.getByLabel('Title (required)').fill('Denied synthetic candidate');
      await restricted.getByLabel('Plaintext content (required)').fill('Synthetic denied content.');
      await restricted.getByLabel('Allowed purposes, one per line (required)').fill('sample:review');
      await restricted.getByLabel('Review deadline in UTC (required)').fill(new Date(Date.now() + 86400000).toISOString());
      await restricted.getByLabel('Reason (required)').fill('Denied test.');
      const [deniedStage] = await Promise.all([
        restricted.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/knowledge'),
        restricted.getByRole('button', { name: 'Stage candidate' }).click(),
      ]);
      expect(deniedStage.status()).toBe(403);
      await expect(restricted.getByRole('alert')).toContainText('not authorized');
      const globalObjectives = await restricted.goto('/objectives');
      expect(globalObjectives.status()).toBe(403);
      const deniedSubmissionRead = await restricted.goto(`/objectives/submit?id=${newObjectiveId}`);
      expect(deniedSubmissionRead.status()).toBe(403);
      const deniedReviewRead = await restricted.goto(`/objectives/review?id=${newObjectiveId}`);
      expect(deniedReviewRead.status()).toBe(403);
      expect((await restricted.goto(importReceiptUrl)).status()).toBe(403);
      expect((await restricted.goto('/objectives/import/receipt/sample-source?'
        + new URLSearchParams({ id: savedReceiptId }))).status()).toBe(403);
      if (captureReadUrl) {
        expect((await restricted.goto(captureReadUrl)).status()).toBe(403);
        await expect(restricted.locator('main')).not.toContainText('"target":720');
      }
      if (captureListUrl) {
        expect((await restricted.goto(captureListUrl)).status()).toBe(403);
        await expect(restricted.locator('main li')).toHaveCount(0);
      }
      if (rehearsalUrl) {
        expect((await restricted.goto(rehearsalUrl)).status()).toBe(403);
        await expect(restricted.locator('main')).not.toContainText('12 minutes');
      }
      if (mappingReadUrl) {
        expect((await restricted.goto(mappingReadUrl)).status()).toBe(403);
        await expect(restricted.locator('main')).not.toContainText('Review the fictional conversion');
      }
      if (proposalListUrl) {
        expect((await restricted.goto(proposalListUrl)).status()).toBe(403);
        await expect(restricted.locator('main li')).toHaveCount(0);
      }
      expect((await restricted.goto('/objectives/import/requests')).status()).toBe(403);
      await restricted.goto('/objectives/import');
      if (mappingRequest) {
        const deniedMappingForm = new URLSearchParams(mappingRequest.postData());
        for (const field of ['_csrf', 'principalName', 'tenantId', 'environmentId']) {
          deniedMappingForm.set(field, await restricted.locator(`input[name="${field}"]`).first().inputValue());
        }
        deniedMappingForm.set('idempotencyKey', randomUUID());
        const deniedMapping = await restricted.request.post('/objectives/import/receipt/synthetic-mapping', {
          headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: deniedMappingForm.toString(),
        });
        expect(deniedMapping.status()).toBe(403);
      }
      if (captureRequest) {
        const deniedCaptureForm = new URLSearchParams(captureRequest.postData());
        for (const field of ['_csrf', 'principalName', 'tenantId', 'environmentId']) {
          deniedCaptureForm.set(field, await restricted.locator(`input[name="${field}"]`).first().inputValue());
        }
        deniedCaptureForm.set('idempotencyKey', randomUUID());
        const deniedCapture = await restricted.request.post('/objectives/import/receipt/sample-capture', {
          headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: deniedCaptureForm.toString(),
        });
        expect(deniedCapture.status()).toBe(403);
        expect(await deniedCapture.text()).not.toContain('Capture receipt');
      }
      await restricted.getByLabel('Receipt ID (required)').fill(savedReceiptId);
      const [deniedImportLookup] = await Promise.all([
        restricted.waitForResponse((response) => response.request().method() === 'GET'
          && new URL(response.url()).pathname === '/objectives/import/receipt'),
        restricted.getByRole('button', { name: 'Find request' }).click(),
      ]);
      expect(deniedImportLookup.status()).toBe(403);
      const deniedSampleSource = await restricted.goto('/objectives/import/sample-source?'
        + new URLSearchParams({ connectionId: 'browser-objective-source',
          externalObjectiveId: 'sample:checkout-recovery', targetWorkloadId: 'browser-objective-workload' }));
      expect(deniedSampleSource.status()).toBe(403);
      await expect(restricted.locator('main')).not.toContainText('"target":720');
      await restricted.goto('/objectives/import');
      await restricted.getByLabel('Connection ID (required)', { exact: true }).fill('synthetic-import-connection');
      await restricted.getByLabel('External objective ID (required)').fill('denied-synthetic-definition');
      await restricted.getByLabel('Target workload ID (required)').fill('browser-objective-workload');
      await restricted.getByLabel('Mapping revision (required, at least 1)').fill('1');
      await restricted.getByLabel('Expected local revision (required, 0 if none)').fill('0');
      const [deniedImport] = await Promise.all([
        restricted.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/objectives/import'),
        restricted.getByRole('button', { name: 'Record pending preview request' }).click(),
      ]);
      expect(deniedImport.status()).toBe(403);
      await expect(restricted.getByRole('alert')).toContainText('not authorized');
      await restricted.goto('/objectives/new');
      const restrictedSubmission = new URLSearchParams(submissionRequest.postData());
      for (const field of ['_csrf', 'principalName', 'tenantId', 'environmentId']) {
        restrictedSubmission.set(field, await restricted.locator(`input[name="${field}"]`).inputValue());
      }
      restrictedSubmission.set('idempotencyKey', randomUUID());
      const deniedSubmissionWrite = await restricted.request.post('/objectives/submit', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: restrictedSubmission.toString(),
      });
      expect(deniedSubmissionWrite.status()).toBe(403);
      await restricted.getByLabel('Objective ID').fill(`denied-objective-${randomUUID()}`);
      await restricted.getByLabel('Workload ID').fill('browser-objective-workload');
      await restricted.getByLabel('Title').fill('Denied synthetic objective');
      await restricted.getByLabel('Metric').fill('sample.latency');
      await restricted.getByLabel('Target').fill('1');
      await restricted.getByLabel('Unit').fill('ms');
      await restricted.getByLabel('Window in seconds').fill('60');
      const [deniedObjective] = await Promise.all([
        restricted.waitForResponse((response) => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/objectives'),
        restricted.getByRole('button', { name: 'Create local draft' }).click(),
      ]);
      expect(deniedObjective.status()).toBe(403);
      await expect(restricted.getByRole('alert')).toContainText('not authorized');
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
      const deniedIncident = await restricted.request.post(`/assessment-cases/${createdId}/incidents`, {
        form: { _csrf: csrfToken, tenantId: 'local-dev', environmentId: 'local',
          idempotencyKey: randomUUID(), workloadId: 'browser-case-workload',
          observedFrom: '2026-01-01T12:00:00Z', description: 'Denied incident',
          evidenceRefs: 'sample-receipt', reason: 'Verify authorization' },
      });
      expect(deniedIncident.status()).toBe(403);
      expect(await deniedIncident.text()).toContain('Incident creation was not authorized');
      const deniedProposal = await restricted.request.post('/assessment-incidents/00000000-0000-0000-0000-000000000001/learning-records', {
        form: { _csrf: csrfToken, tenantId: 'local-dev', environmentId: 'local',
          idempotencyKey: randomUUID(), expectedRevision: '1', hypotheses: 'Synthetic hypothesis',
          actionsJson: JSON.stringify([{ description: 'Investigate', owner_id: 'sample-owner',
            due_at: '2026-12-01T12:00:00Z', closure_test: 'Document findings' }]),
          evidenceRefs: 'sample-receipt', reason: 'Verify authorization' },
      });
      expect(deniedProposal.status()).toBe(403);
      expect(await deniedProposal.text()).toContain('Learning proposal was not authorized');
      const deniedReview = await restricted.request.post('/assessment-learning-records/10000000-0000-0000-0000-000000000002/review', {
        form: { _csrf: csrfToken, tenantId: 'local-dev', environmentId: 'local',
          expectedRevision: '1', decision: 'REJECT', reason: 'Verify authorization' },
      });
      expect(deniedReview.status()).toBe(403);
      expect(await deniedReview.text()).toContain('Learning review was not authorized');
      await sample.goto('/assessment-cases/browser-case-b/seal');
      await expect(sample.getByRole('button', { name: 'Seal this draft' })).toBeVisible();
      const deniedEvaluations = await restricted.goto('/assessment-cases/browser-case-a/evaluations');
      expect(deniedEvaluations.status()).toBe(403);
      await expect(restricted.getByText('browser-evaluation-a', { exact: true })).toHaveCount(0);
      const deniedIncidents = await restricted.goto('/assessment-cases/browser-case-a/incidents');
      expect(deniedIncidents.status()).toBe(403);
      await expect(restricted.getByText('Synthetic browser incident 1', { exact: true })).toHaveCount(0);
      const deniedLearning = await restricted.goto(
        '/assessment-incidents/00000000-0000-0000-0000-000000000001/learning-records');
      expect(deniedLearning.status()).toBe(403);
      await expect(restricted.getByText('Synthetic hypothesis')).toHaveCount(0);
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

      const deniedPolicies = await restricted.goto(`/policies?serviceId=${fixtureServiceId}`);
      expect(deniedPolicies.status()).toBe(403);
      await expect(restricted.getByText(policyId, { exact: false })).toHaveCount(0);
      const deniedCurrentPolicy = await restricted.goto(
        `/policies/detail?serviceId=${fixtureServiceId}&id=${policyId}&revision=current`,
      );
      expect(deniedCurrentPolicy.status()).toBe(403);
      await expect(restricted.getByText(policyId, { exact: false })).toHaveCount(0);
      await restricted.goto(`/policies/new?serviceId=${fixtureServiceId}`);
      const restrictedForm = new URLSearchParams(policyRequest.postData());
      restrictedForm.set('_csrf', await restricted.locator('input[name="_csrf"]').inputValue());
      restrictedForm.set('principalName', await restricted.locator('input[name="principalName"]').inputValue());
      restrictedForm.set('id', `denied-policy-${randomUUID()}`);
      restrictedForm.set('idempotencyKey', randomUUID());
      const deniedPolicyWrite = await restricted.request.post('/policies', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, data: restrictedForm.toString(),
      });
      expect(deniedPolicyWrite.status()).toBe(403);
      expect(await deniedPolicyWrite.text()).toContain('Policy proposal was not authorized');

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
