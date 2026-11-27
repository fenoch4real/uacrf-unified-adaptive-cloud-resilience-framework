# ADR-001: Polyglot Authority & Transactional Outbox

- **Status:** Accepted
- **Summary Description:** Establishes strict storage ownership between ACID relational transactions (PostgreSQL), structural dependency topology (Neo4j), immutable audit artifacts (WORM Object Lock), and asynchronous Kafka messaging so no two data stores can drift out of consistency.

## Context
UACRF requires strong transactional guarantees for tenant isolation, separation-of-duties approvals, single-use execution nonces, and immutable audit logs, alongside fast multi-hop dependency and shared-failure-domain graph traversals across cloud and AI layers.

## Decision
1. **PostgreSQL (`S01`–`S14`):** Owns all system-of-record configuration, customer journeys, impact profiles, objectives, rules, policy approvals, nonces, and transactional outbox queues with kernel-level `FORCE ROW LEVEL SECURITY`.
2. **Neo4j (`58` Node Labels, `55` Relationship Types):** Owns resolved multi-layer dependency topology, failure domains, and bounded witness-path traversals.
3. **Sole Graph Writer (`S06`):** `graph-service` is the exclusive writer to Neo4j via idempotent `MERGE` projections and lease-fenced Kafka outbox consumption (`OutboxKafkaRelay`); application-level dual-writes and distributed two-phase commits are prohibited.
4. **WORM Object Storage:** Immutable Object Lock buckets persist raw qualifying evidence payloads, sealed `DecisionEnvelope` bundles, and external auditor artifacts.

## Consequences
Historical decision replay always validates against sealed snapshots and canonical digests rather than mutable live graph state. Any divergence detected by `GraphDriftReconciler` (`DRIFTED`) fails consequential actions closed to `UNKNOWN` or `DENY`.
