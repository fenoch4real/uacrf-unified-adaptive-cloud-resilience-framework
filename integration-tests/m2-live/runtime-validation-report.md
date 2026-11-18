# M2 Focused Runtime Validation Report

**Generated**: 2026-11-17
**Target**: UACRF S01-S05-S06 objective-context API flow

## Summary

| Step | Status | Exit Code | Details |
|---|---|---:|---|
| Startup | PASS | 0 | Three isolated runs started real S01, S02, S05, S06 and S07 HTTP processes. Each `/actuator/health` endpoint returned 200; per-run startup was 31.5-32.1 seconds. |
| Focused integration tests | PASS | 0 | Final focused rerun with S06 Neo4j projection enabled; 3 tests, 0 failures, 0 errors, 0 skipped. |
| Full Maven reactor | PASS | 0 | `./mvnw --batch-mode --no-transfer-progress verify`; 507 tests, 0 failures, 0 errors, 0 skipped. |
| Browser E2E | N/A | n/a | This gate is API-only and renders no HTML. Browser/UX remains a separate acceptance requirement. |

**Overall**: PASS — this focused gate does not establish full M2 acceptance.

## Environment and validation stack

- Docker: AVAILABLE; `docker info` succeeded (Docker Desktop 29.9.2).
- Java: 25.0.2.
- Maven: root `./mvnw`, Maven 3.10.0.
- Node.js: AVAILABLE, v26.13.1; Playwright was not applicable to this API-only scope.
- Infrastructure tier: PRIMARY — Testcontainers started five isolated PostgreSQL databases and a Neo4j container for each journey.
- Browser tier: NOT APPLICABLE — no HTML is rendered by these flows.

The tests used random loopback ports, test-only credentials and ephemeral RSA keys. Each journey used a distinct tenant/environment and unique fixture identifiers. Service processes, containers and temporary process logs were cleaned up after each test. S02 service/workload and S07 assessment-case fixtures were created through their public APIs; the S07 case was sealed and read back before the journey. The S06 projection worker was enabled; the authorized journey verified Neo4j reconciliation, matching content digests, no missing/extra graph content, and an eligible internal graph receipt.

## Test evidence and journeys

1. **Authorized objective context and graph projection** — accepted an S06 graph and verified the actual Neo4j projection against the canonical digest and public reconciliation before submitting an action-relevant v3 selection. It created and activated an S05 objective and exercised S01 delegation, the signed service receipt from live S02 and the sealed-case receipt from live S07. It verified the exact persisted S05 objective-set digest and objective, confirmed no `GOVERNED_BY_SLO` edge, read the S06 command back, and rejected a missing caller token with 401.
2. **Changed objective set** — persisted a selection, superseded its active S05 objective through the public lifecycle, and classified against the changed set. The result was `INDETERMINATE` with `objective_set_change.changed=true`; the classification command was read back from S06 persistence. An unauthenticated command read returned 401.
3. **Absent identity membership** — completed and read back an authorized selection, then repeated the request with a caller lacking S01 membership. S06 failed closed with 409 `PROOF_UNAVAILABLE_OR_STALE`; no accepted command response was created.

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

- S01, S02, S05, S06 and S07 ran as real HTTP services with separate PostgreSQL databases. S02 service/workload and S07 case fixtures were created through their public APIs; S07's case receipt was obtained by S06 under a scoped S01 delegation. S02 used an ephemeral test receipt-signing key. S07 external owner receipt verification was disabled; evaluation with live S04/S05/S06 receipts was not exercised. Neo4j projection and reconciliation were verified for the authored-edge graph fixture; projection retry/failure and drift recovery are not covered by this live gate.
- S04 evidence summaries remain a test-only HTTP boundary. Its stub was configured to fail if called; it received no calls.
- Kafka, artifact storage, S03/S04/S07 live owners, cloud-provider conformance, EDGE01/UX01, browser rendering and the remaining M2 workflows were not exercised.
- This result does not claim SLO graph relationships, measurement evaluation, freshness/completeness coverage, production readiness or M2 completion.

## Issues found

An initial full run exposed a test-harness mismatch: the S07 stub accepted selection-purpose receipts but rejected the classification-purpose receipt, causing a 409. The test stub was corrected to validate the two expected purposes. The focused suite and full reactor then passed. The focused module was rerun after the subsequent assertion-only refinements; no production code was changed for this issue.
