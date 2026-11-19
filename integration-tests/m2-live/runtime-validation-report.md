# M2 Focused Runtime Validation Report

**Generated**: 2026-11-19
**Target**: UACRF objective-context API and S03-to-S04 outbox process flow

## Summary

| Step | Status | Exit Code | Details |
|---|---|---:|---|
| Startup | PASS | 0 | Four objective/topology runs started real S01, S02, S05, S06 and S07 HTTP processes; a fifth run started real S03 and S04 processes. All health endpoints returned 200; the four topology runs started in 51.6-57.8 seconds. |
| Focused integration tests | PASS | 0 | Final focused rerun with S06 Neo4j projection and S05 workload-owner checks enabled; 5 tests, 0 failures, 0 errors, 0 skipped. |
| Full Maven reactor | PASS | 0 | `./mvnw --batch-mode --no-transfer-progress -Dstyle.color=never verify` completed after the S03 outbox and process-test additions: 584 tests, 0 failures, 0 errors, 0 skipped, including all five M2 live tests. |
| Browser E2E | N/A | n/a | This gate is API-only and renders no HTML. Browser/UX remains a separate acceptance requirement. |

**Overall**: PASS — this focused gate does not establish full M2 acceptance.

## Environment and validation stack

- Docker: AVAILABLE; `docker info` succeeded (Docker Desktop 29.9.2).
- Java: 25.0.2.
- Maven: root `./mvnw`, Maven 3.10.0.
- Node.js: AVAILABLE, v26.13.1; Playwright was not applicable to this API-only scope.
- Infrastructure tier: PRIMARY — each objective/topology journey used five isolated PostgreSQL databases and Neo4j; the S03/S04 publisher journey used two isolated PostgreSQL databases.
- Browser tier: NOT APPLICABLE — no HTML is rendered by these flows.

The tests used random loopback ports, test-only credentials and ephemeral RSA keys. Each journey used a distinct tenant/environment and unique fixture identifiers. Service processes, containers and temporary process logs were cleaned up after each test. The four objective/topology journeys used five isolated PostgreSQL databases and Neo4j per run. A fifth journey used separate PostgreSQL databases for S03 and S04, real HTTP processes for both services, and test-only S01 token/delegation endpoints. S02 service/workload, active workload-version and S07 assessment-case fixtures were created through their public APIs; workload manifest integrity was verified by S02, and the S07 case was sealed and read back before the journey. The S06 projection worker was enabled; the authorized journey verified Neo4j reconciliation, matching content digests, no missing/extra graph content, and an eligible internal graph receipt.

## Test evidence and journeys

1. **Authorized objective context and graph projection** — accepted an S06 graph and verified the actual Neo4j projection against the canonical digest and public reconciliation before submitting an action-relevant v3 selection. It created and activated an S05 objective and exercised S01 delegation, the signed service receipt from live S02 and the sealed-case receipt from live S07. It verified the exact persisted S05 objective-set digest and objective, confirmed no `GOVERNED_BY_SLO` edge, read the S06 command back, and rejected a missing caller token with 401.
2. **Changed objective set** — persisted a selection, superseded its active S05 objective through the public lifecycle, and classified against the changed set. The result was `INDETERMINATE` with `objective_set_change.changed=true`; the classification command was read back from S06 persistence. An unauthenticated command read returned 401.
3. **Absent identity membership** — completed and read back an authorized selection, then repeated the request with a caller lacking S01 membership. S06 failed closed with 409 `PROOF_UNAVAILABLE_OR_STALE`; no accepted command response was created.
4. **Current workload owner enforcement** — enabled the opt-in check across real S01, S02 and S05 services. The exact S02 catalog owner proposed and activated objectives; a separately authorized non-owner was denied proposal with no row persisted and denied activation while the existing active objective remained active. Afterward, the owner activated the approved objective and the previous one was superseded.
5. **S03 outbox to S04 inbox** — registered a connection through the real S03 HTTP API, then verified the scheduled S03 publisher delivered its outbox event to the real S04 inbox in a separate PostgreSQL database. The test-only S01 token and delegation endpoints returned an S04-scoped token signed with an ephemeral key trusted by S04; no live S01 admission or grant flow was exercised.

Surefire evidence: `target/surefire-reports/TEST-org.uacrf.m2live.*.xml`. The focused command was:

```sh
./mvnw --batch-mode --no-transfer-progress -pl integration-tests/m2-live test
```

This focused rerun used service jars produced by the preceding root reactor build.
On a clean checkout, use the canonical root `verify` command below so the service
jars are packaged before these tests run.

The previously recorded full-reactor command was:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

## Boundaries and known gaps

- S01, S02, S05, S06 and S07 ran as real HTTP services in the four objective/topology journeys with separate PostgreSQL databases. S02 service/workload/version and S07 case fixtures were created through their public APIs; S07's case receipt was obtained by S06 under a scoped S01 delegation. S02 used an ephemeral test receipt-signing key. S07 external owner receipt verification was disabled; evaluation with live S04/S05/S06 receipts was not exercised. Neo4j projection and reconciliation were verified for the authored-edge graph fixture; projection retry/failure and drift recovery are not covered by this live gate.
- The fifth journey ran real S03 and S04 HTTP processes with separate PostgreSQL databases. S03 connection registration and scheduled outbox publication reached S04's retained inbox. S01 token/delegation endpoints were test stubs that returned an S04-scoped token signed by an ephemeral key; live S01 admission, grant and delegation conformance remain unverified.
- S04 evidence-summary calls in the four objective/topology journeys remain a test-only HTTP boundary. The stub was configured to fail if called; it received no calls. Kafka, artifact storage, cloud-provider conformance, EDGE01/UX01, browser rendering and the remaining M2 workflows were not exercised.
- This result does not claim SLO graph relationships, measurement evaluation, freshness/completeness coverage, production readiness or M2 completion.

## Issues found

An initial run exposed a test-harness mismatch: the S07 stub accepted selection-purpose receipts but rejected the classification-purpose receipt, causing a 409. The test stub was corrected to validate the two expected purposes. The first owner-check run also showed the shared S02 fixture lacked an active workload version; the fixture now registers and activates a manifest-digest-verified version through the public API. The final focused suite and full reactor passed. No production code was changed for these harness fixes.
