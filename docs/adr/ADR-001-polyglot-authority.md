# ADR-001: Polyglot Authority & Transactional Outbox

- **Status**: Accepted (Milestone 0)
- **Context**: UACRF requires strict ACID transactions for tenant isolation, approvals, single-use nonces, and audit trails alongside multi-hop dependency and failure-domain traversals.
- **Decision**:
  1. **PostgreSQL** owns all transactional configuration, customer journeys, impact profiles, objectives, rules, policy decisions, single-use nonces, and evidence metadata.
  2. **Neo4j** owns resolved topology (58 node labels, 55 controlled relationship types), failure domains, and bounded witness-path traversals.
  3. **S06 (`topology-service`)** is the sole writer to Neo4j via an idempotent, lease-fenced transactional outbox consumer. Two-phase distributed commits and application-level dual-writes are prohibited.
  4. **MinIO / S3 Object Lock** stores raw provider payloads and signed `ResilienceDecisionEnvelope` bundles.
- **Consequences**: Historical replay always reads sealed snapshots/digests rather than mutable live graph state; any Neo4j drift or projection lag fails consequential decisions closed to `UNKNOWN`/`DENY`.
