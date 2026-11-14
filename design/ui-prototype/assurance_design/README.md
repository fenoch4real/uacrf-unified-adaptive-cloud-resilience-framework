# Recovery readiness assurance

Version 1.0 · Runtime revision 4 · Guide revision 19 · Flow revision 9 · November 1, 2026

UACRF now provides a persistent diagnostic workflow for reviewing whether a selected primary and recovery path have changed. An accountable human establishes a baseline for a registered customer journey, identifies the primary and recovery nodes, and describes the failure scenario. The baseline refers to an accepted graph whose complete projection has been verified against PostgreSQL. Later reviews compare another accepted revision with that original baseline. This gives the reviewer a traceable starting point for deciding which evidence or controlled tests need to be refreshed.

The workflow does not certify readiness or independence. Its conclusions are REVIEW_REQUIRED, INSUFFICIENT_EVIDENCE and NO_RELEVANT_CHANGE_OBSERVED. Every response retains independence NOT_CERTIFIED, runtime_verification NOT_ESTABLISHED and automatic_recovery false. These diagnostic states do not replace the framework's formal assessment conclusions or recovery certification states.

## Start with an accepted journey and graph

Register the customer journey and link its supporting service or agent using the catalogue workflows. Obtain and qualify the source evidence, import the graph, seal it and run the graph projection worker. Use runtime/README.md and graph_design/README.md for those prerequisites. A planning link alone is not evidence of an operational dependency.

Create the diagnostic baseline with POST /v1/recovery-assessments. Supply journey_id, graph_revision_id, primary_id, recovery_id, scenario and reason. Send a verified Bearer identity and a distinct Idempotency-Key header. The journey must currently include the graph's root workload. The primary and recovery nodes must exist and differ. The caller needs current graph read access across all represented workloads, catalogue journey read access, and catalogue journey write access for changes. The scenario is the reviewer's declared context; this runtime does not translate prose into an executable fault model.

The server stores an immutable baseline and its first review. It records the actor, graph revision, reason and diagnostic findings. The audit entry, review and notification outbox row commit together. An identical retried request with the same key returns its retained review identity. Conflicting use of a key or a stale expected revision is rejected. No payment, provider action or agent dispatch occurs.

## Review changes and their consequences

GET /v1/recovery-assessments/{id} returns the latest recorded review, bounded review history and later accepted graph revisions that have not been assessed. It checks current access to the baseline, current review and returned historical graphs. It verifies the latest graph's Neo4j content again when evidence remains eligible. An unavailable store returns an error rather than a healthy result. Expired or revoked evidence produces an insufficient-evidence view without rewriting the historical review.

Later accepted revisions in the same graph-root scope are surfaced as pending review on read. This is a local change trigger, not a live cloud event collector or unattended reassessment service. Candidate revisions are not automatically treated as chronologically newer operational truth: source observation times and watermarks still require review. Pending history is bounded to one hundred entries with an explicit truncation flag. Unreadable graph scope blocks the response rather than exposing its identifiers.

POST /v1/recovery-assessments/{id}:reassess accepts graph_revision_id, expected_revision and reason. It verifies the selected graph and compares it with the original baseline. Each new review increments the assessment revision; it does not edit an earlier result. A newly agreed baseline requires a separate assessment. Reassessment does not silently renew expired baseline evidence.

Dependency traversal follows the runtime's documented dependency types in their forward direction from both selected nodes. Each traversal stops at depth eight or one thousand visited nodes. A limit produces INSUFFICIENT_EVIDENCE. Shared reachable nodes are reported as possible convergence that the human must assess against the declared failure scenario. Absence of a shared node does not establish independent recovery.

The comparison includes nodes and dependency edges relevant to either selected path. Revision-local edge identifiers do not create false changes: relationships are grouped by source, type and target, retaining parallel-edge multiplicity and properties. Changes to unrelated nodes are excluded. Relevant property and evidence changes are conservatively flagged; the runtime does not claim a causal or scenario-specific materiality judgment. Added, removed or changed dependencies require review and an agreed retest. The findings are witnesses to a diagnostic comparison, not executed test outcomes.

## Services and agents use the same review boundary

A selected node can represent a conventional service, an agent or a supported resource. Agent profiles retain their existing internal service anchor for permission checks. Models, tools, identities, knowledge sources and infrastructure remain typed dependencies where qualified source evidence records those relationships. A model or tool change can affect the review only when it is represented in the accepted graph. Hidden provider configuration or runtime state is outside this comparison.

Safe agent continuation remains separate implementation work. Before retrying an interrupted external action, an adapter must reconcile whether it completed, verify current authority and determine whether a retry can duplicate an effect. This release neither retries agent actions nor asserts exactly-once external execution. Read-only dependency review must not be marketed as a complete agent recovery controller.

## Install and follow the implementation

Fresh installations automatically apply runtime/003_assurance_runtime.sql after the two existing runtime scripts. For an existing runtime revision 3 deployment, first back up and review the migration, then run the Java admin assurance-install command with the existing offline DBA environment. It refuses to reapply the extension. Restart using the new packaged JAR. The eighteen baseline migrations remain unchanged; two new runtime tables are outside the original business-table count.

The new tables are uacrf_app.recovery_assessment and uacrf_app.recovery_review. Both are append-only, owned by the guarded runtime procedure role, and unavailable directly to API or graph-worker logins. Scoped foreign keys bind journeys, graphs and reviews. API credentials remain server-only. The extension uses the existing environment lock, verified human identity, catalogue grants, audit table and outbox. A customer-facing account billing portal is unrelated to these permissions.

Read openapi.proposed.json for complete request and response contracts, DIAGRAMS.md for step-labelled activities and sequences, and schema.svg for storage relationships. The Java implementation is runtime/src/main/java/org/uacrf/runtime/Assurance.java. RuntimeTest.java and runtime/TEST_RESULTS.json distinguish executed local evidence from planned acceptance. Recovery readiness is integrated into the main Assess workspace at ../ui_workloads/index.html#UI-03. The old index.html address forwards there. Forms, scoped summaries and local recent activities share the same journey state. This local prototype is not connected to the Java API.

## Commercial meaning and remaining acceptance

The intended recurring offer maintains a scoped recovery-readiness assessment as systems change. Pricing tiers, customer demand and revenue estimates remain hypotheses. The billing persistence assets do not activate a subscription, collect payment or enforce this offer. No new patent claim or uniqueness conclusion follows from this implementation.

Live cloud collection, qualification adapters, automatic candidate ordering, unattended reassessment, objective and policy change feeds, independent review and sign-off, controlled retest execution and evidence, scenario-specific certification, notifications delivery and connected product UI remain acceptance work. The existing thirty-nine full-system scenarios remain unexecuted. Local integration tests are reported separately. Version 1.0 and the previous documentation assessment score remain unchanged.


## Unified recovery assurance interface

The current 58-view application integrates recovery assurance into a local prototype. Assess contains the baseline and reassessment forms; journeys, service and agent dependencies, evidence, recovery and overview link to the same in-memory journey record. Scoped recent activities link back to that assessment. The old companion URL forwards to Assess. This is not an authenticated connection to the Java API. Runtime revision 4, thirty routes and the existing 32-test backend evidence are unchanged. Live collection, unattended reassessment, connected UI and recovery certification remain open. No score increase. Open [Assess](../ui_workloads/index.html#UI-03) and choose Place Order or Resolve Order. Establish a baseline, inspect changed context, record a local reassessment, then follow the evidence and recovery links. Changes disappear on reload. A draft journey cannot establish an assessment. Read [the UI integration map](../assurance_design/UI_INTEGRATION.md) for screen and API responsibilities.


## Setup and recurring recovery review

Guide revision 21 and UI refinement 14 correct the recovery-assurance how-to. The embedded graphical path, expanded stage descriptions, standalone Start here page, Markdown instructions and SVG/PNG maps now cover baseline creation at step 9 and change review/reassessment at step 12, with a repeating evidence and retest loop. This is a local prototype walkthrough, not live setup acceptance. Java runtime revision 4 and its existing 32-test evidence are unchanged; the prototype still sends no API requests. Version 1.0 and the readiness assessment remain unchanged. Read [the complete how-to](../ui_workloads/SETUP_HOW_TO_v1_0.md) and [Start here](../ui_workloads/START_HERE.html). The canonical stage and loop records are SETUP_WALKTHROUGH.json and SETUP_OVERVIEW.json in ui_workloads. The source-alignment test is verification/test_setup_assurance.cjs.
