const keycloakUrl = 'http://localhost:8180';
const realm = 'uacrf';

async function getAdminToken() {
  const response = await fetch(`${keycloakUrl}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'admin-cli',
      username: process.env.KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME,
      password: process.env.KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD,
    }),
  });
  if (!response.ok) {
    throw new Error(`Keycloak admin authentication failed with HTTP ${response.status}.`);
  }

  const tokenResponse = await response.json();
  if (typeof tokenResponse.access_token !== 'string') {
    throw new Error('Keycloak admin authentication returned no access token.');
  }
  return tokenResponse.access_token;
}

export default async function globalSetup() {
  const token = await getAdminToken();
  const usersUrl = `${keycloakUrl}/admin/realms/${realm}/users`;
  const headers = {
    authorization: `Bearer ${token}`,
    'content-type': 'application/json',
  };

  const testUsers = [
    ['local-admin', 'UACRF_E2E_ADMIN_PASSWORD'],
    ['local-approver', 'UACRF_E2E_APPROVER_PASSWORD'],
  ];
  for (const [username, passwordVariable] of testUsers) {
    const password = process.env[passwordVariable];
    if (!password) {
      throw new Error(`Missing disposable test password in ${passwordVariable}.`);
    }

    const query = new URLSearchParams({ username, exact: 'true' });
    const usersResponse = await fetch(`${usersUrl}?${query}`, { headers });
    if (!usersResponse.ok) {
      throw new Error(`Keycloak user lookup for ${username} failed with HTTP ${usersResponse.status}.`);
    }

    const users = await usersResponse.json();
    const user = users.find((candidate) => candidate.username === username);
    if (!user?.id) {
      throw new Error(`Keycloak test user ${username} is missing from the imported realm.`);
    }

    const passwordResponse = await fetch(`${usersUrl}/${encodeURIComponent(user.id)}/reset-password`, {
      method: 'PUT',
      headers,
      body: JSON.stringify({
        type: 'password',
        value: password,
        temporary: false,
      }),
    });
    if (!passwordResponse.ok) {
      throw new Error(`Keycloak password setup for ${username} failed with HTTP ${passwordResponse.status}.`);
    }
  }
}
