# Runtime Validation Report

**Generated**: 2026-11-18

**Target**: UACRF repository

## Latest live browser run

On 2026-11-18, `npm --prefix e2e run test:live` passed **11 tests** with exit
code 0. The disposable Compose stack included S02 and a separate PostgreSQL
catalog database. In addition to the established OIDC, context, membership,
admission, disable, CSRF, and denial flows, an independently authorized human
created a draft journey, registered a service, linked it to that journey, and
verified the journey and linked service through S02 readback. Catalog
capabilities were granted through the separate approver decision and
administrator membership workflow; the browser did not bypass owner selection.
An administrator without catalog-write authority remains denied when opening
journey ownership or transfer flows. The stack was removed after the run.
This validates one authorized journey/service path, not all catalog mutations,
successful ownership transfer, or workload registration, and does not cover
cross-origin/HTTPS behavior or production identity-provider configuration.

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
| Browser E2E | PASS | 0 | `npm --prefix e2e run test:live`; 11 tests in 4 files, all passed |

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
- An independently authorized human creates a draft journey, registers a service, links it to the journey, and reads back the persisted journey and service through the browser.
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
- Successful catalog verification is limited to draft journey creation,
  service registration, and journey linking. Standalone and service-bound
  workload registration and successful ownership transfer remain untested.
- Catalog authority is granted only through independently approved global
  memberships for human principals; self-grants remain prohibited.

## Issues found

No unresolved application or test failures.
