# Integration and system acceptance test plan

Version 1.0, editorial revision 4.

This plan explains how to test UACRF after its components are connected. The current alignment-revision-22 scope has 131 requirements and 39 planned integration scenarios; full-system acceptance remains **NOT_EXECUTED**. The 49 reference tests and 25 package checks are recorded for an earlier baseline. Their passing results do not establish live integration or acceptance of the current scope.

Start with one nonproduction journey. Declare the expected result and safety limits before running it, then retain the observed behavior and evidence needed for independent review.

## Test levels and promotion

1. Reference and contract checks run locally without provider mutation.
2. Component integration runs against pinned Neo4j, PostgreSQL, OPA, workflow, signing and evidence-store configurations.
3. Nonproduction provider tests verify each adapter’s native identity, permissions, targets, retries and action semantics.
4. A controlled end-to-end journey demonstrates the complete evidence-to-recovery chain.
5. Operational/security/load and operator acceptance establishes the stated release gate for the declared scope.
6. Comparative experiments and the published paper’s research claims require a separate prespecified empirical protocol.

Record software/provider API versions, tenant/environment, identities, graph revision, policy/plan digests, clock assumptions, fixtures, expected result, actual result, artifact locations, independent reviewer and disposition for every run. A test only passes against predeclared criteria; reference or documentation checks cannot substitute for execution evidence.

## Provider coverage

GCP, AWS and Azure are all targets. Certify the shared control plane once for its deployed configuration, then separately verify each provider adapter, trust boundary and workload/action combination. The recommended first path remains the existing Google Cloud/Kubernetes pilot; subsequently exercise the same canonical contracts on AWS and Azure. Neither certification of one cloud nor a common Kubernetes API proves the other provider’s identity, network or recovery guarantees.

Use one workload family first. For the existing Kubernetes path, use GKE, EKS and AKS adapters/identity integrations. Cloud Run, ECS and Azure Container Apps are optional future workload families, not additional prerequisites for the first Kubernetes scenario. Resource-specific database failover and cross-cloud disaster recovery require separate action certification.

## Evidence for every run

Retain an immutable manifest of the inputs and configuration, then record the expected and observed results. Identify the graph, policy, authorization, and provider records relevant to the test. Preserve execution and validation history, artifact locations, and integrity digests. A reviewer must record the final disposition. The standard-evidence reference in each scenario means all these records are required where applicable.

## Scenario register

The machine-readable source is [integration_scenarios.json](integration_scenarios.json). The 26 scenarios are linked to existing formal requirements; they do not add 26 new requirements. Every scenario is NOT_EXECUTED.

### INT-DB-01 Duplicate and out-of-order projection

Requirements: FR-GPH-004, NFR-GPH-001, FR-CAT-001.

Setup and fault: Deliver versions 2, 1, 2 and a conflicting version-2 payload.

Pass condition: One logical version-2 result; stale version rejected; duplicate acknowledged; conflicting equal version quarantined; no later decision uses divergent context.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-DB-02 Concurrent updates and snapshot capture

Requirements: FR-GPH-004, NFR-GPH-001, FR-CAT-001.

Setup and fault: Race two same-aggregate writers and mutate topology while capturing a decision snapshot.

Pass condition: Only a consistent source-version history is committed; the capture resolves one protected revision/digest or returns Unknown and retries under a bound.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-DB-03 Outbox interruption and reconciliation

Requirements: FR-GPH-004, NFR-GPH-001, FR-CAT-001.

Setup and fault: Stop a worker after source commit, after graph dispatch and before receipt; restart it.

Pass condition: Committed outbox events resume without duplicate logical effects; receipt/checkpoint and graph revision reconcile; incomplete snapshots remain decision-ineligible.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-GRAPH-01 Direction and witness paths

Requirements: FR-GPH-003, FR-GPH-006, FR-GPH-007.

Setup and fault: Inject a database or deployment failure into a known dependency/journey graph.

Pass condition: Reverse dependency and forward consequence paths reach exactly the expected subjects, journeys and segments; retain query, revision, exclusions and witnesses.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-GRAPH-02 Scope and incomplete context

Requirements: FR-GPH-003, FR-GPH-006, FR-GPH-007.

Setup and fault: Add a cross-tenant edge, stale evidence, cycle, low-confidence dependency and depth/path-budget exhaustion.

Pass condition: No unauthorized tenant/environment crossing; required missing or truncated context produces Unknown/Incomplete rather than zero impact.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-GRAPH-03 Impact deduplication

Requirements: FR-GPH-003, FR-GPH-006, FR-GPH-007.

Setup and fault: Reach the same transaction/customer population through multiple paths and submit a corrected late measure.

Pass condition: Deduplicate by governed scope/window key; retain all witnesses; preserve observed versus estimated values and supersede the earlier calculation without rewriting it.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-PATH-01 Hidden correlated dependency

Requirements: FR-GPH-008, FR-IP-005.

Setup and fault: Place primary and alternate in different regions but give them shared identity, DNS, key, data or control-plane domains.

Pass condition: The declared failure hypothesis and domain policy identify prohibited common exposure and return Not Independent with witnesses.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-PATH-02 Unproven fallback

Requirements: FR-GPH-008, FR-IP-005.

Setup and fault: Remove a mandatory domain, expire certification, fail capacity/data compatibility or truncate upstream expansion.

Pass condition: Return Unknown or an ineligible path; no fallback suppression or failover authorization relies on an unproven certificate.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-PATH-03 Conditional certification and change

Requirements: FR-GPH-008, FR-IP-005.

Setup and fault: Apply a scoped approved exception, then expire it or materially change a domain.

Pass condition: Conditional status retains authority, limits and earliest expiry; a material change or exception expiry forces recertification before action.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-AUTH-01 Restrictive policy and human authority

Requirements: FR-POL-001, FR-APR-001, FR-IP-002, FR-IP-003.

Setup and fault: Exercise deny, recommend, approval-required and preauthorized outcomes, including proposer/approver identity separation.

Pass condition: Returned obligations and separation rules are enforced at execution; a recommendation or unsatisfied approval never causes a provider action.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-AUTH-02 Replay and tampering

Requirements: FR-POL-001, FR-APR-001, FR-IP-002, FR-IP-003.

Setup and fault: Replay a consumed authorization; alter target, audience, scope, expiry or signed content.

Pass condition: Signature, nonce, target and audience checks reject the request before provider dispatch; retain the denied attempt.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-AUTH-03 Context change and policy outage

Requirements: FR-POL-001, FR-APR-001, FR-IP-002, FR-IP-003.

Setup and fault: Change a recovery target or path after approval; make OPA unavailable or its bundle stale.

Pass condition: Consequential action fails closed, is suspended or reauthorized under current context; no stale permit is silently reused.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-EXEC-01 Duplicate dispatch and ambiguous timeout

Requirements: FR-WFL-001, FR-WFL-002, FR-IP-003.

Setup and fault: Repeat a request and interrupt acknowledgement after the provider accepted it.

Pass condition: Use the same execution identity/idempotency contract; reconcile provider request/state before retry; no second logical effect is produced.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-EXEC-02 Throttle and partial application

Requirements: FR-WFL-001, FR-WFL-002, FR-IP-003.

Setup and fault: Inject throttling, delayed completion, partial permissions or partially applied provider changes.

Pass condition: Bound retries, preserve actual observed state and return partial/unknown dispositions; no broadening of targets or blind non-idempotent repetition.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-EXEC-03 Stop and bounded compensation

Requirements: FR-WFL-001, FR-WFL-002, FR-IP-003.

Setup and fault: Activate operator stop/kill switch and force a compensating action to fail or reach a configured limit.

Pass condition: Stop further unsafe dispatch, preserve history and escalate; compensation obeys separate/current authority plus count, duration and cumulative-scope limits.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-VAL-01 Mandatory result completeness

Requirements: FR-VAL-001, FR-VAL-002, FR-IP-004, FR-IP-006.

Setup and fault: Make infrastructure pass while a required journey/data/security/AI check fails, is skipped, times out or stays Unknown.

Pass condition: Recovered is prohibited unless every mandatory check has qualified passing evidence and complete coverage; skipped/absent results do not count as passing.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-VAL-02 Stable observation window

Requirements: FR-VAL-001, FR-VAL-002, FR-IP-004, FR-IP-006.

Setup and fault: Allow transient recovery, then introduce a failure or evidence expiry before the required stable window finishes.

Pass condition: The stable window resets or fails according to the bound plan; no premature success is emitted.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-VAL-03 Revalidation after compensation

Requirements: FR-VAL-001, FR-VAL-002, FR-IP-004, FR-IP-006.

Setup and fault: Apply an authorized correction and rerun the entire plan or a verified affected-check dependency closure.

Pass condition: Required downstream checks rerun, earlier attempts remain immutable and final disposition reflects the latest fully supported result.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-EVD-01 Independent historical reconstruction

Requirements: FR-EVD-001, FR-EVD-002, FR-IP-001.

Setup and fault: Replay using the retained graph snapshot, source evidence, exact rules/policy, authorization, workflow and validation versions.

Pass condition: Verify digests/signatures and reproduce the determinate result or report explicit external-state exceptions; never query mutable state as historical truth.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-EVD-02 Missing or modified artifact

Requirements: FR-EVD-001, FR-EVD-002, FR-IP-001.

Setup and fault: Delete a required copy in a test fixture, substitute a policy artifact or alter an evidence object.

Pass condition: Detect missing/digest/signature failure and mark Partial/Failed; preserve the original record and an attributable integrity finding.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-EVD-03 Retention and controlled disclosure

Requirements: FR-EVD-001, FR-EVD-002, FR-IP-001.

Setup and fault: Test authorized export, unauthorized access, retention expiry/legal-hold behavior and signing-key rotation using a controlled configuration.

Pass condition: Tenant-scoped access, chain of custody, retained verification keys and configured retention/hold behavior work as specified; test storage constraints before locking real retention policies.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-OPS-01 Component interruption and restoration

Requirements: NFR-RES-001, NFR-RPO-001, NFR-SEC-001, NFR-PRF-001, NFR-PRF-002, NFR-GPH-004.

Setup and fault: Restart workflow workers, graph/policy services and evidence ingestion; perform controlled backup/restore.

Pass condition: Protected workloads continue independently; consequential action stops or follows certified offline limits; workflows and retained evidence recover to declared objectives.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-OPS-02 Isolation privilege and secrets

Requirements: NFR-RES-001, NFR-RPO-001, NFR-SEC-001, NFR-PRF-001, NFR-PRF-002, NFR-GPH-004.

Setup and fault: Attempt tenant/environment boundary crossing, read-role mutation, invalid federation claims, unauthorized export and secret exposure.

Pass condition: Enforce least privilege and scope; read identities cannot mutate; invalid identity is rejected; secrets and sensitive content do not enter ordinary evidence/log output.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-OPS-03 Certified load and operator exercise

Requirements: NFR-RES-001, NFR-RPO-001, NFR-SEC-001, NFR-PRF-001, NFR-PRF-002, NFR-GPH-004.

Setup and fault: Run agreed event/query/workflow load and have an operator diagnose, approve, stop and reconstruct the scenario.

Pass condition: Measure against the specification’s stated SLIs/SLOs and acceptance criteria, including lag, latency and completeness; retain load configuration, measurements and operator observations.

Evidence: Retain the standard records described under Evidence for every run.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-MC-01 Equivalent bounded intent across GCP AWS and Azure

Requirements: FR-IP-003, NFR-POR-001, NFR-INT-001.

Setup and fault: For the selected workload family, translate one canonical rollback intent on GCP, AWS and Azure. Include unsupported capability, differing provider units/limits, partial permissions and throttling.

Pass condition: Every adapter preserves target/scope/validation semantics or returns a typed unsupported/mismatch response. Preserve native resource IDs, request IDs, before/after state and attestations. Matching intent does not imply interchangeable APIs or identical recovery guarantees.

Evidence to retain:

- Three provider translation plans.
- Capability/version matrix.
- Negative-path outcomes.
- Provider results and attestation.
- Cross-provider review.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

### INT-E2E-01 Qualified deployment failure to governed rollback and replay

Requirements: FR-ING-001, FR-GPH-003, FR-POL-001, FR-WFL-001, FR-VAL-001, FR-EVD-001, FR-LRN-002.

Setup and fault: Use a nonproduction checkout journey with known dependencies, two compatible releases and a synthetic transaction probe. Record fixed lab objectives and safety bounds before injecting a defective deployment.

Pass condition: Qualify evidence; seal the graph context; trace the affected journey; evaluate the scenario-specific rollback path; obtain policy and any human approval; execute one bounded rollback; validate every mandatory layer and stable window; reconstruct the decision; record and verify an approved learning change.

Evidence to retain:

- Approved nonproduction scope and stop conditions.
- Known graph and expected witnesses.
- Qualified telemetry and target compatibility proof.
- Policy/approval/single-use authorization.
- Provider operation and idempotency evidence.
- Stable-window journey/data/control results.
- Reconstructed envelope and learning/change record.

Status: **NOT_EXECUTED**. Provider targets: GCP, AWS and Azure.

## First practical demonstration

Start INT-E2E-01 in nonproduction. Register one checkout journey and its dependencies. Retain a known-good deployment digest and a schema-compatible rollback target. Before injecting a fault, fix the lab objectives, stable window, maximum scope, and stop conditions. A rollback path is certified for the declared deployment-defect scenario; it is not automatically independent for a region, identity or shared-control-plane failure.

Observe and qualify the defect, preserve the current snapshot, compute the expected witnesses, require the exact policy/approval/authorization, and execute the adapter once. Repeat dispatch and simulate an acknowledgement timeout to prove safe reconciliation. Provider API success is insufficient: mandatory journey, data, security, applicable AI and service checks must pass for their stable window. Force one check to fail and demonstrate safe compensation or escalation. Seal and independently replay the decision, then record an implemented learning change and rerun the scenario.

Production mutation remains outside the initial read-only assessment MVP. These lab tests prepare a later Recover gate; documenting them does not expand a customer contract or grant execution authority.

## Scoring impact

The alignment score remains **87.5/100**. This plan adds practical detail within already-scored documentary criteria. No independent review or live integration result has been added. New package-structure checks for the plan demonstrate link integrity only. Reassess the remaining rubric dimensions when actual review, provider integration and acceptance evidence is retained.

## Revision 5 knowledge and agent scenarios

INT-AGCORE-01 tests quarantine, approval, scope, revocation with warm caches, retained-context reconstruction and evidence substitution. INT-AGCORE-02 tests cumulative budget reservations, duplicate and late callbacks, dissent, required-role failure, cancellation, model outage and A2 authority limits. Both require integration evidence across the declared GCP, AWS and Azure target matrix and remain NOT_EXECUTED. The held-out evaluation and promotion gates in Appendix W8 must also pass before live advisory rollout.

## Protecting agent workloads

UACRF protects agent workloads as governed capabilities, whether they stand alone or support a wider service or journey. A protected agent workload uses the existing service identity for ownership, evidence, policy, graph context and recovery authority. Its workload profile adds the versioned instructions, models, knowledge, tools, runtime identity, limits and outcome checks needed to operate it safely. This protected workload is distinct from an optional UACRF assistant: the A2 advisory ceiling applies to UACRF assistance, while a customer agent may use separately authorized business tools under its own runtime controls. Neither role acquires UACRF evidence qualification, approval or recovery authority.

Read the [alignment-revision-22 Technical Approach](../../Technical_Approach/UACRF_Technical_Approach_Design_v1_0_alignment_revision_22.docx), [MS01 architecture design](../../Microservices_Architecture/MS01/README.md), [current UI prototype](../ui_workloads/index.html) and [partial Java runtime reference](../runtime/README.md). The full current baseline is 131 requirements, 74 canonical endpoints, 39 planned integration scenarios, 102 business tables and 338 queries. Earlier sections describing revision 5, the 33 PNG concepts and the 49-view prototype are historical design context. The current prototype provides 58 registered views across 13 destinations; no live runtime or acceptance status follows from those screens.

## Historical journey and resource catalogue baseline

Version 1.0 · Alignment revision 7 · Database revision 4 · Flow revision 3 · UI design revision 6 · Guide revision 6

Read the [current UI package guide](../ui_workloads/README.md) and [navigation registry](../ui_workloads/NAVIGATION.json) for the prototype's service/agent and shared-resource surfaces. The UI catalogue is metadata; runtime access, qualified evidence, recovery authority and billing admission retain their independent controls. No new charge or entitlement is implied by a journey membership, resource link or internal agent anchor.
