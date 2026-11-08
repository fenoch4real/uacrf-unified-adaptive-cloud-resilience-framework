# UACRF implementation roadmap

The architecture is organized into milestones so that implementation can
proceed through useful, testable workflows rather than a collection of
unconnected service stubs. The milestones below are planned work; none should
be treated as complete until its stated evidence exists.

## M0 — Establish the implementation baseline

Preserve the current application and its migration evidence. Reconcile the
effective API contracts and ownership, then establish the build, dependency
policy, local environment, CI checks, threat model, and tenant and event
compatibility policies. Select a bounded recovery action and a local test
adapter.

## M1 — Deliver the first independent service workflow

Implement the API edge, experience API, identity service, and estate catalog
as independently deployable services with separate logical databases and
credentials. Complete a real identity, workload registration, and customer
journey workflow through the browser experience.

## M2 — Establish evidence and topology

Implement connection and ingestion, evidence qualification, objective
management, topology projection, and the first reporting capabilities. Verify
source provenance, graph content, event ordering, and stale-context handling.

## M3 — Add assessment, knowledge, and advisory assistance

Implement assessment, knowledge, and advisory-agent workflows. Retain
evidence, source disclosure, uncertainty, dissent, and review history. Advisory
output remains separate from operational authorization.

## M4 — Implement durable policy and authorization

Implement policy lifecycle, review obligations, approval decisions,
authorization, control fences, and read-only decision replay. Test
authorization against current identity and policy state, including revocation
races.

## M5 — Implement execution and recovery validation

Start with a bounded local adapter whose effects can be independently
observed. Implement execution workflows, status reconciliation, cancellation,
compensation, probes, and recovery certification. Qualify real provider
actions individually before claiming support for them.

## M6 — Complete commercial and operational administration

Implement usage admission, rating, invoicing, payment reconciliation,
notifications, and evidence export. Test duplicate and reordered events,
exact amounts, and interrupted reconciliation.

## M7 — Introduce governed prediction and provider breadth

Implement model and learning workflows with temporal evaluation, uncertainty,
calibration, and shadow testing. Qualify AWS, Azure, and Google Cloud
capabilities individually. Prediction remains advisory until its promotion
criteria pass.

## M8 — Complete migration and release acceptance

Migrate remaining domains under single-writer cutovers. Run the inherited
scenarios and distributed acceptance matrix, complete browser and accessibility
reviews, and retain deployment, restore, provider, and billing evidence for
the release scope.

## Progress and completion

Track each milestone against the detailed [MS01 implementation plan](../Microservices_Architecture/MS01/IMPLEMENTATION_PLAN.md)
and [acceptance and risk register](../Microservices_Architecture/MS01/ACCEPTANCE_AND_RISKS.md).
Design coverage, a passing build, or a working local adapter is not by itself
evidence that a complete workflow or production integration has been accepted.
