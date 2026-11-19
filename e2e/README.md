# Browser E2E tests

The live browser suite verifies the local Keycloak OIDC login and the UX01 →
EDGE01 → S01 context/current-access flow against disposable infrastructure.

## Run

Prerequisites: Docker with a running daemon, Node.js, npm, and free loopback
ports 8080, 8081, 8082, 8084, 8085, and 8180.

```sh
npm ci --prefix e2e
(cd e2e && npx playwright install --with-deps chromium)
npm --prefix e2e run test:live
```

`test:live` builds the required application images, starts an isolated
PostgreSQL/Keycloak/application stack, including S03, waits for service health endpoints, and
runs Playwright headlessly. It generates test passwords, client secrets, and
delegation keys in a private temporary directory; credentials are not stored
in the repository. The runner removes its uniquely named containers, network,
and database volume on exit.

The Playwright tests cover administrator and approver capability separation,
access after a browser reload, invalid-password rejection, protected-route
redirection, and local-session logout. The administrator can admit an
unadmitted user from the trusted local Keycloak directory; the S01 receipt
confirms admission creates no memberships or capabilities. The approver sees
the directory without admission controls, and a CSRF-valid direct submission
is denied by S01. Since the directory is context-scoped to existing
memberships, the newly admitted user does not appear there until separately
granted access.

The same browser journey then verifies the existing independent grant
contract: the approver records a decision for the admitted principal, and the
separate administrator applies it using the authorization reference and
matching grant details. S01 consumes that reference with membership creation.
The approver's direct membership submission and the administrator's direct
decision submission are both denied. This is a manual trusted-channel
handoff—there is no persisted request inbox or browser approval queue.

The synthetic inventory test separately admits a human principal and uses the
same approval and membership workflow to grant owner eligibility, global
owner selection, and S03 read/write access. It imports 42 sample observations,
replays the import, and checks both inventory pages and provider labels. It
does not contact cloud providers or qualify evidence.

An identity manager can also disable an enabled principal from the directory
with a required audit reason and the displayed revision. The browser does not
offer re-enabling; S01 preserves existing memberships and requires trusted
source verification and independent grant review before re-enabling is
available. The suite verifies the approver cannot submit a direct disable
request and the authorized administrator can disable the principal.

The suite also verifies that an administrator without catalog-write authority
cannot open the journey owner selector or ownership-transfer page, and that
CSRF-valid direct journey-creation and ownership-transfer submissions are
denied by S01. The transfer check exercises only the authorization denial; it
does not run S02 or verify a successful catalog mutation. Missing and invalid
CSRF tokens are rejected for context selection and logout without changing
the selected context or session; valid browser forms still work. These are
HTTP rejection checks, not a complete cross-origin or HTTPS review. The suite
uses the isolated first-admin bootstrap fixture; it does not grant
catalog-write capabilities or test production identity-provider
configuration.
