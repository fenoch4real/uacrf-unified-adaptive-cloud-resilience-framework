# UACRF interface and document reading guide

Version 1.0 · UI design revision 8 · Alignment revision 22 · Guide revision 14 · November 7, 2026.

The current UACRF interface returns to the earlier navy navigation and light workspaces while retaining protection for services, standalone agents and multi-agent workloads. Begin with the resilience overview, identify the capability that needs attention, and open the evidence or recovery page that explains the next decision. Detailed execution controls appear when they are relevant to the task.

## Find everything in one place

Open [DOCUMENT_LIBRARY.html](../DOCUMENT_LIBRARY.html) for the architecture, current Technical Approach, UI prototype and seven patent specifications. Keep the assembled folder intact so the links continue to work.

The Technical Approach is at alignment revision 22; the separate microservices architecture is revision MS01. Patent specifications remain unchanged source documents. The current navigation and catalogue guide explains the separate Services and Agents inventories, journey membership and shared resources. All working documents remain Version 1.0; revision numbers identify separate architecture, diagram and design changes.

## Start with the familiar workspaces

Begin with Customer journeys, or open Services and Agents directly when the capability is known. Shared resources contains models, knowledge bases, tools and other reusable dependencies. Knowledge manages approved content and versions. A standalone agent does not need an unrelated parent service; services and agents may both consume the same resource. Use the [navigation registry](../ui_workloads/NAVIGATION.json) and [UI package guide](../ui_workloads/README.md) for the current menu and prototype scope; the Technical Approach remains authoritative for requirements and API contracts.

Overview shows services and protected agent capabilities together. The filter offers All workloads, Service workload and Agent workload. Inventory rows show the capability kind, runtime state, evidence qualification, finding and owner separately. The demonstration contains four workloads, with three needing attention and three missing current evidence. These counts describe the fictional review snapshot and are not a score or production measurement.

Assess explains the scope and quality of evidence. Control connects a finding to policy, an exact candidate and the required approval. Recover presents a proposed response and its missing prerequisites. Evidence retains the decision context and observed outcome. Secondary workspaces cover onboarding, knowledge, scoped access, policies, assessments, incident investigation, notifications, controlled tests and retesting. The existing 49 product view identifiers remain stable; nine additional administration previews (UI-50–UI-58) bring the current register to 58. The added previews do not imply persisted handlers.

## Protect an agent through its full journey

An agent has an accountable owner and a versioned profile describing instructions, models, knowledge, tools, permitted effects, memory, runtime identity, evaluation and limits. Onboarding records these before activation. A service identity anchors governance without requiring an artificial parent service for a standalone agent.

Dependency views make model, tool, knowledge, memory, identity and handoff requirements visible. The execution timeline records admitted attempts, remaining limits, tool outcomes and uncertainty. Model availability cannot establish that the customer's task succeeded. Multi-agent roles share the root execution envelope; a missing mandatory role remains a gap rather than being hidden by another successful response.

Checkpoint review exposes the source execution, event sequence, exact version and input, memory namespace, byte digest and expiry. Stop and containment explains the generation fence and broker acknowledgement. A pause receipt does not prove that an external write stopped. Uncertain tool effects must be reconciled before retry or checkpoint resumption.

Recovery follows the existing proposal, policy, distinct approval, signed bounded authorization and workflow chain. A resumed run receives a new identity, the remaining budget and no later deadline. Current independent evaluation and verified checkpoint bytes are required. No agent receives UACRF approval, qualification or recovery authority. Validation examines the actual task, data, security and business outcome before a recovery conclusion is issued.

## Keep assistance and billing clear

Optional UACRF assistance remains A2 advisory unless separately promoted. It can investigate and draft for human review. A protected customer agent has separately governed business tool permissions. These roles appear separately in setup, output review and access management.

Usage shows operational reservations, measured provider usage and customer charges as different facts. A reservation is not an invoice line. The internal service anchor and agent profile describe one protected capability; they do not automatically create two subscription charges. Existing billing rules, price versions and evidence controls continue to apply.

## Read the screen assets correctly

The HTML prototype is the interaction reference. Twenty current SVG illustrations and their PNG copies show the revised menus, journey and shared-resource workspaces, overview and protected agent workflows. The earlier 33 PNG concepts remain historical visual references and are not relabelled as new captures. The document library keeps current and historical assets clearly identified.

The prototype uses fictional data and local previews. Buttons do not connect providers, activate permissions, send handoffs, stop real processes or dispatch recovery. Targeted browser checks verified expected titles for all nine administration previews and found no `NaN` output. Broader browser, keyboard, accessibility, responsive-layout, live integration and user-acceptance work remains pending. Source consistency, script syntax and static illustration review are recorded separately from those acceptance activities.

## Review completeness and the next implementation work

The Technical Approach at alignment revision 22 consolidates the current requirements and links the architecture and UI. Seven patent specifications remain unchanged source documents. Historical evidence and component test results remain distinct from full-system acceptance.

Review the comprehensive critique for remaining runtime broker, HTTP handler, provider adapter, object storage, reconciliation and independent validation work. Production data binding, responsive behavior, accessibility, cloud integration and user acceptance still require execution. All full requirement acceptances and live integration scenarios remain NOT_EXECUTED. The earlier 87.5 out of 100 document alignment score is historical and is not increased for this interface revision.

Use VERIFY_FILES.md for your file-by-file review. The release manifest records the exact packaged files and their hashes. Start implementation with one read-only workload, then prove bounded dispatch, interruption, one governed recovery path and the intended customer outcome before broadening provider or multi-agent coverage.

## Current prototype context and actions

The current prototype adds owner, disposition and next-review summaries to sixteen key views. Provider marks sit beside names in inventories, resources and graphs. Every view has a primary next step. Review preparation stays local and supports validation and cancellation; unavailable connection/access changes and recovery dispatch have visible explanations. ACTION_REGISTER.json traces API-associated actions to the existing contract and persistence map.

## Shared application visual language

Design revision 8 applies the same visual language across the 58 registered views. Navigation, page headings, resource types, primary actions, states and recent activity use shared icons with visible labels. Provider placement, runtime health, evidence qualification, authorization and billing remain distinct. Recent activity is a connected, newest-first timeline that identifies the affected object, actor and actor kind, recorded time, disposition and useful review link. Requested, recorded and confirmed outcomes are not interchangeable.

The feed is illustrative and incomplete. Its strict display schema and source map use existing audit and workload records; no new activity table or canonical HTTP endpoint is introduced. A live authorized projection, disclosure filtering before pagination and real network-state behavior still require implementation and testing. VISUAL_SYSTEM_GUIDE_v1_0.md and ACTIVITY_DATA_MAP.json in ui_workloads define the current presentation contract. The nine technical and disclosure pairs retain their architecture baseline; this visual revision supersedes their earlier UI presentation references without changing formal requirements or claims.

## Follow the setup and customer journey

Start with ui_workloads/START_HERE.html for a twelve-stage path from scope and owners through connection, registration, customer journey, evidence, testing, validation and handover. Every stage names its expected result and links to the actual prototype screen. The current view register provides next-screen destinations; links do not mark setup complete or bypass evidence and access gates.

The connection screen now distinguishes provider choice, environment scope, access review and collection health. Customer journeys shows the ordered business flow separately from the technical dependency graph. Place Order and Resolve Order remain supplied examples. The planner supports ordered steps and local service/agent associations for each step. Users review and explicitly confirm the composition before preparing a local registration-request preview. All rehearsal state remains in page memory; no request is sent, and reload or Discard local draft removes it. There are now twenty current static screen illustrations. The setup how-to and local planning schema document these boundaries.

## Flexible journeys and the setup map

The journey planner supports adding, removing and moving steps up or down, while preserving their order and service/agent associations. Each step may use services, agents or both. A composition cannot be confirmed until every step has at least one capability association. The confirmation gate precedes an illustrative local registration-request preview; the preview is not a canonical API payload and neither action registers or persists a journey. A journey is not limited to three steps. The local prototype supports 1–50 named steps as a practical display bound; the underlying ordered journey schema has no three-step restriction.

Keep the main menu stable for daily work and use the how-to for the setup sequence. Customer journeys describe outcomes and business steps. Services and agent workloads are peer capabilities protected in Core; standalone agents need no unrelated parent service. Shared resources are reusable dependencies that can have one or more consumers. Knowledge separately manages content, versions, approval and retrieval. Optional UACRF advisory agents remain distinct from protected customer agents.

Read the clickable setup map in ui_workloads/START_HERE.html.

## Objective configuration and screen continuity

Owners of services and agents propose measurable targets using approved templates. Administrators maintain scoped grants, versioned templates and mandatory organization constraints; that role does not confer independent approval authority. A distinct authorized reviewer approves or rejects a submitted revision. The local prototype demonstrates these roles but is not authentication or a running server configuration workflow.

Place Order uses Checkout objectives; Resolve Order uses Order support agent and Policy review team objectives. The team is Waiting throughout the example, and agent validation concerns read-only lookup reconciliation rather than refund execution. All proposed numerical targets remain illustrative and measured outcomes remain Unknown. Twenty-six local review forms now collect task-specific inputs and render a structured preview; none is a complete API payload or a dispatched command. Fixed example graphs retain explicit scope labels and routes back to inventory. The how-to contains one twelve-stage flow with expandable expected results and related destinations.

objective_design contains seven proposed API operations, a strict revision request schema, four proposed record families, a graphical schema and scoped read-query sketches. They are not part of the current baseline of 74 canonical APIs, 102 business tables, 18 applied migration files and 338 named SQL building blocks. The storage design is fail closed with no worker grants. Implement controlled lifecycle procedures, current scoped authorization, command idempotency, atomic audit and outbox, template constraint enforcement and evidence invalidation before promotion or use. Isolated DDL and objective trigger tests are recorded in implementation_review; application-role, concurrency and live acceptance remain pending.

## Configure and apply objective templates

### Configure reusable templates

Open Settings and choose Objective templates and rules. Select a template to create its next local version, or create a new template. Define its name, service or agent scope, metric, unit, default target, minimum and maximum, measurement window and required evidence. The prototype rejects incomplete definitions and ranges that do not contain the default target. Percent ranges cannot exceed one hundred. Saving adds a local version without overwriting earlier versions.

### Apply a template to workload targets

Use the Objectives link under Services or Agents, or choose the matching Apply button in template administration. These links open the workload-target tab in the shared objective workspace. Choose a relevant template and apply its defaults, then add the proposed target, effective date and reason. The proposal must use the selected template version and unit and stay within its allowed range. If the catalog changes that template, apply the current version before submitting a new proposal.

### Review history and operating boundary

A submitted proposal retains its template snapshot. Later template edits do not rewrite that proposal or the example measurement result. A separate reviewer can rehearse approval or rejection in the workload tab; neither action activates a target or proves compliance. Administrator configuration and workload approval are separate responsibilities. The catalog, proposal history and decisions exist only in page memory and disappear on reload. Live scoped permissions, persistent storage, audit, activation and runtime use still require the proposed backend extension.

### Navigation and document control

UI-22 has one canonical placement under Settings, with scoped Objectives aliases under Services and Agents. This preserves forty-nine canonical views and thirteen main destinations. Guide revision 14 and UI refinement 8 describe the local catalog flow. Version 1.0, the architecture and database baselines, formal requirements and patent claims remain unchanged. The proposed objective schemas require the exact template identifier and version; the canonical API count remains seventy-four.

## Objective imports and signal timing

### What comes from a source and what comes from the business

Cloud and monitoring systems report what is happening: response times, errors, deployment changes and permission changes. An approved objective says what the service or agent must achieve. UACRF can import that objective from an existing tool or let an accountable owner propose it locally.

The customer journey explains why the objective matters and which workloads support the customer outcome. It does not supply measurements or invent targets. The source of an objective definition and the evidence used to measure it are separate records.

### Import an existing objective without losing its meaning

A connector is the integration component that reads an approved external system. For each imported objective, retain the source system, external identifier and source version. Also retain a digital fingerprint of the source content, so later reviewers can establish exactly what was read. A separate fingerprint identifies the definition after conversion into UACRF terms.

Record when the source definition changed, if known; when the connector last verified it; and when UACRF received it. Keep the mapping and conversion software versions. The server establishes the tenant, environment and authenticated actor. A name supplied in a message is not proof of identity.

Map the definition to the correct service or agent, metric, unit and measurement window. For example, 720 seconds can become 12 minutes through an approved conversion. Do not silently change a percentage, percentile, comparison direction or calendar window. Keep the original content in controlled storage. Unsupported or incomplete definitions wait for review rather than becoming active targets.

### Compare source changes with local changes

Compare three definitions: the last agreed source definition, the incoming source definition and the current local definition. Identical content is an unchanged import. If source and local definitions changed differently, retain a conflict rather than choosing a winner automatically.

The owner can keep the local definition, prepare a proposal from the source or detach source tracking. Record the choice, reason and exact versions. Check those versions again before accepting the proposal, because another update may have arrived during review.

An imported proposal follows the existing independent review and effective-date process. Delivery by a connector does not approve or activate it. Older evaluations retain their original objective and source references.

### Handle source loss and withdrawal explicitly

An objective missing from a partial scan is not necessarily deleted. Treat it as withdrawn only when the source explicitly reports removal or a complete, authoritative comparison establishes it. Preserve the history and ask the accountable owner to review the linked definition.

A failed synchronization, revoked permission or stale source check does not prove that the target changed. It also does not justify presenting the external definition as current. Apply the evidence rules and show an Unknown or otherwise restrictive conclusion where needed. Authorized containment and human escalation can still remain available.

### Use three separate clocks

Collection timing determines when a connector receives or requests data. Freshness determines how old that data may be for the decision at hand. Evaluation timing determines when UACRF applies its rules. These are separate settings.

For operational signals, calculate age from the source observation time. A late arrival must not make an old observation look new. Polling more frequently cannot create measurements that the source has not produced.

For a stable objective definition, use the last successful source verification instead of the definition's original creation date. An unchanged definition can still be current. Configuration also needs a verified snapshot or attributable change stream; failed checking cannot establish that old configuration is still valid.

### Choose feasible starting intervals

The timing register contains starting examples, not provider guarantees. Metrics use source events plus 30-second polling, with a five-minute reconciliation check and a two-minute freshness limit. Reconciliation means checking the source again to find changes that event delivery may have missed. Routine metric evaluation uses a five-minute observation window.

Configuration uses change events plus five-minute polling and fifteen-minute reconciliation. Objective definitions use source changes plus fifteen-minute checks and hourly reconciliation. Their freshness depends on the last successful verification. Agent runtimes report admission, dispatch, completion, timeout, cancellation and handoff as events; reconciliation and expiry checks expose missing events.

Choose the actual settings for the workload and source. Consider source resolution, delivery delay, criticality, cost and quotas. A freshness limit shorter than the polling interval needs reliable event delivery to fill the gap. If the source cannot support the target, change the plan rather than claim coverage.

### Evaluate meaningful changes and expired evidence

Reevaluate affected workloads when a target is breached, a material dependency changes, a permission is revoked, an agent fails, a budget runs out or an approved objective changes. Closing an observation window can also trigger evaluation. An expiry timer must trigger reevaluation even when no new signal arrives.

Routine observations can be grouped to avoid reacting to every small fluctuation. Urgent revocation, evidence expiry and containment events bypass that routine delay. Each evaluation retains the exact objective, graph, policy and evidence versions it used. An evaluation result does not grant permission to dispatch a recovery action.

The new UI controls rehearse imports and timing in page memory only. They do not connect an external source, run a collector or activate an objective. Live integration, browser review and acceptance tests remain pending. Read import_signal_design/README.md for the complete proposed contracts, diagrams and acceptance plan.

## Historical implementation review snapshot

This earlier review snapshot describes the implementation state at that revision. The current requirements and capability baseline is the [alignment-revision-22 Technical Approach](../../Technical_Approach/UACRF_Technical_Approach_Design_v1_0_alignment_revision_22.docx); the partial runtime status is in the [runtime guide](../runtime/README.md) and [gap-closure report](../runtime/GAP_CLOSURE.md). Objective design and local prototype coverage do not establish production handlers or acceptance.

The earlier flow notes describe 90 baseline flows, 180 diagrams and revision-specific implementation gates. Treat those counts and architecture/database labels as historical; diagrams do not establish executed HTTP handlers or acceptance.

For current implementation and test status, read the [runtime gap-closure report](../runtime/GAP_CLOSURE.md), [verification snapshot guide](../verification/README.md) and [integration test plan](../verification/INTEGRATION_TEST_PLAN.md).

## API contracts and relationship linking update

All 74 canonical operations now have machine-readable definitions. The earlier 45 missing wire contracts are specified; handler, serializer, privilege and live acceptance remain pending. Four proposed relationship-maintenance routes and a proposed command-status read are separate from the baseline, bringing proposed operations to twenty. Journey and resource detail now provide selection, review, local save/change/retirement/history. Dependency screens accept local proposals. Reloading discards drafts; no access, persistence, accepted graph change or recovery is asserted. Baseline migration/query/table counts and readiness scores do not change.

Use the [runtime guide](../runtime/README.md) for implemented API behavior, the [proposed graph API contract](../graph_design/openapi.proposed.json) for graph extensions, and the [graph design](../graph_design/README.md) with its [diagrams](../graph_design/DIAGRAMS.md) for relationship semantics. Proposed handlers and live acceptance remain open.

## Users and owners revision 15

Journey registration requires an existing owner ID. The local interface now selects an eligible human from Users and accountable owners under Settings. Admit users through a trusted identity source, review bounded memberships and eligibility, then register journeys and link workloads. Disabled identities retain history and produce ownership gaps; transferring ownership never transfers permissions. This earlier identity-extension note is historical; authentication, administrative handlers, privileges, concurrency and live acceptance remain pending. For current boundaries, consult the [MS01 architecture](../../Microservices_Architecture/MS01/README.md) and [runtime guide](../runtime/README.md).

## Runtime revision 2

The [local runtime guide](../runtime/README.md) and [gap closure report](../runtime/GAP_CLOSURE.md) supersede earlier statements that every handler is missing. Ten canonical catalogue, nine identity and four relationship-maintenance routes now have executable PostgreSQL/HTTP implementation and scoped local tests. The four maintenance routes return synchronous 200 metadata receipts after commit. The remaining 64 canonical and 16 proposed operations, product UI integration and live-provider acceptance remain open. Version 1.0 is retained.

## Current PostgreSQL and Neo4j implementation

Runtime revision 3 now implements 27 Java Spring Boot routes and integrates both databases. Twenty-seven integration methods pass through real HTTP, PostgreSQL and Neo4j, using synthetic identity/source fixtures. Accepted-source import, graph sealing, durable leased projection, scoped dependency reads, bounded impact and shared-dependency diagnostics are implemented. The totals are eleven implemented canonical operations and sixteen implemented proposed operations, with 63 canonical and sixteen proposed operations still outside this runtime. Read [the dual-database guide](../graph_design/README.md) and the exact runtime route map. The prototype remains illustrative; graph diagnostics do not certify independence or authorize recovery. Version 1.0 and readiness scores remain unchanged.


## Recovery readiness assurance update

Version 1.0 runtime revision 4 adds a locally implemented recovery-assurance diagnostic workflow. Thirty routes comprise eleven canonical and nineteen proposed operations; 63 canonical and sixteen proposed operations remain unimplemented, with 35 proposed operations total. An immutable baseline, explicit reassessment, current evidence checks, pending accepted revisions, append-only reviews, audit and outbox support services and agents. No recovery certification, external action, live collection or unattended reassessment is claimed. Guide revision 19 and flow revision 9 identify the update. No score increase. Read [the implementation guide](../assurance_design/README.md), [the local walkthrough](../assurance_design/index.html) and [the step-labelled diagrams](../assurance_design/DIAGRAMS.md). Fresh installation includes runtime/003_assurance_runtime.sql; an existing runtime revision 3 deployment uses the offline Java admin assurance-install command after backup and migration review.


## Unified recovery assurance interface

Version 1.0 UI refinement 13 integrates recovery assurance into the current 58-view application. Assess contains the baseline and reassessment forms; journeys, service and agent dependencies, evidence, recovery and overview link to the same in-memory journey record. Scoped recent activities link back to that assessment. The old companion URL forwards to Assess. This is a unified local prototype, not an authenticated connection to the Java API. Runtime revision 4, thirty routes and the existing 32-test backend evidence are unchanged. Guide revision 20 records the integration; live collection, unattended reassessment, connected UI and recovery certification remain open. No score increase. Open [Assess](../ui_workloads/index.html#UI-03) and choose Place Order or Resolve Order. Establish a baseline, inspect changed context, record a local reassessment, then follow the evidence and recovery links. Changes disappear on reload. A draft journey cannot establish an assessment. Read [the UI integration map](../assurance_design/UI_INTEGRATION.md) for screen and API responsibilities.


## Setup and recurring recovery review

Guide revision 21 and UI refinement 14 correct the recovery-assurance how-to. The embedded graphical path, expanded stage descriptions, standalone Start here page, Markdown instructions and SVG/PNG maps now cover baseline creation at step 9 and change review/reassessment at step 12, with a repeating evidence and retest loop. This is a local prototype walkthrough, not live setup acceptance. Java runtime revision 4 and its existing 32-test evidence are unchanged; the prototype still sends no API requests. Version 1.0 and the readiness assessment remain unchanged. Read [the complete how-to](../ui_workloads/SETUP_HOW_TO_v1_0.md) and [Start here](../ui_workloads/START_HERE.html). The canonical stage and loop records are SETUP_WALKTHROUGH.json and SETUP_OVERVIEW.json in ui_workloads. The source-alignment test is verification/test_setup_assurance.cjs.
