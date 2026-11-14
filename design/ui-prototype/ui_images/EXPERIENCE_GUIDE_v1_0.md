# UACRF experience guide

Historical image-era guide · Alignment revision 7 · UI design revision 7 · Guide revision 14

This guide describes the 49-view prototype state and the 33 concept images from that earlier design baseline. The current package baseline is alignment revision 22 and UI design revision 8, with 58 registered views across 13 primary destinations. For current navigation and interaction, use [the current experience guide](../ui_guidance/EXPERIENCE_GUIDE_v1_0.md) and [the current prototype](../ui_workloads/index.html).

Start in Overview to see customer outcomes needing attention, then open Customer journeys to understand their supporting capabilities. Services and Agents are permanent direct destinations. This lets a customer start with either an outcome or a known capability without forcing an agent into an unrelated service hierarchy.

The Work menu contains Overview, Customer journeys, Services, Agents and Dependencies. Operations contains Assess, Control, Recover and Evidence. Resources contains Shared resources and Knowledge. Administration contains UACRF assistance and Settings. Selected workspaces reveal their related tasks in a stable order. One selected page, its parent section and the section label explain the current location.

A journey groups ordered steps and the service or agent capabilities that support them. The journey selector scopes the illustrative inventory lists. The graph canvases retain a fixed Checkout and Order support sample and explicitly say that they are not re-queried by the selector. Services lists conventional services; Agents lists protected single-agent and multi-agent capabilities. A standalone agent does not require an unrelated parent service.

Shared resources records a model, knowledge base, tool, data store, runtime or identity once and shows each consumer. Both services and agents can consume the same resource. Knowledge remains the governed content workflow: intake, review, exact versions and grounded retrieval. A shared-resource entry does not approve its content or grant runtime access.

Dependencies keeps the original graph canvas beside service health and selected-relationship detail. Provider marks and resource glyphs remain inline. The contextual links distinguish service dependencies, agent dependencies, models, recovery paths and changed context. A relationship shows reliance; it does not prove cause, independence or current eligibility.

UACRF assistance describes the framework's optional advisory agents. It is separate from the customer agents that UACRF protects. The existing control kernel, independent evidence, distinct approval, bounded authorization and validation still govern consequential action. Demo buttons describe previews; recovery dispatch remains disabled.

At this historical design revision, the prototype had 49 views. Its 33 PNG concepts remain historical references. The current prototype uses illustrative nonproduction data; browser, responsive-layout, keyboard and accessibility acceptance remain pending.

Working documents remain Version 1.0. The current library includes nine established document pairs, an interface reading guide and a journey/catalogue guide. The catalogue adds five tables and ten API operations; migration 018 and the current mappings explain persistence. Implement handlers and test actual runtime behavior before treating the prototype as a live system.

## Shared icons and recent activity

Design revision 7 extended the shared visual language across the 49 workspaces in that historical baseline. Navigation, page headings, resource types, primary actions, states and recent activity use shared icons with visible labels. Provider placement, runtime health, evidence qualification, authorization and billing remain distinct. Recent activity identifies the affected object, actor and actor kind, recorded time, disposition and useful review link. Requested, recorded and confirmed outcomes are not interchangeable.

The feed is illustrative and incomplete. Its strict display schema and source map use existing audit and workload records; no new activity table or canonical HTTP endpoint is introduced. A live authorized projection, disclosure filtering before pagination and real network-state behavior still require implementation and testing. VISUAL_SYSTEM_GUIDE_v1_0.md and ACTIVITY_DATA_MAP.json in ui_workloads define the current presentation contract. The nine technical and disclosure pairs retain their architecture baseline; this visual revision supersedes their earlier UI presentation references without changing formal requirements or claims.

## Start here and see the business flow

Start with [the setup guide](../ui_workloads/START_HERE.html) for a twelve-stage path from scope and owners through connection, registration, customer journey, evidence, testing, validation and handover. Every stage names its expected result and links to the actual prototype screen. The historical 49-view register explained its next-screen destinations; consult the current view register for the present navigation map. These links do not mark setup complete or bypass evidence and access gates.

The connection screen now distinguishes provider choice, environment scope, access review and collection health. Customer journeys shows the ordered business flow separately from the technical dependency graph. Place Order and Resolve Order remain supplied examples. The planning pad previews another journey with ordered steps, held only in the tab memory. It creates no registered journey, connection, evidence or API request; reload or Discard local draft removes it. There are now twenty current static screen illustrations. The setup how-to and local planning schema document these boundaries.

## Guidance placement

The next-review explanation appears inside How to set up UACRF as a secondary link. Each workspace keeps its own main action. The top bar shows the section name; selected navigation and the page heading provide location without a full breadcrumb trail.

## Flexible step planning and relationships

The journey planner supports adding, removing and moving steps up or down, while preserving their order in the flow preview. A journey is not limited to three steps. The local prototype supports 1–50 named steps as a practical display bound; the underlying ordered journey schema has no three-step restriction. Draft planning remains in memory only and creates no server record or API request.

Keep the main menu stable for daily work and use the how-to for the setup sequence. Customer journeys describe outcomes and business steps. Services and agent workloads are peer capabilities protected in Core; standalone agents need no unrelated parent service. Shared resources are reusable dependencies that can have one or more consumers. Knowledge separately manages content, versions, approval and retrieval. Optional UACRF advisory agents remain distinct from protected customer agents.

## Objective configuration and screen continuity

Owners of services and agents propose measurable targets using approved templates. Administrators maintain scoped grants, versioned templates and mandatory organization constraints; that role does not confer independent approval authority. A distinct authorized reviewer approves or rejects a submitted revision. The local prototype demonstrates these roles but is not authentication or a running server configuration workflow.

Place Order uses Checkout objectives; Resolve Order uses Order support agent and Policy review team objectives. The team is Waiting throughout the example, and agent validation concerns read-only lookup reconciliation rather than refund execution. All proposed numerical targets remain illustrative and measured outcomes remain Unknown. Twenty-six local review forms now collect task-specific inputs and render a structured preview; none is a complete API payload or a dispatched command. Fixed example graphs retain explicit scope labels and routes back to inventory. The how-to contains one twelve-stage flow with expandable expected results and related destinations.

objective_design contains seven proposed API operations, a strict revision request schema, four proposed record families, a graphical schema and scoped read-query sketches. They are not part of the current baseline of 74 canonical APIs, 102 business tables, 18 applied migration files and 338 named SQL building blocks. The storage design is fail closed with no worker grants. Implement controlled lifecycle procedures, current scoped authorization, command idempotency, atomic audit and outbox, template constraint enforcement and evidence invalidation before promotion or use. Isolated DDL and objective trigger tests are recorded in implementation_review; application-role, concurrency and live acceptance remain pending.

Objective configuration is available through Settings → Objective templates and rules. Services and Agents each expose an Objectives alias for applying a template to a local proposal. Local catalog versions enforce target ranges and retain earlier definitions; changes disappear on reload. Live authorization, persistence and activation remain pending.

## Source imports and decision timing update

Cloud and monitoring systems report what is happening: response times, errors, deployment changes and permission changes. An approved objective says what the service or agent must achieve. UACRF can import that objective from an existing tool or let an accountable owner propose it locally.

The customer journey explains why the objective matters and which workloads support the customer outcome. It does not supply measurements or invent targets. The source of an objective definition and the evidence used to measure it are separate records.

Collection timing determines when a connector receives or requests data. Freshness determines how old that data may be for the decision at hand. Evaluation timing determines when UACRF applies its rules. These are separate settings.

For operational signals, calculate age from the source observation time. A late arrival must not make an old observation look new. Polling more frequently cannot create measurements that the source has not produced.

For a stable objective definition, use the last successful source verification instead of the definition's original creation date. An unchanged definition can still be current. Configuration also needs a verified snapshot or attributable change stream; failed checking cannot establish that old configuration is still valid.

The new proposed extension contains nine strict schemas, eight API operations and seven storage record families. It includes read-query sketches, two editable sequence diagrams and two graphical design views. These are separate from the baseline: 74 canonical APIs, 102 business tables, 18 applied migration files, 338 named SQL building blocks and 90 implementation flows.

The earlier objective extension still has seven proposed operations and four record families. Neither extension is deployed. Both storage designs loaded in isolated PostgreSQL; the objective integrity slice also passed sequential trigger tests. Import worker and full extension acceptance remain pending. Both reuse the existing authorized connection and workload identities; this update does not implement a new connector registry.

The UI rehearses a fictional objective import conflict and lets the reader prepare a provider-scoped timing plan. It does not fetch external definitions, run collectors or timers, or change active objectives.

## Historical implementation review snapshot

This earlier review snapshot describes the implementation state at that revision. The current requirements and capability baseline is the [alignment-revision-22 Technical Approach](../../Technical_Approach/UACRF_Technical_Approach_Design_v1_0_alignment_revision_22.docx); the partial runtime status is in the [runtime guide](../runtime/README.md) and [gap-closure report](../runtime/GAP_CLOSURE.md). Objective design and local prototype coverage do not establish production handlers or acceptance.

The earlier flow notes describe 90 baseline flows, 180 diagrams and revision-specific implementation gates. Treat those counts and architecture/database labels as historical; diagrams do not establish executed HTTP handlers or acceptance.

For current implementation and test status, read the [runtime gap-closure report](../runtime/GAP_CLOSURE.md), [verification snapshot guide](../verification/README.md) and [integration test plan](../verification/INTEGRATION_TEST_PLAN.md).

## API contracts and relationship linking update

All 74 canonical operations now have machine-readable definitions. The earlier 45 missing wire contracts are specified; handler, serializer, privilege and live acceptance remain pending. Four proposed relationship-maintenance routes and a proposed command-status read are separate from the baseline, bringing proposed operations to twenty. Journey and resource detail now provide selection, review, local save/change/retirement/history. Dependency screens accept local proposals. Reloading discards drafts; no access, persistence, accepted graph change or recovery is asserted. Baseline migration/query/table counts and readiness scores do not change.

Use the [runtime guide](../runtime/README.md) for implemented API behavior, the [proposed graph API contract](../graph_design/openapi.proposed.json) for graph extensions, and the [graph design](../graph_design/README.md) with its [diagrams](../graph_design/DIAGRAMS.md) for relationship semantics. Proposed handlers and live acceptance remain open.

## Identity and ownership prerequisite

Before cloud adapters or catalogue writes, follow the identity and authorization boundaries in the [MS01 architecture](../../Microservices_Architecture/MS01/README.md) and [runtime guide](../runtime/README.md). Establish trusted identity and controlled first-administrator bootstrap, admit users, configure bounded access and eligible human owners, then register journeys, services, agents and resources. Owning a workload does not grant cloud permissions. Real handlers and acceptance remain implementation work.
