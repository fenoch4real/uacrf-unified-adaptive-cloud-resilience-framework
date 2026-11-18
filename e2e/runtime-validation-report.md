# Runtime Validation Report

**Generated**: 2026-11-17

**Target**: UACRF repository

## Latest live browser run

On 2026-11-17, `npm --prefix e2e run test:live` passed **10 tests** with exit
code 0. The suite verifies that an authorized administrator admits an
unadmitted user through UX01, EDGE01, S01, and the trusted local Keycloak
directory. The receipt confirms no runtime effect and no membership; the user
remains absent from the selected-context principal directory until access is
granted separately. The independent approver then records a grant decision,
and the separate administrator applies it using the approval reference and
matching principal, capability, scope, and expiry. S01 consumes the decision
with membership creation. The approver cannot submit a direct principal
disable request; the authorized administrator disables the admitted principal
with a reason and current revision, while S01 preserves its memberships.
Existing OIDC, context, logout, CSRF, invalid-password, and catalog-owner
denial checks also pass. The suite additionally confirms an administrator
without catalog-write authority cannot open a journey ownership-transfer page
or submit a CSRF-valid transfer request; S01 rejects both before S02. No
successful live transfer or catalog-side persistence check is covered. The
stack was removed. These checks use local HTTP and do not cover all mutations
or cross-origin/HTTPS behavior.

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
| Targeted service tests | PASS | 0 | Estate Catalog (20), API Edge (7), and UX01 (76); 103 tests, 0 failures, 0 errors, 0 skipped |
| Browser E2E | PASS | 0 | `npm --prefix e2e run test:live`; 10 tests in 3 files, all passed |

**Overall**: PASS

The targeted service tests were run with:

```sh
./mvnw --batch-mode --no-transfer-progress -Dstyle.color=never -pl services/estate-catalog,services/api-edge,web -am -Dtest='EstateCatalogApplicationTests,OwnerReceiptTests,CatalogRouteControllerTests,WebApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false test
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
- An administrator without catalog-write authority cannot open the ownership-transfer page or submit a CSRF-valid direct transfer request; S01 denies both.
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
- Authorized catalog writes remain untested. In the initial setup, the
  administrator cannot grant catalog authority to themselves, and
  `identity.owner.select` is not delegable to another catalog user. The user
  chose to keep this authorization policy unchanged.

## Issues found

No unresolved application or test failures.
