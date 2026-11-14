> Historical graph-fragment reference. This folder records its original alignment-revision-5 scope. For current design decisions, read the [alignment-revision-22 Technical Approach](../UACRF_Technical_Approach_Design_v1_0_alignment_revision_22.docx), [MS01 microservices architecture](../../Microservices_Architecture/MS01/README.md) and [current UI prototype](../../UI/ui_workloads/index.html).

# Neo4j integration boundary

Release 1.0, alignment revision 5. The Cypher files are transaction and extraction fragments. They are not certified historical query, authorization, or recovery-path engines.

## Write transaction

Validate qualified evidence, canonical scope, identity and payload digest before entry. Serialize same-aggregate writers; order locks globally for multi-aggregate batches. The fragments write and remove a temporary property before reading the current version. Reject older versions and conflicting equal versions; duplicates do not rewrite facts. Execute all batch mutations, projection receipts and checkpoints atomically in the managed graph transaction. A single statement does not implement the distributed outbox/reconciliation protocol.

Advance GraphRevision metadata only after a complete reconciled batch. Seal and durably retain the canonical action-relevant snapshot, query parameters, evidence and digest before making the revision eligible for a decision. An unresolved snapshot-store outcome keeps the decision ineligible. PostgreSQL commits the source/outbox together; the graph receipt and event offsets support replay and reconciliation.

## Read boundary

Capture a reconciled current revision within a boundary that protects or detects changes during node/edge/domain extraction. Check revisions and digests before/after; if they change, retry under a bounded policy or return Unknown. Two reads without a protective/detectable revision protocol are insufficient. Historical decisions consume retained immutable snapshots. Effective UTC intervals and opaque revision identifiers are separate concepts.

The Python reference consumes authenticated sealed snapshots. `bounded_impact` combines reverse dependencies and forward consequences; `expand_upstream` expands both recovery paths; `certify` compares full domain sets and hidden convergence. Truncation, omitted mandatory domain types, stale facts or unproven recovery prerequisites yield Unknown/Incomplete. The revised fragments require no APOC dependency.

Reference fixture keys `id`, `source`, `target`, `type`, `qualification` and epoch-second times map explicitly to canonical_id, endpoint IDs, semantic relationship type, qualification_state and UTC times. They are local function inputs, not a competing API schema.

## Verification status

Finite-model semantics and package structure have local tests. Engine syntax, concurrent database transactions, edition-specific constraints, snapshot loading, outbox recovery, load and security behavior require execution on the selected Neo4j edition/version and remain NOT_EXECUTED. Docker's client was present but no daemon was running during this work.

The service key is now tenant_id + environment_id + service_id. Existing named constraints require a reviewed migration: IF NOT EXISTS does not establish that an existing constraint has the desired definition.

Official syntax and locking references consulted:

- [Concurrent access](https://neo4j.com/docs/operations-manual/current/database-internals/concurrent-data-access/).
- [FOREACH](https://neo4j.com/docs/cypher-manual/current/clauses/foreach/).
- [SET](https://neo4j.com/docs/cypher-manual/current/clauses/set/).

Pin an engine version and edition before integration acceptance. These references inform the fragment design; they do not replace engine execution tests.

## Current implementation boundary

These Cypher files are historical design fragments, not the current runtime implementation or a complete database deployment package. The current service boundaries, persistence responsibilities and deployment target are described by the revision-22 Technical Approach and MS01 architecture. Their contents remain subject to implementation and acceptance testing.
