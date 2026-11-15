# Runtime Validation Report

**Generated**: 2026-11-15T17:20:50Z

**Target**: UACRF repository

## Follow-up browser run

On 2026-11-15, `npm --prefix e2e run test:live` passed **8 tests** with exit
code 0 against a fresh isolated stack. New tests verify that administrator
and approver can read the principal directory without admission or disable
controls. Another confirms that an administrator without catalog-write
authority receives an S01 owner-selection denial both when opening the journey
form and when submitting a CSRF-valid request directly. No S02 service is
present in this fixture, so this is not evidence of an authorized catalog
write or a catalog-side persistence check. The runner removed its disposable
containers, network and database volume.

The preceding follow-up passed **6 tests** with exit code 0, including the
denied creation case. The original run and its integration baseline are retained
below; the full Maven reactor was not rerun for either follow-up.

## Summary

| Step | Status | Exit Code | Details |
|---|---|---:|---|
| Startup | PASS | 0 | Isolated Compose stack; Keycloak realm discovery and all application actuator health endpoints returned 2xx |
| Integration | PASS | 0 | `./mvnw --batch-mode --no-transfer-progress verify`; 334 tests, 0 failures, 0 errors, 0 skipped |
| Browser E2E | PASS | 0 | `npm --prefix e2e run test:live`; 5 tests in 3 files, all passed |

**Overall**: PASS

## Environment

- Docker: AVAILABLE
- Node.js: AVAILABLE — v26.13.1
- Playwright: AVAILABLE — @playwright/test 1.65.1; Chromium installed
- Infrastructure tier: PRIMARY — disposable Docker Compose PostgreSQL and Keycloak
- Browser tier: PRIMARY — headless Playwright Chromium

## Journeys exercised

- Administrator OIDC login, context selection, seven-capability current-access read, and browser reload.
- Approver OIDC login, context selection, two-capability current-access read, and browser reload.
- Invalid-password rejection for administrator and approver accounts.
- Protected-route redirect for an unauthenticated browser and local-session logout.

The access views were served through UX01 and EDGE01, with S01 providing the
authoritative current-context and capability responses.

## Legacy tests

No existing Playwright suite was present. Existing Java/MockMvc tests were
retained and run through the Maven reactor.

## Known gaps

- The browser suite uses the local imported Keycloak realm and isolated
  first-admin bootstrap, not a production identity provider.
- Authorized catalog writes remain untested. In the initial setup, the
  administrator cannot grant catalog authority to themselves, and
  `identity.owner.select` is not delegable to another catalog user. The user
  chose to keep this authorization policy unchanged.

## Issues found

No unresolved application or test failures.
