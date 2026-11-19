# Runtime Validation Report

**Generated**: 2026-11-19

**Target**: UACRF repository

## Latest live browser run

On 2026-11-19, `npm --prefix e2e run test:live` passed **11 tests** with exit
code 0 using one Playwright worker. The disposable Compose stack included S02
and a separate PostgreSQL catalog database. In addition to the established
OIDC, context, membership, admission, disable, and CSRF flows, an independently
authorized human created a draft journey, registered and linked a service,
and verified the persisted journey and linked service. The bootstrap
administrator then transferred both owners to a separately admitted human
with independently approved `owner.eligible`, despite having no catalog-write
capability. S01 provided owner candidates and read-only S02 target delegations;
the S02 transfer delegation carried only `ownership.transfer`. The updated
journey and service owners were verified after redirect, and the service was
transferred before agent workload registration. A separate principal without
transfer authority was denied journey creation and both page and CSRF-valid
direct transfer access. The test also admitted the trusted local S03 publisher
service account as a SYSTEM principal, granted `runtime.principal.use` through
the independent approval flow, and registered standalone and service-bound
agent workloads. The same account also received `evidence.inbox.write` through
independent approval for the client-credentials delegation check. After
registration, UX01 used a separate S01 delegation restricted to
`catalog.agent.read` to read both persisted workloads from S02 and verify their
identifiers, kinds, owners, runtime principals, and service association. No
runtime action was performed. A separately admitted transferee with only
`owner.eligible` received HTTP 403 from S01 on `/agents` and saw no workload
records. The stack was removed after the run.
The same run authenticates the disposable S03 publisher with Keycloak
client-credentials, verifies through EDGE01 that S01 resolves it to the
admitted SYSTEM principal with only its two approved capabilities, and obtains
a 30-second S04 delegation limited to `evidence.inbox.write`; an ungranted
`catalog.service.write` delegation is denied. It also checks the dedicated
`uacrf-s03-collector` client configured for the collection runner: the client
resolves to its separately admitted SYSTEM principal with only
`connections.observation.write` and obtains the matching 30-second S03
delegation. The runner remains disabled, so no provider collection occurs.
Production deployment identity and production identity-provider configuration
remain unverified; the suite does not cover cross-origin/HTTPS behavior.

The preceding `npm --prefix e2e run test:live` run on 2026-11-15 passed
**9 tests** with exit code 0. It additionally checked that context selection
and logout reject missing and invalid CSRF tokens, that rejection does not
select a context or end the session, and that valid forms still work.

On 2026-11-15, an earlier `npm --prefix e2e run test:live` run passed **8 tests** with exit
code 0 against a fresh isolated stack. New tests verify that administrator
and approver can read the principal directory. Another confirms that an
administrator without catalog-write authority receives an S01 owner-selection
denial both when opening the journey form and when submitting a CSRF-valid
request directly. No S02 service is present in this fixture, so this is not
evidence of an authorized catalog write or a catalog-side persistence check.
The runner removed its disposable containers, network and database volume.

The preceding follow-up passed **6 tests** with exit code 0, including the
denied creation case. The original run and its integration baseline are retained
below; the full Maven reactor was not rerun for the follow-ups.

## Latest targeted validation summary

| Step | Status | Exit Code | Details |
|---|---|---:|---|
| Startup | PASS | 0 | Isolated Compose stack; Keycloak realm discovery and all application actuator health endpoints returned 2xx |
| Prior combined targeted tests | PASS | 0 | S01 Identity (25), S02 Estate Catalog (12), and UX01 (85); 122 tests, 0 failures, 0 errors, 0 skipped |
| Targeted UX01 tests | PASS | 0 | Current focused rerun: `WebApplicationTests`, 87 tests, 0 failures, 0 errors, 0 skipped |
| Browser E2E | PASS | 0 | `npm --prefix e2e run test:live`; 11 tests in 4 files, all passed |

**Overall**: PASS

The prior combined targeted tests were run with:

```sh
./mvnw --batch-mode --no-transfer-progress -Dstyle.color=never -pl services/identity,services/estate-catalog,web -am -Dtest='IdentityApplicationTests,EstateCatalogApplicationTests,WebApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false test
```

The UX01 focused rerun was:

```sh
./mvnw --batch-mode --no-transfer-progress -Dstyle.color=never -pl web -am -Dtest=WebApplicationTests -Dsurefire.failIfNoSpecifiedTests=false test
```

## Environment

- Docker: AVAILABLE
- Node.js: AVAILABLE — v26.13.1
- Playwright: AVAILABLE — @playwright/test 1.65.1; Chromium installed
- Infrastructure tier: PRIMARY — disposable Docker Compose PostgreSQL and Keycloak
- Browser tier: PRIMARY — headless Playwright Chromium

## Journeys exercised

- Administrator OIDC login, context selection, seven-capability current-access read, and browser reload.
- Approver OIDC login, context selection, two-capability current-access read, and browser reload.
- Administrator admission of a user from the trusted local Keycloak directory; S01 confirms no membership or runtime effect.
- Approver admission control hidden; a CSRF-valid direct admission submission is rejected by S01.
- Independent approver decision followed by separate administrator membership creation; direct cross-role decision and membership submissions are denied.
- Identity manager disables the admitted principal with an audited reason; a direct approver disable submission is denied, and the existing membership remains intact.
- An independently authorized human creates a draft journey, registers a service, links it to the journey, and reads back the persisted journey and service through the browser.
- The bootstrap transfer operator, without catalog-write authority, transfers the journey and unanchored service to a separately admitted eligible human; the browser verifies both updated owners and denies transfer to a principal without transfer authority.
- The same human registers a standalone and a service-bound workload using a
  separately approved SYSTEM runtime principal; UX01 reads both persisted
  records back from S02 through a narrow read delegation, without executing
  runtime actions. A transferee with only `owner.eligible` is denied readback
  by S01 and sees no records.
- Invalid-password rejection for administrator and approver accounts.
- Protected-route redirect for an unauthenticated browser and local-session logout.

The access views were served through UX01 and EDGE01, with S01 providing the
authoritative current-context and capability responses.

## Legacy tests

No existing Playwright suite was present before the browser-E2E baseline was
added. Existing Java/MockMvc tests are retained and run through Maven.

## Known gaps

- The browser suite uses the local imported Keycloak realm and isolated
  first-admin bootstrap, not a production identity provider.
- The new principal is intentionally absent from the context-scoped principal
  list until an independent membership grant is made.
- The approval handoff uses an authorization reference shared through a
  trusted channel; the browser does not provide a persisted request inbox.
- Successful catalog verification covers draft journey creation,
  service registration/linking and readback, standalone and service-bound
  workload persistence readback through UX01, and JOURNEY/SERVICE ownership
  transfers. This remains local isolated-stack evidence, not production
  deployment-identity or cloud-provider acceptance.
- Catalog authority is granted only through independently approved global
  memberships for human principals; self-grants remain prohibited.

## Issues found

No unresolved application or test failures.
