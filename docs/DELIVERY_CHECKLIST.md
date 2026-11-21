# Granular delivery checklist

This is the working checklist for the complete M0–M8 application, including
connected browser workflows. It complements, rather than replaces, the
[milestone status](IMPLEMENTATION_STATUS.md),
[implementation plan](IMPLEMENTATION_PLAN.md), and
[acceptance register](ACCEPTANCE_AND_RISKS.md). The checked-in
[MS01 plan](../design/ms01/IMPLEMENTATION_PLAN.md) and its inventories provide
the detailed design and scenario requirements.

**Honest scope:** This is the initial breakdown of known work, not a claim that
every future implementation task has already been discovered. Tasks in later
milestones must be expanded into smaller, independently verifiable items as
their contracts and dependencies become clear. The operation, table and
requirement inventories still need item-by-item reconciliation; do not treat
their allocation as proof of implementation. No milestone is complete.
Sample provider-format data supports local development only and is not live
provider acceptance.

## How to maintain this list

- Each checkbox has a stable ID. A checked item means its stated outcome was
  implemented **and verified**; link durable evidence or identify the exact
  test and result. Do not check an item merely because a subset works.
- An unchecked item can be pending, in progress, or blocked. Record blockers
  and partial outcomes in the execution log; don't mark blocked work complete.
- Before an implementation run, add the specific planned work as unchecked
  child items if no suitably granular items exist. Preserve the parent until
  all its stated outcomes are satisfied. If scope expands, add new IDs rather
  than silently broadening an existing item.
- After **every execution/run**, append one dated execution-log line, even for
  a failed, partial, blocked, or documentation-only run. Record each test,
  build, integration or browser-suite invocation on its own line, including
  retries. Include the relevant IDs, outcome, verification and evidence, and
  any remaining work. Link a persistent test report when available.
- Check completed items individually in the same change; leave failed or
  unverified items unchecked. Update
  [implementation status](IMPLEMENTATION_STATUS.md) and the private technical
  approach document when a development slice completes.
- Record synthetic/local boundaries and untested external integrations
  explicitly. A test failure, skipped acceptance, inaccessible dependency or
  unknown outcome is not a pass. Never place credentials or private keys here.
- This log starts with the latest verified slice and this checklist setup.
  Earlier run-by-run history was not reconstructed; prior evidence remains in
  [implementation status](IMPLEMENTATION_STATUS.md) and version history.

## M0 — Baseline, security, and release controls

- [ ] M0-001 Reconcile every retained public operation with its effective contract, owner, implementation and acceptance case.
- [ ] M0-002 Reconcile all 144 operation allocations, preserving the distinction between the 107 registered routes and older 99-route inventory.
- [ ] M0-003 Reconcile all 153 table allocations and identify any unimplemented migration or owner.
- [ ] M0-004 Reconcile all 131 requirement allocations with implemented behavior and evidence.
- [ ] M0-005 Reconcile the original 47 identifiers and documented partial-operation dispositions.
- [ ] M0-006 Record missing legacy source and live database evidence as unknown rather than inferred.
- [ ] M0-007 Review the threat model against all newly implemented services and trust boundaries.
- [ ] M0-008 Design and verify managed signing-key custody, distribution and rotation.
- [ ] M0-009 Execute the first real image signing and SBOM-attestation release workflow.
- [ ] M0-010 Enforce image signature and attestation verification at deployment.
- [ ] M0-011 Review CSRF, cross-origin behavior and HTTPS enforcement for every browser mutation.
- [ ] M0-012 Test runtime logs and events for secret and sensitive-data leakage.
- [ ] M0-013 Implement and test edge/service rate limits.
- [ ] M0-014 Add schema-version and digest compatibility plus inbox/quarantine before consuming S01/S02 events.
- [ ] M0-015 Verify backward-compatible event expansion and rejection of incompatible changes in CI.
- [ ] M0-016 Validate deployment-level database isolation and least privilege for all included services.
- [ ] M0-017 Verify image/runtime probes, graceful shutdown, bounded pools, and operational telemetry for each release service.
- [ ] M0-018 Review shared/remote builder cache access and retention for private-document exposure.
- [x] M0-019 Select the first bounded local recovery action and its acceptance bounds. Evidence: [first recovery action](FIRST_RECOVERY_ACTION.md); adapter implementation remains M5 work.

## M1 — Identity, catalog, and first browser workflow

- [ ] M1-001 Verify production deployment identities and audience-scoped service credentials end to end.
- [ ] M1-002 Configure and verify a production identity provider rather than the local Keycloak fixture.
- [ ] M1-003 Implement trusted-source re-verification and independent review before principal re-enablement.
- [ ] M1-004 Implement and test downstream revocation propagation, including already-issued delegations.
- [ ] M1-005 Design and verify owner-gap reporting across services.
- [ ] M1-006 Implement and verify the legacy UUID-to-owner command routing map.
- [ ] M1-007 Verify command lookup after the issuing service restarts.
- [ ] M1-008 Test concurrent revocation and downstream authorization using independently deployed services.
- [ ] M1-009 Verify public response compatibility against the effective contract inventory.
- [ ] M1-010 Verify cross-service database read and write denial in the actual deployment configuration.
- [ ] M1-011 Complete and retain the full sign-in, owner, service, workload and journey browser acceptance walkthrough.
- [x] M1-012 Exercise synthetic browser journey/service creation, linking, ownership transfer and workload readback. Evidence: [M1 status](IMPLEMENTATION_STATUS.md#milestone-and-capability-tracker); production acceptance remains open.

## M2 — Collection, evidence, objectives, topology, reporting

### S03 connections and ingestion

- [ ] M2-001 Finish controlled source discovery and binding through the connected UI.
- [ ] M2-002 Complete collection execution with production-safe service identity and configuration.
- [ ] M2-003 Implement and verify collection policy, freshness policy and evaluation trigger as separate controls.
- [ ] M2-004 Complete source checkpoint recovery and report collection failures and stale sources in the UI.
- [ ] M2-005 Implement and test AWS Config inventory collection.
- [ ] M2-006 Implement and test AWS CloudWatch metrics collection.
- [ ] M2-007 Implement and test Azure Resource Graph inventory collection.
- [ ] M2-008 Implement and test Azure Monitor metrics collection.
- [ ] M2-009 Implement and test GCP Cloud Asset Inventory collection.
- [ ] M2-010 Implement and test GCP Monitoring metrics collection.
- [ ] M2-011 Independently qualify AWS collection against pagination, permissions, outages, throttling, checkpoints and out-of-order delivery.
- [ ] M2-012 Independently qualify Azure collection against pagination, permissions, outages, throttling, checkpoints and out-of-order delivery.
- [ ] M2-013 Independently qualify GCP collection against pagination, permissions, outages, throttling, checkpoints and out-of-order delivery.
- [ ] M2-014 Expand provider ontology and freshness coverage without presenting sample observations as live results.

### S04 evidence

- [ ] M2-015 Connect source observations to qualification and retained provenance in the browser.
- [ ] M2-016 Verify current-source failure, expired evidence, conflicting events and explicit unavailable outcomes.
- [ ] M2-017 Complete broker-backed outbox/inbox processing with crash recovery and duplicate/conflict handling.
- [ ] M2-018 Verify artifact retention, access, integrity, unavailable bytes and controlled disclosure.
- [ ] M2-019 Verify qualification-policy and gap-waiver governance through the intended end-to-end workflow.

### S05 objectives

- [x] M2-020 Create retry-safe local objective drafts through UX01, EDGE01 and S05 with separate global write authority. Evidence: commit `8239039`; 182 web tests, 56 selected reactor tests and 11 isolated browser tests passed.
- [x] M2-021 Connect objective submission through the browser with expected-revision and separate authorization checks. Evidence: R-0011–R-0015 below; synthetic/local scope only.
  - [x] M2-021a Add atomic, retry-safe objective submission with preserved revision and constraint checks.
  - [x] M2-021b Forward submission and idempotency key through EDGE01.
  - [x] M2-021c Connect a semantic, context-bound UX01 submission form with separate read/write delegations; full accessibility review remains M8-013.
  - [x] M2-021d Verify creation-to-submission, replay, conflict, stale revision, denial and database rollback.
  - [x] M2-021e Update contracts, status and private approach documentation after verified completion.
- [ ] M2-022 Connect independent objective approval/rejection through the browser with review-history disclosure.
- [ ] M2-023 Connect objective activation and active-objective readback through the browser.
- [ ] M2-024 Verify S02 workload ownership at proposal and activation across real owner services when enabled.
- [ ] M2-025 Resolve the objective-import connector and fetch the actual source definition.
- [ ] M2-026 Normalize an imported objective and retain its exact source provenance.
- [ ] M2-027 Compare an imported objective against local state and expose the preview.
- [ ] M2-028 Resolve an import preview and reject changed source/local revisions.
- [ ] M2-029 Propose an imported objective from a reviewed resolution and retain history.
- [ ] M2-030 Connect import preview, resolution, proposal and history to the browser.
- [ ] M2-031 Publish and consume objective outbox events with idempotent downstream handling.

### S06 graph and S14 reporting

- [ ] M2-032 Complete typed dependency proposal and independent acceptance through the browser.
- [ ] M2-033 Connect accepted graph reads and service-only, agent-only and mixed dependency views.
- [ ] M2-034 Connect impact, difference and subgraph operations and their retained results to the browser.
- [ ] M2-035 Verify graph content against retained source facts, not projection markers alone.
- [ ] M2-036 Expose projection watermarks, gaps, stale state and reconciliation reports.
- [ ] M2-037 Verify out-of-order and conflicting graph events cannot make stale context actionable.
- [ ] M2-038 Build the first S14 projection without losing domain facts when reporting is unavailable.
- [ ] M2-039 Run a connected synthetic setup from source binding through qualification, objective activation and accepted graph.
- [ ] M2-040 Run the full M2 integration profile with PostgreSQL, Neo4j, Kafka and artifact storage.

## M3 — Assessment, knowledge, and advisory

### S07 assessment and S08 knowledge

- [ ] M3-001 Complete connected deterministic evaluation and recommendation workflows with source-owner receipts.
- [ ] M3-002 Verify case references and evidence eligibility before any operation that requires them.
- [ ] M3-003 Expose retained recommendations, uncertainty, source disclosure and next valid review steps in the browser.
- [ ] M3-004 Complete incident-to-learning proposal, independent review and governed adoption workflow.
- [ ] M3-005 Verify unavailable or modified source artifacts fail closed and remain visible.
- [ ] M3-006 Complete knowledge staging, independent approval, retrieval and withdrawal as a connected workflow with current disclosure checks.
- [ ] M3-007 Verify approved knowledge cannot be disclosed after relevant access or source eligibility changes.

### S09 advisory

- [ ] M3-008 Design a genuine atomic cross-service current-context commit guard; do not enable connected admission without it.
- [ ] M3-009 Implement and verify the current-context guard, including revocation, source changes and expiry races.
- [ ] M3-010 Connect retry-safe advisory run admission to the durable plan and outbox only after the guard is verified.
- [ ] M3-011 Connect an idempotent consumer and lease-fenced scheduling worker to admitted runs.
- [ ] M3-012 Implement durable gateway dispatch intent, bounded budget admission and reconciliation for ambiguous outcomes.
- [ ] M3-013 Implement the remote runtime journal and prevent a second observed invocation after restart or output retry.
- [ ] M3-014 Implement source/output artifact storage and end-to-end byte and digest verification.
- [ ] M3-015 Retain all agent outputs, abstentions, dissent, usage uncertainty and review history.
- [ ] M3-016 Implement independent human arbitration and prevent advisory output from becoming operational permission.
- [ ] M3-017 Connect run submission, progress, history and review to UX01 with separately scoped access.
- [ ] M3-018 Demonstrate a successful A0 run across independently deployed services.
- [ ] M3-019 Demonstrate a successful A1 run across independently deployed services.
- [ ] M3-020 Demonstrate a successful A2 run across independently deployed services.
- [ ] M3-021 Verify changed-purpose rejection, model-unknown outcome, source/output outages and retry without reinvocation.
- [ ] M3-022 Qualify each selected model/provider adapter independently before enabling real dispatch.

## M4 — Policy and operational authorization

- [x] M4-001 Connect policy proposal, revision browsing, independent rejection and externally signed approval. Evidence: [S10 status](IMPLEMENTATION_STATUS.md#s10-policy-bundle-proposal-review-and-read); governance metadata only.
- [ ] M4-002 Integrate the policy calculator into durable, context-bound policy evaluation.
- [ ] M4-003 Implement review obligations and exact human-reason retention for authorization proposals.
- [ ] M4-004 Issue a narrowly bounded action authorization using current owner receipts.
- [ ] M4-005 Implement short-lived reservation and current-authority revalidation.
- [ ] M4-006 Implement control fences and final dispatch checks across identity, policy and source state.
- [ ] M4-007 Verify valid narrowed approval and valid bounded authorization.
- [ ] M4-008 Race identity revocation against reservation and final dispatch.
- [ ] M4-009 Race policy revocation/change against reservation and final dispatch.
- [ ] M4-010 Verify unavailable required services cannot create a new permission.
- [ ] M4-011 Implement read-only sealed decision replay without provider credentials or side effects.
- [ ] M4-012 Connect policy evaluation, approvals, authorization status and replay to UX01.
- [ ] M4-013 Integrate production signing-key management, rotation and trust provisioning.

## M5 — Execution and recovery validation

- [ ] M5-001 Build the bounded local `workload.set-replicas` adapter with independent state and journal.
- [ ] M5-002 Enforce tenant, target, value, step, permit and attempt-ID bounds before local mutation.
- [ ] M5-003 Implement retry-safe execution intent, provider-plan translation and durable dispatch.
- [ ] M5-004 Verify one independently observed effect for repeated identical attempt IDs.
- [ ] M5-005 Record timeout-after-apply as unknown and reconcile using an independent status read.
- [ ] M5-006 Block dispatch after authority revocation and verify the adapter journal remains unchanged.
- [ ] M5-007 Implement execution status, lease fencing, checkpoint and resume.
- [ ] M5-008 Implement cancellation that preserves and attributes late outcomes.
- [ ] M5-009 Implement bounded compensation using the recorded prior value.
- [ ] M5-010 Implement independent probes and mandatory validation evidence.
- [ ] M5-011 Refuse recovered certification when provider success lacks successful required probes.
- [ ] M5-012 Implement recovery-path reads, experiment history and certification.
- [ ] M5-013 Connect execute, status, validate, cancel and compensate workflows to UX01.
- [ ] M5-014 Choose and qualify the first real provider action with scoped credentials and an approved failure scenario.
- [ ] M5-015 Verify the selected provider's conditional writes, idempotency, cancellation and ambiguous-outcome reconciliation.

## M6 — Commercial and operational administration

- [ ] M6-001 Implement customer, pilot, value, claim and approval-gate records.
- [ ] M6-002 Implement subscription and versioned pricing/allowance configuration.
- [ ] M6-003 Admit immutable usage from owned receipts with stable source identity.
- [ ] M6-004 Rate usage with exact decimals and frozen applicable pricing rules.
- [ ] M6-005 Post invoices with immutable line-item and adjustment history.
- [ ] M6-006 Reconcile payment-provider outcomes, including unknown outcomes and interrupted runs.
- [ ] M6-007 Handle duplicated and reordered usage and payment webhooks without duplicate charges.
- [ ] M6-008 Implement credits, refunds and explicit correction records.
- [ ] M6-009 Enforce separation between commercial entitlement and operational/agent authority.
- [ ] M6-010 Connect customer economics, subscriptions, invoices and payment status to UX01.
- [ ] M6-011 Implement S14 notification routing, delivery attempts, failure visibility and escalation.
- [ ] M6-012 Revalidate current ownership and disclosure before notifications and evidence exports.
- [ ] M6-013 Connect notification and authorized evidence-export workflows to UX01.

## M7 — Prediction, learning, and provider breadth

- [ ] M7-001 Define governed model/feature contracts and approved Core semantic mappings.
- [ ] M7-002 Implement model registry, model cards, ownership and retained training provenance.
- [ ] M7-003 Implement temporally separated offline evaluation with leakage detection.
- [ ] M7-004 Measure uncertainty and calibration against declared acceptance thresholds.
- [ ] M7-005 Implement shadow evaluation and regression gates before model promotion.
- [ ] M7-006 Keep prediction advisory until independently reviewed promotion criteria pass.
- [ ] M7-007 Connect incidents and experiments to hypotheses, owners, due dates and closure tests.
- [ ] M7-008 Route proposed objective, model, workload and policy changes to their owner-specific review workflows.
- [ ] M7-009 Qualify each selected AWS action and record unsupported capabilities.
- [ ] M7-010 Qualify each selected Azure action and record unsupported capabilities.
- [ ] M7-011 Qualify each selected GCP action and record unsupported capabilities.
- [ ] M7-012 Exercise model, knowledge, tool, memory and handoff failures with explicit uncertainty.
- [ ] M7-013 Connect model cards, evaluation results, shadow status and learning review to UX01.

## M8 — Migration, release and end-to-end acceptance

- [ ] M8-001 Inventory each domain's tables, rows, signed bytes, artifacts, commands and outbox watermark.
- [ ] M8-002 Deploy each new owner read-only and compare authorized responses.
- [ ] M8-003 Freeze writes and fence or drain legacy workers for each cutover.
- [ ] M8-004 Export consistent snapshots and replay changes through the declared watermark.
- [ ] M8-005 Preserve IDs, timestamps, digests and legacy reference resolution.
- [ ] M8-006 Reconcile rows, revisions, checksums, artifacts, authorization and inbox/outbox positions.
- [ ] M8-007 Switch each ownership epoch to one authoritative writer and verify the UI workflow.
- [ ] M8-008 Record observation, forward repair and reverse-migration procedures for accepted writes.
- [ ] M8-009 Execute every retained operation contract against its new owner and retain evidence.
- [ ] M8-010 Execute all 30 distributed acceptance cases listed below.
- [ ] M8-011 Execute all 39 inherited scenarios listed below using their individual evidence requirements.
- [ ] M8-012 Complete the full real-browser customer journey, including recovery and billing.
- [ ] M8-013 Complete keyboard, accessibility and role-sensitive-control review.
- [ ] M8-014 Exercise deployment upgrade, rollback/forward repair and backup restoration.
- [ ] M8-015 Verify multi-tenant isolation, revocation races and cross-service fault behavior.
- [ ] M8-016 Complete selected live-provider conformance and billing reconciliation evidence.
- [ ] M8-017 Regenerate and reconcile API/database guides, diagrams, UI illustrations and paired documents.
- [ ] M8-018 Produce code/document packages with manifests, exact test results and remaining deployment limitations.
- [ ] M8-019 Conduct release acceptance against all selected scope, not just structure checks or local fixtures.

## Distributed acceptance cases

Each item requires the complete expected observation in the
[acceptance matrix](ACCEPTANCE_AND_RISKS.md#distributed-acceptance-matrix);
partial service tests do not by themselves close the case.

- [ ] MS-T01 Independent S01/S02 sign-in, workflow and database isolation.
- [ ] MS-T02 Reject tenant/environment substitution without disclosure.
- [ ] MS-T03 Recover publisher crash after broker acknowledgement.
- [ ] MS-T04 Recover consumer crashes around commit without repeating effects.
- [ ] MS-T05 Quarantine same-sequence conflicting payloads.
- [ ] MS-T06 Expose graph event gaps and block stale action context.
- [ ] MS-T07 Reject graph markers when graph bytes differ.
- [ ] MS-T08 Fail closed on missing or substituted evidence/knowledge bytes.
- [ ] MS-T09 Reject stale objective-import resolutions after source change.
- [ ] MS-T10 Verify revocation ordering at reservation and dispatch.
- [ ] MS-T11 Reconcile lost provider response without blind mutation retry.
- [ ] MS-T12 Fence an executor that lost its lease before commit.
- [ ] MS-T13 Preserve and attribute late outcomes after cancellation.
- [ ] MS-T14 Retain unknown usage and abstain on invalid/timeout model outcomes.
- [ ] MS-T15 Prevent repeated gateway invocation after restart or output-send failure.
- [ ] MS-T16 Retain multi-agent missing output, abstention, rejection and dissent.
- [ ] MS-T17 Refuse recovery certification with failed or missing mandatory probes.
- [ ] MS-T18 Require current contract, provenance and evaluation for protected resume.
- [ ] MS-T19 Reconcile duplicate/reordered billing events without duplicate charge.
- [ ] MS-T20 Retain frozen pricing and explicit adjustments across period changes.
- [ ] MS-T21 Recover reporting after outage without rewriting domain facts.
- [ ] MS-T22 Expose notification failure and avoid false acknowledgement.
- [ ] MS-T23 Replay historical decision read-only without provider credentials.
- [ ] MS-T24 Detect and reconcile inconsistent restore points before resuming authority.
- [ ] MS-T25 Prove one writer and reconciled watermarks during cutover.
- [ ] MS-T26 Verify compatible schema/event expansion and reject breaking rollback.
- [ ] MS-T27 Complete the full customer journey in a real browser.
- [ ] MS-T28 Verify keyboard navigation, labels and role-sensitive controls.
- [ ] MS-T29 Prevent model promotion with temporal leakage or poor calibration.
- [ ] MS-T30 Preserve the approved version when shadow/regression gates fail.

## Inherited acceptance scenarios

Use the full setup, expected results, provider scope and evidence requirements
in the [scenario inventory](../design/ms01/inventories/ACCEPTANCE_SCENARIOS.json).
These remain unchecked until the new distributed implementation passes the
complete scenario; prior local or partial tests are not substituted.

- [ ] INT-DB-01 Duplicate and out-of-order projection.
- [ ] INT-DB-02 Concurrent updates and snapshot capture.
- [ ] INT-DB-03 Outbox interruption and reconciliation.
- [ ] INT-GRAPH-01 Direction and witness paths.
- [ ] INT-GRAPH-02 Scope and incomplete context.
- [ ] INT-GRAPH-03 Impact deduplication.
- [ ] INT-PATH-01 Hidden correlated dependency.
- [ ] INT-PATH-02 Unproven fallback.
- [ ] INT-PATH-03 Conditional certification and change.
- [ ] INT-AUTH-01 Restrictive policy and human authority.
- [ ] INT-AUTH-02 Replay and tampering.
- [ ] INT-AUTH-03 Context change and policy outage.
- [ ] INT-EXEC-01 Duplicate dispatch and ambiguous timeout.
- [ ] INT-EXEC-02 Throttle and partial application.
- [ ] INT-EXEC-03 Stop and bounded compensation.
- [ ] INT-VAL-01 Mandatory result completeness.
- [ ] INT-VAL-02 Stable observation window.
- [ ] INT-VAL-03 Revalidation after compensation.
- [ ] INT-EVD-01 Independent historical reconstruction.
- [ ] INT-EVD-02 Missing or modified artifact.
- [ ] INT-EVD-03 Retention and controlled disclosure.
- [ ] INT-OPS-01 Component interruption and restoration.
- [ ] INT-OPS-02 Isolation privilege and secrets.
- [ ] INT-OPS-03 Certified load and operator exercise.
- [ ] INT-MC-01 Equivalent bounded intent across GCP, AWS and Azure.
- [ ] INT-E2E-01 Qualified deployment failure to governed rollback and replay.
- [ ] INT-AGCORE-01 Knowledge eligibility and disclosure.
- [ ] INT-AGCORE-02 Bounded advisory orchestration.
- [ ] INT-AWL-01 Registration and frozen context.
- [ ] INT-AWL-02 Bounded concurrent dispatch.
- [ ] INT-AWL-03 Knowledge and memory isolation.
- [ ] INT-AWL-04 Cancellation and uncertain effects.
- [ ] INT-AWL-05 Checkpoint and governed resumption.
- [ ] INT-AWL-06 Meaningful recovery validation.
- [ ] INT-AWL-07 Multi-agent lineage and limits.
- [ ] INT-AWL-08 Usage and commercial separation.
- [ ] INT-CAT-01 Journey scope and distinct inventories.
- [ ] INT-CAT-02 Catalogue mutation admission and audit.
- [ ] INT-CAT-03 Shared resource access and runtime boundary.

## Execution log

| Run | Date | Items | Outcome and verification | Remaining / limitations |
| --- | --- | --- | --- | --- |
| R-0001 | 2026-11-20 | M2-020 | Clean selected S05/EDGE01 reactor: 56 tests passed. | Service-local integration; not full-system acceptance. |
| R-0002 | 2026-11-20 | M2-020 | Clean UX01 reactor: 182 tests passed. | Mocked service tests do not establish live-owner acceptance. |
| R-0003 | 2026-11-20 | M2-020 | Direct `./e2e/run-live.sh` invocation failed with permission denied (exit 126). | Retried explicitly with Bash in R-0004. |
| R-0004 | 2026-11-20 | M2-020 | `bash e2e/run-live.sh`: 11 isolated browser tests passed. | Synthetic data; optional S02 owner check disabled; not provider acceptance. |
| R-0005 | 2026-11-20 | M2-020 | Focused EDGE01 forwarding test passed, including authorization and replay status. | Does not replace distributed acceptance. |
| R-0006 | 2026-11-20 | M2-020 | Focused S05 application suite: 12 tests passed, including exact target boundaries and rollback. | Does not establish live provider or browser owner-check acceptance. |
| R-0007 | 2026-11-20 | M2-020 | Completed and committed local objective creation as `8239039`; private approach revision 173 verified. | Broader S05 lifecycle/import and provider acceptance remain open. |
| R-0008 | 2026-11-20 | Checklist setup | Established this initial milestone breakdown, individual distributed cases and inherited-scenario checklist from the linked roadmap and inventories. | Item-level operation/table/requirement reconciliation and future task decomposition remain open. |
| R-0009 | 2026-11-20 | Checklist setup | Verified 235 unique checkbox IDs, all 39 inventory scenario IDs, all 30 distributed-case IDs, and no whitespace errors. | The checklist is a living baseline, not a fully reconciled operation-by-operation inventory. |
| R-0010 | 2026-11-20 | Checklist setup | Committed `d851897` and pushed the checklist and preceding verified objective-creation slice to `origin/main`. | Objective submission is the next selected slice. |
| R-0011 | 2026-11-20 | M2-021a, M2-021b | Clean selected S05/EDGE01 reactor: 58 tests passed, including concurrent retry and forwarding. | UI, browser integration and full slice verification remain open. |
| R-0012 | 2026-11-20 | M2-021c | Clean UX01 suite: 186 tests passed, including separate read/write delegations and CSRF/uncertain-outcome handling. | Isolated browser integration and full slice verification remain open. |
| R-0013 | 2026-11-20 | M2-021d | Focused S05 application suite: 14 tests passed, including constraint-violation rollback and safe retry. | Browser integration and end-to-end slice verification remain open. |
| R-0014 | 2026-11-20 | M2-021c | Focused objective form/client tests: 5 passed after adding provenance details to the review page. | Isolated browser validation remains open. |
| R-0015 | 2026-11-20 | M2-021d | Isolated browser suite: 11 passed, including synthetic creation-to-submission, replay, conflict and denied read/write. | Optional S02 workload-owner check is disabled in this browser stack; no provider acceptance. |
| R-0016 | 2026-11-20 | M2-021, M2-021e | Completed the retry-safe submission slice and updated the status, contract, browser evidence and private technical approach revision 174. | Independent review/activation and imported-objective workflow remain open. |
