# M2 Focused Runtime Validation Report

**Generated**: 2026-11-19
**Target**: UACRF objective-context API and S03-to-S04 outbox process flow

## Summary

| Step | Status | Exit Code | Details |
|---|---|---:|---|
| Startup | PASS | 0 | Four objective/topology runs started real S01, S02, S05, S06 and S07 HTTP processes; the fifth run started those support services plus real S03 and S04 processes. All health endpoints returned 200. |
| Focused integration tests | PASS | 0 | The current root reactor ran all five `m2-live` process tests against rebuilt services: 5 tests, 0 failures, 0 errors, 0 skipped. |
| Full Maven reactor | PASS | 0 | `./mvnw --batch-mode --no-transfer-progress -Dstyle.color=never verify` completed after the acknowledgement-ID, collection-token, and aggregate-type changes: 590 tests, 0 failures, 0 errors, 0 skipped. |
| Browser E2E | N/A | n/a | This gate is API-only and renders no HTML. Browser/UX remains a separate acceptance requirement. |

**Overall**: PASS — this focused gate does not establish full M2 acceptance.

## Environment and validation stack

- Docker: AVAILABLE; `docker info` succeeded (Docker Desktop 29.9.2).
- Java: 25.0.2.
- Maven: root `./mvnw`, Maven 3.10.0.
- Node.js: AVAILABLE, v26.13.1; Playwright was not applicable to this API-only scope.
- Infrastructure tier: PRIMARY — each objective/topology journey used five isolated PostgreSQL databases and Neo4j; the S03/S04/S01 delegation journey used seven isolated PostgreSQL databases and Neo4j.
- Browser tier: NOT APPLICABLE — no HTML is rendered by these flows.

The tests used random loopback ports, test-only credentials and ephemeral RSA keys; each run used isolated service databases and unique fixture identifiers. The S03/S01 identity path uses the bootstrap tenant/environment in its isolated identity database. Service processes, containers and temporary process logs were cleaned up after each test. The four objective/topology journeys used five isolated PostgreSQL databases and Neo4j per run. The S03/S04/S01 delegation journey used seven isolated PostgreSQL databases and Neo4j: five for the shared live-service fixture and separate databases for S03 and S04. S02 service/workload, active workload-version and S07 assessment-case fixtures were created through their public APIs; workload manifest integrity was verified by S02, and the S07 case was sealed and read back before the journey. The S06 projection worker was enabled; the authorized journey verified Neo4j reconciliation, matching content digests, no missing/extra graph content, and an eligible internal graph receipt.

## Test evidence and journeys

1. **Authorized objective context and graph projection** — accepted an S06 graph and verified the actual Neo4j projection against the canonical digest and public reconciliation before submitting an action-relevant v3 selection. It created and activated an S05 objective and exercised S01 delegation, the signed service receipt from live S02 and the sealed-case receipt from live S07. It verified the exact persisted S05 objective-set digest and objective, confirmed no `GOVERNED_BY_SLO` edge, read the S06 command back, and rejected a missing caller token with 401.
2. **Changed objective set** — persisted a selection, superseded its active S05 objective through the public lifecycle, and classified against the changed set. The result was `INDETERMINATE` with `objective_set_change.changed=true`; the classification command was read back from S06 persistence. An unauthenticated command read returned 401.
3. **Absent identity membership** — completed and read back an authorized selection, then repeated the request with a caller lacking S01 membership. S06 failed closed with 409 `PROOF_UNAVAILABLE_OR_STALE`; no accepted command response was created.
4. **Current workload owner enforcement** — enabled the opt-in check across real S01, S02 and S05 services. The exact S02 catalog owner proposed and activated objectives; a separately authorized non-owner was denied proposal with no row persisted and denied activation while the existing active objective remained active. Afterward, the owner activated the approved objective and the previous one was superseded.
5. **S03 outbox through S01 to S04 inbox** — admitted the publisher through S01's real `/v1/principals` API using a test-only Keycloak-compatible directory stub, then used a separate bootstrap approver to create an `evidence.inbox.write` grant decision and the bootstrap administrator to create the membership through `/v1/memberships`. A test-only client-credentials endpoint issued a test-signed OIDC token validated by real S01 against a test JWKS. S01's real delegation endpoint issued the S04-scoped delegation used by S03 delivery. A local proxy let S04 persist the first event but returned 503 to S03; S03 retried, S04 identified the event as `DUPLICATE`, and the event was marked published. A request for ungranted `connections.connection.write` was denied with 403.

Surefire evidence: `target/surefire-reports/TEST-org.uacrf.m2live.*.xml`. The full-module command rerun after the acknowledgement-retry change was:

```sh
./mvnw --batch-mode --no-transfer-progress -pl integration-tests/m2-live test
```

This rerun passed 5 tests, 0 failures, 0 errors, and 0 skips before the later
S03 acknowledgement-ID validation change. It used service jars produced by
the preceding root reactor build. On a clean checkout, use the
canonical root `verify` command below so the service jars are packaged before
these tests run.

After adding the acknowledgement-failure retry case, the targeted command passed
with 1 test, 0 failures, 0 errors, and 0 skips:

```sh
./mvnw --batch-mode --no-transfer-progress -Dstyle.color=never \
  -pl integration-tests/m2-live -Dtest=M2S03ToS04OutboxLiveTests test
```

The full five-test module was rerun afterward and passed all 5 tests, with 0
failures, 0 errors, and 0 skips. After the later S03 acknowledgement-ID
validation change, S03 was repackaged and the targeted process test was rerun
successfully (1 test).

The latest full-reactor command was:

```sh
./mvnw --batch-mode --no-transfer-progress -Dstyle.color=never verify
```

This run passed 590 tests, including all five `m2-live` tests, after the
acknowledgement-ID, collection-token, and aggregate-type changes. The live
S03/S04 journey verifies the retained connection event's `aggregate_type`.
It supersedes the earlier 588- and 584-test root verifications. It does not
exercise live provider collection or prove that a retained collection batch
refreshes tokens in a running process.

## Boundaries and known gaps

- S01, S02, S05, S06 and S07 ran as real HTTP services in the four objective/topology journeys with separate PostgreSQL databases. S02 service/workload/version and S07 case fixtures were created through their public APIs; S07's case receipt was obtained by S06 under a scoped S01 delegation. S02 used an ephemeral test receipt-signing key. S07 external owner receipt verification was disabled; evaluation with live S04/S05/S06 receipts was not exercised. Neo4j projection and reconciliation were verified for the authored-edge graph fixture; projection retry/failure and drift recovery are not covered by this live gate.
- The fifth journey ran real S03, S04 and S01 HTTP processes with separate PostgreSQL databases; the shared fixture also started S02, S05, S06 and S07. S03 connection registration and scheduled outbox publication reached S04's retained inbox through S01's real admission, grant-decision, membership and delegation APIs. A test proxy injected a 503 only after S04 persisted the first request, proving the retry is treated as `DUPLICATE` before S03 marks its row published. Principal lookup used a local test-only directory stub, while OAuth client-credentials issuance and OIDC signing/JWKS were also test fixtures. This does not verify production identity or production IdP conformance.
- S03 now checks that S04's terminal acknowledgement identifies the event sent. Its PostgreSQL-backed publisher tests cover missing and mismatched IDs using an HTTP stub; the real-process journey verifies compatible S04 responses and retry, not malformed acknowledgement injection.
- S04 evidence-summary calls in the four objective/topology journeys remain a test-only HTTP boundary. The stub was configured to fail if called; it received no calls. Kafka, artifact storage, cloud-provider conformance, EDGE01/UX01, browser rendering and the remaining M2 workflows were not exercised.
- This result does not claim SLO graph relationships, measurement evaluation, freshness/completeness coverage, production readiness or M2 completion.

## Issues found

An initial run exposed a test-harness mismatch: the S07 stub accepted selection-purpose receipts but rejected the classification-purpose receipt, causing a 409. The test stub was corrected to validate the two expected purposes. The first owner-check run also showed the shared S02 fixture lacked an active workload version; the fixture now registers and activates a manifest-digest-verified version through the public API. The final focused suite and full reactor passed. No production code was changed for these harness fixes.

The S03/S01 journey initially mixed its bootstrap identity context with the randomly generated context used by other fixture helpers. The journey now consistently uses S01's bootstrap tenant/environment for admission, grant, membership, delegation, S03 delivery and S04 read-back. The targeted S03/S04 test passes after injecting a post-persistence 503 and verifying the duplicate retry; the full five-test module was rerun afterward and passed with no failures, errors, or skips.
