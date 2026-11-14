# Detailed service specifications

Version 1.0 | Architecture revision MS 01

These specifications define the target implementation boundaries. All target services are proposed. The existing Java classes and SQL are migration inputs, not independently deployed services. Every service follows the common identity, database, inbox/outbox, command and artifact rules in the main design. API ownership is exhaustive in the accompanying inventory; the examples below explain important behavior rather than repeat every path.

## S01 Identity and access

S01 owns tenants/environments, principal admission, eligible human owners, scoped memberships, revocation epochs and service-delegation receipts. An external identity provider proves who signed in; S01 decides which registered principal and current UACRF permissions that identity has. Deployment bootstrap and emergency recovery are explicit administrator operations with an auditable initial trust chain.

Its principal and membership aggregates preserve issuer/subject identity, enabled state, token-valid-after time, scoped capabilities and expiry. Membership changes require the existing independent authorization rules. Ownership transfer validates the destination's current eligibility and the expected aggregate revision; catalog and other owners receive an owner-eligibility receipt, not SQL access to the membership table. Last-administrator and self-grant protections remain local transactional invariants.

Existing identity-provider, principals, owner-candidates, memberships and transfer APIs remain externally stable. Internal APIs issue narrow audience-bound delegation and current eligibility receipts. Events include principal status, membership revision, ownership eligibility and environment lifecycle changes. Revocation is delivered asynchronously for cache invalidation, while privileged transitions use current online checks. A broker outage therefore does not make a stale positive cache sufficient for an action.

Extract `Identity` verification and identity SQL into this service, while each caller retains transport signature validation. Keep IdP authentication separate from membership administration. Acceptance includes cross-tenant references, expired/deactivated identities, grant narrowing, last-admin safety, independent authorization, confused-deputy attempts and failure during an S10 permit reservation.

## S02 Estate catalog

S02 owns workload identity, journey structure, journey-to-workload/resource links, shared assets, accountable-owner references and protected agent profiles/versions. It distinguishes authored inventory intent from provider-observed configuration. Discovery can propose an identity match; uncertain matches remain reviewable and never overwrite an accepted workload silently.

An agent profile contains its immutable service compatibility anchor, owner, runtime principal, purpose and kind. Each activated version pins model, prompt, knowledge, tools, memory, data policy, runtime build, evaluation and recovery contracts. A human version author cannot independently activate their own bytes even when a different person owns the profile. Retiring a relationship removes it from current views while retaining history.

Public APIs include services, agent-workloads and versions, journeys/steps/capabilities, shared resources/consumers and dependency proposals. Protected execution creation and runtime events belong to S11, not the profile registry. S02 emits workload-version activation, inventory revision and relationship proposal events. S06 accepts and projects qualified relationships; a catalog save alone does not establish graph acceptance or recovery independence.

Use current catalog/agent registration SQL and UI forms as extraction inputs. Replace identity-table joins with S01 receipts. Test service-only journeys, standalone agents, mixed shared resources, owner transfer, duplicate/conflicting links, independent version approval, retired resources and partial discovery. UI selectors must link to current retained objects, not fixed example IDs.

## S03 Connections and ingestion

S03 owns connection enrollment, provider capabilities, credential references, source bindings, discovery runs, collection policies and leased source checkpoints. Credentials are stored by reference in an approved secret store. Collection workers receive read-only provider permissions and cannot use execution-cell credentials.

Collection schedules distinguish polling interval, provider publication interval, observation freshness and evaluation cadence. Scheduler leases prevent two workers from advancing the same source checkpoint. Event sources retain their provider identifiers, sequence/continuation position and source observation time. Duplicates are admitted idempotently; gaps, throttling and conflicting payloads remain observable. A failed poll does not refresh the source's successful-verification timestamp.

Connectors implement inventory snapshots, signal batches, configuration receipts and source-definition reads through a declared provider capability matrix. They submit observations to S04 and objective-definition import material to S05. Connection reconciliation compares source state and scoped catalog/topology receipts, retaining a drift report instead of silently repairing unrelated authorities.

The current collectors are unavailable skeletons. Extract interfaces and policy behavior, then implement and test real adapters separately for AWS, Azure and GCP. Keep fictional UI graph examples in the UI graph-design package; do not seed them as provider inventory or accepted evidence. Acceptance requires real retained source fixtures, pagination, permission denial, source outage, rate limits, checkpoint recovery, out-of-order delivery and live read-only provider conformance before claiming cloud collection works.

## S04 Evidence and qualification

S04 owns evidence events, raw-byte metadata, source schemas, verification receipts, quality decisions, qualification policies, independent review and gap-waiver lifecycle. Measurements, configuration observations and agent events retain distinct typed meaning. A percentile without its unit/window/selector cannot stand in for another measurement.

Observation retention verifies actual scoped bytes and source attribution before commit. Qualification uses the exact approved policy revision and its source/timing/coverage constraints. It retains why evidence was qualified, disqualified or unknown. A waiver must be signed, scoped, independently reviewed and current; it does not create measurements for an uncovered interval.

Qualification and withdrawal APIs emit receipts/events used by S07 and S10. Owner-issued eligibility receipts identify observation/qualification IDs, digests, measurement cutoff, issue time, expiry, policy version and revocation epoch. A service may keep historical results while refusing new use after withdrawal or expiry. Disclosure requires the current classification and purpose checks, not possession of an old artifact reference.

Reuse the evidence processor, qualification engine/processor, waiver trust and strict staging validation. Remove direct access to S01/S02 policy facts when extracting; replace it with checked receipts. Tests include actual missing/corrupt files, unsupported schemas, interval gaps, exact decimal/percentile meaning, same-key conflicts, waiver revocation, authority changes during verification and a fenced late worker commit.

## S05 Objectives and imports

S05 owns templates, comparison/measurement definitions, allowed target ranges, organization constraints, workload objective revisions, decisions and activation history. An approved objective expresses the required outcome. The journey explains its customer relevance; it does not supply telemetry or invent a target.

Imports preserve source definition identity, observed source revision, last verified time and user resolution. A preview is immutable input for a conflict decision. Resolve, propose, submit, decide and activate are separate transitions with expected revisions and retained reasons. An unchanged source definition can remain current after successful re-verification. A source outage cannot establish that an old definition is still valid.

S05 checks workload ownership through S02 and current human authority through S01. It emits activation, supersession and import-resolution events. S07 consumes the effective target definition; S03 supplies collection source material and triggers, but neither activates an objective on the owner's behalf. Mandatory organization constraints apply to both imported and local proposals.

Extract the existing objective procedures and forms, including the partially implemented provenance path. Complete imported provenance before calling that operation done. Acceptance covers conflicting source updates, retired templates, exact units/windows, independent review, mandatory-rule enforcement, source verification outages and a material objective change invalidating a pending action context.

## S06 Topology and dependencies

S06 owns graph proposal acceptance, immutable graph revisions, typed nodes/edges, impact/difference/subgraph records and projection/reconciliation receipts. PostgreSQL is its authority. Neo4j serves graph reads for the exact accepted scope and revision. No other service writes Neo4j.

Graph keys include tenant, environment, entity type and stable entity identity. Every relationship is checked for permitted endpoint roles, source provenance, revision and scope. Acceptance distinguishes an observed dependency from an authored proposal. Projection workers apply idempotent revisions under a lease and verify actual resulting content before marking a generation usable.

Existing graph reads, dependency/impact/independence views and the remaining classify/select/impact/reconciliation operations move here. Queries expose service-only, agent-only and mixed paths and show shared models, identities, cloud resources, regions and failure domains. The UI scope selector filters one authorized accepted revision; it is not a new source of topology or authorization. Reconciliation reports differences between catalog intent, accepted provider inventory and the projection; it cannot promote a drifted graph into an authorized context.

Reuse GraphStore, GraphService, GraphWorker and typed graph validation. Test duplicate, stale and conflicting versions, projection-marker tampering, tenant-filter omission, bounded traversals, rebuild switch-over, lost lease, graph outage and exact revision use in S10 decisions. Diagnose independence here; certify it in S12 against the declared failure scenario.

## S07 Assessment and learning

S07 owns fixed cases, rule packs, deterministic evaluation results, observed risk, retained candidate recommendations, prediction/model records and learning proposals. It obtains exact effective objectives from S05, qualified source receipts from S04 and graph receipts from S06. It retains the complete dependency vector and actual artifacts used for a calculation.

Evaluation modes retain their established meaning. A shadow or dry-run label is not an action permission. A confirmed breach and missing evidence remain distinguishable, and an old healthy result cannot replace a newer unknown context. Recommendations retain effect bounds and prerequisite references while remaining non-executable.

Prediction workers use approved feature windows, pinned model versions, model cards, data lineage, uncertainty and leakage-resistant evaluation. A forecast records forecast time and horizon separately from observation time. Learning records link incidents/experiments to hypotheses, owners, due dates, proposed versions, tests and closure evidence. Adaptation proposals pass through the appropriate owner and review service rather than automatically changing production policy.

Extract AnalysisCases, EvaluationEngine/Measurements/Processor/Worker and ProposalProcessor/Worker. Complete the unimplemented predictive/learning interfaces under explicit feature gates and measurable acceptance. Tests cover actual source-store failures, boundary value calculations, stale revisions, repeated source verification, missing inputs, corrected late measurements, deterministic replay, forecast calibration and rejected unreviewed model promotion.

## S08 Knowledge and retrieval

S08 owns knowledge items, immutable versions, independent reviews, classification, allowed purposes, target scope, retrieval manifests and citation bindings. Uploading a document creates retained material; it does not approve that material for inference or recovery advice.

The initial supported search preserves the existing bounded exact-term retrieval contract and actual excerpt verification. PDF conversion, embeddings and external indexes require separately versioned processors and provenance; they are not implied by a search endpoint. Selected excerpts retain source version, chunk/locator and both source and excerpt digests.

Current eligibility checks owner/reviewer authority, review deadline, lifecycle, purpose and classification. A newer unapproved draft does not replace the last eligible approved version. Withdrawal blocks new use and affected protected reads according to policy while keeping permitted history. S09 may use a manifest only for a matching source case and sealed purpose.

Reuse KnowledgeRetrieval, RetrievalService and staging format checks. Tests include unsupported files, bounded ranking, duplicate chunks, wrong purpose/classification, source/excerpt substitution, missing files, withdrawal, approved successors, changed requester authority and replay without disclosing withdrawn source text.

## S09 Advisory agent orchestration

S09 owns attributable advisory seals, orchestration runs, mandatory roles, budget reservations, assignments, one-time delivery, outputs, dissent and human arbitration. Profile/version truth stays in S02; source cases in S07; retrieval in S08; current identity in S01. S09 retains exact receipts and immutable input material instead of querying those services' databases.

Supported A0/A1/A2 workflows derive their tier from the role set. Every role has a distinct profile/runtime identity and one bounded attempt. The configured gateway receives approved read-only context and a separate credential. The runtime journal retains dispatch intent before invocation and reuses a retained result after output retry. A delivery lost before local durable storage requires explicit reconciliation rather than another automatic invocation.

Outputs bind producer, assignment, attempt, generation, context and supplied citations. MODEL_OUTCOME_UNKNOWN retains null usage and abstention. Cancellation blocks new work and preserves late ineligible results. Arbitration checks the exact viewed revision and complete retained output/dissent set, re-verifies actual files and retains the human rationale with its canonical fingerprint. Advisory selection never grants action permission.

Extract the tested AdvisoryWorkflow, AdvisoryArtifacts, AdvisoryRuns, gateway, journal and remote runtime client; replace their shared SQL prepare/finish context with owner receipt APIs and local fencing. Test A0/A1/A2, duplicate attempts, shared source dissent IDs, missing role output, wrong runtime, model timeout, cache/journal failure, stale purpose, cancelled generation, complete human review and output retry with exactly one observed fixture gateway invocation.

## S10 Decision governance

S10 owns signed authorization-policy versions, policy decisions, approval obligations/decisions, operational authorization reservations, revocation fences, decision envelopes and read-only replay. A candidate record, a model recommendation and a commercially licensed feature are inputs, not approvals.

A policy decision compares exact candidate effects, qualified prerequisites, action scope, validation/recovery plan and required reviewers. Independent approval can narrow scope, defer or reject. Issuance pins the approved context vector. Final permit reservation and revalidation are local transactional state transitions bound to one executor attempt. Critical publication/revocation uses the control-fence protocol described in the main architecture.

Decision envelopes retain immutable source references, digests, rules, arithmetic, reviewer decisions and assumptions. Replay runs in a read-only worker without provider credentials. It reconstructs historical reasoning and cannot reissue an operational authorization or call a provider. Replayed facts remain historical even if a current model would suggest a different candidate.

Reuse the ActionPolicyEngine and policy signing/trust components; durable decisions, approvals, permit APIs and distributed source checks still require implementation. Acceptance needs a successful bounded authorized action path as well as denial cases, reviewer independence, stale source/policy, revocation races, expired reservations, replay without side effects and an unavailable dependency preventing new permission.

## S11 Execution and workload control

S11 owns provider plans, workflow steps, attempt ledgers, protected agent executions, generation control, checkpoints and compensation orchestration. Provider adapters run in tenant-isolated execution cells with narrow short-lived credentials. Translation is separate from mutation and retains both provider-neutral intent and exact provider request constraints.

A durable workflow moves through accepted, waiting for authorization, ready, dispatching, observing, validation requested and a justified terminal result. Compensation and cancellation are explicit branches. Before a provider call, persist its attempt and stable request identity. A timeout yields unknown outcome until provider reconciliation; do not automatically repeat an irreversible effect.

Protected agent events consume declared budgets in sequence and bind the active version, runtime identity and generation. A checkpoint is not sufficient for resume without its memory/context provenance and required S12 evaluation. A new model, identity, tool or handoff contract requires the appropriate version/context review.

Extract current guarded agent execution procedures into S11 and implement actual provider plan/execute/status/compensate ports. First prove a real bounded local execution adapter with an observable effect; then qualify a provider action individually. Tests cover authorization consumption, duplicate delivery, process death around dispatch, late response, conditional-version mismatch, cancellation, compensation failure and recovery verification through S12.

## S12 Recovery assurance and validation

S12 owns validation plans, approved probe bindings, results, resilience experiments, recovery path definitions/certifications and independent recovery assessments. A retained plan or a service dependency diagram is not an executed probe or a certified path.

A plan declares mandatory checks, target scope, expected baseline, probe versions, schedule/dependency graph, concurrency and budgets. Probe workers receive least-privilege read/test identities distinct from mutating executors where independence is required. Results retain actual observations, time, source and plan/version binding. Missing, stale or failed mandatory checks prevent a successful validation.

Certification binds a declared scenario, path, workload version, fault-domain assumptions, evidence and validity interval. Change review evaluates whether a graph, policy, identity, source or workload change invalidates that certificate. Controlled experiments require an approved blast radius, stop condition and action authorization when they cause effects. Diagnostic assurance remains available without claiming that a live recovery was proven.

Reuse ValidationPlanProcessor/Worker, ValidationPlans and Assurance; add real probe dispatch and outcome verification. Tests include dependency cycles, source/probe-policy drift, incomplete mandatory checks, shared recovery dependencies, agent model/knowledge/tool failures, unavailable probe identities, containment failures and an independently observed successful recovery.

## S13 Commercial and billing

S13 owns customer qualification, paid-pilot charters, value review, evidence-backed product claims, product investment/launch gates, billing accounts, subscriptions, price/allowance versions, admitted usage, invoices, payments, credits/refunds and reconciliation. Operational policy approvals remain S10 records with different purposes and authority.

A source usage receipt has an immutable identity, producer, metric, unit, quantity, observation interval and scope. Admission verifies it before rating. Rating uses exact decimals and frozen contractual rules; a later correction is another retained record. Invoice posting, payment allocation and refunds have local transactional invariants and stable provider webhook IDs. Agents cannot act as finance reviewers or increase their own entitlements.

Provider cost and customer revenue are separate ledgers. Model/provider reservations do not create charges, and ambiguous completion follows an explicitly approved charging policy. Customer economics aggregates measured revenue, cost and effort using attributable records and declared currency conversions. Marketing/research claims retain their supporting evidence and approved wording rather than being generated as fact from a value estimate.

The existing billing DDL and query catalogue are substantial design inputs, not a completed subscription system. Implement ingestion, rating, posting, webhook handling, reconciliation and UI through actual workflows. Test duplicate usage, out-of-order webhooks, price changes during a period, allowance boundaries, tax/currency rules, credits/refunds, unknown completion and payment-provider outage before commercial launch.

## S14 Audit reporting and notifications

S14 owns audit read models, activity projections, finding notification routes/delivery state and evidence-bundle assembly. Source domain services remain authoritative for their business facts and transactional audit receipts. S14 does not approve actions or serve as a distributed transaction coordinator.

Consumers retain producer sequence, source receipt identity, classification and watermark. Activity reads disclose only records currently visible to the caller. Exports obtain current owner-authorized references, seal a manifest and retain export provenance. Missing source content is reported; it is not replaced by an invented complete bundle. A notification delivery receipt remains separate from human acknowledgement and actual finding closure.

Implement queues for destination delivery, retry/redaction policy, escalation owners and dead-letter review. UI recent activity links to each actual owning record. Test duplicate/out-of-order audit events, withheld source fields, export with an unavailable owner, destination outage, ownership transfer, replay and complete tenant/environment isolation.

## UX01 and EDGE01 Experience and API edge

UX01 retains the current single browser application and composes domain API responses. It owns personal saved views/preferences and the server-side session, not domain truth. Page-specific view models avoid making the browser perform fourteen independent authentication flows. Screens preserve task inputs, exact reviewed revisions and links to retained results. Partial failure shows which part is unavailable and prevents unjustified action controls.

EDGE01 terminates the public transport, enforces limits, maps legacy and new command IDs, preserves route-specific serializers and routes to one designated owner. Owner services still perform current authorization. The gateway does not directly query any domain database. Internal domain callbacks never trust actor/tenant headers from the public network.

Extract UiGateway, UiSecurity and controllers into UX01 while replacing their in-process dispatch with typed authenticated clients. Extract route ownership and transport functions from Api/Routes into EDGE01. Acceptance needs real sign-in, current permissions, request protection, accessible keyboard navigation, task completion, correct error retention, role-sensitive buttons and browser workflows across multiple running services. Existing mockups and authenticated HTML tests remain useful, with separate visual and deployed acceptance still required.
