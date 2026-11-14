# How to set up UACRF and maintain a recovery assessment


Version 1.0 · UI refinement 14 · Guide revision 21 · November 1, 2026


The twelve-stage path now includes recovery baseline creation and a repeating change-review loop. It applies to services and agents. In the prototype, all baseline and reassessment actions are local examples that disappear on reload. The Java APIs are implemented separately; these pages do not call them or establish live acceptance.


Open [Start here](START_HERE.html), [the graphical map](SETUP_OVERVIEW.svg), or [Assess recovery readiness](index.html#UI-03).


## 1 Agree scope and owners

Admit verified identities, review bounded owner eligibility and access, then name accountable people and scope.

Accountable ownership and an access plan. The local rehearsal does not verify live identity, grant access or prove deployment acceptance.

[Open this stage](index.html#UI-30) · [Related UI-46](index.html#UI-46)

## 2 Prepare a read-only connection

Choose the provider and inspect the discovery/access boundary.

An adapter and permission review; no live connection is created.

[Open this stage](index.html#UI-15) · [Related UI-30](index.html#UI-30)

## 3 Check collection coverage

Review permission limits, collection freshness and missing sources.

A retained collection/permission report before claiming coverage.

[Open this stage](index.html#UI-17) · [Related UI-15](index.html#UI-15)

## 4 Register protected capabilities

Register services or standalone agents with clear owners and scope.

Canonical service or agent profiles; use either branch or both.

[Open this stage](index.html#UI-16) · [Related UI-35](index.html#UI-35) · [Related UI-02](index.html#UI-02) · [Related UI-34](index.html#UI-34)

## 5 Define the customer journey

State the customer outcome and the steps that must succeed.

An ordered journey and accountable owner; draft planning stays local.

[Open this stage](index.html#UI-46) · [Related UI-47](index.html#UI-47)

## 6 Link shared resources

Identify models, knowledge, tools, stores and runtime identities for each consumer.

Typed consumer links and separately reviewed content/access.

[Open this stage](index.html#UI-48) · [Related UI-49](index.html#UI-49) · [Related UI-07](index.html#UI-07) · [Related UI-09](index.html#UI-09)

## 7 Review dependencies and objectives

Inspect service or agent reliance and define measurable task objectives.

A scoped dependency view, unresolved gaps and measurement definitions.

[Open this stage](index.html#UI-18) · [Related UI-37](index.html#UI-37) · [Related UI-22](index.html#UI-22) · [Related UI-23](index.html#UI-23)

## 8 Establish evidence and policies

Review source provenance, expiry, access and policies. A live baseline requires a qualified accepted graph and completed verified projection.

Qualified evidence and an accepted projected graph for the Java workflow. UI examples do not import, qualify or accept evidence.

[Open this stage](index.html#UI-21) · [Related UI-32](index.html#UI-32) · [Related UI-30](index.html#UI-30) · [Related UI-06](index.html#UI-06)

## 9 Establish recovery baseline

In Assess choose Place Order or Resolve Order, identify the example primary and fallback path, and enter the declared failure scenario and review reason.

A local baseline and first diagnostic review linked to the selected journey. The Java API instead requires authorized server records and a qualified projected graph; neither result certifies recovery.

[Open this stage](index.html#UI-03) · [Related UI-47](index.html#UI-47) · [Related UI-18](index.html#UI-18) · [Related UI-37](index.html#UI-37) · [Related UI-06](index.html#UI-06)

## 10 Plan a controlled recovery test

Define a nonproduction scenario, expected effects, approval and stop conditions.

An approved test plan before a connected runtime can act.

[Open this stage](index.html#UI-27) · [Related UI-25](index.html#UI-25) · [Related UI-26](index.html#UI-26) · [Related UI-05](index.html#UI-05)

## 11 Validate and retain the result

Review actual customer, application, data, security and agent outcomes after an independently authorized controlled test.

Retained actual test outcomes and independent review. Neither a local baseline nor a recorded reassessment supplies executed test evidence.

[Open this stage](index.html#UI-28) · [Related UI-42](index.html#UI-42) · [Related UI-06](index.html#UI-06) · [Related UI-29](index.html#UI-29)

## 12 Review changes and reassess

Inspect pending changed context in Assess. Review shared dependencies, unrelated changes or expired evidence, record a reasoned reassessment when eligible, and agree any required retest.

Append-only review history and a current diagnostic view. Expired evidence blocks a new local review; refresh and qualify evidence before a real reassessment. Operating ownership, escalation and billing review remain handover tasks.

[Open this stage](index.html#UI-44) · [Related UI-03](index.html#UI-03) · [Related UI-20](index.html#UI-20) · [Related UI-06](index.html#UI-06) · [Related UI-44](index.html#UI-44) · [Related UI-33](index.html#UI-33) · [Related UI-29](index.html#UI-29) · [Related UI-01](index.html#UI-01)

## Repeat the recovery review loop

### Inspect changed context

Identify the changed dependency or evidence and open its journey assessment.

[Open inspect changed context](index.html#UI-20)

### Refresh missing evidence

Expired or revoked evidence prevents reliance. Source qualification is a separate trusted process.

[Open refresh missing evidence](index.html#UI-06)

### Record reassessment

Select the journey, inspect the example change and enter a review reason. Retain earlier reviews.

[Open record reassessment](index.html#UI-03)

### Agree controlled retest

Review findings and obtain the independent authority needed for a controlled test.

[Open agree controlled retest](index.html#UI-27)

REVIEW_REQUIRED, INSUFFICIENT_EVIDENCE and NO_RELEVANT_CHANGE_OBSERVED are diagnostic states. Independence remains NOT_CERTIFIED; no automatic recovery is authorized.

## Follow the local example

In Assess, select Place Order, enter an identity-provider outage scenario and a baseline reason, then establish the local baseline. Inspect the shared-identity example and enter a reassessment reason. The new review requires human attention; it is not a passed recovery test. Visit Journey detail, Evidence or Recover to inspect the same status and follow recent activity back to Assess. Resolve Order demonstrates the agent branch with a separate history. An unregistered planning draft cannot establish a baseline.

Select expired evidence to see INSUFFICIENT_EVIDENCE and a blocked local reassessment. Refreshing and qualifying real evidence is not implemented by changing an example selector. Reload resets local history.

## Use the Java implementation separately

The implemented endpoints are POST /v1/recovery-assessments, GET /v1/recovery-assessments/{id}, and POST /v1/recovery-assessments/{id}:reassess. Creation needs journey_id, graph_revision_id, primary_id, recovery_id, scenario and reason. Reassessment needs graph_revision_id, expected_revision and reason. Writes require a verified identity and an Idempotency-Key. The Java server rechecks permissions, evidence eligibility and projection content. Read [the API contracts](../assurance_design/openapi.proposed.json) and [the implementation guide](../assurance_design/README.md).

The prototype sends no API request. Live collection, unattended reassessment, authenticated UI connection, controlled retests and recovery certification remain unaccepted. The Java source and its previously executed 32-test evidence are unchanged by this guide correction.
