# UACRF visual language and recent activity

Version 1.0 · UI design revision 8 · Alignment revision 22 · Guide revision 7 · November 7, 2026

The application uses one visual language across all 58 workspaces. Icons help a reader identify a destination, resource or next step; visible labels carry the meaning. The navy navigation, light workspaces and provider marks remain. A calmer hierarchy separates the page purpose, current scope, primary next step, review content and recent activity.

## Five meanings stay separate

Provider branding describes placement. A neutral icon describes a service, agent, model, store, identity or other resource. Runtime state describes operation. Evidence qualification describes whether observations support a conclusion. Authorization and billing have their own states. A provider logo or a successful request is never a substitute for independent evidence or permission.

The shared vector set has 35 icons with a 24-pixel view box and a consistent stroke. Navigation shows an icon beside each of its 13 main destinations. Each of the 58 view headings has a registered icon, primary action, activity scope and visual key. Resource and provider names remain visible. The provider fallback is Placement unknown; an unspecified provider is not guessed from the model vendor.

States use a shape, label and restrained color. Available and Qualified can use a check only in their own dimension. Degraded uses a warning triangle. Unknown uses a question mark. Paused uses pause bars; Requested and Pending approval use a clock. Blocked uses a lock and Failed uses a cross. Recorded means that a record was retained. It does not mean that the customer outcome succeeded. Status text is never replaced by color alone.

## The next step stays clear

Each workspace has one registered primary next step. Navigation opens the relevant review. A preparation action opens a local form with a required review note, a 2,000-character input bound, an explanation of its purpose and a cancellation control. Validation and local completion are announced beside the form. The preparation is ephemeral: it does not call a provider, create a server command or add a durable activity event.

Unavailable connection provisioning, access editing, catalogue maintenance and recovery dispatch remain visibly unavailable where their contracts or live handlers are incomplete. The UI explains why. A clock or spinner must never suggest that a cloud operation is running when no request was sent. The current prototype does not simulate successful server execution.

## Recent activity explains what happened

Every workspace includes the same activity component with a relevant scope. Overview combines the visible examples. Services shows service activity; billing shows usage-related activity; governed Knowledge shows content activity. Agent execution views use their protected capability scope, while UACRF advisory activity stays separate. The journey selector and activity disposition/search filters narrow the examples together. Clearing activity filters preserves the selected journey.

An entry contains an event glyph, plain-language description, affected object link, actor name and actor kind, recorded timestamp, scope, disposition, separate evidence state and a useful review link. Entries are newest-first and connected by a subtle vertical line so the chronology reads as a timeline without hiding the metadata or controls. Human, agent and system actors have different icons and visible type labels. Provider marks appear only when the example supplies a placement. Examples use October 31 times with the explicit America/Los_Angeles offset; these are fixed examples, not a current live clock.

A pause request is not a confirmed remote stop. A record of checkpoint creation is not verified checkpoint bytes. A usage reservation is not an invoice or a rated charge. Missing critic output cannot be hidden behind another agent's response. These distinctions are retained in the example event wording and dispositions.

The feed says when it is illustrative and incomplete. An empty result explains that no examples match the selected filters; it does not assert that no real event occurred. The prototype has no background refresh. A future feed must handle loading, stale data, failed requests and partial pages truthfully, retaining visible context while reporting a failure. The current empty state and local validation can be exercised; live loading/failure states are implementation requirements rather than executed network results.

## Existing data and the live integration boundary

The existing records provide useful source fields. audit_event retains actor, action, target, digest, tenant/environment scope, created_at and payload. principal retains actor kind and display name. workload_event retains execution, sequence, source key, kind, digest and object reference. Evaluations, checkpoints, catalogue records and billing records supply their own domain facts. A server can derive the display object, safe label and navigation target after current authorization checks.

Those fields support a display projection without a new activity table or a duplicated event ledger. The presentation schema defines that projection. It is not a new canonical HTTP endpoint. The canonical inventory remains 74 operations, with 102 business tables, 18 migrations and 338 SQL building blocks unchanged. Activity fields absent from a source remain unknown or unavailable; the renderer must not invent an event outcome from a generic INSERT or a 2xx receipt.

The existing generic audit list is a bounded source query, not a complete authorized cross-domain feed. A live projection handler still needs source-specific access checks, classification and field redaction before filtering, sorting and pagination. It must bind cursors to tenant, environment, current principal disclosure scope, journey, resource and filters, and recheck grants after they change. It must not disclose hidden event counts or targets. A recorded timestamp is not an occurrence timestamp; the schema permits occurred_at to be null and the prototype labels its time Recorded.

Read ACTIVITY_DATA_MAP.json and activity-view.schema.json for exact bindings and limits. These contracts define implementation work rather than claiming it is already running. No public activity endpoint, new permission grant or database mutation is introduced by this visual revision.

## Accessibility and verification

Meaning-bearing icons keep a visible label. Decorative repeated SVGs are hidden from assistive technology. Native buttons, links, form labels, visible focus, status announcements, disabled-action explanations, wrapping controls and reduced-motion rules are part of the shared components. Interactive controls use a 44-pixel minimum height where practical. A full WCAG conformance claim still requires actual browser, keyboard, assistive-technology and responsive testing.

The design follows the W3C guidance on information beyond color, understandable status messages and control target sizes. These sources guide implementation; they do not certify this prototype:

https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html
https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html
https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html

Source fixtures exercise all 58 views, navigation, actor/state distinctions, activity scope and filters, empty results and local preparation. Static illustrations and paired guides are reviewed separately. Browser behavior, live API delivery, cloud and runtime integration and complete acceptance remain pending. Working documents stay Version 1.0; the earlier readiness score is unchanged.

## Guidance placement

The next-review explanation appears inside How to set up UACRF as a secondary link. Each workspace keeps its own main action. The top bar shows the section name; selected navigation and the page heading provide location without a full breadcrumb trail.

## Flexible step planning and relationships

The journey planner supports adding, removing and moving steps up or down, while preserving their order and service/agent associations. Each step may use services, agents or both; every step must have an association before the user can confirm the composition. Confirmation is followed by an illustrative local registration-request preview, not a canonical API payload. A journey is not limited to three steps. The local prototype supports 1–50 named steps as a practical display bound; the underlying ordered journey schema has no three-step restriction. Draft planning remains in memory only and creates no server record or API request.

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


## Alignment revision 22 navigation

The customer-administration destination is visually distinct from UACRF advisory assistance. Its nine child views (UI-50–58) use the same page heading, scope, state labels, primary-navigation behavior, and light workspace surface. Every screen remains directly reachable; the setup sequence does not lock unrelated tasks. The new views are previews.

## Alignment revision 22 navigation

The customer-administration destination is visually distinct from UACRF advisory assistance. Its nine child views (UI-50–58) use the same page heading, scope, state labels, primary-navigation behavior, and light workspace surface. Every screen remains directly reachable; the setup sequence does not lock unrelated tasks. The new views are previews.