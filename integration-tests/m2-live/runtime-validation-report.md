# M2 Focused Runtime Validation Report

**Generated**: 2026-11-17
**Target**: UACRF S01-S05-S06 objective-context API flow

## Summary

| Step | Status | Exit Code | Details |
|---|---|---:|---|
| Startup | PASS | 0 | Three isolated runs started real S01, S05 and S06 HTTP processes. Each `/actuator/health` endpoint returned 200; per-run startup was 27.7-32.6 seconds. |
| Focused integration tests | PASS | 0 | Final focused rerun after assertion refinements; 3 tests, 0 failures, 0 errors, 0 skipped. |
| Full Maven reactor | PASS | 0 | Earlier run of `./mvnw --batch-mode --no-transfer-progress verify`; 509 tests, 0 failures, 0 errors, 0 skipped. It predates the final assertion-only refinements. |
| Browser E2E | N/A | n/a | This gate is API-only and renders no HTML. Browser/UX remains a separate acceptance requirement. |

**Overall**: PASS — this focused gate does not establish full M2 acceptance.

## Environment and validation stack

- Docker: AVAILABLE; `docker info` succeeded (Docker Desktop 29.9.2).
- Java: 25.0.2.
- Maven: root `./mvnw`, Maven 3.10.0.
- Node.js: AVAILABLE, v26.13.1; Playwright was not applicable to this API-only scope.
- Infrastructure tier: PRIMARY — Testcontainers started three isolated PostgreSQL databases and a Neo4j container for each journey.
- Browser tier: NOT APPLICABLE — no HTML is rendered by these flows.

The tests used random loopback ports, test-only credentials and ephemeral RSA keys. Each journey used a distinct tenant/environment and unique fixture identifiers. Service processes, containers and temporary process logs were cleaned up after each test. The S06 projection worker was disabled; Neo4j projection behavior was not verified.

## Test evidence and journeys

1. **Authorized objective context** — created and activated an S05 objective, accepted an S06 graph, and submitted an action-relevant v3 selection. The flow exercised S01 delegation, the signed S02 service receipt and the S07 case receipt. It verified the exact persisted S05 objective-set digest and objective, confirmed no `GOVERNED_BY_SLO` edge, read the S06 command back, and rejected a missing caller token with 401.
2. **Changed objective set** — persisted a selection, superseded its active S05 objective through the public lifecycle, and classified against the changed set. The result was `INDETERMINATE` with `objective_set_change.changed=true`; the classification command was read back from S06 persistence. An unauthenticated command read returned 401.
3. **Absent identity membership** — completed and read back an authorized selection, then repeated the request with a caller lacking S01 membership. S06 failed closed with 409 `PROOF_UNAVAILABLE_OR_STALE`; no S02 or S07 receipt request followed and no accepted command response was created.

Surefire evidence: `target/surefire-reports/TEST-org.uacrf.m2live.*.xml`. The focused command was:

```sh
./mvnw --batch-mode --no-transfer-progress -pl integration-tests/m2-live test
```

This focused rerun used service jars produced by the preceding root reactor build.
On a clean checkout, use the canonical root `verify` command below so the service
jars are packaged before these tests run.

The final full-reactor command was:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

## Boundaries and known gaps

- S01, S05 and S06 ran as real HTTP services with separate PostgreSQL databases. S06 was configured with Neo4j, but its projection worker was disabled, so projected graph content and reconciliation were not part of this gate.
- S02 service receipts, S04 evidence summaries and S07 case receipts were test-only HTTP boundaries. The S02 receipt used a test signing key. The S04 stub was configured to fail if called; it received no calls.
- Kafka, artifact storage, S03/S04/S07 live owners, cloud-provider conformance, EDGE01/UX01, browser rendering and the remaining M2 workflows were not exercised.
- This result does not claim SLO graph relationships, measurement evaluation, freshness/completeness coverage, production readiness or M2 completion.

## Issues found

An initial full run exposed a test-harness mismatch: the S07 stub accepted selection-purpose receipts but rejected the classification-purpose receipt, causing a 409. The test stub was corrected to validate the two expected purposes. The focused suite and full reactor then passed. The focused module was rerun after the subsequent assertion-only refinements; no production code was changed for this issue.
