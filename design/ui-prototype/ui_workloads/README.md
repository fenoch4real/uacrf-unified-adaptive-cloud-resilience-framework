# UACRF application prototype

Version 1.0 · UI design revision 8 · Alignment revision 22 · Guide revision 14

Open [the prototype](index.html), [the shared visual language](VISUAL_LANGUAGE.html), [current screen illustrations](SCREEN_GALLERY.html) and [visual system guide](VISUAL_SYSTEM_GUIDE_v1_0.md). All 58 views use consistent navigation and heading icons, primary actions, state cues and scoped recent activity.

The feed contains fixed illustrative examples, not live telemetry. Current API and database contracts remain unchanged. The display projection schema and source map define the boundary for a future authorized feed handler. Browser, keyboard, accessibility, responsive and live integration acceptance remain pending. Earlier complete release archives are preserved.

The current navigation baseline has 13 primary destinations and 58 registered views. Files under `verification/` are historical evidence and can contain earlier view counts; they do not define the current registry.

Start with [the onboarding and setup path](START_HERE.html) or [the written how-to](SETUP_HOW_TO_v1_0.md). Connection review now separates provider selection, access and collection. Customer journeys includes an editable, ephemeral planner for ordered business steps and service/agent associations. The flow includes an explicit composition-confirmation gate before a local registration-request preview; it does not persist or send a request. Recent activity uses a connected chronological timeline while preserving event details, filters, evidence status and review links. All 58 views explain their next review destination. Twenty current screen illustrations include connection, connection health and customer-flow references.

## Flexible step planning and relationships

The journey planner supports adding, removing and moving steps up or down, while preserving step-to-capability associations. Each step can be supported by one or more services, agents, or both. The journey preview shows the selected composition and links to the corresponding service or agent inventory. An explicit confirmation is required before preparing a local registration-request preview, and every step must have at least one selected service or agent. The preview is an illustrative local composition shape, not the canonical API payload, and is never sent or persisted. A journey is not limited to three steps. The local prototype supports 1–50 named steps as a practical display bound; the underlying ordered journey schema has no three-step restriction. Draft planning remains in memory only and creates no server record or API request.

Keep the main menu stable for daily work and use the how-to for the setup sequence. Customer journeys describe outcomes and business steps. Services and agent workloads are peer capabilities protected in Core; standalone agents need no unrelated parent service. Shared resources are reusable dependencies that can have one or more consumers. Knowledge separately manages content, versions, approval and retrieval. Optional UACRF advisory agents remain distinct from protected customer agents.

## Objective configuration and screen continuity

Owners of services and agents propose measurable targets using approved templates. Administrators maintain scoped grants, versioned templates and mandatory organization constraints; that role does not confer independent approval authority. A distinct authorized reviewer approves or rejects a submitted revision. The local prototype demonstrates these roles but is not authentication or a running server configuration workflow.

Place Order uses Checkout objectives; Resolve Order uses Order support agent and Policy review team objectives. The team is Waiting throughout the example, and agent validation concerns read-only lookup reconciliation rather than refund execution. All proposed numerical targets remain illustrative and measured outcomes remain Unknown. Twenty-six local review forms now collect task-specific inputs and render a structured preview; none is a complete API payload or a dispatched command. Fixed example graphs retain explicit scope labels and routes back to inventory. The how-to contains one twelve-stage flow with expandable expected results and related destinations.

objective_design contains seven proposed API operations, a strict revision request schema, four proposed record families, a graphical schema and scoped read-query sketches. They are not part of the current baseline of 74 canonical APIs, 102 business tables, 18 applied migration files and 338 named SQL building blocks. The storage design is fail closed with no worker grants. Implement controlled lifecycle procedures, current scoped authorization, command idempotency, atomic audit and outbox, template constraint enforcement and evidence invalidation before promotion or use. Isolated DDL and objective trigger tests are recorded in implementation_review; application-role, concurrency and live acceptance remain pending.

The Dependencies and Agent dependency graph workspaces now render service-only, agent-only and combined topology views. The fictional Northstar Payments sample includes AWS resources and shared service/agent dependencies. It is an API-shaped UI fixture stored in ../graph_design/sample-topologies.js; it does not seed a service, simulate a provider response, qualify evidence or represent an accepted graph. The view links to the S06 graph contract and integration guide. A production UI must load an authorized, explicit accepted revision from S06 rather than use this fixture.

Recovery independence and changed-context reviews each have a Service/Agent example selector. It updates the fixed graph, scenario checks and supporting review route; hypothetical changes do not modify inventory or establish live recovery. See ui_workloads/RECOVERY_REVIEW_GUIDE_v1_0.md in the release for details.

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


## Customer administration and menu alignment

Alignment revision 22 retains the 49 existing views and adds nine customer-administration design views, UI-50 through UI-58. The 13 primary destinations remain directly navigable; customer settings are separate from UACRF advisory assistance and the restricted platform-operator plane. The setup guide is guidance, not a route lock. These new views are static design previews without connected handlers.
