# UACRF implementation status and delivery checklist

[Overview](#milestone-and-capability-tracker) · [Work by milestone](#granular-delivery-checklist) · [Inventory reconciliation](#item-by-item-inventory-reconciliation) · [Acceptance cases](#distributed-acceptance-cases) · [Execution log](#execution-log) · [Evidence](#implementation-evidence)

The target is the complete M0-M8 roadmap in
[`implementation-roadmap.md`](implementation-roadmap.md). No milestone is
complete yet. The repository now contains tested slices across several
services, but they do not yet constitute an accepted end-to-end microservices
system.

The [UACRF architecture overview and diagrams](architecture-overview.md)
shows the current services, agents, cloud-provider integrations, and target
infrastructure; dotted connections identify planned or unaccepted paths.

## Milestone and capability tracker

For individual work items, acceptance cases, and the per-run execution log,
see the [granular delivery checklist](#granular-delivery-checklist).

**Status snapshot: 2026-11-20.** This roll-up summarizes the evidence and gaps
recorded in the detailed sections below; it is not a percentage-complete
estimate.

| Milestone | Services and capabilities | Progress |
| --- | --- | --- |
| [M0 — Implementation baseline](#m0--baseline-security-and-release-controls) | Contracts and ownership, Maven/CI, security and dependency controls, Docker images, bounded recovery-action selection | In progress |
| [M1 — First service workflow](#m1--identity-catalog-and-first-browser-workflow) | EDGE01, UX01, S01 identity, S02 catalog, browser customer journeys | In progress |
| [M2 — Evidence and topology](#m2--collection-evidence-objectives-topology-reporting) | S03 connections and ingestion, S04 evidence, shared-schema validation, qualification-policy and gap-waiver governance, S05 objectives, S06 topology and projection | In progress |
| [M3 — Assessment and advisory](#m3--assessment-knowledge-and-advisory) | S07 assessment, S08 knowledge and retrieval, S09 advisory orchestration | In progress |
| [M4 — Policy and authorization](#m4--policy-and-operational-authorization) | S10 policy-bundle proposal, review, and read; policy lifecycle and authorization | Partial |
| [M5 — Execution and recovery validation](#m5--execution-and-recovery-validation) | Bounded action adapter, execution, cancellation, compensation, probes, recovery certification | Not started |
| [M6 — Commercial and operational administration](#m6--commercial-and-operational-administration) | Usage admission, rating, invoicing, payment reconciliation, notifications, evidence export | Not started |
| [M7 — Governed prediction and provider breadth](#m7--prediction-learning-and-provider-breadth) | Model evaluation and learning; AWS, Azure, and Google Cloud capabilities | Not started |
| [M8 — Migration and release acceptance](#m8--migration-release-and-end-to-end-acceptance) | Single-writer cutovers, inherited scenarios, distributed and browser acceptance, deployment/restore/provider/billing evidence | Not started |

<details>
<summary>Milestone scope, evidence and remaining gaps</summary>

- **M0 — Implementation baseline:** Reconcile inherited design allocations against this fresh implementation; record unavailable legacy source or database evidence as unknown. Close remaining threat-model and release-control gaps.
- **M1 — First service workflow:** The live browser suite creates, links, reads back, and transfers ownership of a journey and service; it registers standalone and service-bound workloads through independent S01 grants and owner selection, then reads both persisted records back through UX01 with only `catalog.agent.read`. A separately admitted user holding only `owner.eligible` is denied workload readback by S01 and receives no records. The isolated S03 publisher and collector client-credentials identities resolve to separately admitted SYSTEM principals through EDGE01 and receive only their approved S04/S03 delegations; the collection runner remains disabled. Production deployment identity and end-to-end acceptance remain open. Re-enabling principals remains unavailable pending verification and independent review.
- **M2 — Evidence and topology:** The focused S01/S02/S05/S06/S07 API runtime gate passes with live Neo4j projection and reconciliation. The `m2-live` S03/S04 process test exercises S01's public admission, separate grant-decision, membership and delegation APIs; it also injects a 503 after S04 persists the first event and verifies S03 retries against S04's `DUPLICATE` response. The targeted test passed 1/1, and the full five-test module has now passed after the aggregate-type contract change (5 tests, 0 failures, 0 errors, 0 skips). S04 sequence identity now includes aggregate type; the V8 migration classifies retained S03 events and conservatively preserves ambiguous legacy quarantines. The publisher is admitted through a test-only directory stub and receives only `evidence.inbox.write`; an ungranted `connections.connection.write` request is denied. Production identity and IdP conformance remain unverified. A paginated S03 source-collection outcome report is available through EDGE01. Broader S01 owner/grant conformance, AWS Config and CloudWatch metrics, Azure Resource Graph and Monitor metrics, GCP Cloud Asset Inventory and Monitoring metrics (all providers remain unaccepted), broader freshness and ontology coverage, full live multi-service acceptance, and additional reporting capabilities remain.
- **M3 — Assessment and advisory:** UX01 creates retry-protected S07 draft cases, incidents and learning proposals, reviews and seals drafts, runs retry-protected evaluations, and supports learning review and historical decision reads through EDGE01 with separate globally scoped, context-bound delegations. UX01 also stages retry-protected S08 candidates, browses metadata, independently reviews exact versions, withdraws approved items, creates retry-protected retrievals and browses retrieval history with explicit classification grants. Case creation and sealing do not verify references; skipped receipt verification does not qualify evidence for incident creation; learning proposals and reviews do not establish adoption. Knowledge reviews, metadata and retrievals are not current authorization. Complete connected assessment/knowledge/advisory workflows. S09 now has plan-bound idempotent admission and a lease-fenced outbox foundation for renewal, bounded jittered exponential retries, and active/unexpired-run delivery gating, but no connected run handler, current-context commit guard, authorized provider dispatch, or reconciliation.
- **M4 — Policy and authorization:** UX01 proposes retry-safe policy revisions, browses service-scoped summaries and exact revisions, and submits rejections or signed approvals through EDGE01 with separate S10 write, read and review delegations. A local PEM-key helper can generate approval assertions; production key management, policy evaluation, action authorization, current-state fences, and read-only decision replay remain open. The existing S10 slice is governance metadata, not operational authority.
- **M5 — Execution and recovery validation:** Build and independently verify the local adapter and execution lifecycle; qualify any real provider action separately.
- **M6 — Commercial and operational administration:** Implement and test the commercial and operational workflows, including duplicate/reordered events and interrupted reconciliation.
- **M7 — Governed prediction and provider breadth:** Implement temporal evaluation, uncertainty/calibration and shadow criteria; qualify providers individually. Existing model metadata and provider stubs do not meet this milestone.
- **M8 — Migration and release acceptance:** Complete migration and the release-scope acceptance matrix; retain the required operational evidence.

</details>

**Status meanings:** *In progress* means implementation and tests exist, but
the milestone acceptance criteria remain open. *Partial* means a limited
capability exists while major milestone workflows are not implemented.
*Not started* means no milestone-level implementation is recorded; design
artifacts or preparatory work alone do not count as delivery.

The M2 evidence-backed topology process test now covers the local S01/S02/S04/S06
qualification-to-projection path and replay-conflict denial using synthetic
provider-format data. This does not constitute provider or milestone acceptance.

As implementation advances, update this table with the milestone's services,
progress label, evidence, and remaining gaps. Change a milestone to complete
only when its roadmap and acceptance criteria are met; keep detailed test
results and scope limits in the corresponding sections below.

## Granular delivery checklist

This is the working checklist for the complete M0–M8 application, including
connected browser workflows. Each milestone groups verified work with its
remaining work; a checked historical slice does not close its broader
milestone. An open task may also contain checked substeps. IDs remain stable,
including `HIST` IDs. It complements the
[implementation plan](IMPLEMENTATION_PLAN.md) and
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
  unverified items unchecked. Update this document's milestone tracker and
  implementation evidence, and the private technical approach document,
  when a development slice completes.
- Record synthetic/local boundaries and untested external integrations
  explicitly. A test failure, skipped acceptance, inaccessible dependency or
  unknown outcome is not a pass. Never place credentials or private keys here.
- This log starts with the latest verified slice and this checklist setup.
  Earlier run-by-run history was not reconstructed; prior evidence remains in
  [implementation evidence](#implementation-evidence) and version history.

## M0 — Baseline, security, and release controls

### Verified work

Evidence: [current handoff](#current-handoff) and [fresh-build delivery tracking](#fresh-build-delivery-tracking).

- [x] HIST-001 Establish the Java/Maven build and dependency policy.
- [x] HIST-002 Add non-root service images, image smoke checks and local build-context exclusions.
- [x] HIST-003 Add initial SBOM and security-scanning configuration; release enforcement remains M0-009–M0-010.

- [x] M0-019 Select the first bounded local recovery action and its acceptance bounds. Evidence: [first recovery action](FIRST_RECOVERY_ACTION.md); adapter implementation remains M5 work.

### Remaining work

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
- [ ] M0-020 Reconcile the remaining historical implementation slices and executions against status evidence and version history; add verified granular items without inventing results.
- [ ] M0-021 Add and reconcile any implemented operations and requirements discovered after the inherited inventories were produced.

## M1 — Identity, catalog, and first browser workflow

### Verified work

Evidence: [current handoff](#current-handoff) and [independent grant authority](#independent-grant-authority).

- [x] HIST-004 Implement S01 tenant, provider, principal and membership persistence.
- [x] HIST-005 Implement expiring, narrowed S01 capability delegations.
- [x] HIST-006 Implement independently approved grants and membership creation.
- [x] HIST-007 Implement trusted-directory principal admission without automatic grants.
- [x] HIST-008 Implement principal disable and membership revocation with audit and retry handling.
- [x] HIST-009 Implement S02 service, journey and agent-workload records.
- [x] HIST-010 Implement S02 ownership transfer and workload-version records.
- [x] HIST-011 Implement signed S02 workload receipts and local verification.
- [x] HIST-012 Implement initial fixed-owner EDGE01 routes and authorization forwarding.
- [x] HIST-013 Implement browser OIDC sign-in with PKCE, context selection and local logout.
- [x] HIST-014 Connect principal admission, independent grant and membership forms.
- [x] HIST-015 Connect synthetic journey, service and workload creation and readback.
- [x] HIST-016 Connect synthetic journey and service ownership transfer.
- [x] HIST-045 Track S01 capability and session expiry horizons, membership digests and scoped generations. Evidence: [identity handoff](#current-handoff); not an atomic fence.
- [x] HIST-046 Consume context-bound independent grant decisions atomically with membership creation. Evidence: [grant authority](#independent-grant-authority).
- [x] HIST-047 Test signed delegation key, issuer, audience and lifetime validation. Evidence: [identity handoff](#current-handoff).
- [x] HIST-048 Test S02 tenant isolation, registration, idempotency, links and snapshot behavior. Evidence: [identity handoff](#current-handoff).

- [x] M1-012 Exercise synthetic browser journey/service creation, linking, ownership transfer and workload readback. Evidence: [M1 status](#milestone-and-capability-tracker); production acceptance remains open.

### Remaining work

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

## M2 — Collection, evidence, objectives, topology, reporting

### S03 connections and ingestion

#### Verified work

Evidence and limits: [S03 status](#m2--s03-connections-in-progress). Provider paths are bounded local implementations, not live-provider acceptance.

- [x] HIST-017 Implement S03 connection and source-binding persistence.
- [x] HIST-018 Implement S03 observation intake, replay/conflict handling and quarantine.
- [x] HIST-019 Implement leased collection, checkpoint and bounded runner foundations.
- [x] HIST-020 Implement opt-in S03 outbox publishing and acknowledgement fencing.
- [x] HIST-021 Add bounded AWS, Azure and GCP collection paths; provider qualification remains M2-011–M2-013.
- [x] HIST-049 Compare S03 duplicate observations using original nanosecond precision. Evidence: [observation replay](#s03-observation-replay-integrity).
- [x] HIST-050 Implement bounded S03 request timeouts and refresh expiring collection credentials. Evidence: [S03 status](#m2--s03-connections-in-progress).
- [x] HIST-051 Renew and fence S03 collection leases during bounded cycles. Evidence: [S03 status](#m2--s03-connections-in-progress).
- [x] HIST-052 Match S04 acknowledgement event IDs before S03 outbox disposition. Evidence: [S03 status](#m2--s03-connections-in-progress).
- [x] HIST-053 Implement bounded AWS Config and CloudWatch collection paths. Evidence: [S03 provider details](#s03-leased-collection-and-cloud-provider-inventory); live conformance is open.
- [x] HIST-054 Implement bounded Azure Resource Graph and Monitor collection paths. Evidence: [S03 provider details](#s03-leased-collection-and-cloud-provider-inventory); live conformance is open.
- [x] HIST-055 Implement bounded GCP Asset Inventory and Monitoring collection paths. Evidence: [S03 provider details](#s03-leased-collection-and-cloud-provider-inventory); live conformance is open.
- [x] HIST-056 Import and browse synthetic provider-format observations with replay and partial-result disclosure. Evidence: [inventory projection](#s03-inventory-observation-projection).
- [x] HIST-057 Exercise S03 delivery to S04 with real test delegations and retry after a lost acknowledgement. Evidence: [S03 status](#m2--s03-connections-in-progress); test identities only.

#### Remaining work

The provider tasks below remain open beyond the bounded paths listed above;
independent provider qualification is tracked separately in M2-011–M2-013.

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

#### Verified work

Evidence and limits: [S04 status](#m2--s04-evidence-qualification-and-waiver-workflows).

- [x] HIST-022 Implement S04 evidence inbox, sequence/digest checks and quarantine.
- [x] HIST-023 Implement S04 qualification review, policy and waiver records.
- [x] HIST-024 Implement S04 measurement receipts and cutoff checks.
- [x] HIST-025 Implement S04 aggregate-type identity and legacy-quarantine migration.
- [x] HIST-058 Verify distinct S03 aggregate types sharing an ID do not collide at S04. Evidence: [S04 status](#m2--s04-evidence-qualification-and-waiver-workflows).
- [x] HIST-059 Record S04 evidence byte length and digest and serialize replay/conflict admission. Evidence: [S04 status](#m2--s04-evidence-qualification-and-waiver-workflows).
- [x] HIST-060 Block independent qualification of synthetic SAMPLE_LOCAL evidence. Evidence: [S04 status](#m2--s04-evidence-qualification-and-waiver-workflows).
- [x] HIST-061 Validate provider evidence envelopes and schemas before qualification. Evidence: [S04 status](#m2--s04-evidence-qualification-and-waiver-workflows).
- [x] HIST-062 Retain versioned qualification policies and independently reviewed gap waivers. Evidence: [S04 status](#m2--s04-evidence-qualification-and-waiver-workflows).
- [x] HIST-063 Enforce evidence qualification and measurement cutoff checks in S04 receipts. Evidence: [S04 status](#m2--s04-evidence-qualification-and-waiver-workflows); receipts are point-in-time.
- [x] HIST-064 Browse S04 evidence metadata through separately scoped browser access. Evidence: [S04 status](#m2--s04-evidence-qualification-and-waiver-workflows).

#### Remaining work

- [ ] M2-015 Connect source observations to qualification and retained provenance in the browser.
- [ ] M2-016 Verify current-source failure, expired evidence, conflicting events and explicit unavailable outcomes.
- [ ] M2-017 Complete broker-backed outbox/inbox processing with crash recovery and duplicate/conflict handling.
- [ ] M2-018 Verify artifact retention, access, integrity, unavailable bytes and controlled disclosure.
- [ ] M2-019 Verify qualification-policy and gap-waiver governance through the intended end-to-end workflow.

### S05 objectives

#### Verified work

Evidence and limits: [S05 status](#m2--s05-objectives-first-slice).

- [x] HIST-026 Implement S05 objective proposal, independent decision, activation and supersession APIs.
- [x] HIST-027 Implement S05 organization constraints and active-objective receipts.
- [x] HIST-028 Implement retry-safe S05 import-preview admission; source resolution remains M2-025.
- [x] HIST-029 Implement opt-in S05 reviewer and workload-owner authority checks; these are not atomic cross-service fences.
- [x] HIST-065 Enforce S05 activation constraints and supersede the prior active workload/metric objective. Evidence: [S05 status](#m2--s05-objectives-first-slice).
- [x] HIST-066 Issue S05 active-objective set receipts with retained digests. Evidence: [S05 status](#m2--s05-objectives-first-slice); S05 outbox publishing remains open.
- [x] HIST-067 Verify S05 workload ownership with optional signed S02 receipts. Evidence: [S05 status](#m2--s05-objectives-first-slice); the check is point-in-time.

- [x] M2-020 Create retry-safe local objective drafts through UX01, EDGE01 and S05 with separate global write authority. Evidence: commit `8239039`; 182 web tests, 56 selected reactor tests and 11 isolated browser tests passed.
- [x] M2-021 Connect objective submission through the browser with expected-revision and separate authorization checks. Evidence: R-0011–R-0015 below; synthetic/local scope only.
  - [x] M2-021a Add atomic, retry-safe objective submission with preserved revision and constraint checks.
  - [x] M2-021b Forward submission and idempotency key through EDGE01.
  - [x] M2-021c Connect a semantic, context-bound UX01 submission form with separate read/write delegations; full accessibility review remains M8-013.
  - [x] M2-021d Verify creation-to-submission, replay, conflict, stale revision, denial and database rollback.
  - [x] M2-021e Update contracts, status and private approach documentation after verified completion.

#### Remaining work

- [ ] M2-022 Connect independent objective approval/rejection through the browser with review-history disclosure. Current-decision display is complete; full historical decision disclosure remains open.
  - [x] M2-022a Add retry-safe S05 decisions bound to objective ID, revision, digest, decision and exact reason, preserving independent reviewer checks. Evidence: R-0028.
  - [x] M2-022b Forward decision requests and idempotency keys through EDGE01. Evidence: R-0028.
  - [x] M2-022c Connect the review form and recorded decision display with separate read/review grants. Evidence: R-0025 and R-0029.
  - [x] M2-022d Verify concurrent retries, conflicts, stale revisions, independent-review denial and browser authorization. Evidence: R-0028 and R-0029; synthetic/local scope only.
  - [x] M2-022e Update contracts, status and private approach document after verification. Evidence: R-0030.
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

#### Verified work

Evidence and limits: [S06 status](#m2--s06-topology-first-slice).

- [x] HIST-030 Implement S06 graph authority, immutable revisions and relationship checks.
- [x] HIST-031 Implement S06 Neo4j projection and reconciliation checks.
- [x] HIST-032 Implement S06 dependency proposals, review, selection and difference classification.
- [x] HIST-068 Reconcile S06 graph projections against retained graph digests. Evidence: [S06 status](#m2--s06-topology-first-slice).
- [x] HIST-069 Bind S06 dependency selection to retained graph and owner receipts. Evidence: [S06 status](#m2--s06-topology-first-slice); provider acceptance remains open.
- [x] HIST-070 Verify evidence replay conflicts make affected S06 projected context ineligible. Evidence: [S06 status](#m2--s06-topology-first-slice).

#### Remaining work

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

### S07 assessment

#### Verified work

Evidence and limits: [S07 status](#m3--s07-assessment-first-slice).

- [x] HIST-033 Implement S07 assessment cases and deterministic evaluation foundations.
- [x] HIST-034 Implement S07 incident and learning proposal/review records; adoption remains M3-004.
- [x] HIST-035 Implement S07 model-governance and adaptation-draft records; model promotion remains M7 work.
- [x] HIST-071 Browse S07 cases, evaluation history and investigation-only recommendations. Evidence: [S07 status](#m3--s07-assessment-first-slice).
- [x] HIST-072 Submit retry-safe incident creation requests through the browser and verify skipped evidence is rejected. Evidence: [S07 status](#m3--s07-assessment-first-slice); successful qualified browser creation remains open.
- [x] HIST-073 Browse S07 incident and learning records with separate read grants. Evidence: [S07 status](#m3--s07-assessment-first-slice).
- [x] HIST-074 Independently review learning proposals and display retained review decisions. Evidence: [S07 status](#m3--s07-assessment-first-slice); review is not adoption.
- [x] HIST-075 Force insufficient evidence when enabled S07 owner-receipt checks fail. Evidence: [S07 status](#m3--s07-assessment-first-slice).
- [x] HIST-076 Block S07 model promotion without the required model-specific evaluation. Evidence: [S07 status](#m3--s07-assessment-first-slice).

- [x] M3-023 Create and read retry-safe synthetic assessment case drafts through UX01, EDGE01 and S07. Evidence: [S07 status](#m3--s07-assessment-first-slice); reference verification remains M3-002.
- [x] M3-024 Review and seal a synthetic draft with separate read/write grants. Evidence: [S07 status](#m3--s07-assessment-first-slice); sealing is not idempotent.
- [x] M3-025 Run and replay an empty synthetic assessment evaluation through the browser. Evidence: [S07 status](#m3--s07-assessment-first-slice); verified source-owner evidence remains M3-001.
- [x] M3-026 Submit and replay a learning proposal for a directly inserted synthetic incident. Evidence: [S07 status](#m3--s07-assessment-first-slice); complete qualified incident-to-adoption workflow remains M3-004.

#### Remaining work

- [ ] M3-001 Complete connected deterministic evaluation and recommendation workflows with source-owner receipts.
- [ ] M3-002 Verify case references and evidence eligibility before any operation that requires them.
- [ ] M3-003 Expose retained recommendations, uncertainty, source disclosure and next valid review steps in the browser.
- [ ] M3-004 Complete incident-to-learning proposal, independent review and governed adoption workflow.
- [ ] M3-005 Verify unavailable or modified source artifacts fail closed and remain visible.

### S08 knowledge

#### Verified work

Evidence and limits: [S08 status](#m3--s08-knowledge-and-retrieval-conservative-first-slice).

- [x] HIST-036 Implement S08 retry-safe knowledge staging and version-bound review.
- [x] HIST-037 Implement S08 withdrawal and retrieval concealment.
- [x] HIST-038 Implement S08 exact-term retrieval, classification filtering and history readback.
- [x] HIST-077 Browse S08 knowledge metadata with explicit classification grants. Evidence: [S08 status](#m3--s08-knowledge-and-retrieval-conservative-first-slice).
- [x] HIST-078 Stage and replay synthetic knowledge candidates through UX01, EDGE01 and S08. Evidence: [S08 status](#m3--s08-knowledge-and-retrieval-conservative-first-slice).
- [x] HIST-079 Review exact candidate versions and reject stale or self-review requests. Evidence: [S08 status](#m3--s08-knowledge-and-retrieval-conservative-first-slice).
- [x] HIST-080 Withdraw an exact approved knowledge version and conceal withdrawn excerpts. Evidence: [S08 status](#m3--s08-knowledge-and-retrieval-conservative-first-slice).
- [x] HIST-081 Create and replay exact-term synthetic knowledge retrievals with classification clearance. Evidence: [S08 status](#m3--s08-knowledge-and-retrieval-conservative-first-slice).
- [x] HIST-082 Read retained S08 retrievals and paginated classification-filtered retrieval history. Evidence: [S08 status](#m3--s08-knowledge-and-retrieval-conservative-first-slice).
- [x] HIST-083 Verify S08 knowledge source and excerpt digests and prevent retained-record substitution. Evidence: [S08 status](#m3--s08-knowledge-and-retrieval-conservative-first-slice).

#### Remaining work

- [ ] M3-006 Complete knowledge staging, independent approval, retrieval and withdrawal as a connected workflow with current disclosure checks.
- [ ] M3-007 Verify approved knowledge cannot be disclosed after relevant access or source eligibility changes.

### S09 advisory

#### Verified work

Evidence and limits: [S09 status](#m3--s09-advisory-gateway-foundation).

- [x] HIST-039 Implement S09 plan-bound idempotent admission and run/deadline fences.
- [x] HIST-040 Implement S09 lease-fenced outbox delivery, bounded retry and cancellation/expiry handling.
- [x] HIST-041 Implement S09 budget and dispatch-journal foundations; connected admission and provider dispatch remain disabled.
- [x] HIST-084 Test S09 planned-run idempotency, immutable dispatch plans and rejection of unplanned legacy intents. Evidence: [S09 status](#m3--s09-advisory-gateway-foundation).
- [x] HIST-085 Test S09 outbox claim exclusivity, lease renewal, token fencing and atomic acknowledgement. Evidence: [S09 status](#m3--s09-advisory-gateway-foundation).
- [x] HIST-086 Test bounded, jittered S09 retry scheduling and cancellation/deadline delivery gating. Evidence: [S09 status](#m3--s09-advisory-gateway-foundation).
- [x] HIST-087 Test S09 requester capability, expiry and scope snapshots at journal boundaries. Evidence: [S09 status](#m3--s09-advisory-gateway-foundation); not transaction-fenced.
- [x] HIST-088 Exercise S09 provider adapter contracts with fake transports. Evidence: [S09 status](#m3--s09-advisory-gateway-foundation); adapters remain unwired and unqualified.

#### Remaining work

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

### Verified work

Evidence and limits: [S10 status](#s10-policy-bundle-proposal-review-and-read).

- [x] HIST-042 Implement S10 immutable policy revisions and scoped read/write grants.
- [x] HIST-043 Implement S10 independent review, signed approval verification and quorum decisions.
- [x] HIST-044 Add a local policy-assertion signer; production key management remains M4-013.
- [x] HIST-089 Test S10 trust rollback and contradictory trust-revision denial. Evidence: [S10 status](#s10-policy-bundle-proposal-review-and-read).
- [x] HIST-090 Test S10 concurrent replay and competing revisions on one service instance. Evidence: [S10 status](#s10-policy-bundle-proposal-review-and-read).

- [x] M4-001 Connect policy proposal, revision browsing, independent rejection and externally signed approval. Evidence: [S10 status](#s10-policy-bundle-proposal-review-and-read); governance metadata only.
  - [x] M4-001a Propose and replay synthetic policy revisions with separate service-scoped write authority. Evidence: [S10 status](#s10-policy-bundle-proposal-review-and-read).
  - [x] M4-001b Browse immutable revisions and navigate to predecessor and current versions. Evidence: [S10 status](#s10-policy-bundle-proposal-review-and-read).
  - [x] M4-001c Submit an independent rejection and verify replay, conflicts and self-review denial. Evidence: [S10 status](#s10-policy-bundle-proposal-review-and-read).
  - [x] M4-001d Submit a signed approval using disposable browser-test trust material. Evidence: [S10 status](#s10-policy-bundle-proposal-review-and-read); production signing remains M4-013.

### Remaining work

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

## Item-by-item inventory reconciliation

Each unchecked item below means its allocation still needs reconciliation with
the current implementation, evidence and remaining work; it does not mean
the underlying functionality is absent. Check only after recording the result
and evidence. These are source allocations, not live database findings or
proof that an inherited system exists. New routes or requirements discovered
during implementation must also be added and reconciled.

### Allocated operations

Source: [API ownership inventory](../design/ms01/inventories/API_OWNERSHIP.csv).

- [ ] INV-OP-001 Reconcile `GET /v1/actions/proposals` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-002 Reconcile `GET /v1/actions/proposals/{id}` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-003 Reconcile `GET /v1/advisory-assignments` with owner S09 and implementation/acceptance evidence.
- [ ] INV-OP-004 Reconcile `GET /v1/agent-cases/{id}` with owner S09 and implementation/acceptance evidence.
- [ ] INV-OP-005 Reconcile `GET /v1/agent-workload-executions/{id}` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-006 Reconcile `GET /v1/agent-workloads` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-007 Reconcile `GET /v1/agent-workloads/{id}` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-008 Reconcile `GET /v1/analysis-cases` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-009 Reconcile `GET /v1/analysis-cases/{id}` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-010 Reconcile `GET /v1/authorization-policy-bundles` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-011 Reconcile `GET /v1/authorization-policy-bundles/{id}` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-012 Reconcile `GET /v1/commands/{id}` with owner EDGE01 and implementation/acceptance evidence.
- [ ] INV-OP-013 Reconcile `GET /v1/connections/{id}/collection-policy` with owner S03 and implementation/acceptance evidence.
- [ ] INV-OP-014 Reconcile `GET /v1/customer-journeys` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-015 Reconcile `GET /v1/customer-journeys/{id}` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-016 Reconcile `GET /v1/decision-envelopes/{id}` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-017 Reconcile `GET /v1/economics/customers/{id}` with owner S13 and implementation/acceptance evidence.
- [ ] INV-OP-018 Reconcile `GET /v1/evaluation-rule-packs` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-019 Reconcile `GET /v1/evaluation-rule-packs/{id}` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-020 Reconcile `GET /v1/evaluations/{id}` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-021 Reconcile `GET /v1/evidence/bundles/{id}` with owner S14 and implementation/acceptance evidence.
- [ ] INV-OP-022 Reconcile `GET /v1/evidence/events` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-023 Reconcile `GET /v1/evidence/events/{id}` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-024 Reconcile `GET /v1/evidence/events/{id}/qualification-context` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-025 Reconcile `GET /v1/evidence/qualifications/{id}` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-026 Reconcile `GET /v1/graphs/revisions/{revision}` with owner S06 and implementation/acceptance evidence.
- [ ] INV-OP-027 Reconcile `GET /v1/graphs/revisions/{revision}/dependencies` with owner S06 and implementation/acceptance evidence.
- [ ] INV-OP-028 Reconcile `GET /v1/graphs/revisions/{revision}/impact` with owner S06 and implementation/acceptance evidence.
- [ ] INV-OP-029 Reconcile `GET /v1/graphs/revisions/{revision}/recovery-independence` with owner S06 and implementation/acceptance evidence.
- [ ] INV-OP-030 Reconcile `GET /v1/identity-providers` with owner S01 and implementation/acceptance evidence.
- [ ] INV-OP-031 Reconcile `GET /v1/knowledge-items` with owner S08 and implementation/acceptance evidence.
- [ ] INV-OP-032 Reconcile `GET /v1/me/access` with owner S01 and implementation/acceptance evidence.
- [ ] INV-OP-033 Reconcile `GET /v1/memberships` with owner S01 and implementation/acceptance evidence.
- [ ] INV-OP-034 Reconcile `GET /v1/objective-imports/{id}` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-035 Reconcile `GET /v1/objective-imports/{id}/history` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-036 Reconcile `GET /v1/objective-templates` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-037 Reconcile `GET /v1/orchestration-runs/{id}` with owner S09 and implementation/acceptance evidence.
- [ ] INV-OP-038 Reconcile `GET /v1/owner-candidates` with owner S01 and implementation/acceptance evidence.
- [ ] INV-OP-039 Reconcile `GET /v1/path-certifications/{id}` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-040 Reconcile `GET /v1/principals` with owner S01 and implementation/acceptance evidence.
- [ ] INV-OP-041 Reconcile `GET /v1/qualification-policies` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-042 Reconcile `GET /v1/qualification-policies/{id}` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-043 Reconcile `GET /v1/qualification-policies/{id}/history` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-044 Reconcile `GET /v1/qualification-waivers` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-045 Reconcile `GET /v1/qualification-waivers/{id}` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-046 Reconcile `GET /v1/recovery-assessments/{id}` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-047 Reconcile `GET /v1/recovery-paths/{id}` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-048 Reconcile `GET /v1/retrieval-manifests/{id}` with owner S08 and implementation/acceptance evidence.
- [ ] INV-OP-049 Reconcile `GET /v1/services` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-050 Reconcile `GET /v1/services/{id}` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-051 Reconcile `GET /v1/services/{id}/qualified-state` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-052 Reconcile `GET /v1/shared-resources` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-053 Reconcile `GET /v1/shared-resources/{id}` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-054 Reconcile `GET /v1/states/{service}` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-055 Reconcile `GET /v1/validation-plans/{id}` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-056 Reconcile `GET /v1/workloads/{id}/objectives` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-057 Reconcile `POST /v1/actions/proposals` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-058 Reconcile `POST /v1/advisory-assignments/{id}:claim` with owner S09 and implementation/acceptance evidence.
- [ ] INV-OP-059 Reconcile `POST /v1/advisory-outputs:inline` with owner S09 and implementation/acceptance evidence.
- [ ] INV-OP-060 Reconcile `POST /v1/agent-arbitrations` with owner S09 and implementation/acceptance evidence.
- [ ] INV-OP-061 Reconcile `POST /v1/agent-cases` with owner S09 and implementation/acceptance evidence.
- [ ] INV-OP-062 Reconcile `POST /v1/agent-outputs` with owner S09 and implementation/acceptance evidence.
- [ ] INV-OP-063 Reconcile `POST /v1/agent-workload-executions/{id}/checkpoints` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-064 Reconcile `POST /v1/agent-workload-executions/{id}/evaluations` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-065 Reconcile `POST /v1/agent-workload-executions/{id}/events` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-066 Reconcile `POST /v1/agent-workload-executions/{id}:control` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-067 Reconcile `POST /v1/agent-workload-executions/{id}:resume` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-068 Reconcile `POST /v1/agent-workloads` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-069 Reconcile `POST /v1/agent-workloads/{id}/executions` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-070 Reconcile `POST /v1/agent-workloads/{id}/versions` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-071 Reconcile `POST /v1/agent-workloads/{id}:activate` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-072 Reconcile `POST /v1/analysis-cases` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-073 Reconcile `POST /v1/approvals/{id}:decide` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-074 Reconcile `POST /v1/authorization-policy-bundles` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-075 Reconcile `POST /v1/authorization-policy-bundles/{id}:decide` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-076 Reconcile `POST /v1/authorizations` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-077 Reconcile `POST /v1/authorizations/{id}:revalidate` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-078 Reconcile `POST /v1/claims` with owner S13 and implementation/acceptance evidence.
- [ ] INV-OP-079 Reconcile `POST /v1/connections/{id}:reconcile` with owner S03 and implementation/acceptance evidence.
- [ ] INV-OP-080 Reconcile `POST /v1/customer-journeys` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-081 Reconcile `POST /v1/customer-journeys/{id}/capabilities` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-082 Reconcile `POST /v1/customer-journeys/{id}/capabilities/{link_id}:retire` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-083 Reconcile `POST /v1/customer-journeys/{id}/capabilities/{link_id}:update` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-084 Reconcile `POST /v1/customer-journeys/{id}/steps` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-085 Reconcile `POST /v1/customers/qualifications` with owner S13 and implementation/acceptance evidence.
- [ ] INV-OP-086 Reconcile `POST /v1/decision-envelopes` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-087 Reconcile `POST /v1/decision-replays` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-088 Reconcile `POST /v1/dependencies` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-089 Reconcile `POST /v1/evaluation-rule-packs` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-090 Reconcile `POST /v1/evaluation-rule-packs/{id}:decide` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-091 Reconcile `POST /v1/evaluations` with owner S07 and implementation/acceptance evidence.
- [ ] INV-OP-092 Reconcile `POST /v1/evidence/events` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-093 Reconcile `POST /v1/evidence/events:inline` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-094 Reconcile `POST /v1/evidence/qualifications` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-095 Reconcile `POST /v1/experiments` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-096 Reconcile `POST /v1/gates/{gate}:decide` with owner S13 and implementation/acceptance evidence.
- [ ] INV-OP-097 Reconcile `POST /v1/graphs/differences:classify` with owner S06 and implementation/acceptance evidence.
- [ ] INV-OP-098 Reconcile `POST /v1/graphs/subgraphs:select` with owner S06 and implementation/acceptance evidence.
- [ ] INV-OP-099 Reconcile `POST /v1/impact:calculate` with owner S06 and implementation/acceptance evidence.
- [ ] INV-OP-100 Reconcile `POST /v1/knowledge-items` with owner S08 and implementation/acceptance evidence.
- [ ] INV-OP-101 Reconcile `POST /v1/knowledge-items/{id}/versions/{version}:review` with owner S08 and implementation/acceptance evidence.
- [ ] INV-OP-102 Reconcile `POST /v1/knowledge-search` with owner S08 and implementation/acceptance evidence.
- [ ] INV-OP-103 Reconcile `POST /v1/memberships` with owner S01 and implementation/acceptance evidence.
- [ ] INV-OP-104 Reconcile `POST /v1/memberships/{id}:revoke` with owner S01 and implementation/acceptance evidence.
- [ ] INV-OP-105 Reconcile `POST /v1/metering/events` with owner S13 and implementation/acceptance evidence.
- [ ] INV-OP-106 Reconcile `POST /v1/objective-imports/{id}:propose` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-107 Reconcile `POST /v1/objective-imports/{id}:resolve` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-108 Reconcile `POST /v1/objective-imports:preview` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-109 Reconcile `POST /v1/objective-revisions/{id}:activate` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-110 Reconcile `POST /v1/objective-revisions/{id}:decide` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-111 Reconcile `POST /v1/objective-revisions/{id}:submit` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-112 Reconcile `POST /v1/objective-templates` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-113 Reconcile `POST /v1/onboarding/{id}:transition` with owner S03 and implementation/acceptance evidence.
- [ ] INV-OP-114 Reconcile `POST /v1/orchestration-runs` with owner S09 and implementation/acceptance evidence.
- [ ] INV-OP-115 Reconcile `POST /v1/orchestration-runs/{id}:cancel` with owner S09 and implementation/acceptance evidence.
- [ ] INV-OP-116 Reconcile `POST /v1/ownership:transfer` with owner S01 and implementation/acceptance evidence.
- [ ] INV-OP-117 Reconcile `POST /v1/pilots` with owner S13 and implementation/acceptance evidence.
- [ ] INV-OP-118 Reconcile `POST /v1/policies:evaluate` with owner S10 and implementation/acceptance evidence.
- [ ] INV-OP-119 Reconcile `POST /v1/principals` with owner S01 and implementation/acceptance evidence.
- [ ] INV-OP-120 Reconcile `POST /v1/principals/{id}:set-status` with owner S01 and implementation/acceptance evidence.
- [ ] INV-OP-121 Reconcile `POST /v1/provider-actions:execute` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-122 Reconcile `POST /v1/provider-plans:translate` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-123 Reconcile `POST /v1/qualification-policies` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-124 Reconcile `POST /v1/qualification-policies/{id}:decide` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-125 Reconcile `POST /v1/qualification-waivers` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-126 Reconcile `POST /v1/qualification-waivers/{id}:decide` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-127 Reconcile `POST /v1/qualification-waivers/{id}:revoke` with owner S04 and implementation/acceptance evidence.
- [ ] INV-OP-128 Reconcile `POST /v1/reconciliations` with owner S06 and implementation/acceptance evidence.
- [ ] INV-OP-129 Reconcile `POST /v1/recovery-assessments` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-130 Reconcile `POST /v1/recovery-assessments/{id}:reassess` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-131 Reconcile `POST /v1/recovery-paths:certify` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-132 Reconcile `POST /v1/services` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-133 Reconcile `POST /v1/shared-resources` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-134 Reconcile `POST /v1/shared-resources/{id}/consumers` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-135 Reconcile `POST /v1/shared-resources/{id}/consumers/{link_id}:retire` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-136 Reconcile `POST /v1/shared-resources/{id}/consumers/{link_id}:update` with owner S02 and implementation/acceptance evidence.
- [ ] INV-OP-137 Reconcile `POST /v1/validation-plans` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-138 Reconcile `POST /v1/validations` with owner S12 and implementation/acceptance evidence.
- [ ] INV-OP-139 Reconcile `POST /v1/value-reviews` with owner S13 and implementation/acceptance evidence.
- [ ] INV-OP-140 Reconcile `POST /v1/workflows/{id}:compensate` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-141 Reconcile `POST /v1/workflows/{id}:execute` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-142 Reconcile `POST /v1/workflows/{id}:validate` with owner S11 and implementation/acceptance evidence.
- [ ] INV-OP-143 Reconcile `POST /v1/workloads/{id}/objectives` with owner S05 and implementation/acceptance evidence.
- [ ] INV-OP-144 Reconcile `PUT /v1/connections/{id}/collection-policy` with owner S03 and implementation/acceptance evidence.

### Allocated data

Source: [data ownership inventory](../design/ms01/inventories/DATA_OWNERSHIP.csv).

- [ ] INV-DATA-001 Reconcile `uacrf.agent_assignment` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-002 Reconcile `uacrf.agent_dissent` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-003 Reconcile `uacrf.agent_output` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-004 Reconcile `uacrf.analysis_case` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-005 Reconcile `uacrf.approval` with owner S10 and migration/implementation evidence.
- [ ] INV-DATA-006 Reconcile `uacrf.arbitration` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-007 Reconcile `uacrf.audit_event` with owner PER_SERVICE and migration/implementation evidence.
- [ ] INV-DATA-008 Reconcile `uacrf.authorization` with owner S10 and migration/implementation evidence.
- [ ] INV-DATA-009 Reconcile `uacrf.budget_reservation` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-010 Reconcile `uacrf.capability_asset` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-011 Reconcile `uacrf.claim` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-012 Reconcile `uacrf.command` with owner PER_SERVICE and migration/implementation evidence.
- [ ] INV-DATA-013 Reconcile `uacrf.connection` with owner S03 and migration/implementation evidence.
- [ ] INV-DATA-014 Reconcile `uacrf.customer` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-015 Reconcile `uacrf.customer_qualification` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-016 Reconcile `uacrf.decision_envelope` with owner S10 and migration/implementation evidence.
- [ ] INV-DATA-017 Reconcile `uacrf.decision_replay` with owner S10 and migration/implementation evidence.
- [ ] INV-DATA-018 Reconcile `uacrf.discovery_run` with owner S03 and migration/implementation evidence.
- [ ] INV-DATA-019 Reconcile `uacrf.environment` with owner S01 and migration/implementation evidence.
- [ ] INV-DATA-020 Reconcile `uacrf.evaluation` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-021 Reconcile `uacrf.evidence_bundle` with owner S14 and migration/implementation evidence.
- [ ] INV-DATA-022 Reconcile `uacrf.evidence_event` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-023 Reconcile `uacrf.evidence_qualification` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-024 Reconcile `uacrf.execution_attempt` with owner S11 and migration/implementation evidence.
- [ ] INV-DATA-025 Reconcile `uacrf.experiment` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-026 Reconcile `uacrf.gate_decision` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-027 Reconcile `uacrf.graph_difference` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-028 Reconcile `uacrf.graph_edge` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-029 Reconcile `uacrf.graph_endpoint_role` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-030 Reconcile `uacrf.graph_node` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-031 Reconcile `uacrf.graph_revision` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-032 Reconcile `uacrf.impact_result` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-033 Reconcile `uacrf.journey` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-034 Reconcile `uacrf.journey_capability` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-035 Reconcile `uacrf.journey_step` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-036 Reconcile `uacrf.knowledge_item` with owner S08 and migration/implementation evidence.
- [ ] INV-DATA-037 Reconcile `uacrf.knowledge_review` with owner S08 and migration/implementation evidence.
- [ ] INV-DATA-038 Reconcile `uacrf.knowledge_version` with owner S08 and migration/implementation evidence.
- [ ] INV-DATA-039 Reconcile `uacrf.membership` with owner S01 and migration/implementation evidence.
- [ ] INV-DATA-040 Reconcile `uacrf.metering_event` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-041 Reconcile `uacrf.notification` with owner S14 and migration/implementation evidence.
- [ ] INV-DATA-042 Reconcile `uacrf.onboarding` with owner S03 and migration/implementation evidence.
- [ ] INV-DATA-043 Reconcile `uacrf.orchestration_run` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-044 Reconcile `uacrf.outbox` with owner PER_SERVICE and migration/implementation evidence.
- [ ] INV-DATA-045 Reconcile `uacrf.path_certification` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-046 Reconcile `uacrf.pilot` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-047 Reconcile `uacrf.policy_decision` with owner S10 and migration/implementation evidence.
- [ ] INV-DATA-048 Reconcile `uacrf.policy_version` with owner S10 and migration/implementation evidence.
- [ ] INV-DATA-049 Reconcile `uacrf.principal` with owner S01 and migration/implementation evidence.
- [ ] INV-DATA-050 Reconcile `uacrf.proposal` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-051 Reconcile `uacrf.provider_plan` with owner S11 and migration/implementation evidence.
- [ ] INV-DATA-052 Reconcile `uacrf.reconciliation` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-053 Reconcile `uacrf.recovery_path` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-054 Reconcile `uacrf.resource` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-055 Reconcile `uacrf.retrieval_citation` with owner S08 and migration/implementation evidence.
- [ ] INV-DATA-056 Reconcile `uacrf.retrieval_manifest` with owner S08 and migration/implementation evidence.
- [ ] INV-DATA-057 Reconcile `uacrf.saved_view` with owner UX01 and migration/implementation evidence.
- [ ] INV-DATA-058 Reconcile `uacrf.schema_migration` with owner PER_SERVICE and migration/implementation evidence.
- [ ] INV-DATA-059 Reconcile `uacrf.service` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-060 Reconcile `uacrf.service_resource` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-061 Reconcile `uacrf.shared_asset` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-062 Reconcile `uacrf.subgraph_selection` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-063 Reconcile `uacrf.ui_preference` with owner UX01 and migration/implementation evidence.
- [ ] INV-DATA-064 Reconcile `uacrf.validation_plan` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-065 Reconcile `uacrf.validation_result` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-066 Reconcile `uacrf.value_review` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-067 Reconcile `uacrf.workflow` with owner S11 and migration/implementation evidence.
- [ ] INV-DATA-068 Reconcile `uacrf.workload_checkpoint` with owner S11 and migration/implementation evidence.
- [ ] INV-DATA-069 Reconcile `uacrf.workload_dependency` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-070 Reconcile `uacrf.workload_evaluation` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-071 Reconcile `uacrf.workload_event` with owner S11 and migration/implementation evidence.
- [ ] INV-DATA-072 Reconcile `uacrf.workload_execution` with owner S11 and migration/implementation evidence.
- [ ] INV-DATA-073 Reconcile `uacrf.workload_profile` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-074 Reconcile `uacrf.workload_recovery` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-075 Reconcile `uacrf.workload_version` with owner S02 and migration/implementation evidence.
- [ ] INV-DATA-076 Reconcile `uacrf_app.advisory_arbitration` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-077 Reconcile `uacrf_app.advisory_assignment` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-078 Reconcile `uacrf_app.advisory_delivery` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-079 Reconcile `uacrf_app.advisory_event` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-080 Reconcile `uacrf_app.advisory_output` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-081 Reconcile `uacrf_app.advisory_run` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-082 Reconcile `uacrf_app.agent_case_record` with owner S09 and migration/implementation evidence.
- [ ] INV-DATA-083 Reconcile `uacrf_app.analysis_context` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-084 Reconcile `uacrf_app.application_job` with owner PER_SERVICE and migration/implementation evidence.
- [ ] INV-DATA-085 Reconcile `uacrf_app.application_job_event` with owner PER_SERVICE and migration/implementation evidence.
- [ ] INV-DATA-086 Reconcile `uacrf_app.application_worker_binding` with owner PER_SERVICE and migration/implementation evidence.
- [ ] INV-DATA-087 Reconcile `uacrf_app.authorization_policy` with owner S10 and migration/implementation evidence.
- [ ] INV-DATA-088 Reconcile `uacrf_app.authorization_policy_review` with owner S10 and migration/implementation evidence.
- [ ] INV-DATA-089 Reconcile `uacrf_app.evaluation_input` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-090 Reconcile `uacrf_app.evaluation_result` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-091 Reconcile `uacrf_app.evaluation_rulepack` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-092 Reconcile `uacrf_app.evaluation_rulepack_review` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-093 Reconcile `uacrf_app.evidence_receipt` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-094 Reconcile `uacrf_app.graph_projection` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-095 Reconcile `uacrf_app.graph_projection_event` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-096 Reconcile `uacrf_app.graph_worker_binding` with owner S06 and migration/implementation evidence.
- [ ] INV-DATA-097 Reconcile `uacrf_app.policy_trust_configuration` with owner S10 and migration/implementation evidence.
- [ ] INV-DATA-098 Reconcile `uacrf_app.proposal_input` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-099 Reconcile `uacrf_app.proposal_record` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-100 Reconcile `uacrf_app.qualification_input` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-101 Reconcile `uacrf_app.qualification_policy` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-102 Reconcile `uacrf_app.qualification_policy_review` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-103 Reconcile `uacrf_app.qualification_result` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-104 Reconcile `uacrf_app.qualification_waiver` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-105 Reconcile `uacrf_app.qualification_waiver_review` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-106 Reconcile `uacrf_app.qualification_waiver_revocation` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-107 Reconcile `uacrf_app.recovery_assessment` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-108 Reconcile `uacrf_app.recovery_review` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-109 Reconcile `uacrf_app.retrieval_record` with owner S08 and migration/implementation evidence.
- [ ] INV-DATA-110 Reconcile `uacrf_app.validation_plan_input` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-111 Reconcile `uacrf_app.validation_plan_record` with owner S12 and migration/implementation evidence.
- [ ] INV-DATA-112 Reconcile `uacrf_app.waiver_trust_configuration` with owner S04 and migration/implementation evidence.
- [ ] INV-DATA-113 Reconcile `uacrf_billing.account` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-114 Reconcile `uacrf_billing.allowance` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-115 Reconcile `uacrf_billing.allowance_application` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-116 Reconcile `uacrf_billing.contact` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-117 Reconcile `uacrf_billing.credit_line` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-118 Reconcile `uacrf_billing.credit_note` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-119 Reconcile `uacrf_billing.currency` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-120 Reconcile `uacrf_billing.entitlement` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-121 Reconcile `uacrf_billing.exchange_rate` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-122 Reconcile `uacrf_billing.invoice` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-123 Reconcile `uacrf_billing.invoice_line` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-124 Reconcile `uacrf_billing.outbox` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-125 Reconcile `uacrf_billing.payment` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-126 Reconcile `uacrf_billing.payment_allocation` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-127 Reconcile `uacrf_billing.payment_attempt` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-128 Reconcile `uacrf_billing.period` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-129 Reconcile `uacrf_billing.plan_version` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-130 Reconcile `uacrf_billing.price` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-131 Reconcile `uacrf_billing.provider_cost` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-132 Reconcile `uacrf_billing.provider_event` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-133 Reconcile `uacrf_billing.rated_usage` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-134 Reconcile `uacrf_billing.reconciliation` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-135 Reconcile `uacrf_billing.refund` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-136 Reconcile `uacrf_billing.refund_allocation` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-137 Reconcile `uacrf_billing.subscription` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-138 Reconcile `uacrf_billing.subscription_item` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-139 Reconcile `uacrf_billing.tax_line` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-140 Reconcile `uacrf_billing.usage_admission` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-141 Reconcile `uacrf_billing.usage_event` with owner S13 and migration/implementation evidence.
- [ ] INV-DATA-142 Reconcile `uacrf_ingestion_design.collection_policy` with owner S03 and migration/implementation evidence.
- [ ] INV-DATA-143 Reconcile `uacrf_ingestion_design.evaluation_schedule` with owner S07 and migration/implementation evidence.
- [ ] INV-DATA-144 Reconcile `uacrf_ingestion_design.import_resolution` with owner S05 and migration/implementation evidence.
- [ ] INV-DATA-145 Reconcile `uacrf_ingestion_design.objective_import` with owner S05 and migration/implementation evidence.
- [ ] INV-DATA-146 Reconcile `uacrf_ingestion_design.signal_receipt` with owner S03 and migration/implementation evidence.
- [ ] INV-DATA-147 Reconcile `uacrf_ingestion_design.source_binding` with owner S03 and migration/implementation evidence.
- [ ] INV-DATA-148 Reconcile `uacrf_ingestion_design.source_checkpoint` with owner S03 and migration/implementation evidence.
- [ ] INV-DATA-149 Reconcile `uacrf_objective_design.activation` with owner S05 and migration/implementation evidence.
- [ ] INV-DATA-150 Reconcile `uacrf_objective_design.objective_revision` with owner S05 and migration/implementation evidence.
- [ ] INV-DATA-151 Reconcile `uacrf_objective_design.review_decision` with owner S05 and migration/implementation evidence.
- [ ] INV-DATA-152 Reconcile `uacrf_objective_design.template_version` with owner S05 and migration/implementation evidence.
- [ ] INV-DATA-153 Reconcile `uacrf_relationship_design.change_history` with owner S02 and migration/implementation evidence.

### Allocated requirements

Source: [requirement allocation](../design/ms01/inventories/REQUIREMENT_ALLOCATION.csv).

- [ ] INV-REQ-001 Reconcile `FR-CAT-001` (S02): The system shall create and maintain a canonical service record with technical and business owners, criticality, environment, lifecycle state, essential functions, objectives, and certification status.
- [ ] INV-REQ-002 Reconcile `FR-CAT-002` (S02): The system shall detect missing, stale, conflicting, or unverified ownership and criticality data and shall prevent production certification while required fields remain unresolved.
- [ ] INV-REQ-003 Reconcile `FR-CAT-003` (S02): The system shall support provider resource aliases without changing the canonical service identity and shall retain source, confidence, and observation timestamps for each mapping.
- [ ] INV-REQ-004 Reconcile `FR-GPH-001` (S06): The system shall represent services, resources, models, datasets, identities, locations, failure domains, controls, vendors, and recovery targets as typed Neo4j nodes and relationships.
- [ ] INV-REQ-005 Reconcile `FR-GPH-002` (S06): The system shall record direction, dependency type, criticality, synchrony, fallback, provenance, confidence, freshness, owner, and propagation behavior for every decision-relevant relationship.
- [ ] INV-REQ-006 Reconcile `FR-GPH-003` (S06): The system shall calculate bounded blast-radius and upstream-cause traversals from a declared graph revision and shall return the paths, exclusions, confidence, and freshness used in the result.
- [ ] INV-REQ-007 Reconcile `FR-GPH-004` (S06): The system shall reconcile catalog, provider inventory, and Neo4j topology; classify divergence; and place affected decisions into Unknown when context no longer satisfies policy.
- [ ] INV-REQ-008 Reconcile `FR-GPH-005` (S06): The system shall enforce the canonical node registry and controlled relationship vocabulary in Section 5.2.7 and Appendix M; unsupported labels, relationship names, directions, properties, or schema versions shall be rejected or quarantined.
- [ ] INV-REQ-009 Reconcile `FR-GPH-006` (S06): The system shall map technical dependencies to customer journeys, journey steps, essential functions, customer segments, SLOs, controls, impacts, outcomes, and outcome measures using versioned, attributable relationships.
- [ ] INV-REQ-010 Reconcile `FR-GPH-007` (S06): The system shall distinguish observed, estimated, forecast, and bounded impacts; deduplicate overlapping paths and populations; and retain method, inputs, window, unit, assumptions, exclusions, confidence, graph revision, and supersession history.
- [ ] INV-REQ-011 Reconcile `FR-GPH-008` (S06): The system shall evaluate candidate recovery paths against the declared failure scenario, correlation domains, target health, capacity, compatibility, evidence qualification, test status, expiry, and policy tolerances and shall return certification status with witness and rejection paths.
- [ ] INV-REQ-012 Reconcile `FR-GPH-009` (S06): The system shall bind material impact, fallback, authorization, and validation decisions to an immutable graph revision and action-relevant subgraph digest and shall require re-evaluation when a material graph delta occurs.
- [ ] INV-REQ-013 Reconcile `FR-GPH-010` (S06): The system shall preserve canonical identity, provenance, qualification, freshness, contradiction, reconciliation, revision, query identifier, parameters, result digest, and witness paths sufficient to reproduce every consequential graph result.
- [ ] INV-REQ-014 Reconcile `FR-ING-001` (S03): The system shall ingest required metrics, logs, traces, changes, security events, incidents, experiments, and AI-lifecycle evidence from approved sources.
- [ ] INV-REQ-015 Reconcile `FR-ING-002` (S03): The system shall validate schema, provenance, timestamp, tenant, environment, identity, and data classification before accepting an event for decision use.
- [ ] INV-REQ-016 Reconcile `FR-ING-003` (S03): The system shall normalize provider-specific evidence into the canonical event contract while preserving the original provider identifier, unit, value, and drill-down reference.
- [ ] INV-REQ-017 Reconcile `FR-ING-004` (S03): The system shall deduplicate, order where required, correlate, and enrich events with service, deployment, model, objective, and graph context.
- [ ] INV-REQ-018 Reconcile `FR-RUL-001` (S07): The system shall store each production rule with an owner, rationale, scope, inputs, condition, output, precedence, suppression, cooldown, tests, evidence requirements, effective dates, and rollback version.
- [ ] INV-REQ-019 Reconcile `FR-RUL-002` (S07): The system shall evaluate deterministic detection, propagation, state, recommendation, authorization-input, validation, and adaptation rules using versioned context.
- [ ] INV-REQ-020 Reconcile `FR-RUL-003` (S07): The system shall resolve conflicts using documented precedence and shall produce Unknown rather than unsupported certainty when material evidence conflicts or is stale.
- [ ] INV-REQ-021 Reconcile `FR-RSK-001` (S07): The system shall maintain Normal, Guarded, Elevated, Critical, and Unknown states for each UACRF layer and the overall service.
- [ ] INV-REQ-022 Reconcile `FR-RSK-002` (S07): The system shall explain every active state using source evidence, rule versions, graph paths, service context, suppressions, confidence, and outstanding validation.
- [ ] INV-REQ-023 Reconcile `FR-POL-001` (S10): The system shall evaluate every candidate action against environment, criticality, action risk, scope, reversibility, confidence, separation of duties, maintenance state, and regulatory constraints.
- [ ] INV-REQ-024 Reconcile `FR-POL-002` (S10): The system shall return one of deny, recommend, approval required, or preauthorized and shall prevent the rule engine or an ML model from bypassing that decision.
- [ ] INV-REQ-025 Reconcile `FR-APR-001` (S10): The system shall route approval to eligible roles, enforce expiry and separation of duties, capture rationale, and support rejection or requests for additional evidence.
- [ ] INV-REQ-026 Reconcile `FR-APR-002` (S10): The system shall implement controlled break-glass approval with stronger authentication, mandatory reason, limited duration, immediate notification, and retrospective review.
- [ ] INV-REQ-027 Reconcile `FR-WFL-001` (S11): The system shall execute approved runbooks through durable, idempotent, timeout-bounded, retry-aware, and compensatable workflows.
- [ ] INV-REQ-028 Reconcile `FR-WFL-002` (S11): The system shall invoke only allowlisted provider-adapter operations against policy-approved targets and shall reject arbitrary cloud API or shell execution.
- [ ] INV-REQ-029 Reconcile `FR-VAL-001` (S12): The system shall validate technical health, SLO restoration, data integrity, security posture, and AI behavior as applicable after every consequential action.
- [ ] INV-REQ-030 Reconcile `FR-VAL-002` (S12): The system shall hold the service in its current or more restrictive state until the required stable observation window succeeds; otherwise it shall roll back or escalate according to the workflow.
- [ ] INV-REQ-031 Reconcile `FR-EVD-001` (S04): The system shall create an immutable evidence bundle that reconstructs inputs, context, graph revision, rule or model, policy, approval, execution, validation, and outcome.
- [ ] INV-REQ-032 Reconcile `FR-EVD-002` (S04): The system shall support authorized search and export by service, incident, decision, action, experiment, time, provider, and control without modifying retained evidence.
- [ ] INV-REQ-033 Reconcile `FR-LRN-001` (S07): The system shall link incidents and experiments to findings, root causes, action items, owners, due dates, proposed changes, verification tests, and closure evidence.
- [ ] INV-REQ-034 Reconcile `FR-LRN-002` (S07): The system shall not classify a lesson as implemented until an approved rule, policy, runbook, architecture, model, control, or test change is deployed and verified.
- [ ] INV-REQ-035 Reconcile `FR-ONB-001` (S03): The system shall provide environment and service onboarding workflows with discovery, ownership, graph, telemetry, objective, runbook, policy, test, and certification gates.
- [ ] INV-REQ-036 Reconcile `FR-ONB-002` (S03): The system shall identify deltas when a new resource, dependency, model, data source, action, provider capability, or service version changes the certified baseline.
- [ ] INV-REQ-037 Reconcile `FR-PRD-001` (S13): The initial commercial release shall implement the approved Critical Journey Resilience Assessment boundary and shall prevent unlicensed, unvalidated, or unreleased Control, Recover, Evidence, Quantitative, or Adaptive capabilities from appearing as generally available.
- [ ] INV-REQ-038 Reconcile `FR-PRD-002` (S13): The system shall record target segment, buyer, champion, funded problem, critical journey, current tools, security constraints, procurement path, success measures, and disqualification reasons for every design partner or pilot.
- [ ] INV-REQ-039 Reconcile `FR-PRD-003` (S13): The system shall lock each paid pilot to an approved service population, provider boundary, failure scenario, recovery path, evidence outputs, responsibilities, exclusions, duration, acceptance measures, and change-control process.
- [ ] INV-REQ-040 Reconcile `FR-PRD-004` (S13): The system shall baseline and measure time to determine impact, time to authorize, time to validated recovery where applicable, dependency gaps, recovery-path deficiencies, manual evidence effort, assessment effort, and customer acceptance for each pilot.
- [ ] INV-REQ-041 Reconcile `FR-PRD-005` (S13): The system shall calculate protected-service, provider-connection, assessment-unit, certified-workflow, evidence-retention, and enabled-intelligence usage without duplicating aliases or deleting retained evidence when entitlement changes.
- [ ] INV-REQ-042 Reconcile `FR-PRD-006` (S13): The customer onboarding workflow shall assign owner, dependency, access, evidence, validation, and approval responsibilities; record entry and exit criteria for every stage; and prevent production certification while mandatory customer or UACRF tasks remain unresolved.
- [ ] INV-REQ-043 Reconcile `FR-PRD-007` (S13): Every external product, resilience, compliance, certification, prediction, savings, and autonomy claim shall reference approved wording, supported evidence, applicable product version, limitations, owner, review date, and withdrawal status.
- [ ] INV-REQ-044 Reconcile `FR-PRD-008` (S13): The system shall support implementation plans, success measures, quarterly value reviews, certification renewals, expansion decisions, support health, renewal evidence, and orderly offboarding for contracted customers.
- [ ] INV-REQ-045 Reconcile `FR-PRD-009` (S13): The system shall record gate criteria, accountable approver, required evidence, decision, conditions, expiry, and reopened status for problem validation, MVP, paid pilot, subscription, Control, Recover, Adaptive, sector expansion, and provider expansion.
- [ ] INV-REQ-046 Reconcile `FR-PRD-010` (S13): The system shall attribute implementation labor, platform consumption, support effort, partner cost, contracted revenue, discounts, and expansion to each pilot or customer sufficiently to evaluate gross margin, payback, and services dependency.
- [ ] INV-REQ-047 Reconcile `FR-TST-001` (S12): The system shall plan, authorize, execute, observe, abort, validate, and retain evidence for controlled fault experiments.
- [ ] INV-REQ-048 Reconcile `FR-TST-002` (S12): The system shall enforce experiment blast-radius limits, stop conditions, protected periods, target allowlists, communications, and recovery readiness.
- [ ] INV-REQ-049 Reconcile `FR-UI-001` (UX01): The system shall present current state, reasons, evidence freshness, dependency impact, proposed actions, authority requirements, workflow status, test status, and unresolved adaptations for an authorized service.
- [ ] INV-REQ-050 Reconcile `FR-UI-002` (UX01): The system shall provide accessible APIs and events for catalog, graph queries, findings, decisions, approvals, workflows, evidence, onboarding, experiments, and administration.
- [ ] INV-REQ-051 Reconcile `FR-QNT-001` (S07): The system shall calculate calibrated layer and systemic scores with parameter provenance, uncertainty, sensitivity, and an explicit mapping back to Core states.
- [ ] INV-REQ-052 Reconcile `FR-QNT-002` (S07): The system shall retain observed outcomes needed to estimate thresholds, propagation parameters, error costs, and forecast performance without allowing a score to override fixed policy.
- [ ] INV-REQ-053 Reconcile `FR-ADP-001` (S07): The system shall support governed models for anomaly detection, incident prediction, cause ranking, blast-radius estimation, and remediation recommendation in shadow or advisory mode.
- [ ] INV-REQ-054 Reconcile `FR-ADP-002` (S07): The system shall promote a model to bounded automation only after safety, utility, calibration, drift, explanation, rollback, and human-authority gates are approved.
- [ ] INV-REQ-055 Reconcile `FR-AGT-001` (S09): When collaborative agents are enabled, the system shall seal a case manifest containing qualified evidence, graph revision, policy version, objectives, constraints, assignments, deadline, and integrity digest before agent execution.
- [ ] INV-REQ-056 Reconcile `FR-AGT-002` (S09): Each agent finding, proposal, and dissent shall identify the producer, role, model, prompt, retrieval sources, tools, authority grant, evidence, graph and policy versions, assumptions, confidence, time, digest, and signature.
- [ ] INV-REQ-057 Reconcile `FR-AGT-003` (S09): The system shall retain material dissent independently from proposals and shall not permit a coordinator, summary, or majority result to remove it.
- [ ] INV-REQ-058 Reconcile `FR-AGT-004` (S09): The system shall arbitrate eligible proposals through a versioned deterministic rule bundle and shall route blocking dissent, a substantive tie, or missing mandatory roles to human review or a policy block.
- [ ] INV-REQ-059 Reconcile `FR-AGT-005` (S09): Agents shall use short-lived, audience-bound, tenant- and environment-scoped tool grants and shall have no provider credentials, approval authority, evidence-qualification authority, policy-write authority, or direct execution path.
- [ ] INV-REQ-060 Reconcile `FR-AGT-006` (S09): The agent runtime shall isolate retrieved content from instructions, enforce tool and egress allowlists, classify data, limit time and cost, detect injection attempts, and fail closed on invalid or stale context.
- [ ] INV-REQ-061 Reconcile `FR-AGT-007` (S09): The system shall define deadlines, mandatory roles, attempt identities, duplicate handling, canonical ordering, partial-agent failure behavior, and idempotent tool invocation.
- [ ] INV-REQ-062 Reconcile `FR-AGT-008` (S09): Each agent role shall pass offline, shadow, and advisory evaluations for factuality, evidence citation, safety, reproducibility, injection resistance, latency, and cost before governed collaboration.
- [ ] INV-REQ-063 Reconcile `FR-AGT-009` (S09): Authorized humans shall be able to reject, narrow, defer, or replace an agent proposal and shall provide a reason that remains linked to the original proposal and dissent.
- [ ] INV-REQ-064 Reconcile `FR-AGT-010` (S09): The Resilience Decision Envelope shall bind the sealed case, every eligible and rejected agent output, dissent, arbitration, policy decision, approval, authorization, execution, validation, and outcome.
- [ ] INV-REQ-065 Reconcile `FR-IP-001` (S13): The system shall create a versioned Resilience Decision Envelope for every consequential recommendation or action. The envelope shall bind the normalized evidence, service and dependency context, graph revision, rule or model version, policy bundle, authorization, workflow, validation, and outcome by immutable identifiers and cryptographic digests.
- [ ] INV-REQ-066 Reconcile `FR-IP-002` (S13): The system shall bind every consequential recovery authorization to an explicit dependency-graph revision and shall re-evaluate, narrow, suspend, or invalidate the authorization when a material topology change affects target identity, blast radius, fallback validity, failure-domain independence, or required validation.
- [ ] INV-REQ-067 Reconcile `FR-IP-003` (S13): The system shall issue a signed, short-lived, single-use bounded recovery authorization that identifies the provider-neutral action, permitted targets, tenant, environment, maximum scope, prerequisites, prohibited operations, expiry, compensation, validation plan, and exact provider-adapter translation. Adapters shall reject replay, target substitution, expired authority, and semantic expansion.
- [ ] INV-REQ-068 Reconcile `FR-IP-004` (S13): The system shall determine whether a service state is Qualified, Provisional, Unknown, or Disqualified from evidence sufficiency, provenance, freshness, consistency, observation-window completion, and required validation coverage. Missing or contradictory evidence shall not be silently treated as healthy.
- [ ] INV-REQ-069 Reconcile `FR-IP-005` (S13): The system shall certify a primary and alternate recovery path only when graph analysis shows that the paths do not share prohibited correlated-failure domains, including regions, zones, clusters, control planes, identities, keys, networks, data stores, vendors, or upstream dependencies, subject to declared policy tolerances.
- [ ] INV-REQ-070 Reconcile `FR-IP-006` (S13): The system shall evaluate an ordered Cross-Layer Validation Plan after recovery across infrastructure, platform, application, data, security, AI behavior, SLO, and business-function checks. A failed mandatory check shall trigger the declared compensating action, rollback, containment, or escalation and shall prevent a successful recovery declaration.
- [ ] INV-REQ-071 Reconcile `FR-IP-007` (S13): A deployment enabling collaborative agents shall bind independently attributable proposals and dissent to qualified evidence, a graph revision, a policy version, and bounded agent authority; apply deterministic non-majority arbitration; preserve material dissent; and couple selection to separate bounded authorization, execution, cross-layer validation, and reconstructable provenance. Core conformance does not require collaborative agents.
- [ ] INV-REQ-072 Reconcile `NFR-AVL-001` (CROSS_CUTTING): Availability report and architecture test.
- [ ] INV-REQ-073 Reconcile `NFR-RES-001` (CROSS_CUTTING): Control-plane isolation experiment.
- [ ] INV-REQ-074 Reconcile `NFR-RPO-001` (CROSS_CUTTING): Backup and restore exercise.
- [ ] INV-REQ-075 Reconcile `NFR-PRF-001` (CROSS_CUTTING): Load and latency test.
- [ ] INV-REQ-076 Reconcile `NFR-PRF-002` (CROSS_CUTTING): Performance test.
- [ ] INV-REQ-077 Reconcile `NFR-GPH-001` (CROSS_CUTTING): Projection-lag and reconciliation report.
- [ ] INV-REQ-078 Reconcile `NFR-GPH-002` (CROSS_CUTTING): Dependency-quality report and certification test.
- [ ] INV-REQ-079 Reconcile `NFR-GPH-003` (CROSS_CUTTING): Decision-replay audit.
- [ ] INV-REQ-080 Reconcile `NFR-GPH-004` (CROSS_CUTTING): Backup, restore, replay, and recertification exercise.
- [ ] INV-REQ-081 Reconcile `NFR-SCL-001` (CROSS_CUTTING): Capacity and saturation test.
- [ ] INV-REQ-082 Reconcile `NFR-SEC-001` (CROSS_CUTTING): IAM review and penetration test.
- [ ] INV-REQ-083 Reconcile `NFR-SEC-002` (CROSS_CUTTING): Configuration and leakage test.
- [ ] INV-REQ-084 Reconcile `NFR-SEC-003` (CROSS_CUTTING): Segregation-of-duties test.
- [ ] INV-REQ-085 Reconcile `NFR-AUD-001` (CROSS_CUTTING): Audit completeness test.
- [ ] INV-REQ-086 Reconcile `NFR-PRI-001` (CROSS_CUTTING): Privacy review and retention test.
- [ ] INV-REQ-087 Reconcile `NFR-DAT-001` (CROSS_CUTTING): Data-quality rule test.
- [ ] INV-REQ-088 Reconcile `NFR-INT-001` (CROSS_CUTTING): Compatibility and consumer-driven contract tests.
- [ ] INV-REQ-089 Reconcile `NFR-MNT-001` (CROSS_CUTTING): Operability review and upgrade test.
- [ ] INV-REQ-090 Reconcile `NFR-TST-001` (CROSS_CUTTING): CI quality report.
- [ ] INV-REQ-091 Reconcile `NFR-EXP-001` (CROSS_CUTTING): Explanation usability test.
- [ ] INV-REQ-092 Reconcile `NFR-ACC-001` (CROSS_CUTTING): Accessibility audit.
- [ ] INV-REQ-093 Reconcile `NFR-OBS-001` (CROSS_CUTTING): Telemetry and dashboard inspection.
- [ ] INV-REQ-094 Reconcile `NFR-POR-001` (CROSS_CUTTING): Multi-provider acceptance test.
- [ ] INV-REQ-095 Reconcile `NFR-CST-001` (CROSS_CUTTING): FinOps report and quota test.
- [ ] INV-REQ-096 Reconcile `NFR-IMP-001` (CROSS_CUTTING): Implementation analytics and pilot comparison.
- [ ] INV-REQ-097 Reconcile `NFR-CLM-001` (CROSS_CUTTING): Claims-control and negative publication test.
- [ ] INV-REQ-098 Reconcile `NFR-COM-001` (CROSS_CUTTING): Entitlement outage and contract-expiry exercise.
- [ ] INV-REQ-099 Reconcile `NFR-OPS-002` (CROSS_CUTTING): Ownership registry and operational-readiness audit.
- [ ] INV-REQ-100 Reconcile `METH-001` (CROSS_CUTTING): Two qualified assessors applying the same pack to the same approved evidence produce the same categorical result or a documented, reviewable judgment difference.
- [ ] INV-REQ-101 Reconcile `METH-002` (CROSS_CUTTING): Every issued conclusion can be reconstructed from the engagement charter, pack version, scope, graph revision, workpapers, evidence, decisions, reviews, and approvals.
- [ ] INV-REQ-102 Reconcile `METH-003` (CROSS_CUTTING): Missing, stale, weak, contradictory, and excluded evidence cannot silently produce a passing result or inflated confidence.
- [ ] INV-REQ-103 Reconcile `METH-004` (CROSS_CUTTING): Sampling decisions are reproducible and failed samples trigger the published expansion rule.
- [ ] INV-REQ-104 Reconcile `METH-005` (CROSS_CUTTING): Criticality, severity, priority, confidence, residual risk, and conclusion rules operate consistently across supported packs and disclose approved differences.
- [ ] INV-REQ-105 Reconcile `METH-006` (CROSS_CUTTING): Customer factual challenge, independent quality review, correction, reissuance, withdrawal, expiration, and reassessment preserve history and authority separation.
- [ ] INV-REQ-106 Reconcile `METH-007` (CROSS_CUTTING): Assessment outputs state intended use, limitations, validity, evidence cutoff, uncertainty, and prohibited claims clearly enough for the target audience.
- [ ] INV-REQ-107 Reconcile `METH-008` (CROSS_CUTTING): The methodology can be executed manually from controlled templates and through UACRF Assess without changing substantive results.
- [ ] INV-REQ-108 Reconcile `FR-KNO-001` (S08): Version history and attributable lifecycle audit
- [ ] INV-REQ-109 Reconcile `FR-KNO-002` (S08): Quarantine, approval and retrieval-denial records
- [ ] INV-REQ-110 Reconcile `FR-KNO-003` (S08): Denied access and zero unauthorized content exposure
- [ ] INV-REQ-111 Reconcile `FR-KNO-004` (S08): Manifest, excerpts, source hashes and locator checks
- [ ] INV-REQ-112 Reconcile `FR-KNO-005` (S08): Invalidation, blocked stale reuse and retained history
- [ ] INV-REQ-113 Reconcile `FR-KNO-006` (S08): Unknown disposition and explicit source limitations
- [ ] INV-REQ-114 Reconcile `FR-ORC-001` (S09): Sealed run and attributable attempts
- [ ] INV-REQ-115 Reconcile `FR-ORC-002` (S09): Reservation ledger and bounded denied requests
- [ ] INV-REQ-116 Reconcile `FR-ORC-003` (S09): Immutable terminal outcomes and rejection reasons
- [ ] INV-REQ-117 Reconcile `FR-ORC-004` (S09): Blocked escalation and human-review record
- [ ] INV-REQ-118 Reconcile `FR-ORC-005` (S09): Grant revocation and independently governed manual continuation
- [ ] INV-REQ-119 Reconcile `FR-ORC-006` (S09): Version-bound evaluation pack and promotion or rollback disposition
- [ ] INV-REQ-120 Reconcile `FR-AWL-001` (S02): The platform shall register each protected agent workload with a tenant, environment, service identity, accountable owner, runtime principal, intended purpose and workload kind.
- [ ] INV-REQ-121 Reconcile `FR-AWL-002` (S02): The platform shall bind each execution to an immutable workload version containing model, instruction, knowledge, tool, memory, data and evaluation contracts, and shall require a new version for a material change.
- [ ] INV-REQ-122 Reconcile `FR-AWL-003` (S06): The platform shall retain qualified and version-bound agent dependencies, including tool effects, identity, model, knowledge, memory, runtime and handoff dependencies, without treating topology alone as proof of eligibility or recovery independence.
- [ ] INV-REQ-123 Reconcile `FR-AWL-004` (S11): The platform shall enforce authenticated scoped admission, a deadline and atomic per-attempt limits for steps, tokens and cost before runtime dispatch, including each parallel child.
- [ ] INV-REQ-124 Reconcile `FR-AWL-005` (S11): The platform shall retain an attributed append-only event stream and shall return the original receipt for an identical source key and content while rejecting a conflicting reuse without a second budget debit.
- [ ] INV-REQ-125 Reconcile `FR-AWL-006` (S11): The platform shall isolate sensitive memory and checkpoint artifacts by authorized scope and shall bind an eligible checkpoint to verified bytes, the workload version, input digest, event sequence, namespace and expiry.
- [ ] INV-REQ-126 Reconcile `FR-AWL-007` (S11): The platform shall fence cancellation and pause requests at admission and dispatch, preserve late output without accepting it as eligible, and reconcile uncertain external effects before retry or resumption.
- [ ] INV-REQ-127 Reconcile `FR-AWL-008` (S10): The platform shall require the existing governed recovery authority chain for consequential agent recovery and shall bind the recovery workflow to the exact source execution, checkpoint, evaluation and target version without granting recovery authority to the agent.
- [ ] INV-REQ-128 Reconcile `FR-AWL-009` (S11): The platform shall stage resumption as a new linked execution with no greater remaining budget or deadline and shall reject stale evaluation, expired checkpoint, unresolved effects or incompatible context.
- [ ] INV-REQ-129 Reconcile `FR-AWL-010` (S12): The platform shall assess agent recovery against a versioned mandatory evaluation suite and qualified observations of task, data, security and business outcomes, preserving FAIL or UNKNOWN when required checks are absent.
- [ ] INV-REQ-130 Reconcile `FR-AWL-011` (S10): The platform shall preserve agent execution, context, tool effect, handoff, evaluation and recovery lineage in its decision evidence and shall retain a governed human continuation route.
- [ ] INV-REQ-131 Reconcile `FR-AWL-012` (S13): The platform shall distinguish runtime reservations, measured provider usage and customer charges, correlate child attempts, prevent duplicate receipt charges and preserve safety controls when billing is unavailable.

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
| R-0017 | 2026-11-20 | M2-022 | Confirmed `main` and `origin/main` both point to `83d5628`; no additional commit was pending before this run. | Objective review work is in progress. |
| R-0018 | 2026-11-20 | M2-022a, M2-022b | Attempted focused clean S05/EDGE01 tests with `mvn`; command failed because `mvn` is not on PATH (exit 127). | Retry with the repository's Maven wrapper. |
| R-0019 | 2026-11-20 | M2-022a, M2-022b | Clean focused S05/EDGE01 tests with `./mvnw`: 16 S05 and 1 EDGE01 test passed, including concurrent replay, conflicts and rollback. | Browser flow and full integration remain open. |
| R-0020 | 2026-11-20 | M3-023–M3-026, M4-001a–M4-001d | Backfilled eight previously verified, narrowly scoped browser slices using the existing S07/S10 status evidence. | This is not a reconstruction of every earlier execution; broader acceptance and source verification remain open. |
| R-0021 | 2026-11-20 | M2-022d | `node --check e2e/tests/sample-inventory.spec.mjs` found a duplicate local variable; `git diff --check` passed. | Renamed the variable; syntax and browser execution must be rerun. |
| R-0022 | 2026-11-20 | M2-022d | Browser spec syntax check passed after the rename. | Browser execution remains open. |
| R-0023 | 2026-11-20 | M2-022c | Clean focused UX01 tests passed: 2 review-form tests and 5 objective-client tests. | A subsequent reviewer-binding test and browser execution remain to be run. |
| R-0024 | 2026-11-20 | HIST-001–HIST-044 | Backfilled bounded earlier implementation slices from the existing implementation-status evidence. | Detailed operation/table/requirement reconciliation and full acceptance remain open. |
| R-0025 | 2026-11-20 | M2-022c | Clean full UX01 suite passed 190 tests, including reviewer binding and receipt handling. | Isolated browser execution remains open. |
| R-0026 | 2026-11-20 | M0-001–M0-004 | Expanded the source inventories into 144 operation, 153 data and 131 requirement reconciliation checkboxes. | Unchecked means not reconciled, not necessarily unimplemented; newly discovered scope must be added. |
| R-0027 | 2026-11-20 | M0-001–M0-004 | Verified all 428 generated identifiers match their source rows, all 725 checklist IDs are unique, and `git diff --check` passes. | This verifies inventory coverage, not implementation or acceptance. |
| R-0028 | 2026-11-20 | M2-022a, M2-022b, M2-022d | Clean S05/EDGE01 reactor passed 60 tests, including the added same-key revision and objective-ID conflicts. | Isolated browser execution remains open. |
| R-0029 | 2026-11-20 | M2-022c, M2-022d | Isolated browser suite passed 11 tests including synthetic independent approval/rejection, unchanged replay, changed-reason conflict, self-review denial and denied read. | S02 owner verification is disabled in this stack; provider acceptance and complete review-history disclosure remain open. |
| R-0030 | 2026-11-20 | M2-022e | Updated contract, status and browser evidence; private approach revision 175 verified with 169 tables, 44 inline images, unchanged non-document parts and mode `0600`. | Full review-history disclosure remains open under M2-022. |
| R-0031 | 2026-11-20 | M2-022c | Clean focused UX01 review/client tests passed 8 tests, including the displayed digest-coverage limitation. | Full accessibility acceptance remains M8-013. |
| R-0032 | 2026-11-20 | Delivery tracking | Combined milestone status, granular checklist, run log and implementation evidence in this document; redirected workspace links and retained a compatibility pointer at the old status path. | Historical operation-by-operation reconciliation remains open. |
| R-0033 | 2026-11-20 | Delivery tracking | Verified prior status-detail lines remain in order, combined-document heading links resolve, redirected workspace links point to the combined document, and `git diff --check` passes. | External links to the old file land on its compatibility pointer. |
| R-0034 | 2026-11-20 | Delivery tracking | Rechecked the combined checklist: 727 unique IDs, including all 144 operations, 153 data allocations and 131 requirements; browser spec syntax and whitespace checks pass. | Unchecked reconciliation entries do not imply unimplemented features and are not a percentage-complete estimate. |
| R-0035 | 2026-11-20 | M2-022, delivery tracking | Committed the verified review flow and unified tracker as `7b6d2e3` and pushed it to `origin/main`. | Complete decision-history disclosure and historical allocation reconciliation remain open. |
| R-0036 | 2026-11-20 | Local Docker cleanup | Removed 11 image tags from this run's completed disposable browser stack. | No shared containers, volumes or build cache were removed. |
| R-0037 | 2026-11-20 | Local Docker cleanup | Removed 357 image tags belonging to 36 prior browser projects identified in this session's logs, after checking none had containers. Docker-reported image usage changed from 15.11 GB to 11.53 GB; all three unrelated running containers remained up. | Other images and shared build cache were left untouched. |
| R-0038 | 2026-11-20 | M0-004 | Expanded 20 abbreviated requirement checklist entries using their retained source text. | Requirements still need individual evidence reconciliation. |
| R-0039 | 2026-11-20 | Delivery tracking | Removed the obsolete implementation-status compatibility file at the user's request; workspace references already point to this combined tracker. | External references to the deleted file must be updated. |
| R-0040 | 2026-11-20 | M0-020, HIST-045–HIST-090 | Backfilled 46 additional narrowly scoped historical slices from the retained implementation evidence. | Further historical reconciliation and release acceptance remain open. |
| R-0041 | 2026-11-20 | Delivery tracking | Verified the old file is absent, tracked workspace references are absent, all local tracker links and anchors resolve, 773 checklist IDs are unique, all 428 inventory allocations remain represented, and `git diff --check` passes. | Inventory entries remain reconciliation tasks, not acceptance results. |
| R-0042 | 2026-11-20 | Delivery tracking | Committed the consolidated tracking updates and obsolete-file removal as `0847fdd` and pushed them to `origin/main`. | Historical reconciliation and application implementation remain open. |
| R-0043 | 2026-11-20 | Delivery tracking | Initial checklist-reorganization script stopped at its preservation assertion because the verification pattern also captured surrounding blank lines; it wrote no changes. | Corrected the pattern and retried in R-0044. |
| R-0044 | 2026-11-20 | Delivery tracking | Reorganized the historical work into its relevant milestone and service sections, separated verified and remaining work, and simplified the overview while retaining its detailed scope. The script verified unchanged checkbox text, states and nesting before writing. | Historical reconciliation and milestone acceptance remain open. |
| R-0045 | 2026-11-20 | Delivery tracking | The first post-edit grouping check failed because its section-boundary pattern treated nested headings as milestone boundaries. | Corrected the validation and reran it in R-0046; no checklist data was changed by the check. |
| R-0046 | 2026-11-20 | Delivery tracking | The second grouping check failed because its scan started inside the current heading and misidentified it as the next heading. | The validation was corrected to start after the heading; the document itself was unchanged. |
| R-0047 | 2026-11-20 | Delivery tracking | Verified all 773 checkbox IDs, states and descriptions are unchanged; all 90 historical items are grouped by milestone or service; all 428 source allocations, local links, anchors and 42 earlier log rows are retained. `git diff --check` passes. | This verifies document preservation, not implementation or acceptance. |

## Implementation evidence

## S10 policy-bundle proposal, review, and read

S10 now has a database-backed initial policy-bundle slice: propose immutable
revisions, list the current revision for a service, and read current or
historical revisions. S01 issues the `authorization.policy.write` and
`authorization.policy.read` capabilities only through its existing explicit
grant flow; no automatic grants were added. S01 now also makes
`authorization.policy.review` grantable only to HUMAN principals and only at
an exact service scope. S10 records immutable reviewer votes through
`POST /v1/authorization-policy-bundles/{id}:decide`: the reviewer must have
that exact-service capability, be listed in `eligible_approver_ids`, and differ
from the proposer. Approval requires an RS256 compact JWS with RFC 8785
canonical claims, verified against an operator-provisioned, versioned
public-key trust set scoped to tenant, environment, service, and reviewer.
Distinct approvals must reach the configured quorum; rejection is unsigned and
terminal. Trust rollback or a contradiction makes approvals ineligible on
reads and blocks new approvals. EDGE01 forwards the fixed internal decide
route. This remains governance metadata only: it creates no action permit,
reservation, or operational authorization, and policy evaluation is not
implemented. Focused S01/S10/EDGE01 and dependency tests passed in the selected
reactor: 222 tests, with no failures, errors, or skips. The MS01 design check
passed 24 checks; these results do not establish live deployment acceptance.

UX01 now lists current policy revisions for a selected service and reads a
specific immutable revision through EDGE01 using a context- and
service-scoped `authorization.policy.read` delegation. The browser validates
scope, revision, review metadata, the canonical material's SHA-256 digest and
its service, ID and time bindings before rendering escaped content. A displayed
digest is an integrity check, not a signature or independent verification of
the record. Listing is paginated, with independent page reads; concurrent
changes can affect traversal. Review eligibility and key trust are
point-in-time S10 results, not action authorization. A revision page links to
the preceding immutable revision and to a fresh read of the current revision;
the latter is point-in-time and may change after the read.

UX01 can also submit a new policy or next revision with an independently
granted, service-scoped `authorization.policy.write` delegation. The form
retains its idempotency key and inputs on an unconfirmed outcome, and warns
that a retry must be unchanged and use the same user and context. A link from
a revision preselects its service, policy ID and expected revision; an
outdated revision can conflict. The receipt validates the returned material
against the submitted definition, times, ID, service, reason and expected
revision. The principal and context fields guard against accidental changes;
S01 and S10 enforce authorization. The clean full UX01 suite passed 172 tests.
The isolated browser suite passed 11 tests, including first and later
synthetic revisions, original-key replay after a later revision, changed-key
content conflict, two-page browsing, historical readback, and denied access.
This proposal test does not establish live-provider acceptance.

UX01 can submit a retry-safe unsigned rejection after displaying the exact
revision's canonical material. S01 issues the separately granted service-scoped
`authorization.policy.review` delegation, while S10 enforces current revision,
expiry, reviewer eligibility and separation from the proposer. UX01 checks the
returned revision, digest and rejection reason; its digest check is after the
write and is not an independent commit fence. A retry of an unconfirmed result
must preserve the form, key, user and context. The isolated browser suite
exercises independent rejection, unchanged replay, changed-content conflict,
stale-revision conflict and self-review denial.

UX01 also submits an externally produced compact RS256 JWS approval. The form
displays the exact policy material and required claims; it does not generate
signatures or accept private keys. S10 verifies signature, trust scope, signed
claims, eligibility and separation. UX01 checks the returned material digest
and vote assertion digest after submission; this is not independent signature
verification or a pre-commit fence. Replay may show a later review state. The
clean full UX01 suite passed 178 tests; the isolated browser suite passed 11,
including invalid signature denial, signed approval, unchanged replay and
changed-content conflict. Its signing key and trust set are disposable test
fixtures, and the fixed reviewer's two service-scoped memberships are inserted
directly rather than established through S01 grant decisions. Production key
provisioning, production signing integration and provider acceptance remain open.

The clean UX01 suite passed 179 tests, and the isolated browser suite passed
11 tests including navigation from a current policy revision to its predecessor
and back to a fresh current read. These reads use the existing independent
service-scoped read delegation; they do not enable policy evaluation or action
authorization.

The repository also provides a local, dependency-free Node signer for the
approval assertion. It takes the exact reason on standard input and uses a
local RSA PEM key; it does not transmit the key or assert that it is trusted.
The isolated browser test uses this helper to generate the signed assertion.
It is not an HSM, encrypted-key or production key-management integration.

The EDGE01 policy-get route now forwards revision selectors to S10 instead of
silently returning the latest revision. Four PostgreSQL-backed concurrency tests
exercise same-key replay and conflicts and competing first or later revisions;
they cover controlled interleavings on one service instance, not multi-instance
load. The focused edge and S10 suites passed 11 tests.

The S10 image is now included in CI image build/port checks, SBOM and
vulnerability scanning, and release signing. The local all-image smoke test
starts S10 with a distinct database and runtime role, verifies readiness and
database isolation, and confirms EDGE01 forwards its internal route while the
public policy route remains absent. All eleven local application images passed
readiness and route/isolation checks. This is local container evidence; GitHub
Actions were not exercised. Authenticated policy-review operations are covered
by the selected reactor tests with generated RSA keys and test delegations, not
by a live S01/EDGE01/S10 deployment or operator trust configuration. The
selected Maven reactor covering S03/S04/S07 and dependencies passed 225 tests
with no failures, errors or skips.

The MS01 API ownership inventory's `runtime_registered` and
`current_recorded_status` fields describe the inherited source runtime, not
the current microservice implementation. S10 now implements the policy review
decision route described above; the inherited inventory does not imply that
policy evaluation, permits, or operational authorization are implemented.

## S03 observation replay integrity

S03 now compares the complete retained observation, including the original
nanosecond instant, before treating a replay as a duplicate. Conflicting IDs or
reused source sequences are quarantined; submissions for the same observation
ID are serialized across source bindings. Observations created before migration
V3 lack recoverable original timestamp precision and their replays fail closed
as `REPLAY_CONFLICT`. Avoid mixed old/new ingestion instances and rollback
without accounting for the weaker old behavior. The focused connections and
delegation suites passed 20 tests; the MS01 architecture check passed 24
checks. This does not qualify cloud collectors or replace S04 qualification.

## S03 inventory-observation projection

S03 and its internal EDGE01 route now expose a paginated, authorized on-read
projection for AWS Config, Azure Resource Graph, and GCP Asset Inventory
observations. It maps native IDs and types, available location, observation time,
and raw-observation provenance without storing another copy of provider data.
It preserves `SAMPLE_LOCAL` provenance; GCP resource location remains unknown
with the current payload. It is not a deduplicated current inventory, shared
resource taxonomy, qualified evidence, relationship discovery, or graph promotion.
The cloud providers remain `UNACCEPTED`.

S03 and EDGE01 now expose a paginated, tenant/environment-scoped collection
outcome report for source bindings. It returns the last collector status and
detail, active-lease state, last successful collection timestamp, and next
scheduled collection time without exposing checkpoints or pending payloads.
`COLLECTED` means the batch was submitted to intake; per-observation accepted,
duplicate, or quarantined results are not aggregated. The timestamps do not
establish freshness because the endpoint applies no freshness policy.

The browser application now lists connections and renders their inventory
observations through a context-bound S03 read delegation and EDGE01. Sample
connections are visibly identified as synthetic, unknown locations remain
unknown, and pagination and raw-observation provenance are shown. A separate
context-bound read page displays paginated raw observations only for
`SAMPLE_LOCAL` connections; payload text uses escaped native disclosures.
The explicit fixture import now binds ten sample sources and submits 122
accepted observations: 42 inventory and 80 metric. It continues to exclude
simulated pages, graph records, and deliberate fault deliveries. The full
web module passed 99 tests in a clean run, including delegation, route rendering, and
HTTP-client contract checks. The browser does not automatically ingest the
sample fixture. The explicit import reports partial results and supports
identical retries. Invalid upstream connection, inventory, collection-status,
and observation responses now produce visible gateway errors; missing or null
collection-running flags are rejected rather than treated as known values.
An inventory read error is no longer presented as an empty result, and either
inventory or collection status can still be displayed if the other read fails.
The earlier isolated browser suite passed all 11 tests, including independently
approved memberships, import/replay, both inventory pages, and paginated
retained-observation readback for all three metric payload shapes. It also
verifies unknown GCP locations and an authorized S02 flow that creates a
journey, registers and links a service,
reads persisted journey/service state back, and registers standalone and
service-bound agent workloads, then reads both persisted workloads through the
UX01 page using a narrow `catalog.agent.read` delegation. A separate admitted
user with only `owner.eligible` is denied this read by S01 and receives no
workload records. No runtime action is performed and no live cloud provider is
queried.

S01 now supports selecting eligible owners for `CONNECTION` through its
existing owner-candidates API. The caller must hold both global
`identity.owner.select` and global `connections.connection.write` in the
selected tenant and environment; a connection ID cannot substitute for global
write authority. PostgreSQL-backed HTTP tests cover eligible-owner filtering,
denial, revocation and the S03 delegation's owner claims.

The historical technical-approach Mermaid examples now use the dictionary's
relationship endpoint roles; sample-data tests check those examples. The
archival PNG/SVG illustrations have not all been reconciled with the dictionary
or regenerated and must not be used as authoritative relationship definitions.
Two Mermaid previews are available as a potentially more readable alternative; their source
and embedded Markdown remain synchronized by a test. Visual rendering has not
been verified in a Mermaid previewer. The focused sample-data, S03, and EDGE01
tests passed 21 tests with no failures or skips, including PostgreSQL-backed
sample intake and projection; these are not live cloud conformance.

## S03 leased collection and cloud-provider inventory

The opt-in collection runner claims active source bindings through persisted
per-source leases and checkpoints. Before submission it durably saves each
observation batch, so retries after partial or uncertain intake replay the
same IDs, payloads, timestamps, and sequence values instead of recollecting.
It uses a dedicated OAuth client identity and a short-lived S01 delegation
limited to `connections.observation.write`; it stores no signing key and
remains disabled by default.

AWS connection registration validates an IAM role ARN and creates a unique
per-connection ExternalId, which authorized connection reads expose for
configuring the target role trust policy. The implemented first source is
`aws/<region>/config/inventory`: the collector assumes that role through STS
and queries one AWS Config `SelectResourceConfig` page (up to 100 results) per
run, retaining `NextToken` in the checkpoint. Observations use the Config
capture time and stable IDs derived from source and sequence. Mocked pagination
tests verify that the next page resumes from its returned token with continuous
sequence numbers and distinct IDs. AWS connections remain `UNACCEPTED` until
live read-only conformance; the runner therefore does not schedule them.

Azure Resource Graph inventory uses `DefaultAzureCredential`, validates the
subscription GUID, and collects selected-region resource metadata in pages of
100 using `$skipToken`. Truncated results without a continuation token do not
advance the checkpoint.

Google Cloud Asset Inventory uses Google Application Default Credentials and
the project-scoped `ListAssets` API. It validates the project ID, reads
project-wide assets in pages of 100 using `nextPageToken`, and deliberately does
not filter by the region segment because project inventory can include global
assets. Observation payloads retain only the asset name, type, and update time.

A bounded Google Cloud Monitoring collector now queries one metric type and
one monitored-resource type in the configured project. It uses `view=FULL`,
pages at 100 points, preserves the original query interval with the page token,
and limits each window to 24 hours (five minutes by default). Points outside
the half-open request interval are discarded. Partial responses reporting
`executionErrors` or unreachable regions, and observations exceeding S03's
16,384-character payload limit, retain the previous checkpoint.

AWS, Azure, and GCP connections remain `UNACCEPTED` until their live read-only
conformance suites pass; local tests do not qualify provider connections.
AWS supports bounded SQS metrics and exact selector-based CloudWatch metrics;
bounded Azure Monitor and Google Cloud Monitoring metric collectors complement
their inventory slices. The paginated S03 collection outcome report is
available through EDGE01 and rendered on the browser inventory page with a
cursor independent from inventory pagination. The targeted web suite passed 89
tests (85 browser application and 4 connections client), with no failures,
errors, or skips. The clean S03/EDGE01/delegation reactor passed 92 tests (57
connections, 27 API edge, and 8 delegation-auth), with no failures, errors, or
skips. The isolated browser E2E suite passed 11 tests, including the synthetic
source `NEVER_RUN` report and its freshness limitation; the disposable stack
was removed afterward. No cloud provider was contacted. These are local
results, not live provider conformance.

**Deferred live checks:** Continue implementation and application work using
clearly labeled provider-shaped sample fixtures while account access and live
conformance are deferred. The [connections live-check guide](../services/connections/README.md#deferred-live-provider-checks)
records target identifiers, authentication and permissions, provider service
prerequisites, owner approval and cost considerations, and the distinction
between a smoke check and conformance. The initial local credential readiness
check did not authenticate the three collector processes; no live inventory
was queried. A separate opt-in live-check path and conformance evidence remain
to be implemented; the `UNACCEPTED` binding/scheduling gate must not be bypassed.
Existing resources can be used; an empty estate cannot demonstrate resource
collection or pagination. The fictional multi-cloud inventory fixture now
delivers one observation per resource using the fields emitted by the current
AWS, Azure and GCP collectors; simulated provider response pages are retained
separately. This field-shape alignment does not establish live provider parity,
real cloud capabilities, or an end-to-end browser flow. The sample-data suite passed
5 tests, and the focused PostgreSQL-backed S03 multi-cloud fixture ingestion
test passed with no failures, errors, or skips.

## Current handoff

- The selected MS01 design, its contracts and inventories, the UI reference,
  and relevant graph/data-schema assets are under [`design/`](../design/).
- The five Spring Boot modules are EDGE01, UX01, S01, S02, and the separate
  Thymeleaf browser application. S02 now has database-backed service,
  agent-workload, and customer-journey registration/read endpoints, with
  tenant-scoped persistence, idempotency records, and outbox events.
- A root Dockerfile builds separate images for all five application modules
  from digest-pinned Java 25 base images. Each runs as UID/GID 10001 and
  exposes its configured application port; CI builds every target and checks
  the non-root user and port metadata, then smoke-tests all five images against
  disposable PostgreSQL instances using generated test keys. In that smoke
  topology, S01 can reach only its identity database and S02 only its catalog
  database; EDGE01, UX01, and the browser app cannot reach either database.
  Fresh database initialization creates distinct superuser admin and restricted
  runtime roles. The smoke test also migrates disposable legacy-role fixtures,
  verifies existing table data and ownership are preserved/transferred, and
  confirms the old bootstrap login is disabled. All five images returned `UP`
  from readiness after startup/migrations. An ephemeral dual-network probe
  confirmed each runtime database credential works only against its owning
  database. The CI workflow is configured to
  generate SPDX SBOMs and scan the five runtime images plus the shared Maven/JDK
  builder image for HIGH and CRITICAL vulnerabilities, retaining reports as
  workflow artifacts; this workflow change has not yet been exercised by
  GitHub Actions. The images are not yet wired into Compose or published or
  signed. This does not verify OIDC login, authorization, or database isolation
  in the deployment configuration.
- EDGE01 now routes the implemented S01 identity GET operations and membership
  revocation and principal disabling to a fixed S01 target, forwards bearer,
  idempotency, body, and scoped query data, and preserves owner status/body and
  authentication challenges without caching responses. UX01's public S01
  reads and writes now target EDGE01; delegation issuance remains a
  server-to-server call to S01's internal endpoint and is not exposed at the
  edge. EDGE01 also forwards the existing delegated S02 catalog reads and
  writes through the explicitly local-only `/internal/catalog/v1/...` route
  prefix; this is not the canonical
  public catalog API. UX01 now provides
  current access and paginated current-context reads as fixed-target BFF
  routes. Principal, membership, and owner-candidate reads, membership
  revocation, principal disabling, and delegation issuance also pass through
  UX01; S01 remains authoritative for validation and mutation. These are
  initial slices, not full public route coverage, edge traffic-limit policies,
  or live service-to-service acceptance.
- S01 current access returns a capability expiry horizon computed from the
  selected scope: the earliest of each returned capability's latest active
  membership expiry, or null for an empty set. Duplicate grants and
  global/service scope are included in the calculation. This point-in-time
  metadata is not a guarantee of continued access: revocation, newly granted
  capabilities, principal or context changes, and token expiry can change
  authority sooner. It is not a cross-service reservation or commit fence.
  S01 also reports `session_expires_at` from the authenticated JWT, or null
  when absent, and a scoped `membership_snapshot_digest` covering the active
  membership IDs, capabilities, scopes, expirations and revisions. S09
  requires both expirations to be parseable and future, validates the digest,
  and rejects changes between initial and refreshed requester reads. A
  PostgreSQL regression shows a redundant grant changes the digest even when
  the capability list and expiry horizon do not; unrelated service-scoped
  grants do not change it. The digest is not monotonic and cannot detect a
  change fully reversed between reads. S01 now supplements it with scoped,
  transactionally incremented membership generations, read with memberships
  under repeatable-read isolation; S09 checks the global generation across
  its two reads. This detects committed changes reversed between reads, but
  natural expiry does not increment a generation and neither mechanism is a
  commit fence. Deploy the S01 migration and response changes before the S09
  client; older responses fail closed. PostgreSQL tests cover backfilling
  pre-existing memberships, rollback, concurrent increments, scope moves,
  reversed changes and natural expiry. The clean selected reactor passed
  delegation (8), advisory (169), identity (41), catalog (24), evidence (27),
  assessment (50), knowledge (17), and web (110) tests; the live
  S01/S02/S07/S08 advisory process test also passed. The separate
  evidence/assessment process test initially failed because its existing
  provider fixture did not meet S04's observation schema. After updating the
  fixture, a clean rerun of the selected reactor passed all 448 tests,
  including both live process tests, with no failures or skips. This is not a
  full-reactor or production-acceptance result. A web client regression
  verifies that the additive S01 fields do not break current access
  deserialization; the web suite passed 110 tests.
- S02 authenticates short-lived RS256 delegation JWTs and checks claimed
  capabilities, owners, and runtime principals. S01 now issues narrowed
  delegations after checking the user's current membership authority. The
  local Keycloak realm and manually configured signing keys remain development
  fixtures, and downstream revocation propagation is incomplete; this is not
  production authorization.
- S01 now signs delegations with an RSA private key that is not configured in
  S02; S02 verifies with the matching public key and expected key ID. RSA keys
  shorter than 2048 bits and mismatched pairs are rejected. The delegation-token
  module has focused issuer, audience, key-ID, and maximum-lifetime tests. S02
  has PostgreSQL Testcontainers HTTP integration
  tests for migration, registration, idempotency, tenant separation,
  authorization checks, journey links, and invalid references.
- S01 now has an owner-local PostgreSQL schema for tenants, environments,
  trusted identity providers, principals, scoped memberships, and bootstrap
  audit state. Its opt-in first-admin bootstrap requires a future grant expiry,
  serializes initialization, and refuses an already-populated identity store;
  it accepts HTTPS or loopback HTTP for local development. S01 exposes
  tenant-scoped provider, principal, and current-membership reads alongside
  current-access and owner-candidate APIs. Its paginated current-context
  endpoint lists active contexts from current memberships; selected context IDs
  are selectors only, and S01 rechecks membership when evaluating access.
  Implemented directory reads, owner-candidate listing, membership revocation,
  and delegation accept the selected context pair instead of requiring token
  context claims. S01 issues narrowed, short-lived S02 delegations only after
  checking current membership authority. Membership revocation performs a
  revision-checked, idempotent mutation with audit and outbox records, and
  protects the final membership administrator. A revision-checked, idempotent
  principal-disable operation records a separate audit row and outbox event,
  preserves memberships, and protects the final membership administrator.
  Re-enabling returns 503 until fresh trusted-source verification and
  independent grant review are available. The outbox has no downstream
  revocation consumer yet, so already-issued S02 delegations may remain usable
  until expiry; cross-service owner-gap reporting is also pending. Trusted-
  provider principal admission and approval-gated membership creation are
  implemented in S01. UX01 now provides a CSRF-protected admission form that
  offers only S01-trusted providers and forwards the context-scoped,
  idempotent request through EDGE01. S01 remains authoritative for directory
  subject verification; admission creates no memberships or capabilities.
  UX01 tests cover provider filtering, validation, CSRF, request forwarding,
  and receipt handling. The isolated browser suite verifies S01 admission
  against the local Keycloak directory, confirms the admission receipt reports
  no runtime effect, and verifies that a directory reader without
  `identity.principal.manage` sees no form and receives an S01 denial on a
  CSRF-valid direct submission. The browser now exposes the separate
  approver-decision and membership-manager steps for approval-gated membership
  creation; live coverage verifies both cross-role denials.
- Keycloak is now available as a loopback-only local Compose service with an
  imported development realm. Its UX01 client emits the S01 audience and fixed
  `local-dev` / `local` scope claims; the `uacrf-s01-context` scope also maps
  the Keycloak user ID to the access-token `sub` claim required by S01. The
  import defines the requested profile scope and a reserved `.invalid` demo
  email so first sign-in needs no profile completion. This is a one-tenant
  development fixture, not production identity configuration. The
  browser app now supports OIDC authorization-code sign-in with PKCE, a
  server-side session, and CSRF-protected local sign-out. The OIDC client can
  keep its issuer and browser authorization endpoint externally reachable
  while using service-network token, JWK, and user-info endpoints. Its
  current-access view sends the session-held OIDC access token to UX01, which
  forwards the request through EDGE01 to a fixed S01 target; S01 remains
  authoritative.
  EDGE01 reports S01 connection failures as 502. Catalog reads and writes use
  a short-lived S01 delegation routed through EDGE01 to S02; the browser never
  receives that delegation. UX01 rejects requests without a bearer-shaped
  authorization header and propagates EDGE01's 502 when S01 is unavailable.
  Tokens are not rendered to the browser. The context selector displays
  paginated contexts from S01, revalidates posted selections against S01, and
  stores the selected context only in the server session. Current access then
  passes the selected context back to S01 for membership revalidation.
  Customer-journey list and detail
  views request a short-lived `catalog.journey.read` delegation from S01 and
  pass it server-side through EDGE01's local-only catalog routes to S02;
  neither token is rendered. Existing web tests exercise these views with
  mocked service clients, and the smoke topology verifies EDGE01 reaches both
  S01 and S02, retains each owner's
  unauthenticated response, and does not expose S01's internal delegation
  route. A repeatable Playwright suite under [`e2e/`](../e2e/) now exercises
  the Keycloak OIDC authorization-code flow with PKCE and separate
  browser-facing issuer/internal back-channel endpoints, then context
  selection and current-access evaluation through UX01, EDGE01, and S01. Its
  isolated Compose runner creates disposable PostgreSQL and Keycloak services,
  generates credentials and signing keys outside the repository, and removes
  only its uniquely named containers, network, and volume. All ten browser
  tests pass across three files: administrator and approver access with
  reloads, trusted-directory admission by the authorized administrator,
  read-only principal-directory access and S01 denial of a CSRF-valid direct
  admission submission by the approver, invalid-password rejection for both
  users, unauthenticated-route redirection, local-session logout, and denial
  of journey owner selection plus direct journey-creation and ownership-
  transfer submissions without catalog-write authority. The newly admitted
  principal is absent from the context-scoped principal list because
  admission adds no membership. The
  suite also verifies missing and invalid CSRF tokens cannot select a context
  or end the session, while valid context selection and logout still succeed.
  These checks use local HTTP and do not constitute a complete cross-origin
  or HTTPS review. S01 returned only the
  administrator's seven
  intended identity-management capabilities or the approver's two separately
  seeded capabilities; catalog write access remains denied. S02 is not running
  in this browser fixture, so the denied creation test is not a catalog-side
  persistence check. The checked-in
  membership-create contract requires S01 to verify an
  `authorization_ref` for an independent grant decision. S01 now implements
  that verifier (see "Independent grant authority" below). Do not seed catalog
  memberships or bypass the gate. The initial approver exists, but an
  authorized browser journey was blocked: the initial administrator
  cannot grant catalog authority to themselves, and `identity.owner.select`
  is not delegable to another catalog user. The user chose to keep that policy
  unchanged at the time. This decision has since been superseded:
  `identity.owner.select` is now grantable to human principals at global
  scope via the existing independent approval workflow. No grants are
  automatic, and self-grants remain prohibited. The browser also supports draft-journey
  registration: S01 supplies
  paged eligible human owners, the selected owner is revalidated before a
  write delegation is requested, and S02 receives a stable
  `cmd_<owner>_<uuid>` identifier with an idempotency key. The local bootstrap
  does not grant `catalog.journey.write`, so this flow remains denied unless
  that capability is independently authorized. Journey details also provide
  accessible step registration, service registration/linking, and agent-linking
  workflows. Service registration
  revalidates an S01 eligible human owner, requests `catalog.service.write`,
  and submits an idempotent S02 registration; a separate
  `catalog.journey.write` delegation links the created service to the journey
  or a currently listed step. The local bootstrap does not grant either
  catalog write capability, so these operations remain denied unless they are
  independently authorized. Agent workload registration now supports both
  standalone records and records anchored to an existing service. S01's
  service scope is carried in the short-lived delegation. S02 filters service
  and agent reads to that scope, requires matching scope for service/agent
  registration, and requires global scope for journey reads, writes, and
  capability links because journeys are not service-scoped. A V2
  migration preserves deployed V1 schemas while allowing standalone records.
  The browser now offers separate standalone and service-bound registration
  paths; S01 revalidates the selected human owner, and S02 validates the typed
  runtime-principal ID against the narrowed delegation before recording the
  workload. The local bootstrap still does not grant catalog write
  capabilities by default, so these operations remain denied unless they are
  independently authorized. S02 now also retains immutable workload-version
  manifests, activates them behind a workload revision fence, atomically
  supersedes the prior active version, and exposes only the current active
  version through a `catalog.agent.read`-protected internal receipt. That
  receipt accepts `profile_id` because version IDs are unique only within a
  workload. An unqualified ID matching multiple visible active workloads
  fails with `AMBIGUOUS_WORKLOAD_VERSION`; service-scoped queries only see
  matching service-bound workloads, and absent or out-of-scope targets return
  404. S09 supplies the profile ID and checks it against the returned receipt.
  PostgreSQL-backed tests cover duplicate IDs, tenant isolation, and service
  scope; a real S01/S02/S07/S08 process test prepares a run with two active
  workloads sharing a version ID. Deploy S02's query support before relying
  on unambiguous multi-workload selection by S09.
  The activation check requires an actor different from the version author, but
  the current delegation principal does not establish that the actor is human
  or provide a separate review record.
  Ownership transfer for JOURNEY and SERVICE is implemented through UX01,
  EDGE01's fixed internal catalog route, and S02. At submission, UX01
  revalidates the selected human owner with S01 and obtains a fresh,
  short-lived delegation containing only `ownership.transfer` (service-scoped
  for SERVICE). S01 permits the transfer operator, who has global
  `identity.owner.select` and non-grantable `ownership.transfer`, to obtain
  only the S02 target-read capability and list eligible owners without
  catalog-write authority. S02 requires `ownership.transfer` and the correct
  global or service scope; it checks the eligible-owner claim and expected
  revision, then atomically updates ownership and revision with immutable
  audit evidence, an idempotency response, and an outbox event. SERVICE
  transfers also append a service revision snapshot. Same-owner transfers are
  rejected, and a service referenced as an agent workload's internal anchor
  cannot be transferred; AGENT transfer remains out of scope. The focused
  S01, S02, and UX01 suites pass 122 tests (25, 12, and 85 respectively). The
  live browser suite transfers a journey and an unanchored service from the
  bootstrap operator to a separately admitted human with independently
  approved `owner.eligible`, then verifies the updated owner on readback. The
  operator has no catalog-write grant, and the service transfer precedes
  workload registration. A separate principal without transfer authority
  remains denied on both the journey creation and transfer paths.
  The selected-context membership page reads S01's active membership pages and
  submits revision-checked, idempotent revocations with an audit reason; S01
  remains authoritative and prevents removal of the final identity
  administrator. The principal directory displays S01's paginated records in
  the selected context and conditionally offers principal admission from
  trusted providers. Admission creates no memberships or capabilities.
  Approval-gated membership creation is exposed through distinct approver and
  membership-manager forms. Identity managers can disable an enabled
  principal with a required, audited reason and the current revision; S01
  remains authoritative and preserves memberships. The browser hides this
  action from readers without `identity.principal.manage`, and S01 denies
  direct unauthorized submissions. Re-enabling remains unavailable pending
  trusted-source verification and independent grant review.
  The rest of the customer workflow remains unimplemented.
- Local delegation configuration now uses RS256; place the private key only in
  S01 and configure the matching public key and key ID in S01 and S02, as
  described in [`.env.example`](../.env.example). Managed key storage and
  rotation remain unresolved. The S02 verifier requires the explicit
  `service_scope` claim and configured key ID, so deploy the S01 issuer and S02
  verifier as a coordinated change and allow pre-change 30-second tokens to
  expire before relying on service-bound agent registration.
- The local Compose file defines separate identity and catalog PostgreSQL
  instances with persistent volumes, separate superuser admin credentials, and
  restricted S01/S02 runtime credentials. Fresh volumes initialize the
  runtime roles; existing volumes require the explicit, non-data-deleting
  `tools/upgrade_local_database_roles.sh` migration after backup. Persistent
  Compose services have not been started.
- Full reactor verification passes with
  `./mvnw --batch-mode --no-transfer-progress verify`, including PostgreSQL
  Testcontainers HTTP integration tests for S01 and S02. Compose configuration
  validates, and the pinned Keycloak image successfully imported the realm in
  a temporary smoke test. The persistent Compose services have not been
  started. The latest root `verify`, after S03 acknowledgement validation,
  collection-token refresh, and S03/S04 aggregate-type sequencing, passed 590
  tests with zero failures, errors, or skips, including all five `m2-live`
  tests against rebuilt service jars.
- Java 25 and Docker are available in the development environment. The root
  Maven wrapper pins Maven 3.10.0 and verifies its download; the Docker builder
  image also replaces Maven's vulnerable bundled Guava with checksum-verified
  33.7.2. Maven's local dependency cache is removed from the final builder
  image. The image Trivy gate scans vulnerabilities only, blocking HIGH and
  CRITICAL findings in all five runtime images and the builder image; this has
  passed locally but has not yet run in GitHub Actions.
  The root Docker build context now excludes `private-docs/`. A real Docker
  regression test verifies that private files cannot be copied while ordinary
  source files remain available. Existing builder caches from before this
  exclusion may contain those files if they were present during a build;
  review shared or remote builder access and retention separately.
  Maven is not installed globally. Node.js v26.13.1 is available locally.

## Fresh-build delivery tracking

Delivery is measured only by implemented microservices behavior and tests
executed against this target. The latest full reactor verification passed 571
tests with zero failures, errors, or skips. CI is configured to run the MS01
architecture checker, which verifies checked-in inventory allocation,
contract ownership, and package-manifest hashes for frozen design artifacts.
The checker excludes the two QA files it regenerates and does not assert
runtime acceptance. CI is configured to run an offline Trivy filesystem secret
scan before the Maven build; the identical local command found no secrets. The
scan rejects a generated private-key canary locally and reports finding
locations and rules without printing secret values. The GitHub Actions build
for commit `08ebd73` passed, including the secret scan and Docker-context
checks; it does not cover subsequent uncommitted changes. The 107-route,
153-table and 131-requirement counts describe
inherited planning inventories, not target implementation coverage.
The identity integration suite also verifies that revoking a membership blocks
subsequent issuance of an S02-scoped delegation for that membership.

## Patent-reference handling

The seven patent specifications were consulted locally for their
implementation mappings. They remain outside this repository. Their
synchronization sections point to an older Technical Approach alignment
revision than the current revision-22/MS01 design; before implementing any
mapped algorithm, reconcile its schema, state, security, and failure
requirements against the current contracts. The older mapping describes a
read-only initial commercial scope, while the current roadmap includes later,
separately gated execution work. Resolve the release boundary before connecting
provider credentials or making release claims.

## M0 still open

- The CI workflow is configured to check the allocations for 144 operations,
  107 registered routes, 153 tables, 131 requirements, and the original 47
  operations, including route operation/contract mapping and duplicate/orphan
  detection. It also checks the design-package manifest's byte counts and
  SHA-256 hashes, except for the two generated QA outputs. This establishes
  consistency only for available artifacts; exhaustive discovery from the
  absent legacy source and live database introspection remain unavailable.
- UX01 now exposes trusted-provider principal admission through the existing
  S01 `POST /v1/principals` route via EDGE01; this does not add the route to
  the canonical operation inventory. Keep the route outside that inventory
  until source and release-boundary evidence supports the decision. The
  approval-gated membership browser flow is implemented as a separate
  approver-issued decision and membership-manager application; it uses the
  existing S01 contracts and does not create a persisted request inbox. The
  existing principal-status route supports disable, and UX01 now exposes the
  action from the selected-context directory with revision and idempotency
  checks. Re-enabling and distributed revocation remain gated by fresh source
  verification and current-authority propagation.
- Threat model and tenant/event compatibility review written
  (`docs/THREAT_MODEL.md`); its open items (key custody, deploy-time signature
  verification, CSRF review, runtime log/event leakage tests, rate limits, and
  event inbox) are tracked there.
- The complementary service-ownership gate is also configured in CI. It checks
  S01/S02 database configuration, Compose admin/runtime credential separation,
  application-role restrictions, service-module dependencies, and qualified
  schema references. Run locally with
  `python3 -m unittest tools.test_check_service_ownership` and
  `python3 tools/check_service_ownership.py`. The image smoke test now also
  proves each runtime database credential is rejected by the other database;
  broader deployment-level isolation evidence remains open.
- Image signing and SBOM attestation: `release.yml` scans before push, then
  pushes, cosign-signs and verifies each image by digest on `v*` tags. It now
  generates an SPDX JSON SBOM from each pushed digest and attaches and verifies
  a cosign attestation against the release workflow identity. Still open:
  first real tag run and signature/attestation enforcement at deployment.
- First bounded recovery action selected: `workload.set-replicas` with a local
  stub adapter; see `docs/FIRST_RECOVERY_ACTION.md`. The adapter itself is
  built in M5.

## Independent grant authority

S01 exposes `POST /v1/grant-decisions` (requires `identity.grant.approve`) and
`POST /v1/memberships` (requires `identity.membership.manage`), both routed
through EDGE01. `authorization_ref` is the id of a grant decision. Membership
creation succeeds only when the decision exists in the same context, is
unexpired (15 minutes) and unconsumed, matches the exact principal, capability,
scope and expiry, was made by a different principal than the creator and the
target, and its approver still holds a current `identity.grant.approve` grant.
The decision is consumed atomically with the membership, audit event and outbox
event. Grants are limited to a delegable capability set, reject wildcards and
self-grants, and are bounded to 365 days. `identity.owner.select` is
grantable only to humans at global scope; other identity administration
capabilities remain non-delegable through this route. PostgreSQL-backed S01
tests cover the grant restrictions; the identity suite passed 24 tests and
the updated grant test also passed separately. EDGE01 routing is tested.

UX01 now exposes this contract through two capability-gated browser forms:
`identity.grant.approve` records a decision and displays its 15-minute
authorization reference; a separate `identity.membership.manage` principal
applies the grant using that reference and the same principal, capability,
scope, and expiry. The reference is handed off through the organization's
trusted channel; there is no persisted request inbox or browser-side approval
queue. S01 remains authoritative on every mutation and atomically consumes the
reference with membership creation. The live browser suite verifies the
approver cannot create memberships, the administrator cannot record grant
decisions, and the separately approved membership appears only after the
manager applies it. The earlier focused UX01/experience-api reactor passed 84
tests; the current targeted `WebApplicationTests` run passed 87 tests. The
isolated Playwright suite passes 11 tests. Its authorized catalog path
creates a draft journey, registers and links a service, and verifies S02
readback after independent approval and membership assignment. It also registers
standalone and service-bound workloads after separately admitting and granting
runtime use to a trusted SYSTEM principal, then verifies both persisted records
through UX01 using only `catalog.agent.read`; the UI confirms no runtime action
occurred. Operator-only ownership transfer is verified by the live browser
suite. The isolated S03 publisher and collector client-credentials flows also
resolve to their admitted SYSTEM principals through EDGE01 and receive narrow,
approved delegations; ungranted catalog capabilities are denied. Production
deployment identity configuration and full M1 acceptance remain unverified.

## Trusted-provider principal admission

S01 exposes `POST /v1/principals` (requires `identity.principal.manage`),
routed through EDGE01. The handler verifies the provider is trusted, then reads
the subject from the provider's directory through a server-held connection
(`TrustedDirectory`; Keycloak admin API with a read-only service account,
`uacrf-s01-directory`). Subject, kind and display name come from the verified
directory entry, never from the caller. New principals hold no memberships.
Admission is idempotent, writes an audit row and an outbox event, returns 409
on an id or issuer/subject conflict, 404 for unknown provider or subject, 422
for disabled or inadmissible entries, and 503 `TRUSTED_SOURCE_UNAVAILABLE`
when the directory is unconfigured or unreachable. The directory is disabled
by default (`IDENTITY_DIRECTORY_ENABLED=false`); set the
`IDENTITY_DIRECTORY_*` variables and `KEYCLOAK_DIRECTORY_CLIENT_SECRET` to
enable it. The realm import was checked against Keycloak 26.8.2: the service
account can read users but is refused writes and client listing (it needs the
`kc-admin-api` scope for the admin API audience check). Compose now also passes
`KEYCLOAK_LOCAL_ADMIN_PASSWORD` to Keycloak, which the realm import already
referenced. Covered by one S01 integration test (stub directory), four
Keycloak client tests (mock server), and one EDGE01 routing test.

## First approver

The one-time bootstrap can create a separate first approver (settings in
`docs/SECURITY_AND_CONTRACT_POLICIES.md`). It holds only
`identity.directory.read` and `identity.grant.approve`, differs from the
administrator, and is recorded in `bootstrap_audit` (V6). The administrator
still cannot approve or self-grant. Covered by an S01 integration test showing
the approver can create a decision but cannot create memberships.

## M2 / S03 connections (in progress)

S03 (`services/connections`, port 8085, database `uacrf_connections`) owns
connections and source bindings: `POST/GET /v1/connections`, `GET
/v1/connections/{id}` and `POST/GET /v1/connections/{id}/source-bindings`.
Only provider `SAMPLE_LOCAL` becomes `ACTIVE`; AWS, AZURE and GCP are stored
`UNACCEPTED` with a visible reason and cannot be bound (409). Writes need the
global scope and an eligible owner. S01 issues S03 delegations (audience `S03`,
`connections.connection.read/write`), EDGE01 routes `/internal/connections/**`,
and S03's outbox carries `schema_version` and `payload_digest`. S03 is wired
into Docker, Compose, CI, release, the ownership checker and the image smoke
test (database isolation and the EDGE01 route verified locally).

Observation intake is built (`POST /v1/connections/{id}/observations`, needs
`connections.observation.write` and the global scope): always 202 with
ACCEPTED, DUPLICATE or QUARANTINED (`DIGEST_MISMATCH`, `REPLAY_CONFLICT`,
`STALE_SEQUENCE`); unbound sources get 409. Sequence gaps are accepted.

The opt-in collection runner claims active source bindings through persisted
per-source checkpoints and expiring leases, invokes only registered
`ProviderCollector`s, and submits bounded batches through the existing S03
intake. It uses a dedicated OAuth client-credentials identity and obtains a
narrow S01 delegation for `connections.observation.write`; a checkpoint
advances only after all intake results are durably accepted, duplicated, or
quarantined. Failed and unimplemented collections retain the prior checkpoint
for retry, and provider-supplied retry times are honored. The runner is disabled
by default and fails at startup if enabled without a nonblank client ID/secret
or absolute HTTP(S) token, delegation, and intake URLs. The secret must be
configured outside the repository; validation does not establish production
credential validity.
Its identity, delegation, and intake HTTP calls use bounded 2-second connect
and 3-second response timeouts; timeout failures retain the prior checkpoint
and replay any persisted batch unchanged.
During each collection cycle, cached identity and delegation tokens refresh
five seconds before their reported expiry, measured from the start of the
token request using monotonic time. Tokens without `expires_in` are not cached;
invalid lifetimes fail the attempt without advancing the checkpoint. Focused
tests simulate short, reusable, absent, and invalid lifetimes with HTTP
fixtures and verify a retained batch completes without recollection.
The runner renews leases before provider calls and each outbound request, and
stale lease tokens cannot renew after another runner takes ownership.
Failure to record a lost lease is logged without aborting unrelated claims in
the same collection cycle. Sources are claimed one at a time up to the cycle
limit, avoiding expiry of leases held by queued sources.
AWS, Azure, and GCP remain
UNACCEPTED, and synthetic sample fixtures are never scheduled as provider
data. STS, Config, and CloudWatch SDK calls have a 10-second per-attempt timeout
and a 30-second total API-call timeout. The opt-in outbox publisher now shares
the runner's bounded 2-second connect and 3-second response timeouts and rejects
incomplete identity/endpoints or a nonpositive batch size at startup. A slow
identity-endpoint test verifies a token-endpoint timeout leaves its outbox row
pending and succeeds on retry. S03 also requires the S04 acknowledgement's
`event_id` to match the sent event before treating `RECEIVED`, `DUPLICATE`, or
`QUARANTINED` as terminal; a missing or mismatched ID leaves the row pending.
The full connections module suite passed 70 tests, and its delegation-auth
dependency passed 8 tests (78 total in the selected reactor), with no failures,
errors, or skips. This includes bounded runner and publisher identity calls,
AWS SDK timeout, and active lease renewal cases. The latest
targeted runner/application/publisher rerun passed 27 tests, including rejection
of incomplete identity/endpoints, timeout retry, stale-token fencing, and
processing after lease loss. The focused `OutboxPublisherTests` rerun passed
7 tests, including missing and mismatched acknowledgement IDs for each
terminal status and the S04 `QUARANTINED` path: the row is marked published,
excluded from the delivered count, and not retried on the next cycle. These
adverse responses are simulated by the publisher test's HTTP stub and are not
process-level S04 quarantine evidence. A separate `m2-live` process test
creates a connection through S03 HTTP and verifies its scheduled publisher
persists the event in the real S04 inbox. The test uses S01's public
principal-admission API with a test-only directory stub, a separate approver's
grant-decision API, the administrator's membership API, and S01's real
delegation endpoint; a broader ungranted capability is denied. A test proxy
returns 503 after S04 persists the first event; the S03 retry receives
`DUPLICATE` and then marks the outbox row published. The targeted process test
passed 1/1 using the rebuilt S03 publisher after acknowledgement-ID validation.
The subsequent full reactor passed all five `m2-live` tests after the
acknowledgement-ID, collection-token, and aggregate-type changes. The process test now creates a source binding and an accepted observation with
the same aggregate ID as their connection and verifies all three sequence-1
events persist with `CONNECTION`, `SOURCE_BINDING`, and `OBSERVATION` aggregate
types; its targeted post-extension rerun passed 1/1. The
client-credentials endpoint, directory and OIDC signing/JWKS remain
test fixtures, so this does not verify the production IdP or production
identity. Real provider collection and production deployment identity
configuration remain unverified. The isolated
browser suite also verifies the dedicated `uacrf-s03-collector` client-credentials
identity resolves to an admitted SYSTEM principal with only
`connections.observation.write` and obtains the matching narrow S03
delegation; the runner remains disabled, so this is not collection execution
or production deployment-identity evidence.

The S03/S04 aggregate collision is addressed by carrying `aggregate_type` into
the inbox and including it in sequence locks, stale-sequence checks, uniqueness,
and integrity reporting. Migration V8 classifies retained S03 events and
preserves ambiguous legacy quarantines conservatively. The S03/S04 live process
test verifies a connection, source binding, and accepted observation with the
same aggregate ID all reach the inbox at sequence 1 with distinct aggregate
types.

Sample data lives in `sample-data/` and is generated and validated by Java
(`./mvnw -pl sample-data test`, add `-Duacrf.sample.regenerate=true` to rewrite).
It holds a local estate, a multi-cloud (AWS, Azure, Google Cloud)
provider-shaped estate ingested through `SAMPLE_LOCAL`, and a graph fixture that
covers all 58 node labels and 55 relationship types of the data dictionary.
These are never provider inventory or accepted evidence.

### Where to implement real provider connections (S03)

Provider collectors live in `services/connections/src/main/java/org/uacrf/connections/`.
They return provider observations only; a partial collector does not make a cloud
connection accepted. Current implementation state:

| Provider | Class | Implemented | Remaining |
| --- | --- | --- | --- |
| AWS | `AwsCollector` | `aws.config.resources`; bounded SQS and selector-based CloudWatch metrics | Live conformance |
| Azure | `AzureCollector` | Regional `azure.resourcegraph.rows`; bounded `azure.monitor.metrics` | Live conformance |
| Google Cloud | `GcpCollector` | Project-wide `gcp.assetinventory.assets`; bounded `gcp.monitoring.timeseries` | Live conformance |

The AWS collector uses the AWS SDK default credential chain for STS role
assumption. STS, Config, and CloudWatch API calls have a 10-second per-attempt
timeout and a 30-second total timeout; this bounds hangs without establishing
provider conformance. Its bounded CloudWatch slice accepts
`aws/<region>/cloudwatch/sqs/<queue-name>` for the two SQS queue metrics, or
`aws/<region>/cloudwatch/metric/<base64url-selector>` for one exact metric,
statistic, and dimensions supplied by the source binding. Both use a
five-minute period, a maximum 24-hour window, half-open point filtering, and
the S03 payload limit; results flow as `aws.cloudwatch.metrics` observations
through the existing intake. This does not enumerate metric descriptors or
establish live conformance.
Azure uses `DefaultAzureCredential` with credentials configured
outside the repository; its `read_only_identity_ref` is the subscription GUID,
and `azure/<region>/resourcegraph/inventory` is a regional Resource Graph source.
It projects only resource identifiers and basic inventory metadata, pages at 100
rows, and preserves `$skipToken`. It refuses to advance when Resource Graph
reports truncation without a continuation token. Its bounded Azure Monitor slice
accepts one resource-scoped metric source in the form
`azure/monitor/<base64url-resource-id>/<metric-name>`; the unpadded Base64URL
resource ID must belong to the configured subscription. It queries the
resource-specific Azure Monitor Metrics REST API using the default metric
namespace, `Average` aggregation, five-minute intervals, and a maximum 24-hour
window. Returned points are restricted to the half-open request window and
retained as `azure.monitor.metrics` observations through S03 intake. This
resource-specific collector does not enumerate metric definitions, select
non-default namespaces, or establish live conformance. Google Cloud uses Application Default Credentials to list project-wide Cloud
Asset Inventory resources, retaining only name, asset type, and update time;
pages of 100 use `nextPageToken`, and the source region does not restrict assets.
Its bounded Cloud Monitoring collector accepts
`gcp/monitoring/<base64url-metric-type>/<resource-type>` and queries one
project-scoped metric type and monitored resource type through
`projects.timeSeries.list`. It reads full time series with at most 100 points
per page, retains native metric/resource labels and point values, and carries
the original time interval in its continuation checkpoint. Partial responses
and oversize observation payloads do not advance that checkpoint. The runtime
identity needs `monitoring.timeSeries.list`, normally granted by
`roles/monitoring.viewer`. This collector does not enumerate metric descriptors
or aggregate/reduce series. AWS, Azure, and GCP connections stay `UNACCEPTED`
until live read-only conformance verifies
pagination, permission denial, source outage, rate limits, checkpoint recovery,
and out-of-order delivery. The sample-data fixture is not provider conformance
evidence. The latest connections/delegation reactor passed 74 tests (66
connections and 8 delegation-auth), including the bounded provider metric tests,
with no failures, errors or skips. No live provider conformance was performed.

## M2 / S04 evidence, qualification, and waiver workflows

`services/evidence` (port 8086, audience `S04`, schema and database `uacrf_evidence`)
retains evidence events and decides qualification.

UX01 provides a paginated, read-only S04 evidence metadata view through EDGE01,
using a context-bound S01 delegation for `evidence.event.read`. It identifies
sample-only records and does not expose payloads or treat a recorded
qualification state as proof of current usability. S03 connection observations
do not automatically become S04 evidence. Invalid upstream pages and denied
or unavailable reads are reported rather than shown as empty results.
An optional service ID requests an exact-service delegation and is retained
across pages; an empty selection requires global authority. UX01 rejects a
service-scoped response containing an unbound or differently bound record.
The clean web-module suite passed 105 tests with no failures, errors or skips.
The isolated live browser suite passed all 11 tests with S04 and its own database
added to the stack. A separately admitted SYSTEM fixture writer receives only
`evidence.event.write`; the browser reader receives `evidence.event.read` via
independently approved memberships. The S04 image's required contract schemas
are now included in the Docker build context; their prior omission prevented
the image from starting. A separate human with only a service-scoped evidence
grant is denied global and different-service reads, and can read the matching
synthetic service-bound record through the browser.

S04 evidence list, detail, qualification receipt and measurement reads now
filter service-scoped delegations to records with the same `service_id`;
unbound records are visible only to global readers. Pagination filters before
applying the limit, and mismatched or unbound detail/receipt reads return 404.
The separate resource- and purpose-scoped topology summary contract is unchanged.
The evidence and delegation modules passed 35 tests; a subsequent targeted
PostgreSQL test also verified pagination past records outside the delegated
scope. Production identity, provider conformance, and full M2 acceptance
remain unverified.

- `POST /internal/v1/observations` retains submitted payload text. Admission
  computes its UTF-8 bytes once to verify SHA-256 and byte length. Accepted rows
  retain the byte length alongside the validated payload digest; quarantined rows
  retain the actual digest, claimed digest and byte length. Submit responses expose
  both digests and the length, while evidence reads expose the retained length.
  Migration V7 backfills lengths from the stored UTF-8 text and reconstructs a
  historical quarantine's actual digest only where the reason guarantees the
  claimed digest was previously validated; historical `DIGEST_MISMATCH` rows
  remain unknown. No separate binary payload copy is stored.
  Digest mismatch, replay conflict and stale per-source sequence are quarantined,
  never dropped.
  Exact replays must match source, class, sequence, kind, timestamp at PostgreSQL
  precision, payload and digest. Transaction-scoped ID and source locks serialize
  concurrent submissions. Migration V4 retains source class, timestamp, kind and
  submitter for new quarantine rows; historical metadata remains unknown.
- Sample data (`SAMPLE_LOCAL`) is always `SAMPLE_ONLY` and can never be qualified;
  the database also enforces this and the independent-reviewer rule by CHECK.
- Provider evidence starts `UNVERIFIED`; `POST /v1/evidence/{id}/reviews` needs
  `evidence.qualification.review`, a reviewer who is not the submitter, and the
  expected revision.
- Provider observations are assembled into the canonical versioned observation
  envelope and checked against the shared Draft 2020-12 contract before
  retention. Accepted and quarantined provider records retain `service_id` and
  `schema_version`; schema failures are quarantined as `SOURCE_SCHEMA_INVALID`.
  The payload digest remains over the exact stored payload string. This is
  contract validation, not live provider conformance; sample-local evidence
  remains sample-only.
- Qualification-policy proposals and decisions are validated against the
  proposed OpenAPI request schemas and stored as immutable, tenant- and
  environment-scoped revisions with idempotent writes, authorized reads and
  history. S01 exposes explicit read/write/review grants; review grants are
  restricted to HUMAN principals at an exact service scope. Policy approval and
  `current_eligible` are governance metadata only and never qualify evidence or
  authorize an action.
- Qualification-gap waivers now support proposal, scoped list/read, independent
  review, and revocation. A proposal must name an explicitly waivable requirement
  on the current, approved, unexpired policy revision and carries a bounded
  observation window, gap types, expiry and reason. Approval requires a compact
  RS256 assertion bound to the retained waiver digest and a reviewer-specific,
  operator-provisioned S04 public-key trust set; trust revisions, key identity
  and assertion digests are retained. Waiver records, decisions, revocations and
  idempotency receipts are immutable. S01 exposes separate waiver read/write/
  review/revoke grants, all at exact service scope, with review restricted to
  HUMAN principals. `current_eligible` reflects current policy, waiver expiry,
  revocation and trust state only; waivers do not qualify evidence or authorize
  actions. Configure public trust material with
  `UACRF_QUALIFICATION_WAIVER_TRUST_SET_JSON`; signer private keys remain outside
  S04 and source control.
- `POST /internal/v1/qualifications/{id}:receipt` returns whether evidence is usable
  for a purpose given an expected digest and cutoff.
- `POST /internal/v1/qualifications/{id}:measurement` internally attests parsed
  measurement fields from the qualified, digest-checked owner payload at the cutoff;
  it does not return the raw payload.
- Both receipt types require the observation and its independent qualification
  to be no later than the requested cutoff (the boundary is inclusive). A
  qualified observation timestamped after the cutoff is unusable, with an
  explicit reason; PostgreSQL-backed tests cover both sides of the boundary.
- Both receipt types fail closed with a conflict reason when a retained replay
  conflicts with the accepted ID, or a retained replay or stale sequence conflicts
  with its source and sequence. Unrelated stale sequences and digest mismatches
  alone do not invalidate it. The original qualification state is preserved.
  These checks use the same database statement snapshot as the receipt: previously
  misclassified and unretained conflicts cannot be recovered, and a conflicting
  submission may arrive after a receipt has been issued. Consumers still need
  safeguards for decisions made later.
  PostgreSQL integration tests cover concurrency, timestamp precision, tenant
  isolation, unaffected submissions and migration of legacy quarantines.
- Measurement receipts now require the retained evidence kind to start with
  `measurement.` and have a nonempty suffix, in addition to the existing
  qualification, cutoff, digest and payload checks. Measurement-shaped payloads
  under other evidence kinds remain unusable. This prevents a configuration
  observation from being treated as a measurement; it does not define a broader
  source-kind registry or provider attestation contract.
- `POST /internal/v1/inbox/events` is the consumer-side inbox (digest, duplicate,
  per-aggregate-type sequence checks, quarantine). A duplicate must match the retained
  source service, event type, aggregate type and ID, sequence, payload and digest; reusing an
  ID with changed metadata is quarantined. Transaction-scoped locks serialize
  concurrent deliveries for both event ID and aggregate. New quarantine rows
  retain the event type;   rows predating migration V2 have an unknown (`NULL`)
  type rather than a fabricated value. Migration V8 backfills aggregate types
  from the three known S03 event types, preserves other retained event types,
  and narrows sequence uniqueness to include aggregate type. Legacy quarantines
  with no event type inherit a matching accepted event's aggregate type when
  available; otherwise the integrity view conservatively applies them to any
  matching type. PostgreSQL-backed HTTP tests cover
  changed envelopes, simultaneous conflicting IDs and a V1-to-V2 upgrade with
  existing quarantine data. Migration V3 adds a local `inbox_event_integrity`
  view that identifies accepted events with retained conflicting IDs or
  aggregate sequences, including old quarantine rows. It does not expose a
  service API, establish aggregate-wide eligibility, or detect conflicts that
  were previously misclassified and never retained. `NO_KNOWN_CONFLICT` is a
  point-in-time observation, not proof of future consistency. The S03 publisher
  below delivers to the inbox; retaining an event is not a downstream domain
  effect or broker crash-recovery acceptance. No consumer uses this view yet;
  future consumers must block ambiguous context and address conflicts arriving
  after a check before making decisions.
- S01 registers `evidence.event.read|write`, `evidence.inbox.write` and
  `evidence.qualification.review`, plus
  `evidence.qualification.policy.read|write|review`.
- Wired into Dockerfile, compose, CI, release, ownership check and image smoke test.
- EDGE01 routes only `GET /v1/evidence`, `GET /v1/evidence/{id}` and
  `POST /v1/evidence/{id}/reviews` under `/internal/evidence`; the `/internal/v1`
  intake/qualification endpoints and all qualification-policy routes are
  deliberately not routed through the edge.
- S03 `OutboxPublisher` (off by default, `UACRF_OUTBOX_PUBLISHER_ENABLED`) delivers
  pending `outbox_event` rows to the S04 inbox. It signs in to Keycloak with the
  client-credentials grant, asks S01 for a 30-second S04 delegation limited to
  `evidence.inbox.write`, and marks rows published only on RECEIVED, DUPLICATE or
  QUARANTINED (retained by S04) with the matching event ID. A missing or
  mismatched acknowledgement ID leaves the row pending. When enabled, it fails startup on blank client
  identity, non-absolute/non-HTTP(S) token/delegation/inbox URLs, or nonpositive
  batch size. Its HTTP calls use a 2-second connect and 3-second response timeout;
  errors and timeouts leave rows pending for retry, including when S04 accepted
  delivery but the response was lost. The digest is recomputed over the exact
  jsonb text sent. The module publisher tests use a stub S04 inbox. A separate
  `m2-live` process journey starts S03, S04, and the shared S01 fixture with
  separate PostgreSQL databases, registers a connection through S03 HTTP, and
  verifies the event in S04's real inbox through S01's real delegation API.
  S01's public principal-admission API uses a test-only directory stub, a
  separate bootstrap approver creates the grant decision, and the administrator
  creates the membership through the public API; an ungranted capability is
  denied. The client-credentials endpoint and OIDC signing/JWKS are test
  fixtures, so this does not verify the production IdP or production identity.
  S01 now accepts audience `S04` and
  delegates `evidence.inbox.write` to SYSTEM principals only.
- The Keycloak realm now defines the confidential client `uacrf-s03-publisher`
  (service account id `...0d2`, `uacrf-s01-context` scope, secret from
  `KEYCLOAK_S03_PUBLISHER_CLIENT_SECRET`). Still needed before enabling the publisher:
  admit that service account as a SYSTEM principal through the S01 admission flow,
  grant it `evidence.inbox.write` through an approved grant, and set
`UACRF_S03_CLIENT_SECRET` for S03. Operator trust provisioning, live
S01-to-S04 grant/delegation conformance, and live provider conformance remain
outstanding. The focused evidence reactor passed 24 tests (20 evidence HTTP
integration tests and four topology-summary tests); the identity HTTP suite
passed 23 tests, with no failures, errors or skips. This is focused local test
evidence, not full-reactor or live multi-service acceptance; operator trust
provisioning and live S01-to-S04 grant/delegation conformance remain outstanding.

## M2 / S05 objectives (first slice)

- `services/objectives` (port 8087, audience S05, schema/database `uacrf_objectives`) owns
  workload objective revisions and mandatory organization constraints.
- UX01 provides a paginated objective browser via EDGE01 and S01;
  its optional workload ID is a filter, not an authorization scope. Ordinary
  objective list, detail, and import-preview status reads require a global
  `objectives.objective.read` grant. A service-scoped grant is not treated as
  a workload grant even when the IDs match, because workload-to-service
  ownership is not verified for these reads. The internal active-objective
  receipt also accepts S01's explicitly workload- and purpose-bound topology
  exchange delegation for the exact workload and matching selection or
  classification purpose. Global receipt reads remain available. No grant is
  widened automatically. The browser reports invalid or unavailable upstream
  responses and distinguishes recorded state from measured compliance.
  The clean web suite passed 110 tests and the objectives/delegation reactor
  passed 23 tests, with no failures, errors or skips; the updated objective
  application suite also passed 10 tests. The isolated browser suite passed all
  11 tests with S05 and a separate database. Its two explicitly synthetic DRAFT
  fixtures were inserted directly into the disposable database, so this is not
  an objective proposal, approval, activation or ownership-validation test.
  A separately admitted user with only a matching service-scoped objective
  grant was denied both global and filtered browser reads.
  The earlier five-test live M2 process suite passed, but its `test` command
  did not repackage service JARs and therefore cannot establish that it
  exercised the latest S05 changes. The rebuilt-service targeted verification
  is recorded in the S06 section below.
- UX01 also creates local objective drafts through EDGE01 using a separately
  granted global `objectives.objective.write` delegation. The creation form
  retains its key and inputs on uncertainty and checks the signed-in user and
  context before retry. S05 atomically retains the actor/context-scoped
  idempotency key, objective and outbox event; exact retries return the
  objective's current recorded state and changed content conflicts. The
  dedicated route rejects targets that cannot fit `numeric(24,6)` without
  rounding and rejects imported provenance. UX01 verifies returned objective
  content against the request. This is a draft, not an import, approval,
  activation, measurement or compliance determination. Optional S02
  workload-owner verification is a point-in-time check, not an atomic fence.
  The clean UX01 suite passed 182 tests and the selected S05/EDGE01 reactor
  passed 56 tests, with no failures, errors or skips. The isolated browser
  suite passed 11 tests, including synthetic local draft creation, unchanged
  replay, changed-content conflict and denied write access. Its optional S02
  owner check was disabled; this is not owner or provider acceptance.
- UX01 now reads an objective using a separate global read delegation and
  submits the reviewed draft revision and content digest using a global write
  delegation. The retry-safe S05 route checks the draft's revision, digest and
  mandatory constraint, and atomically records the submission key, transition
  and outbox event. Unchanged retries return the current recorded state; changed
  requests conflict. The browser retains the original form for an unconfirmed
  outcome and validates the returned ID, digest, state and revision. Submission
  requests independent review; it is not approval, activation or compliance.
  The clean S05/EDGE01 reactor passed 58 tests, UX01 passed 186 tests, and the
  isolated synthetic browser suite passed 11 tests. Browser tests do not
  establish workload-owner or provider acceptance.
- UX01 now displays a submitted objective and its provenance using separate
  global read access and records approval or rejection using a separate global
  review delegation. S05 binds the actor/context-scoped idempotency key to the
  objective ID, expected revision, content digest, decision and exact reason;
  the key, independent decision and outbox event commit together. Exact
  retries return the current objective, while changed requests conflict.
  The browser checks the reviewer, reason, state, revision and digest on the
  returned record and preserves the form for unchanged retries after
  uncertainty. The digest does not cover every displayed field; the revision
  binds the record. The receipt exposes the current recorded decision, not a
  complete review history. This does not activate the objective, prove
  compliance or atomically fence current cross-service authority. The clean
  S05/EDGE01 reactor passed 60 tests, the clean UX01 suite passed 190 tests,
  and 11 isolated browser tests passed, including independent approval and
  rejection, replay, conflict, self-review denial and denied reads. The
  browser stack disables the optional S02 owner check and is not provider
  acceptance.
- Separate transitions with expected revisions: propose (DRAFT) -> submit -> decide
  (APPROVE/REJECT by a principal other than the proposer) -> activate (SUBMITTED,
  APPROVED, ACTIVE). Activation supersedes the active objective for the same
  workload and metric; a partial unique index allows only one ACTIVE per workload and
  metric.
- Targets are exact decimals with a comparator, unit and window. Organization
  constraints (`PUT /v1/organization-constraints/{metric}`) bound unit and target
  range and are enforced at submit and again at activation, so a later rule change
  blocks stale approvals.
- Imported objectives must carry source, definition id, observed revision and
  verification time (database CHECK plus API validation); local ones cannot.
- `POST /internal/v1/workloads/{id}/objectives:receipt` returns the exact active set
  and a set digest. Events (proposed, submitted, decided, activated, superseded) go
  through the transactional outbox; no publisher yet.
- S05 now durably admits objective-import preview commands through
  `POST /v1/objective-imports:preview` and exposes their status at
  `GET /v1/objective-imports/{id}`. Admission requires `objectives.objective.write`,
  global scope and an idempotency key. The tenant/environment/actor-scoped command,
  canonical request digest and outbox event commit together; exact retries return
  the same receipt, while key reuse for a different request returns a conflict.
  Status reads require `objectives.objective.read` and global service scope;
  earlier exact-workload matching is no longer accepted for ordinary grants.
  The response remains `PENDING` with
  `runtime_verification=NOT_ESTABLISHED`: no connector is resolved, no source is
  fetched, and no candidate is normalized or compared. The API Edge forwards the
  idempotency key and preserves the owner's 200/202 response. This is request
  admission only; it is not import completion or a provider/source acceptance claim.
  The focused `./mvnw -q -pl services/objectives,services/api-edge -am test`
  gate passed 36 S05 and API Edge tests with no failures, errors or skips.
- S01 registers the `objectives.*` capabilities and issues S05 delegations. EDGE01
  routes only the public operations under `/internal/objectives`.
- S01 now restricts new `objectives.objective.review` grants to human principals
  and global scope, and refuses new review delegations to non-humans. S05 requires
  global scope and a reviewer distinct from the proposer. Existing memberships
  are not removed: legacy scoped human memberships can still yield scoped
  delegations, which S05 rejects. Previously issued non-human delegations remain
  valid until expiry; see the optional activation recheck below.
- S01 exposes a narrow internal `POST /internal/v1/objective-review-authority:check`
  for a specified reviewer and tenant/environment. A directly authenticated,
  currently admitted SYSTEM caller needs a global `objectives.review-authority.check`
  membership; new grants of that capability require independent approval, global
  scope and a SYSTEM principal. It cannot be delegated. S01 checks in one
  database statement that the caller remains authorized and the reviewer is an
  enabled human with a trusted provider and an unexpired, unrevoked global
  objective-review grant. A successful response is an unsigned point-in-time
  snapshot, not an activation permit or an atomic fence; later revocation can
  change the answer. EDGE01 does not route it.
- If `UACRF_OBJECTIVES_REVIEWER_CHECK_ENABLED=true`, S05 checks the retained
  reviewer's current S01 authority before activating an approved revision. The
  gate defaults to false; when disabled, activation keeps its existing behavior
  and does not perform this check. Configure the `UACRF_OBJECTIVES_REVIEWER_CHECK_*`
  token URL, S01 base URL, client ID and secret, admit the S05 service account
  as a SYSTEM principal, and independently grant it
  `objectives.review-authority.check` in each relevant context.
  The local Keycloak realm now defines the `uacrf-s05-objective-reviewer`
  confidential service-account client with the S01 context/audience scope. Set
  `KEYCLOAK_S05_OBJECTIVE_REVIEWER_CLIENT_SECRET` to the same value as
  `UACRF_OBJECTIVES_REVIEWER_CHECK_CLIENT_SECRET`; the client is not admitted
  or granted S01 authority automatically.
  The token must have S01's audience. URLs require HTTPS; HTTP is accepted only
  for numeric loopback addresses when the explicit loopback flag is true. Both
  calls have two-second timeouts and responses are size-limited. S05 validates
  the returned context, reviewer, status and check time (allowing five seconds
  of clock skew); failure returns `REVIEWER_AUTHORITY_UNAVAILABLE` without
  activating or superseding. The test uses real S05/PostgreSQL and simulated
  S01/OIDC. The check is unsigned and point-in-time: no atomic fence prevents
  revocation after the response, and it does not recheck the activator or run
  at decision time. S05 does not retain the check result as a signed receipt.
- S05's opt-in workload check now runs before objective proposal and activation.
  S01 accepts S05 assertions only for the proposal/activation receipt purposes,
  checks the corresponding existing objective capability and global scope, and
  issues a resource- and purpose-scoped S02 delegation. S02's
  `POST /internal/v1/workloads/{id}:current-receipt` checks that the delegated
  principal is the workload's current catalog `owner_id`, its parent service
  and active workload version are active, and the retained manifest digest is
  valid; it then signs an S05-audience receipt. S05 verifies the signature,
  actor, tenant/environment, workload, purpose, eligibility, revision and
  validity window. Denial, stale/ineligible state, malformed proof, or service
  unavailability fails closed before proposal persistence or before activation
  can supersede an existing objective. The check is disabled by default;
  enabling it requires the S02 receipt verification key and HTTPS S01/S02
  endpoints (numeric loopback HTTP is permitted only with the explicit
  development flag). Focused S01/S02/S05 tests passed: 75 total (38 identity,
  23 catalog, 14 objectives), with no failures, errors or skips.
  The M2 Testcontainers gate exercises the enabled check across real S01, S02
  and S05 HTTP services: the exact catalog owner can propose and activate,
  while an otherwise authorized non-owner is denied before proposal
  persistence and activation without superseding the current objective. The
  suite passed four live journeys with no failures, errors or skips. This is
  local integration evidence, not deployed or provider acceptance. The signed
  receipt is point-in-time, is not persisted by S05, and is not an atomic
  fence: an ownership or workload change after S02 responds can race the later
  S05 commit.
- Not yet built: source-backed preview/candidate processing, approved connector
  resolution, mapping and expected-revision rechecks, conflict resolution,
  an import worker/outbox publisher, complete current-authority/fencing checks,
  retired templates, and material change invalidation of pending actions.

## M2 / S06 topology (first slice)

- `services/topology` (port 8088, audience S06, schema/database `uacrf_topology`) is the sole authority, backed by PostgreSQL. Neo4j is provisioned in Compose for the S06 projection; only S06 writes it. The off-by-default projection worker is lease-based, idempotent, reads back projected content and verifies the accepted revision digest before a graph can be eligible. Reconciliation reports missing/extra topology and makes drifted receipts ineligible.
- Generic proposal, graph, subgraph, impact, reconciliation and internal graph
  receipt reads require global `topology.graph.read`. A service-scoped grant
  previously reached tenant-wide results; matching an arbitrary graph scope
  or a node ID does not prove service ownership of all returned content.
  These reads fail closed until an owner-verified boundary is implemented.
  Service-bound selection/classification commands and their status retain their
  existing exact-service scope checks; no grant is widened.
- The topology/delegation reactor passed 50 tests with no failures, errors or
  skips. After rebuilding the S01, S02, S05, S06 and S07 service JARs, two
  focused M2 process tests passed: the authorized selection used a locally
  signed, service-scoped fixture assertion and real S01 resource exchanges,
  while the drift test exercised the S05 objective receipt and change
  classification. The live test also verified that generic graph and receipt
  reads are denied for that scope and command status is denied for a different
  scope. Memberships and caller assertions were test fixtures; production
  identity conformance and broader M2 acceptance remain unverified.
- Public operations are routed by EDGE01 under `/internal/topology`: `POST/GET /v1/dependency-proposals`, `POST /v1/dependency-proposals:evidence-backed`, `GET /v1/dependency-proposals/{id}`, `POST /v1/dependency-proposals/{id}:review`, `POST /v1/graphs:accept`, `GET /v1/graphs/{id}`, `GET /v1/graphs/{id}/subgraph`, `GET /v1/graphs/{id}/impact`, `GET /v1/graphs/{id}/reconciliation`, `POST /v1/graphs/subgraphs:select`, `POST /v1/graphs/differences:classify`, and `GET /v1/commands/{id}`. The private `POST /internal/v1/graphs/{id}:receipt` endpoint is not routed.
- The evidence-backed path currently accepts only human-authored `agent_workload DEPENDS_ON agent_workload` proposals. S06 obtains short-lived, resource/purpose-bound delegations from S01, signed active-version receipts from S02, and a bounded qualified-evidence summary from S04. It rechecks owner state during proposal, review, and current-use receipt preparation; stale or unavailable evidence fails closed without rewriting historical accepted revisions. Separate S02 RSA signing-key and S06 public-verification-key settings are required before enabling this path. A focused local process test now exercises real S01, S02, S04 and S06 for proposal, independent review, graph projection and current-use receipt. It verifies `SAMPLE_LOCAL` cannot be qualified or used and that a replay conflict makes the qualified S04 receipt unusable and the projected S06 receipt ineligible. The provider-format observation and caller assertions are synthetic fixtures; real provider and production identity conformance remain unverified. See [the M2 runtime-validation report](../integration-tests/m2-live/runtime-validation-report.md) for the precise scope.
- PostgreSQL owns `permitted_relationships`, `dependency_proposals`, `graph_revisions`, `graph_nodes`, `graph_edges`, `graph_edge_conflicts`, `graph_projections`, and `outbox_event`. New revisions receive a `PENDING` projection row transactionally; existing revisions are backfilled. Projection status is `PENDING`, `PROJECTING`, `PROJECTED`, `FAILED`, or `DRIFTED`; only `PROJECTED` with a matching verified digest is eligible. Proposals are tenant/environment scoped and constrained by a relationship allowlist. Observed proposals require source system, source revision, and verification time; authored proposals forbid those observed-source fields. Reviews use expected revisions and require a reviewer distinct from the proposer.
- Accepting a graph requires the expected current revision for its scope. Accepted proposals are merged deterministically into an immutable graph revision with a canonical digest; new digest version 2 binds edge provenance and proposal IDs, while historical version 1 revisions retain their prior verification semantics. Repeating the same graph content does not create another revision. Conflicting provenance for duplicate accepted relationships is recorded in `graph_edge_conflicts`. Graph acceptance/conflict events are written transactionally to the outbox. The internal receipt reports the projection row's current status and is eligible only after digest verification.
- S02 now retains immutable service-revision snapshots with deterministically ordered `owned_agent_workload_ids`; membership changes append a new revision, and no historical membership is fabricated. S06 verifies the exact signed S02 receipt, retained snapshot and digest, then uses that membership only as a bounded set of selection roots; it never infers dependency edges from catalog membership. Async selection and difference-classification commands are persisted with an outbox event, idempotency, status lookup, and stable expected-revision targets. Selection binds the accepted graph revision, S02 snapshot, purpose-scoped current S07 case receipt and bounded S04 summaries, then retains a digest and bounded typed node/edge references. `action-relevant-dependencies.v1` preserves the bounded dependency/failure-domain selection; `.v2` additionally selects accepted reachable `READS_FROM` edges from represented `service`/`agent_workload` nodes to `shared_resource` nodes. New `.v3` preserves v2 and adds the point-in-time current S05 active-objective set for the S07 case workload as owner-derived context only; it creates no SLO graph node or `GOVERNED_BY_SLO` edge. S06 requires the case workload in the exact S02 service membership and uses a resource-scoped S01 delegation to read and digest-check S05's bounded active-objective receipt. Classification re-reads that set for the same case workload; each owner response is a point-in-time snapshot, and a changed set digest forces `INDETERMINATE` without treating objective changes as graph widening, narrowing or target changes. The retained query ID binds classification to the same selection semantics. Classification emits advisory dependency, failure-domain and v2/v3 data-read deltas; exact same-source read-target replacements are target-changing, while mixed or unpaired read deltas are indeterminate. The public selection result also carries a digest-bound, count-only reconciliation between the exact S02 membership snapshot and `agent_workload` nodes in the exact accepted graph. Unknown S02 membership is `INDETERMINATE`; nodes outside the service membership are counts, not inventory defects. This is not a provider-inventory, freshness or completeness claim. S06 now accepts canonical `MEMBER_OF_FAILURE_DOMAIN` relationships, normalizes legacy `IN_FAILURE_DOMAIN` proposals, and interprets retained legacy edges equivalently. Read-edge properties do not establish freshness. SLO topology relationships and measurement evaluation, broader ontology, and freshness coverage remain incomplete or indeterminate; these operations do not write graph edges. The focused S06/S01 reactor passed 40 tests (31 topology and 9 identity; no failures, errors or skips), and the generated-contract suite passed 6 tests. The API-only Testcontainers gate now runs S01, S02, S05, S06 and S07 as live HTTP processes with isolated PostgreSQL databases and the S06 Neo4j projection worker enabled. Four objective/topology journeys verify S02's signed service receipt and S07's case lifecycle/receipt through their real services, the projected graph digest/reconciliation/eligible graph receipt, and S05's exact workload-owner proposal and activation checks. A fifth local process journey verifies S03 connection registration and outbox delivery into the real S04 inbox through the real S01 delegation endpoint. It uses a test-signed OIDC token and test JWKS; the SYSTEM publisher is admitted through S01's public principal API using a test-only directory stub, a separate approver creates its `evidence.inbox.write` grant decision, and the administrator creates its membership through S01's public membership API. An ungranted `connections.connection.write` request is denied. All five focused m2-live tests passed after this enhancement; the prior 584-test root verification predates the S01 delegation and public admission/grant test enhancements, and the root reactor was not rerun. S07 external owner receipt verification remains disabled in this bounded gate; Kafka, artifact storage, live provider conformance, browser UX and full M2 acceptance remain unverified. See [the M2 runtime-validation report](../integration-tests/m2-live/runtime-validation-report.md).
- After the earlier full-module run, the live S03/S04 process test injected a 503 after S04 persisted the event; S03 retried and S04 returned `DUPLICATE`. The targeted test passed 1/1; the subsequent full-module rerun passed 5/5, with 0 failures, 0 errors, and 0 skips.
- The latest canonical full Maven reactor passed 590 tests, including all five `m2-live` tests, after S03 acknowledgement-ID validation, collection-token refresh, and aggregate-type sequencing. It supersedes the earlier 588- and 584-test verifications and the historical "root reactor was not rerun" statements above. After the same-ID connection/source-binding/observation assertion was added, the targeted S03/S04 process test passed 1/1. The focused evidence inbox suite passed 7 tests, and the S03 publisher suite passed 7 tests, both with zero failures, errors, or skips. These are code/test-suite results only; production identity, provider acceptance, and full M2 acceptance remain open.
- Still needed: SLO graph relationships and objective-to-measurement evaluation,
  broader classify/select ontology and freshness coverage, and
  S10 consumption pinned to an exact graph revision. S06 produces its own
  domain events; they are not routed through S03.

## M3 / S07 assessment (first slice)

- `services/assessment` (port 8089, audience S07, database/schema `uacrf_assessment`) provides fixed assessment cases, deterministic evaluation, and non-executable recommendations. EDGE01 routes the public operations under `/internal/assessment`; the private `/internal/v1/cases/{id}:receipt` endpoint is not routed.
- `POST /v1/cases:idempotent-create`, also routed by EDGE01, requires
  `Idempotency-Key` and global `assessment.case.write`. A key of 1–200
  non-whitespace characters is scoped to tenant, environment, and actor.
  Reservation, creation and binding commit in one transaction. Equivalent
  canonical content replays the same case in its **current** state; different
  content conflicts. Source kinds are sorted and decimal thresholds normalized;
  matching uses the current Jackson serialization and may conflict after a
  serialization change. The existing `POST /v1/cases` remains unkeyed and
  offers no retry protection. Deploy S07 and EDGE01 with the new route before
  UX01: older versions fail closed rather than silently dropping the key.
  Neither route verifies objective or graph references on creation.
- UX01 offers a CSRF-protected draft form using only that keyed route and a
  context-bound S01 delegation containing `assessment.case.write`. It checks
  input and the returned case content, preserves the key and input when an
  error response is rendered, and refuses a retry under a changed context.
  A user must retry an unconfirmed submission unchanged with the same actor
  and context, or check for an existing case before opening a new form; the
  form does not recover its key across a lost session or a newly opened form.
  Creating a draft does not seal, evaluate, verify references, or authorize
  action. The clean S07 and EDGE01 focused suites passed 36 and 2 tests; an
  additional focused S07 run checked competing different-content requests. The
  clean web suite passed 123 tests, followed by seven focused client tests. An
  isolated 11-test browser run created, replayed,
  and read back a synthetic draft and checked denied creation.
- UX01 can review a case's retained content and seal a draft through EDGE01.
  The review uses `assessment.case.read`; the CSRF-protected confirmation
  separately requests `assessment.case.write`. S07's expected-revision check
  prevents an old or repeated request from sealing a changed case; the browser
  treats a timeout, invalid receipt or conflict as unconfirmed and links back
  to the current case state rather than claiming success. Context changes
  prevent submission. Neither creation nor sealing verifies the supplied
  objective or graph references, qualifies evidence, performs evaluation, or
  authorizes action. S07 sealing is not an idempotent command: an exact replay
  after a successful seal returns a conflict. The clean web suite passed 126
  tests; a subsequent nine-test focused run checked conflict and receipt
  validation. The isolated browser suite passed 11 tests, including a real
  draft-to-sealed transition, stale replay conflict, and denied sealing.
- `POST /v1/cases/{id}:idempotent-evaluate`, also routed by EDGE01, requires a
  key and global `assessment.evaluation.run`. The key is scoped to tenant,
  environment and actor. Evaluation, optional investigation-only recommendation,
  outbox events and key binding commit together; identical canonical content
  returns the original historical result without rechecking owners, and
  changed content conflicts. The original `:evaluate` route remains unkeyed.
  S07 and EDGE01 must be deployed before UX01; the browser never falls back to
  the old route. Canonical matching depends on current serialization.
- UX01 submits a CSRF-protected evaluation for a sealed case with a separate
  run delegation. It preserves the key and input on rendered errors, rejects
  context changes, and validates the receipt. An unconfirmed request must be
  retried unchanged by the same actor in the same context; a lost session or
  newly opened form cannot recover its key. Empty measurements yield
  `INSUFFICIENT_EVIDENCE`. `SKIPPED` verification does not establish evidence
  qualification; even an investigation-only recommendation is non-executable
  and no evaluation authorizes action. Synthetic measurements are not live
  provider acceptance. The clean web suite passed 129 tests. Focused S07 and
  EDGE01 suites passed 38 and 3 tests, including competing same-key inputs,
  rollback, authorization and historical replay; an additional focused test
  verified tenant and environment key isolation. The isolated 11-test browser
  run created and sealed a case, submitted and replayed an empty evaluation,
  confirmed one retained `INSUFFICIENT_EVIDENCE` / `SKIPPED` result, and
  checked denial without the run grant.
- `POST /v1/incidents:idempotent-create`, also routed by EDGE01, requires a key
  scoped to tenant, environment and actor. Reservation, creation and binding
  commit together; canonical matching sorts and deduplicates evidence IDs and
  depends on current serialization. An identical retry returns the original
  historical receipt without rechecking owners; changed content conflicts.
  The existing unkeyed route remains available, but UX01 never falls back to
  it. Deploy S07 and EDGE01 before UX01. New creation still requires a sealed
  matching case, a VERIFIED evaluation and current S04/S05/S06 owner-receipt
  revalidation. UX01 uses a separate incident-write delegation and preserves
  the form and key on rendered failures, but cannot recover a lost key after
  a lost session or opening a new form. An unconfirmed request must be retried
  unchanged by the same actor in the same context. An incident is historical
  and does not authorize action. The clean web suite passed 135 tests; focused
  S07 and EDGE01 suites passed 40 and 4 tests, including competing requests,
  actor isolation, rollback, authorization and historical replay. The isolated
  11-test browser suite checked that skipped/synthetic evidence is rejected,
  the form and key remain available, and an ungranted user is denied; it did
  not demonstrate successful browser creation with verified evidence.
- `POST /v1/learning-records:idempotent-propose`, also routed by EDGE01,
  requires a key scoped to tenant, environment and actor. The proposal,
  outbox event and binding commit together; canonical matching sorts and
  deduplicates evidence IDs and depends on current serialization. An identical
  retry returns the original proposal; changed content conflicts. The original
  unkeyed route remains available, but UX01 never falls back to it. Deploy S07
  and EDGE01 before UX01. Creation checks the incident revision and requires
  evidence IDs retained on that incident; it does not revalidate owner evidence.
  UX01 reads the incident using `assessment.incident.read` and submits a
  CSRF-protected proposal with a separate `assessment.learning.write`
  delegation. It preserves form and key on rendered failures, but cannot recover
  a lost key after a lost session or opening a new form. Retrying an unconfirmed
  request requires the same actor, context and unchanged content. A proposal
  does not establish review, adoption, current qualification or permission to
  act. The clean web suite passed 138 tests; focused S07 and EDGE01 suites
  passed 41 and 5 tests, including competing requests, actor isolation,
  rollback, authorization and duplicate-event prevention. The isolated 11-test
  browser suite submitted and replayed one proposal linked to a directly
  inserted synthetic incident, verified one retained record, and denied an
  ungranted user. This does not demonstrate qualified evidence or review.
- Case, evaluation and recommendation reads, including internal case receipts and
  material, now require the relevant capability and global (`*`) service scope.
  Previously a service-scoped delegation could read records outside its scope.
  This is deliberately fail-closed: even a scope matching a workload ID is
  denied until S07 can verify workload-to-service ownership, including
  service-bound agents. Global-scoped, tenant/environment-isolated reads remain
  available; no existing grants are automatically widened.
- UX01 exposes a read-only assessment-case list through EDGE01. S01 issues a
  tenant/environment-bound global delegation containing only
  `assessment.case.read`; the optional workload filter is checked against the
  returned cases. Denied, unavailable, and malformed reads produce visible
  errors rather than an empty result. The isolated browser suite passed all 11
  tests, including filtering and denial for a separately admitted user; its two
  synthetic draft cases were inserted directly into the disposable S07
  database, bypassing case creation. The clean web module suite passed 114
  tests with no failures, errors, or skips. S07's case list is unpaginated. These
  checks do not establish case-creation, evaluation, provider, or milestone
  acceptance, and a sealed case is not authority to act.
- Case pages link to read-only evaluation histories with a separate
  `assessment.evaluation.read` delegation. The page shows each retained outcome,
  observation cutoff, mode, and point-in-time receipt-verification status and
  reason. It does not revalidate evidence or authorize an action. Invalid or
  case-mismatched responses fail closed. The clean web module suite passed
  116 tests with no failures, errors, or skips. The isolated browser suite
  passed 11 tests, covering a failed-verification result, empty history, and
  denied access. Its case and evaluation rows and placeholder digests were
  inserted directly, bypassing case creation, sealing, and evaluation; they
  are not qualified evidence. The history API is unpaginated.
- The case list also links to workload-level recommendations using a separate
  `assessment.recommendation.read` delegation. The browser accepts only
  non-executable `INVESTIGATE_BREACH` / `INVESTIGATE_ONLY` records matching
  the requested workload and presents them as historical investigation
  suggestions, not current qualification or authority to act. The clean web
  suite passed 118 tests; a subsequent five-test focused client run also
  verified workload query encoding. The isolated browser suite passed 11 tests,
  including a synthetic recommendation, empty workload, and denied access.
  The synthetic records and placeholder digests bypass assessment workflows;
  the unpaginated list does not establish real recommendation production or
  provider acceptance.
- UX01 lists incidents for a case and learning proposals for an incident through
  EDGE01 with 50-item cursor pages. The respective delegations contain
  `assessment.case.read` plus `assessment.incident.read`, or
  `assessment.incident.read` plus `assessment.learning.read`. The browser
  validates parent identifiers, page ordering, cursor shape and displayed
  fields; an invalid, unavailable or denied response is not an empty page.
  Legacy incident records without retained source-evaluation IDs are labeled
  explicitly. The pages are historical reads and do not establish current
  evidence qualification, adoption or action authorization. The clean web
  suite passed 132 tests, and the isolated 11-test browser suite exercised both
  paginated paths and denied access. Its incident and learning rows were
  inserted directly with synthetic references and placeholder digests, bypassing
  creation, qualification and review.
- When `UACRF_ASSESSMENT_RECEIPTS_ENABLED=true`, every evaluation obtains short-lived S01 delegations as the S07 service account and verifies each measurement against the qualified S04 owner payload, current S05 active objectives, and S06 projected graph eligibility. Verification failures force `INSUFFICIENT_EVIDENCE`, retain a reason code and received owner receipt facts in the dependency vector, and cannot produce a recommendation. Verification is off by default; disabled evaluations retain `receipt_verification: SKIPPED` and caller-supplied measurements are not verified. Before enabling it, grant the S07 SYSTEM principal `evidence.event.read`, `objectives.objective.read`, and `topology.graph.read` through normal S01 membership administration.
- S07 now retains structurally valid, explicitly unusable S04 measurement receipts
  for the requested evidence ID and purpose, including the owner's reason and
  nullable measurement fields. They remain `SOURCE_UNVERIFIED` and cannot produce
  a recommendation. Mismatched IDs or purposes are not retained as matching
  rejected receipts. An opt-in integration test runs real S04 and S07 processes
  with separate PostgreSQL databases, test-signed delegations and simulated S01,
  S05 and S06: qualification initially yields a verified evaluation, and a
  later S04 replay conflict makes a new evaluation insufficient while preserving
  the owner reason and the earlier snapshot. It also checks S04 audience and
  tenant denial. Run it with
  `./mvnw -Padvisory-cross-service-tests -pl services/evidence,services/assessment,integration-tests/advisory -am -Dtest=EvidenceAssessmentCrossServiceTests -Dsurefire.failIfNoSpecifiedTests=false package`.
  This does not test real S01 grant issuance or real S05/S06, refresh prior
  evaluations after a conflict, or eliminate conflicts arriving after a receipt.
  Retained receipts remain point-in-time evidence.
- Immutable model-version governance is implemented at `/v1/model-versions`: candidate metadata is immutable and tenant/environment scoped; independent reviews require verified S04/S05/S06 evaluation receipts and a passing workload result for approval. Outbox events are transactional. Normal S01 grants control the distinct `assessment.model.read`, `assessment.model.write`, and `assessment.model.review` capabilities; there are no automatic grants. Commands are disabled by default and can be enabled with `UACRF_ASSESSMENT_MODELS_ENABLED=true`.
- Model review and promotion now reverify each referenced evaluation against current
  S04/S05/S06 owner receipts and require the retained objective set, graph and
  measurement digests/revisions to match. Disabled verification, stale or
  unavailable owners, and incomplete snapshots fail closed; an unsuccessful
  promotion does not supersede the active version. A real S04–S07 process test
  confirms a later evidence conflict blocks both review and promotion, with
  simulated S01/S05/S06. These remote checks are sequential point-in-time reads,
  not atomic authorization; later changes do not automatically deactivate an
  active version or change historical evaluations.
- Before model-review revalidation, S07 now recomputes each retained evaluation's
  result digest and fails closed with `EVALUATION_DIGEST_MISMATCH` when it does
  not match. A PostgreSQL-backed regression test confirms a mismatch cannot
  approve the model or emit the review decision event. The digest still depends
  on current Jackson serialization and is not a versioned canonical format.
- **Model promotion is blocked:** workload evaluations do not bind results to a
  model artifact or prove model accuracy, calibration, or safe operation. Even
  with both feature flags enabled and fresh owner receipts, SHADOW and ADVISORY
  promotion returns `MODEL_EVALUATION_REQUIRED` until model-specific evaluation
  and acceptance are implemented. Existing active rows are not changed or
  automatically deactivated. `APPROVED` and any pre-existing `ACTIVE` status are
  governance metadata, not evidence of model readiness or permission to execute.
  PostgreSQL and separate S04–S07 process tests cover the gate; S01/S05/S06 are
  simulated in the process test.
- Internal model-evaluation declarations retain immutable, tenant/environment-scoped
  lineage bound to the stored model-version digest, with attribution and a
  transactional outbox event. Creation requires `assessment.model.write`,
  global service scope and the model feature flag; reads require
  `assessment.model.read` and remain available when the flag is disabled.
  Declared SHA-256 digests and time windows are validated syntactically, with
  nanosecond precision retained, but artifacts are not fetched or verified.
  `DECLARED_UNVERIFIED` does not prove model performance, data separation or
  readiness and cannot unblock promotion. These endpoints are internal only.
  `GET /internal/v1/model-evaluation-declarations?model_version_id=...`
  lists declarations for one model version in ID order, with `limit` (default
  50, maximum 100) and `after` set to the previous `next_cursor`. Each page is
  a separate read, not a snapshot: declarations inserted before the cursor can
  be missed until the listing is restarted.
- Incident retention is implemented at `/v1/incidents`: creation requires a sealed case for the same workload and a VERIFIED evaluation no later than `observed_from`, with usable qualified S04 measurement receipts and retained S05/S06 receipts. Before insertion, S07 revalidates the current owner receipts for evaluations covering every requested evidence reference. It chooses the newest qualifying evaluations first and fails closed on a selected evaluation's stale, incomplete or unavailable owner evidence, without falling back to older evaluations. Revalidation covers all measurements retained in each selected evaluation, including ones not requested by the incident. It is a sequence of point-in-time owner checks, not an atomic guarantee against later changes. New incidents atomically retain the selected evaluation IDs in `source_evaluation_ids`, in selection order; older incidents report `null` because their exact lineage is unknown. A database insert guard requires every non-null ID to be unique and to reference an evaluation in the same tenant, environment and case; migration fails on existing invalid lineage rather than modifying immutable rows. The guard checks referential integrity, not receipt freshness or evidence coverage, which remain application checks; the nullable column also permits legacy unknown lineage. Existing incidents are not revalidated or rewritten. A real S04–S07 process test verifies that a later S04 replay conflict blocks new incident creation while preserving an earlier incident; S01, S05 and S06 are simulated. Rows are tenant/environment scoped, attributable, revisioned, and immutable. `GET /v1/cases/{id}/incidents`, also routed through EDGE01, lists a case's incidents with `assessment.case.read` and `assessment.incident.read`, global scope and tenant/environment isolation. It returns `items` and `next_cursor` with an ID-ordered keyset cursor, default limit 50 and maximum 100. Pages are independent reads: concurrent inserts before the cursor require restarting to discover them. S01-managed `assessment.incident.read`, `assessment.incident.write`, and `assessment.learning.write` grants are not automatic.
- Learning records are immutable proposals at `/v1/learning-records`, linked to an incident with a revision fence and evidence limited to that incident. Their transactional `uacrf.s07.learning.proposed.v1` outbox event records attribution and PROPOSED state. `GET /v1/learning-records/{id}` reads a retained proposal with an S01-granted `assessment.learning.read` capability and global service scope; it is tenant/environment scoped and is also routed through EDGE01. `GET /v1/incidents/{id}/learning-records`, also routed through EDGE01, lists that incident's proposals with both `assessment.learning.read` and `assessment.incident.read`, global scope and tenant/environment isolation. It returns `items` and `next_cursor` with an ID-ordered keyset cursor, default limit 50 and maximum 100. Pages are independent reads: concurrent inserts before the cursor require restarting to discover them. The read capabilities are not automatically granted.
- An independent human can record one immutable learning review with `POST /v1/learning-records/{id}:review` when `UACRF_ASSESSMENT_LEARNING_REVIEW_ENABLED=true`; the flag defaults to false. S01 restricts `assessment.learning.review` grants to humans with global scope and delegates it only to humans; S07 requires global scope and rejects the proposer, stale revisions and duplicate decisions. Approving requires retained incident provenance, verifies that qualifying evaluations cover the proposal evidence, and rechecks every selected evaluation against current owner receipts. Unknown legacy provenance cannot be approved but can be rejected. Checks are sequential point-in-time reads; later owner changes do not revoke an existing decision. New incidents retain nanosecond observation cutoffs for these comparisons; older rows use their existing database timestamp precision and may fail closed at a sub-microsecond boundary. `GET /v1/learning-records/{id}/review` remains readable with `assessment.learning.read` even when review commands are disabled. The decision and its outbox event are transactional; the proposal remains `PROPOSED`, and approval does not adopt, execute or change any policy, model or workload. A real S04–S07 replay-conflict process test covers the freshness gate with simulated S01, S05 and S06.
- UX01 displays the retained proposal before a CSRF-protected review, requests
  separate `assessment.learning.read` and `assessment.learning.review`
  delegations, and validates returned decision details. A changed context
  blocks submission. The review command is not idempotent: a repeated decision
  conflicts, so an unconfirmed response hides the submit form and directs the
  user to the historical decision read instead of automatically retrying. A
  failed decision read, including HTTP 404, is not presented as proof that no
  review occurred. The page warns that historical approval is not current
  qualification or authorization to act. The clean web suite passed 141 tests,
  followed by 131 focused web tests including missing-decision handling. The
  isolated 11-test browser suite explicitly enabled review, rejected self-review
  and approval of a synthetic fixture, recorded and read an independent
  rejection, rejected a duplicate and denied an ungranted user. The fixture
  approval did not establish the owner-evidence gate or real approval; the
  normal deployment default remains disabled.
- Before approval or review-receipt issuance, S07 checks the retained proposal's typed content against its separately stored incident and evidence references, recomputes its digest and compares that digest with exactly one retained proposal outbox event. Mismatches and missing events fail closed with `LEARNING_INTEGRITY_MISMATCH`; rejection and historical reads remain available. This detects inconsistencies, not malicious coordinated changes to all copies, and does not verify the full event payload. The digest uses the current Jackson serialization rather than a versioned canonical scheme, so serialization changes can cause existing proposals to fail verification.
- Approval and receipt issuance also recheck that the proposal's expected incident revision matches the retained incident and that every proposal evidence reference belongs to that incident. Evidence appearing in a supporting evaluation alone is insufficient. An inconsistent legacy proposal or retained approval fails closed with `INCIDENT_PROVENANCE_INVALID`; historical decisions remain readable.
- Internal `POST /internal/v1/learning-records/{id}:review-receipt` requires `assessment.learning.read`, global scope and the enabled learning-review flag. It returns an unsigned point-in-time verification of an approved review, its proposal and review digests, contributing evaluation IDs and a local check time, with `authorizes_adoption=false`; it is not the target signed owner-receipt protocol. It rechecks the retained review digest against its outbox event and revalidates the evidence with current owners; missing, rejected, changed or unavailable evidence fails closed. It does not check the entire outbox payload or create an authorization. The review digest uses S07's existing Jackson serialization, not a versioned canonical format; serialization changes or removal of the retained event can make receipt verification fail. Checks are sequential and do not prevent subsequent changes. The endpoint is not exposed through EDGE01 or consumed by S10.
- S07 can retain an internal adaptation draft with `POST /internal/v1/adaptation-proposals` when `UACRF_ASSESSMENT_ADAPTATION_ENABLED=true` and learning review is enabled; both default off. Creation requires global-scope `assessment.learning.write` and `assessment.learning.read`, and a fresh successful learning-review verification. It stores that point-in-time check and both learning digests alongside a bounded proposed target type, owner, controlled-version reference, reason, tests, proposed reviewer and rollback criteria. These caller declarations are **unverified**; `DRAFT_UNVERIFIED` does not verify owner, version, reviewer independence, tests or rollback feasibility and does not submit, approve, adopt or execute a change. The immutable tenant/environment-scoped draft and its local `uacrf.s07.adaptation.proposed.v1` outbox event commit together. The event is not published or a permission; its content digest uses current Jackson serialization. `GET /internal/v1/adaptation-proposals/{id}` and paginated `GET /internal/v1/adaptation-proposals?learning_record_id=...` require global-scope `assessment.learning.read` and remain available with either flag disabled. Listing uses ID order, default limit 50, maximum 100 and an `after` cursor; independent pages can miss concurrent inserts before the cursor. Creation requires an `Idempotency-Key` token (1–200 characters, no whitespace), scoped to tenant, environment and actor. Concurrent identical requests return the same retained draft; reuse with different content returns `IDEMPOTENCY_CONFLICT`, and failed creation leaves the key reusable. Replay requires both flags and capabilities but returns historical data without rechecking owners; it is not current approval. Request matching hashes the current Jackson serialization, so serialization changes can cause a replay conflict. No route is exposed via EDGE01, no target-owner workflow is invoked, and later evidence changes do not alter historical drafts; a future submission must revalidate current authority, source evidence and the target owner's own requirements.
- Remaining work: implement forecasts and forecast execution, learning adoption and the outbox publisher, and integrate S10 consumption. Model governance, incident retention, proposal retention and review decisions do not enable model execution or production policy changes.

## M3 / S08 knowledge and retrieval (conservative first slice)

- UX01 `/knowledge` reads S08 metadata through EDGE01 using a current
  tenant/environment-bound S01 delegation and global `knowledge.item.read`.
  PUBLIC is the default; INTERNAL or RESTRICTED explicitly requests only the
  corresponding additional classification capability and denial does not fall
  back. Exact item and purpose filters are validated and appear in the URL.
  Returned classifications and filter matches are checked before rendering;
  failures are visible rather than shown as empty results. The list is
  unpaginated and includes historical lifecycle states, not content or proof
  of current eligibility. Source references are inert text, never fetched.
  A disposable browser test used directly inserted synthetic metadata to
  verify public/internal visibility, another tenant's exclusion, denied
  restricted clearance and denied ungranted access. These fixtures bypass the
  knowledge lifecycle and do not establish provider acceptance. The clean web
  suite passed 146 tests and the isolated browser suite passed 11 tests.
- UX01 `/knowledge/new` stages plaintext through a new keyed S08
  `POST /v1/knowledge-items:idempotent` route forwarded by EDGE01; the legacy
  unkeyed route remains unchanged. It obtains a global, context-bound
  `knowledge.item.write` delegation and validates the returned candidate,
  request fields, and source digest. S08 commits the candidate, scoped key,
  request digest and original staging receipt in one transaction. Concurrent
  identical submissions return that original receipt, including after a
  later review; changed content with the same key conflicts, and failed
  creation does not reserve the key. Keys are scoped by tenant, environment
  and actor. The request digest uses current Jackson serialization, so future
  serialization changes may cause a replay conflict. The retained original
  receipt is historical and not current state, approval or authorization.
  Staging does not emit a staging outbox event. The browser test stages and
  replays synthetic plaintext and verifies one visible candidate; an
  ungranted user is denied. These tests do not establish provider acceptance.
  Clean focused tests passed 19 S08, 1 EDGE01 and 125 UX01 tests; the isolated
  browser suite passed 11 tests.
- UX01 supports independent review of a candidate's plaintext through new S08
  candidate-content and version-bound review routes, both forwarded by
  EDGE01. Both require global `knowledge.item.read` and
  `knowledge.item.review`, plus the applicable classification clearance.
  Candidate bytes are verified against the source digest and rendered as
  escaped plaintext. The bound review locks the candidate and checks its
  version, revision and digest before enforcing independence and deadline, so
  a stale form cannot decide a replacement candidate. The existing unbound
  `:review` API remains available for compatibility and does not provide
  this version binding; UX01 uses only `:review-bound`. Review is not keyed:
  after an uncertain result UX01 hides the form and directs the user to
  current metadata, but an unavailable or missing read does not prove a
  decision did not occur. A successful response is historical, not current
  eligibility or action authority. The isolated browser suite verified
  self-review denial, independent rejection and an ungranted content-read
  denial with synthetic data; it did not demonstrate approval or provider
  acceptance. Clean focused tests passed 20 S08, 1 EDGE01 and 128 UX01 tests,
  and 11 browser tests passed.
- UX01 supports irreversible withdrawal of an approved knowledge item using
  S08 `:withdraw-bound` through EDGE01. Confirmation reads current metadata
  using a context-bound read delegation; submission requires global read and
  withdrawal capabilities and applicable classification clearance. S08 locks
  the approved version and checks its exact version, revision and source
  digest before withdrawal. The legacy unbound `:withdraw` remains available
  for compatibility and is not safe against a replacement version with the
  same revision; UX01 does not use it. Withdrawal is not keyed. An uncertain
  submission hides the form and directs the user to current metadata; a failed
  or missing read does not prove that withdrawal did not occur. Withdrawal
  permanently prevents new versions and subsequent candidate approval. Clean
  focused tests passed 19 S08, 1 EDGE01 and 131 UX01 tests; a subsequent
  focused S08 test also verified wrong-digest and scoped denial. The isolated
  11-test browser suite independently approved and withdrew synthetic
  material, denied an ungranted withdrawal and read back the withdrawn state.
  This is not provider acceptance.
- UX01 `/knowledge/retrieve` creates a manifest through the new keyed S08
  `POST /v1/retrievals:idempotent` route via EDGE01 and a context-bound
  `knowledge.retrieval.run` delegation with explicit classification clearance.
  The key is scoped to tenant, environment and actor; the request digest,
  manifest and outbox event commit together. Concurrent identical requests
  retain one manifest, changed requests conflict, and failed creation leaves
  the key reusable. Replays return the same manifest ID but recheck current
  source eligibility and, for verified cases, the live case binding; excerpts
  can therefore be concealed after withdrawal, expiration or case change.
  These checks are point-in-time and do not establish requester authority for
  protected inference. Request matching uses current Jackson serialization,
  so future serialization changes can cause replay conflicts. UX01 validates
  the response and excerpt digests and escapes content; the query is retained.
  The legacy unkeyed route remains available. Clean focused tests passed 21
  S08, 1 EDGE01 and 135 UX01 tests; a further S08 test verified concealment
  after a verified case changed. The 11-test isolated browser suite verified
  synthetic retrieval and replay, ungranted denial and concealment after
  withdrawal. Its case binding was `NOT_VERIFIED`; it is not provider
  acceptance.
- UX01 can recheck a known retained retrieval ID through EDGE01 using a
  separate context-bound `knowledge.retrieval.read` delegation and explicitly
  selected classification clearance. It validates the returned ID,
  classification ceiling, manifest metadata and available excerpt digests;
  excerpts are escaped and owner-side current case/source checks can conceal
  them. Denial or malformed data is visible, not an empty result or fallback.
  The lookup supports a known ID.
  Clean focused web tests passed 138 tests, with additional tests for
  mismatched IDs and denial. The 11-test isolated browser suite verified an
  ungranted read denial and readback after synthetic source withdrawal;
  this does not establish provider acceptance.
- UX01 `/knowledge/retrieval-history` browses bounded, ID-ordered S08
  summaries through EDGE01 using a context-bound, globally scoped
  `knowledge.retrieval.read` delegation and explicit classification
  clearance. S08 filters by tenant, environment and the delegation's
  classification ceiling; it returns no queries or excerpts. Page size is
  1–100 (UX01 requests 50), and an exclusive ID cursor continues the listing.
  Pages are independent reads: concurrent inserts before a cursor require
  restarting. Summaries are historical metadata, not current eligibility;
  following a link performs the separate owner-checked retrieval read. Clean
  focused tests passed 22 S08, 1 EDGE01 and 140 UX01 tests, with additional
  tenant and page-boundary checks. The isolated 11-test browser suite verified
  synthetic history navigation, ungranted denial and concealed readback after
  withdrawal; it does not establish provider acceptance.
- `services/knowledge` (port 8090, audience S08, database/schema
  `uacrf_knowledge`) owns its own retained material, version lifecycle,
  retrieval manifests, and transactional outbox. Its PostgreSQL database has a
  distinct runtime role and Compose service. S01 issues the six
  `knowledge.*` capabilities; review capability membership is restricted to
  human principals, and S08 review additionally checks capability, global
  scope, independence, expected revision, and deadline.
- `POST /v1/knowledge-items` accepts bounded UTF-8 plaintext only (maximum
  256 KiB), stores a source SHA-256 digest computed by S08, and creates a
  CANDIDATE. `source_ref` is inert metadata and is never fetched. Per-item
  version bodies are immutable; independent review moves candidate versions to
  APPROVED or REJECTED, and a newer candidate does not shadow the previous
  approved version. Classification access derives from explicit S01
  `knowledge.classification.internal.read` and
  `knowledge.classification.restricted.read` grants; callers cannot elevate
  their own clearance by setting `max_classification`. Metadata reads are
  tenant/environment scoped, filtered by classification, and can filter by
  allowed purpose.
- `POST /v1/retrievals` implements exact-term tokenized search only. It
  produces deterministic, non-overlapping chunks of at most 1,000 characters,
  ranks exact query-term matches deterministically, and retains offsets,
  source/excerpt digests, purpose, classification, and a manifest digest.
  Workload-targeted material remains excluded until an owner-verified workload
  scope is implemented; a caller-supplied workload ID is rejected and cannot
  authorize access.
  `GET /v1/retrievals/{id}` rechecks the retained source and excerpt digests
  and only returns excerpt text while its source version remains APPROVED,
  unwithdrawn, before its review deadline, and purpose/classification eligible
  for the current reader. Expired reviews are also excluded from new searches
  and cause S09 eligibility receipts to fail closed.
  Withdrawal blocks new
  retrievals immediately and conceals prior excerpts while retaining
  manifest metadata. Database triggers prevent body, lifecycle, and manifest
  substitution/deletion.
- The internal `POST /internal/v1/retrievals/{id}:receipt` is not routed by
  EDGE01. EDGE01 routes the public metadata, staging, review, withdrawal, and
  retrieval operations under `/internal/knowledge`; the receipt remains
  private. `UACRF_KNOWLEDGE_CASE_RECEIPTS_ENABLED` defaults to `false`; in
  that mode retrievals remain `NOT_VERIFIED`, omit trusted workload binding,
  exclude targeted material, and are never S09-eligible. When explicitly
  enabled, S08 uses the `uacrf-s08-retriever` service account to request the
  narrow S01 S08→S07 `assessment.case.read` delegation and verify the live,
  sealed S07 receipt before creating a manifest. A normal S01 administrator
  must admit the service account as a SYSTEM principal and independently
  grant that capability; no capability is auto-granted. Verified manifests
  bind the S07 case digest and workload ID, can include targeted material only
  for that trusted workload, and become S09-eligible only after the internal
  receipt rechecks the case and every current source/version/excerpt. Mismatches
  and owner outages fail closed. Content and eligible receipt reads reload source
  state after the S07 case check, so a withdrawal committed during that check is
  observed. These remain point-in-time checks; a later withdrawal can still race
  with disclosure. The caller-supplied case identifier is not
  treated as proof. `uacrf.s08.knowledge.review.changed.v1`
  and `uacrf.s08.retrieval.retained.v1` are written transactionally; publishing
  is not implemented.
- Not implemented: PDF conversion, embeddings/external indexes, current
  requester-authority refresh, protected inference replay, and outbox publishing. The
  receipt binding does not independently establish external requester
  authority or authorize protected inference/recovery use.

## M3 / S09 advisory gateway foundation

- The development API contract now specifies durable asynchronous admission:
  `POST /v1/orchestration-runs` returns 202 with a `Location` only after the run,
  idempotency record, and dispatch-scheduling outbox entry commit atomically.
  Idempotency is scoped to the authenticated principal, tenant, environment,
  operation and key; a same-request replay returns the retained run's current
  state, while changed content conflicts. Source verification remains outside
  locks, and admission requires the context to remain unchanged. This is
  not yet connected. `DispatchJournal.admitRun` now provides a durable library
  primitive that commits the run fence and allocations, immutable
  principal/tenant/environment/operation-scoped idempotency record, and
  `uacrf.s09.run.admitted.v1` scheduling outbox event in one transaction. Exact
  retries return the retained run key without another event; changed digests
  conflict. The digest is computed from the validated typed request using a
  deterministic JSON form; JSON property ordering and equivalent deadline
  offsets do not change it. Admission now persists a complete immutable
  per-role dispatch plan in the same transaction. A run requiring a plan cannot
  activate until every allocation is covered, and `begin` requires an exact
  match for the retained attempt, assignment, role/profile, model, output
  ceiling, timeout, context digest, and packet bytes before it records a
  dispatch intent. Incomplete plans fail before creating a run; substituted
  intents cannot dispatch from a planned run. The package-local `registerRun`
  path also requires and persists a complete plan. Migration V5 defaults new
  run fences to plan-required and rejects inserts that disable the requirement.
  Existing pre-V5 fences are not assigned invented plans; `begin` now requires
  an exact retained plan for every run, regardless of that flag, so legacy
  fences fail closed for new intents. Previously persisted dispatch intents
  remain available for completion and reconciliation. Their lifecycle still
  needs an operational disposition before a connected runtime is introduced.
  Migration V6 adds per-event delivery state, backfills existing events, and
  keeps delivery-row creation atomic with admission. Bounded claims are scoped
  by tenant/environment, use `FOR UPDATE SKIP LOCKED`, and replace expired
  leases with fresh claim tokens. Migration V7 allows the current token to
  extend an active lease without incrementing attempts; each extension is
  bounded and caps the resulting lease at five minutes from renewal. Publication
  and renewal also bind the retained tenant, environment, run, generation and
  event type from the claim. Migration V8 makes delivery publication update the
  matching event in the same database transaction and rejects direct event
  publication until delivery is published. A failure updating the event rolls
  back delivery acknowledgment. Publication requires the current, unexpired
  token. Migration V9 adds a persisted retry availability time; V10 enforces a
  minimum exponential backoff starting at one second, doubling by attempt and
  capping at 15 minutes. V11 derives per-claim jitter from the claim token,
  ranging from zero to 50% of the base delay, limited by the same cap. The
  database enforces the jittered minimum; a caller may select a longer delay
  up to 15 minutes. Pending claims are not eligible before their availability
  time, and attempts advance only when reclaimed. Expired or replaced claim
  tokens cannot release work. A crash after sending but before acknowledgment
  can cause redelivery, so any future consumer must be idempotent. PostgreSQL
  regressions cover existing-event backfill, delivery-row creation failure
  rolling back admission, concurrent exclusive claims, tenant/environment
  isolation, expired-lease reclamation, active-token renewal, delayed retry
  scheduling, database-enforced jittered backoff, stale/expired-token
  rejection, direct publication rejection, rollback when event publication
  fails, and single-winner concurrent acknowledgments. Claims, renewals, retry
  releases, and publication now also require the parent run to remain active
  and before its persisted deadline. Claim selection locks the parent run
  fence, and claim mutations lock it before updating, serializing these
  operations with cancellation. Regressions verify locked or cancelled runs
  cannot be claimed, cancelled runs cannot complete a current claim, and
  expired runs cannot be claimed or renew, release, or publish a current claim.
  This does not retract a network delivery already in flight. The broader
  PostgreSQL-backed regressions cover concurrent same-key admission, scope
  isolation, immutable plans and records, substitution rejection, and deadline
  failure. A delayed-plan regression confirms that expiration before database
  activation rejects and rolls back the run, idempotency, and outbox rows. The
  advisory suite passed 163 tests with no failures, errors, or skips in an
  isolated copy of the changed source. No
  service handler uses the admission or delivery library paths;
  no outbox publisher or provider dispatch is wired.
  Both durable journal admission and its lower-level run-registration path
  require the private-constructor S01 requester proof, which binds a HUMAN
  principal with `advisory.run.create` to the exact tenant/environment; a
  scope-mismatch regression confirms neither path persists a run. Both paths
  also reject a proof whose capability-expiry horizon or session expiration
  has passed when the method begins; PostgreSQL-backed regressions cover both
  boundaries and verify no run or outbox is persisted. The clean advisory
  suite passed 167 tests. This does not fence expiry during the transaction.
  Requester source checking also requires the refreshed S01 capability set
  to exactly match the initial proof and fails closed on drift; a changed-but-still-
  eligible capability regression verifies that stale permissions cannot drive
  delegated owner reads. These are point-in-time checks and do not prevent
  later S01 revocation or establish that owner context remains unchanged at
  commit. No connected handler or current-context commit guard is wired; the
  publisher and provider dispatch remain disabled, and the operation remains
  `PENDING_CONNECTED_VERIFICATION`. The MS01 design check passes 24 consistency
  checks; target runtime acceptance remains not evaluated.
- `services/advisory` contains an internal provider-neutral invocation contract
  and a disabled gateway. This is a library foundation, not a deployable service;
  its durable journal and scheduling outbox are not consumed by a connected
  runtime. An unknown model outcome is represented separately from a response
  with usage.
- An unwired OpenAI-compatible chat-completions adapter is restricted to a
  literal-loopback HTTP test endpoint; it has no runtime wiring. Loopback alone
  does not prevent a local endpoint from forwarding to a live provider, so only
  controlled fake endpoints may be used for these tests. The shared simulated
  transport bypasses configured proxies. It sends
  a single bounded text request with `max_completion_tokens`, without streaming
  or tools. It accepts only a complete text response with consistent token usage;
  errors, refusals, truncation, missing usage, and unsupported responses yield
  unknown outcomes. Local simulated-endpoint tests cover request mapping, size
  limits, redirects, failures, and timeouts. This is a narrow protocol slice,
  not verification of any provider or model, runtime budget enforcement,
  connected admission, or reconciliation. The adapter has no runtime
  configuration or wiring; the default gateway remains disabled.
- An unwired Microsoft Foundry v1 chat adapter reuses that protocol handling,
  sends the deployment name as `model`, and uses a supplied bearer token.
  When filter results are present it rejects filtered or malformed results,
  but missing results do not prove filtering occurred. It does not acquire
  Entra credentials or support the separate Responses API. Its tests use a
  controlled fake endpoint.
- An unwired Anthropic Messages adapter uses the same simulated-only transport,
  with the `2026-10-21` API-version header and a bounded single-message request.
  It accepts one completed text block and reported token usage; unsupported
  content, refusal or truncation, nonzero cached-token or server-tool usage, and
  malformed responses produce unknown outcomes. Tests use only a local fake
  endpoint.
- An unwired Gemini `generateContent` adapter uses the same simulated-only
  transport, sends a bounded text request with its API key in a header, and
  validates the model path. It accepts a single unblocked text response with
  consistent usage; reported thinking tokens count toward output usage, while
  cached or tool-use tokens and unsupported content fail closed. Local fake
  endpoint tests cover mapping, safety responses, usage and path injection.
- An unwired Ollama native chat adapter sends an unauthenticated, non-streaming
  bounded request with thinking disabled and requires a matching model name,
  completed text and reported usage. It rejects thinking traces, tools, images,
  cached-token use and incomplete responses. Its tests use only a fake endpoint;
  a real local Ollama server can itself forward cloud-model requests and must
  not be used until the dispatch gates are complete.
- An unwired Bedrock Converse adapter uses a controlled fake loopback endpoint,
  a restricted model ID in the path, a bounded text request, and consistent
  reported token usage. It rejects incomplete responses, unsupported content,
  guardrail traces and nonzero cache usage. It does not sign requests, obtain
  AWS credentials, configure guardrails or support ARN model identifiers.
  None of these adapters is qualified against a live provider.
- A standalone budget value validates schema bounds, distinct role/profile
  allocations, and aggregate call, token and cost limits. Journal registration
  now atomically persists and seals those allocations; admission requires the
  matching role/profile, allows one attempt per role, constrains requested
  output ceilings against the allocation and run token budget, and accounts
  reported usage and outstanding output reservations. New admission stops after
  an unknown outcome. It does not verify profile identities, reserve input
  tokens for in-flight calls, verify provider accounting, enforce provider-cost
  usage, or authorize the requester at the run boundary.
- An unwired typed run-request parser accepts bounded, strict UTF-8 JSON with
  the contract's required fields and integer encodings. It checks identifiers,
  workflow names, reason and proposal bounds, and requires each artifact
  reference to match its digest. Deadlines require a known offset, seconds,
  and at most nanosecond precision; unsupported date-time forms fail closed.
  It snapshots proposal IDs and reuses the
  budget value. It requires the exact role set for each workflow version:
  analyst only, analyst plus reviewer, or analyst plus reviewer plus critic.
  It is not an HTTP endpoint, does not verify artifact access, proposal
  eligibility or deadline freshness, and does not authorize or admit a run.
- A separate unwired preparation check binds that request's case and retrieval
  IDs to expected owner receipts, parses caller-supplied model and prompt
  artifacts against its references, digests and scope, and performs the
  requester/full-source check. This composition is also exercised against real
  S01, S07 and S08 with caller-supplied configuration and source bytes. Its
  successful result is only a point-in-time
  consistency snapshot: artifact authorization, independent human review of
  profile activation, proposal eligibility, requester-authorized connected run
  admission and provider dispatch remain unimplemented.
  A profile-aware variant requires exactly one profile pin and gateway request
  per allocation, obtains the current active-version receipt from S02, and
  binds profile/version identity, manifest digest, model and instruction pins,
  and request limits to that receipt before final S01 requester, S02 profile,
  and S08 retrieval rechecks.
  S02 receipt integer parsing rejects out-of-range and non-integral version
  numbers and limits rather than truncating oversized values. A regression
  reproduces the former wraparound, including a manifest with a matching
  digest. Two existing digest-mismatch and missing-catalog-grant tests were
  moved out of an anonymous clock so the test runner actually executes them.
  It retains immutable copies of the checked requests and selections. The
  cross-service test exercises this composition with real S01, S02, S07 and
  S08 services; this remains a point-in-time check, not an atomic authorization
  decision. After the final S07, requester and S02 refreshes, preparation
  re-reads the S08 retrieval receipt with a fresh delegation and rejects a
  changed, withdrawn, or unavailable receipt before returning the snapshot.
  Requester refresh also rejects capability-set, capability-expiry-horizon,
  session-expiry or membership-snapshot drift, including changed grants that
  leave the capability set and horizon unchanged. These sequential checks
  do not provide an atomic authorization decision. A simulated-owner
  regression covers withdrawal during the requester refresh.
  Preparation now constructs a distinct,
  bounded JSON input packet per role from the verified sealed case material,
  selected prompt text, and receipt-verified excerpts. It generates each
  gateway request from the checked model and limits rather than forwarding
  caller-supplied free-form context. The prepared snapshot can derive journal
  intents only when caller-supplied run, generation and assignment identifiers
  match its tenant/environment and allocation profiles; each intent uses the
  generated attempt ID and exact packet bytes, with context digest equal to the
  packet-byte digest. This rejects mismatched gateway attempts, models, limits
  or contexts. Intent derivation rechecks the original run deadline, so a
  prepared snapshot cannot derive new journal intents after expiry. Each
  derived intent also carries that deadline, and journal admission requires an
  exact match with the sealed run fence; a later fence cannot extend the
  prepared request's validity. Mismatch leaves no journal entry. The expired-
  snapshot, deadline-extension and injected-clock regressions pass; intent
  derivation now uses the same injected clock as preparation instead of
  switching to the system clock. The full advisory module suite passed 140
  tests with no failures or skips. Intent derivation and journal guarding
  remain separate from requester authorization and connected run admission;
  provider dispatch remains disabled.
  Future profile changes, run-boundary authorization, complete graph/evidence
  inputs, and current eligibility beyond point-in-time owner checks remain
  unestablished; provider dispatch remains disabled. Preparation rejects a
  deadline that has already expired and checks again after owner reads and
  packet construction so a request that expires during preparation is not
  returned as ready.
- A standalone consistency check compares S07 case and S08 retrieval receipt
  fields, requiring the same case, workload, purpose and digests, verified
  binding, current eligibility, and a nonempty bounded source list. An unwired,
  disabled-unless-enabled owner client can fetch the receipts using separate
  injected S07/S08 delegations, with bounded responses, timeouts, no redirects,
  and HTTPS (or explicitly enabled literal-loopback HTTP). It also fetches the
  S08 retrieval excerpts and checks the response metadata and ordered results
  against the receipt, including query, creation time, and ranking scores, then
  verifies each excerpt's Unicode code-point length and UTF-8 SHA-256 digest.
  S08 checks its retained manifest digest. S09's unwired owner client and
  full-source verifier independently reconstruct S08's current ordered Jackson
  representation and check that digest; the owner client rejects a mismatch
  before requesting excerpts. Tests exercise it against real S08 responses,
  including Unicode and escaped query text. This is not RFC 8785, and a
  serializer change can make verification fail until the existing digest
  format is explicitly versioned.
  The client has also been tested against a real S08 HTTP instance backed by
  PostgreSQL, including classification and tenant denial, unavailable or changed
  case verification, and source withdrawal. That test uses test-signed S08
  delegations and simulated S07 receipts and case verification; it does not
  exercise live S01 or S07. A separate test uses a real S07 HTTP instance and
  PostgreSQL with test-signed S07 delegations and simulated S08 responses,
  checking sealed-case access, tenant and audience isolation, and mismatched
  case digests. These individual tests do not combine real S01, S07, and S08. The owner
  services' advisory dependencies are test-only. The client now refreshes the
  S08 receipt after reading excerpts and rejects changed, ineligible or
  unavailable results. This refresh has been tested with a real S08 instance
  and a withdrawal after its content response. These separate reads are not
  an atomic authorization or a guarantee of future eligibility. An unwired,
  disabled-unless-enabled S01 delegation client
  can request those narrow S07/S08 capabilities using an injected actor token;
  it does not cache tokens. It has been exercised against a local fake S01 and
  against a real S01 test instance for requester delegations.
  An unwired, separately disabled client-credentials token provider can obtain
  a service actor token using a configured OAuth endpoint and client secret,
  with the same HTTPS/loopback, timeout, response-size, strict JSON and
  no-redirect protections. The provider does not cache tokens; its tests use
  a fake identity provider. Neither client establishes the requester's
  authority or validates that the service principal has been admitted.
  A separate unwired, disabled-unless-enabled requester check asks S01 for
  current global grants using the requester's own token. It requires a human
  principal with `advisory.run.create`, case/retrieval read and the appropriate
  classification capability. S01 permits the new run-creation grant only for
  humans and global scope, through the existing independent grant process; no
  grant is assigned automatically. The check does not cache access and cannot
  itself admit a run, prove access to specific source objects, or guarantee
  authority remains current after the response. S01's grant and revocation
  behavior is tested against a real S01 instance using the advisory requester
  client, including current-grant checks and revocation. S01's tests use a
  test-only advisory dependency; the runtime artifact remains separate.
  An unwired requester-source check combines that snapshot with S07 case
  receipt and material and S08 receipt and excerpt reads using fresh S01
  delegations obtained with the same requester token, rather than a service
  actor. It validates case material before fetching the retrieval, then
  refreshes S08 eligibility after the excerpts. After those reads it refreshes
  the S07 case receipt and any requested S02 active workload versions, rejecting
  any change, then checks requester grants again and rejects a changed
  principal. It finally re-reads the S08 retrieval receipt after those other
  refreshes and fails closed if eligibility or any receipt field changed; this
  narrows but does not eliminate source or revocation races.
  It requests a restricted
  classification delegation for an internal retrieval when that is the
  requester's applicable grant. The combined flow has also been tested against
  a real S01 instance and simulated owners that validate its signed requester
  delegations. The same combined check is also tested against real S07 with
  simulated S01 and S08. It does not authorize access to referenced artifacts
  or guarantee continuing requester authority or source
  eligibility. It does not authorize journal admission or provider dispatch.
  The post-read requester refresh has also been tested against real S01 with
  revocation during a simulated owner content read.
  An optional variant validates caller-supplied full knowledge bytes against
  every excerpt and the manifest before the final S08 eligibility, S07 case,
  S02 active workload-version, and S01 requester refreshes. It has been
  exercised against real S01, S07 and S08, including altered bytes, withdrawal
  and grant revocation. Supplied bytes are not proof of artifact ownership or
  disclosure authorization.
  The combined check shares a five-second monotonic preparation budget across
  requester access, delegation and owner HTTP calls, in addition to the
  two-second per-call bound. Timeout fails closed; it is not a run deadline or
  a guarantee that a remote owner stopped processing the cancelled request.
  An opt-in integration test now runs S01, S07, and S08 as separate local
  processes with separate PostgreSQL databases. It exercises real S01
  delegations, S08's S07 case verification, the requester-source check,
  and caller-supplied full knowledge bytes. A controlled local proxy triggers
  grant revocation or source withdrawal after S08 returns content but before
  the requester receives it; the final S01 or S08 refresh rejects each case.
  These checks do not eliminate later races. The
  OIDC issuer and tokens are test fixtures and identity participants and
  memberships are seeded directly; it does not test their admission or grant
  approval. Run it with
  `./mvnw -Padvisory-cross-service-tests -pl services/identity,services/estate-catalog,services/assessment,services/knowledge,integration-tests/advisory -am clean verify`.
  Operation authorization at the run boundary, runtime identity verification,
  proposal eligibility and provider dispatch remain unimplemented. S02 version
  digests cover the ordered Jackson serialization of the typed manifest, not a
  separately versioned canonical JSON format; registration retains the
  `object_ref` but does not retrieve or verify the referenced artifact bytes.
  Journal admission is a separate fail-closed primitive, not an authorization
  decision.
- S07 now provides an internal, `assessment.case.read`-protected material
  endpoint for sealed cases. It reconstructs the canonical JSON and rejects a
  digest mismatch before returning it. A separate, disabled-unless-enabled S09
  client verifies the UTF-8 SHA-256 digest and case, purpose, workload and
  source-kind consistency with an S07 receipt. Tests exercise it against a
  real S07 instance with test-signed delegations, including tenant isolation.
  S07 reconstructs rather than retaining the original serialized bytes, so a
  serialization change may make existing material unavailable; the mismatch
  fails closed. The client is used by the unwired requester-source check, whose
  resulting case material is included in prepared packets. It does not authorize
  journal admission, verify referenced graph, objective or evidence material,
  or guarantee continuing authorization.
- A standalone content-addressed artifact value checks that an
  `artifact:sha256:` reference, declared digest and retained bytes agree, and
  copies the bytes defensively. An unwired model-configuration parser binds
  requested reference and digest to bounded UTF-8 JSON, checks tenant and
  environment, and validates model entries against the V1 contract. It rejects
  blank or duplicate model references to avoid ambiguity. The parser does not
  load artifacts, authorize models, or enable dispatch. A separate unwired
  request check matches a configured model and checks requested output, timeout
  and declared maximum cost against configuration and allocation limits. It
  does not estimate input tokens, verify prices, enforce provider cost or bind
  the request to a journal intent. An unwired
  prompt parser performs the same artifact, scope, UTF-8 and JSON checks and
  validates the V1 entry shape. It rejects blank text and duplicate instruction
  digests. The contract does not define how `instructions_digest` is calculated
  from prompt text, so that relationship is not verified; neither parser
  establishes approval, provenance or authorization.
  A separate unwired consistency check matches caller-supplied profile version
  model and instruction pins to those configurations and compares allocation
  and request limits with supplied version limits. By itself it does not
  retrieve an activated S02 version, prove the prompt text matches its declared
  digest, or authorize dispatch. The profile-aware preparation variant binds
  these pins to S02's active receipt, but neither path proves prompt provenance
  or authorizes dispatch.
  A batch variant requires exactly one caller-supplied pin and gateway request
  per allocated profile, rejects duplicate attempt IDs and checks each against
  its allocation. These remain caller assertions, not owner authorization;
  only the internally consistent prepared snapshot can derive an intent
  carrying its checked request and exact packet.
  Digest equality does not establish artifact ownership, disclosure permission,
  approval, provenance or current eligibility. An unwired knowledge-source
  verifier checks bounded UTF-8 artifact bytes against an S08 source digest,
  verifies the S08 code-point chunk locator and matches the exact excerpt and
  its digest. A batch check requires matching source bytes for every receipt
  result and has been tested with a real S08 instance and caller-supplied
  source bytes; the S07 receipt and case verification are simulated. It does
  not obtain source bytes or verify their authorization, and it does not verify
  case graph, policy or evidence artifacts. Authorized
  artifact storage and end-to-end full-source verification remain unimplemented.
- An unwired PostgreSQL dispatch-journal primitive commits an intent before
  returning success, prevents duplicate attempts per assignment, and retains
  immutable responses or unknown outcomes without usage. It retains up to 1 MiB
  of exact packet bytes, verifies their SHA-256 on read, and requires the
  context digest to equal the packet-byte digest for new intents. The additive
  intent also retains the checked model, output limit and timeout so a journal
  read can reconstruct the exact gateway request. The additive constraints are
  not validated against legacy rows; old entries without request identity fail
  closed on reconstruction. These digests do not verify the delivery protocol
  or owner receipts. Admission also requires the requested output-token ceiling
  to fit the sealed allocation and the request timeout to finish before the
  run deadline at the database admission timestamp. It also accounts reported
  response tokens and reserved output ceilings against the run token budget,
  and blocks new intents once an attempt has an unknown outcome. This does not
  reserve future input usage or stop already-admitted calls. An intent without
  a result must be reconciled, not automatically dispatched again. A bounded,
  run-scoped snapshot lists retained intents and unknown outcomes for
  reconciliation. A separate snapshot sums known provider-reported token
  counts against the run budget and exposes unknown and unresolved counts;
  usage is unavailable unless known counts already exceed the budget. This is
  diagnostic only: it does not establish actual provider usage or authorize
  retries. Journal reads also
  compare reported input-plus-output tokens against the sealed allocation,
  retaining over-limit responses and reporting unavailable usage for unknown
  outcomes. The journal does not enforce aggregate input-token or provider-cost
  use, verify provider accounting, or determine result eligibility. A
  revision- and generation-checked cancellation fence serializes against journal
  admission;
  run registration and admission also require an unexpired retained deadline,
  checked against the database clock after acquiring the run lock. Outcomes
  retain whether the fence was active, cancelled or expired when recorded;
  concurrent cancellation is serialized. This snapshot is not a current
  eligibility decision: later cancellation and other required checks still
  matter. The journal does not stop a provider call following an already-admitted
  intent or enforce late-result ineligibility. Authorization, protected packet
  handling, verified identity, runtime budget enforcement, result delivery, and
  reconciliation are not implemented.
  It is not a complete runtime journal.
- Planned adapters include Azure AI Foundry, OpenAI, Anthropic, Gemini,
  OpenAI-compatible endpoints, Ollama, and Amazon Bedrock. AgentCore is a
  separate potential agent-hosting integration, not the Bedrock inference API.
  Provider-specific capabilities and credentials must be handled explicitly;
  no live invocation should be enabled before source authorization, bounded
  budgets, and durable dispatch/reconciliation are implemented.

## Next implementation step

The live browser suite verifies authorized draft journey creation,
service registration/linking and readback, standalone and service-bound
workload registration and persisted UX01/S02 readback, denial of readback to a
user lacking `catalog.agent.read`, operator-only ownership transfer, and
isolated S03 publisher/collector client-credentials delegation through
independently granted S01 authority. The isolated collector runner remains
disabled.
Continue M1 with production deployment-identity verification and full
end-to-end acceptance; do not bypass authorization. Continue integrated work
with explicitly synthetic samples while live provider validation remains
deferred. Before enabling collection, provision production S03 credentials
outside the repository and complete the live provider conformance suite. The
isolated `uacrf-s03-collector` identity proves only local client-credentials
resolution and narrow delegation; it does not execute collection. Keep each
cloud provider UNACCEPTED until its live conformance suite verifies pagination,
permission denial, outages, rate limits, checkpoint recovery and out-of-order
delivery. Preserve one-owner-per-domain and no-cross-database-access
boundaries.
