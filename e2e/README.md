# Browser E2E tests

The live browser suite verifies the local Keycloak OIDC login and the UX01 →
EDGE01 → S01 context/current-access flow against disposable infrastructure.

## Run

Prerequisites: Docker with a running daemon, Node.js, npm, and free loopback
ports 8080, 8081, 8082, 8084, and 8180.

```sh
npm ci --prefix e2e
(cd e2e && npx playwright install --with-deps chromium)
npm --prefix e2e run test:live
```

`test:live` builds the required application images, starts an isolated
PostgreSQL/Keycloak/application stack, waits for service health endpoints, and
runs Playwright headlessly. It generates test passwords, client secrets, and
delegation keys in a private temporary directory; credentials are not stored
in the repository. The runner removes its uniquely named containers, network,
and database volume on exit.

The Playwright tests cover administrator and approver capability separation,
read-only principal-directory access for both roles, access after a browser
reload, invalid-password rejection, protected-route redirection, and
local-session logout. They also verify that an administrator without
catalog-write authority cannot open the owner selector or submit a
journey-creation request. Missing and invalid CSRF tokens are rejected for
context selection and logout without changing the selected context or session;
valid browser forms still work. These are HTTP rejection checks, not a complete
cross-origin or HTTPS review. The suite uses the isolated first-admin bootstrap
fixture; it does not grant catalog-write capabilities, run S02, or test
production identity-provider configuration.
