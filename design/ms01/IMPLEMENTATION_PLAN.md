# Microservices implementation plan

Version 1.0 | Architecture revision MS 01

The target is a fresh microservices implementation in `development/UACRF_v1_0_microservices`; the existing application is not a build or runtime dependency. Use the checked-in MS01 contracts, inventories and scenarios as implementation requirements. Each milestone delivers a working cross-service workflow, a retained contract, data migrations and acceptance evidence. Track delivery from this target's executed tests and acceptance evidence; do not create a completion count by treating an unavailable stub as an implemented operation.

## M0 Freeze the implementation baseline and reconcile contracts

Freeze the checked-in contracts and design artifacts by hash, and preserve the distinction between 107 inherited registered-route records and the older 99-route completion inventory. Reconcile advisory contracts with consolidated references without modifying original signed artifacts. Resolve each included public operation's effective contract, owner and implementation acceptance case. Record legacy behavior as unknown wherever the source is unavailable. Preserve the original 47 identifiers and their documented partial-operation explanations.

Deliver a root Maven build, dependency/image lock policy, architecture enforcement rules, shared test utilities, local environment specification and CI jobs. Record the initial threat model, tenant boundary and event/receipt compatibility policies. Choose one bounded first recovery action and its local test adapter. Completion requires all 144 operations, 153 table records and 131 requirement IDs in the checked-in inventories to remain allocated with no unexplained orphan. These counts establish coverage of the available artifacts. Create and run a new-service test baseline and report those results for this target.

## M1 Establish independent services and the first real journey

Build EDGE01, UX01, S01 and S02 as independently deployed images with separate logical databases and credentials. Implement real HTTP between them, audience-restricted service identity, local audit/outbox/inbox and scoped ownership receipts. Complete sign-in, principal/owner setup, a service and standalone agent registration, and a journey link in the same UI.

Use `cmd_<owner>_<uuid>` for new opaque public command IDs and a migrated routing map for old UUIDs. Test actual command lookup after issuer restart. Prove one service cannot directly query or write another database. Run two-service authorization/revocation tests and preserve public response compatibility. The milestone is incomplete if UI controllers still call the legacy Java dispatcher or shared database.

## M2 Establish source evidence and topology

Build S03, S04, S05, S06 and the first S14 projection. Connect a controlled source adapter through discovery, source binding, observation retention, qualification, objective proposal/independent activation and accepted graph projection. Use actual PostgreSQL, Neo4j, Kafka and artifact storage in the integration profile.

Implement the objective-import preview/read/resolve/propose/history path and provenance into objective proposals. Implement typed dependency acceptance, graph impact/difference/subgraph operations and reconciliation reports. Add leased collection/evaluation triggers with distinct collection, freshness and evaluation policies. Current unavailable cloud collectors stay visibly unaccepted until their real provider conformance milestone.

Completion requires service-only, agent-only and mixed dependency views; current-source failure handling; duplicated/out-of-order events; graph content verification; and a UI walkthrough that can complete the setup with retained results. S14 can lag without losing domain facts, while downstream action eligibility must detect stale required context.

## M3 Establish assessment knowledge and advisory agents

Build S07, S08 and S09. Migrate deterministic evaluations, observed risk, non-executable recommendations, approved retrieval, case seals and the tested advisory lifecycle. Replace the former database joins with source-owner APIs/receipts and local fingerprints. Preserve bounded source reads, output/dissent history, current source disclosure, independent human arbitration and the remote runtime journal.

Prove one successful A0, A1 and A2 run across independently deployed services, an unknown model outcome, a changed-purpose rejection, actual source/output-file outage and output retry without a second observed gateway invocation. Connect recommendations, retrieved guidance, retained run history and next valid review steps in UX01. Do not promote advisory output into operational permission.

## M4 Establish durable policy decisions and authorization

Build S10's signed policy lifecycle, durable policy evaluation, proposal review obligations, approval decisions, issuance/revalidation, control fences and decision-envelope replay. Reuse the tested policy calculator as an internal component. Add owner receipts and current authority checks around its durable result.

Exercise a valid narrowed approval and a valid bounded authorization, not just rejection tests. Race identity/policy revocation with reservation and final dispatch checks. Demonstrate unavailable required services cannot create new permission. Replay a sealed decision in a worker without provider credentials and verify that no operational effect occurs. Preserve the exact human reason and context vector.

## M5 Establish execution and independent recovery evidence

Build S11 and S12's real workflow, provider-plan translation, action dispatch, checkpoint/resume, compensation, probes, experiments and certification. First use a bounded local adapter with an independently observable side effect and an idempotent status query. This proves the whole lifecycle without claiming cloud acceptance.

Then qualify the first real provider action with scoped live credentials, conditional-update/request-ID behavior and an approved failure scenario. Add provider capabilities one by one. Complete recovery-path reads/certification and workflow execute/validate/compensate operations. Show provider success with failed validation remains unrecovered. Show an ambiguous external effect is reconciled before any repeated mutation.

## M6 Establish commercial and operational administration

Build S13's customer/pilot/value/claim/gate workflows, subscription configuration, immutable usage admission/rating, invoice posting and payment-provider reconciliation. Complete the customer economics view and UI. Add notification routing, escalation and evidence export through S14, with current owner disclosure.

Test pricing/allowance boundaries, exact decimals, duplicate usage/webhooks, unknown outcomes, credits/refunds and interrupted reconciliation. A product claim needs evidence and approval; it is not automatically established by telemetry or a generated report. No commercial entitlement may bypass an action approval or agent authority bound.

## M7 Establish prediction learning adaptation and provider breadth

Implement the additional controlled S07 feature/model and learning-record interfaces, model registry/card, offline and shadow evaluation, uncertainty/calibration and the mapping to approved Core semantics. Link incidents and experiments to root-cause hypotheses, owners, due dates and closure tests. Route proposed objective, model, workload or policy changes through their owning review workflow.

Qualify AWS, Azure and GCP collection and the explicitly selected execution actions individually. Compare capability differences and retain Unsupported where a requirement is unmet. Exercise agent model, knowledge, tool, memory and handoff failures. Prediction remains advisory until its declared promotion criteria pass; A3 automatic operational selection is not introduced by inference from A2.

## M8 Complete migration and release acceptance

Run every retained operation contract against the new owner, migrate each remaining domain under its ownership epoch and reconcile data/artifacts/events. Run all thirty-nine inherited scenarios plus the distributed acceptance matrix. Complete real browser task journeys, accessibility review, deployment upgrades, backup restoration, revocation races, multi-tenant isolation, provider conformance and billing reconciliation.

Regenerate design/API/database guides, activity and sequence diagrams, UI illustrations, the document library and paired document outputs. Build the complete code and document packages with manifests. Publish exact executed results and remaining deployment limitations. Production readiness or a revised score requires its own evidence; passing structure checks or planning tests is insufficient.

## Milestone dependencies and delivery controls

M0 precedes all extraction. M1 provides identity/catalog to M2. M2 provides qualified context to M3. M4 depends on current source receipts from M1 through M3. M5 depends on M4 and usable independent validation. M6 can develop commercial domain logic alongside M3 through M5, but billing admission must consume actual owned usage receipts. M7 builds on stable measurement and action evidence. M8 requires all selected capabilities and their migration gates.

A milestone record lists the source revision, changed contracts, migrated tables, executed tests, observed failure behavior, UI steps, evidence locations and open provider/deployment limitations. Estimate effort only after M0 and the first measured extraction; the design does not invent calendar commitments or staffing capacity. Work can be parallelized by independent service ownership after contracts stabilize, without requiring a shared database or synchronized release of every service.

## Standard service implementation structure

Each service module contains API DTOs/controllers, application commands/queries, domain aggregates/state transitions, ports and infrastructure adapters. SQL migrations and owner-local transaction functions live with that service. Integration tests start actual required dependencies and verify runtime-role permissions. A domain worker consumes only its declared command/event families and commits under a current lease.

An image has a non-root runtime, immutable artifact version, health/readiness/startup probes, graceful shutdown and bounded connection/concurrency pools. CI builds the owning module and its contracts, scans/signs its image, publishes its SBOM and runs consumer compatibility tests. Deployment updates use expand/migrate/contract database changes, one write owner and a tested rollback/forward-repair plan.

## Migration procedure for one domain

1. Inventory its exact tables, rows, signed bytes, artifact references, command statuses and outbox watermark.
2. Deploy the new owner read-only and compare its authorized responses with the existing owner.
3. Freeze new domain writes and drain or fence active legacy workers; retain their late outcomes explicitly.
4. Export a consistent snapshot and replay accepted changes to the declared watermark. Preserve original IDs, times and digests.
5. Replace cross-owner foreign keys with scoped references and verified owner receipts; retain a legacy reference-resolution map.
6. Reconcile row counts, aggregate revisions, checksums, artifact availability, authorization behavior and event/inbox positions.
7. Switch the route ownership epoch and permit only the new owner to write that domain.
8. Run the real UI workflow and fault cases, retain cutover evidence, and apply the agreed observation period before retiring the adapter.

A rollback after accepted new writes is a data migration, not a routing toggle. Record a forward correction or explicitly reconcile reverse changes before moving ownership again.
