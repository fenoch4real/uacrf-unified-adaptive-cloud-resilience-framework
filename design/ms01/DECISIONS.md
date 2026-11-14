# Architecture decision records

Version 1.0 | Architecture revision MS 01

## ADR 01 Business boundaries and staged deployment

Adopt fourteen domain services plus an experience API and API edge. Keep workers inside their owning domain, with separate deployment/privilege when needed. Per-endpoint or per-table services would fragment transactions and create needless network calls; a shared-database collection of applications would preserve the current coupling. The chosen boundaries separate authoritative facts and privileged effects while retaining a coherent domain transaction.

## ADR 02 Exclusive logical databases

Each domain owns a separate logical PostgreSQL database and credentials. Multiple databases may share physical infrastructure initially. Cross-domain references use current owner receipts and immutable IDs/digests; they are not cross-database foreign keys. This increases the need for reconciliation and explicit failure handling, which the workflow and acceptance design address.

## ADR 03 PostgreSQL authority and Neo4j projection

S06 alone writes Neo4j. Accepted graph material remains authoritative in its PostgreSQL database. Decision use requires the exact accepted and verified projected revision. This preserves traceability and rebuildability while preventing a graph query result from becoming independent authority or recovery certification.

## ADR 04 Durable events with outbox and inbox

Use Kafka, a local transactional outbox publisher and idempotent consumer inboxes. Accept duplicate delivery and per-aggregate ordering rather than claim exactly-once database/provider effects. Polling outbox is the initial implementation; change capture can replace publication mechanics after operational qualification without changing domain contracts.

## ADR 05 Owner-local workflow state machines

Use explicit PostgreSQL-backed state machines and leased workers within S03, S07, S09, S11 and S12 as needed. Do not add a separate general workflow platform to the first extraction. Reconsider a dedicated engine only if measured workflow/versioning/operations complexity justifies it, with a separate migration decision and compatibility proof.

## ADR 06 Online current authority and execution fencing

Use S01 for current identity and S10 for operational permits/control fences. Authoritative owner checks and conditional provider writes protect bounded action admission. No distributed transaction or event bus erases the race between an external provider accepting an effect and a later revocation. The architecture documents and tests that boundary rather than promising an impossible retroactive cancellation.

## ADR 07 Preserve the Java and browser investment

Start from Java 25, Spring Boot 4.1.2 and the existing Thymeleaf/Bootstrap interface. UX01 becomes a real backend for frontend with typed remote clients. Separate source extraction from framework/UI rewrites so behavior can be compared and migrated. Pin actual dependency versions and image digests in the implementation rather than use floating latest tags.

## ADR 08 Advisory agents and protected agents are distinct

S09 assistance remains advisory at A0/A1/A2. S02/S11/S12 protect customer agent workload versions, execution and recovery. Separate identities do not prove model or failure independence. Typed shared dependencies and declared scenario validation remain necessary for defensible resilience claims.

## ADR 09 One writer during migration

Use shadow reads, a freeze/drain boundary, consistent snapshot plus retained change replay, ownership epoch and row/digest/event reconciliation. Avoid independent dual writes. A rollback after new accepted writes requires forward correction or a reconciled reverse migration; routing alone cannot restore lost facts.

## ADR 10 Commercial isolation

S13 owns money and usage admission. Operational reservations/model reports do not create charges, finance roles do not confer action authority, and subscription entitlements do not elevate an agent tier. Exact decimal strings, frozen rules and immutable adjustments preserve billing traceability.

## ADR 11 Command routing without admission races

New public commands use a controlled owner-prefixed opaque ID that fits the existing string/path contracts. EDGE01 routes from its allowlist; owner authorization remains mandatory. Legacy UUIDs use a pre-populated migration directory. A domain outbox does not need a second database write to make a newly issued status location routable.

## ADR 12 Version and evidence discipline

Keep document Version 1.0 with architecture revision MS 01. Retain original source hashes, signed bytes and historical releases. Machine coverage checks demonstrate allocation, not working microservices or acceptance. Production readiness and scoring require separately executed evidence.
