# Unified recovery assurance interface

Version 1.0 · UI refinement 13 · Guide revision 20

Recovery readiness now lives in Assess, using the application shell and journey selector. Assess → Recovery readiness is the permanent navigation alias. No separate application screen or additional top-level menu is introduced. The existing forty-nine views remain the inventory.

A user opens a registered example journey, follows Establish baseline into Assess, declares a failure scenario and reason, and saves a local baseline. Place Order uses Checkout; Resolve Order uses Order support agent. The examples have separate baseline and review histories. The journey's current example owner is shown; this display never substitutes for live authorization.

The baseline and last review appear in Overview, Control, Recover, Evidence, Customer journeys and Journey detail. Service and agent dependency screens link to the same assessment, as do recovery independence and changed-context reviews. The local assurance path display distinguishes the primary and fallback identity dependencies. Existing retained graph illustrations remain fixed snapshots and are labelled separately; the forms do not rewrite them or accept graph evidence.

Within Assess, select a shared-identity change, an unrelated rename or expired evidence. Inspecting it updates the current local view. A review reason is required before appending a reassessment. Historical reviews remain unchanged. Expired evidence blocks a new review, while the current state becomes INSUFFICIENT_EVIDENCE. Neither a clean comparison nor a shared-dependency finding certifies independence. All screens keep that boundary visible.

Local activity entries are scoped to the journey and service or agent. Their links return to Assess with the matching journey selected. They use the existing activity controls, timestamps and pagination. Discarding a local example does not erase server history; reload clears all rehearsal state. A planning draft may include proposed per-step service and agent associations, but it has no registered graph or journey identifiers and cannot establish an assessment.

## Backend connection remains separate

The three Java endpoints remain POST /v1/recovery-assessments, GET /v1/recovery-assessments/{id}, and POST /v1/recovery-assessments/{id}:reassess. The prototype shows a request preview with illustrative IDs; it sends no request and stores no token. A connected application must obtain verified identity, resolve authorized server journey and graph identifiers, use a unique idempotency key, handle expected-revision conflicts and replace local example state with server responses. Implement reconciliation of pending and unknown outcomes before enabling connected writes.

The UI uses the same three diagnostic state names as the backend. The shared local state is a demonstration rather than another source of evidence. Billing entitlements do not confer permissions, and no recovery dispatch, notification, evidence qualification or cloud collection is activated.

## Verification

verification/test_unified_assurance.cjs executes the inline handlers with finite element fixtures. It covers all existing views plus cross-screen baseline state, separate journey histories, unrelated and shared changes, expired evidence, activity scope and discard behavior. These are source-interaction checks. Browser layout, keyboard and screen-reader acceptance, live identities, authenticated API integration and cloud data remain unexecuted.


## Setup and recurring recovery review

Guide revision 21 and UI refinement 14 correct the recovery-assurance how-to. The embedded graphical path, expanded stage descriptions, standalone Start here page, Markdown instructions and SVG/PNG maps now cover baseline creation at step 9 and change review/reassessment at step 12, with a repeating evidence and retest loop. This is a local prototype walkthrough, not live setup acceptance. Java runtime revision 4 and its existing 32-test evidence are unchanged; the prototype still sends no API requests. Version 1.0 and the readiness assessment remain unchanged. Read [the complete how-to](../ui_workloads/SETUP_HOW_TO_v1_0.md) and [Start here](../ui_workloads/START_HERE.html). The canonical stage and loop records are SETUP_WALKTHROUGH.json and SETUP_OVERVIEW.json in ui_workloads. The source-alignment test is verification/test_setup_assurance.cjs.
