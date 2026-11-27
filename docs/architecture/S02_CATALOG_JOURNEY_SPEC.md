# S02 Catalog, Protected Agent Workloads & Customer Journeys Specification (Milestone 1)

## Responsibilities
- Maintains separate inventories for **Conventional Services** (`GET /v1/services` filters `service_kind = 'CONVENTIONAL'`) and **Protected Agent Workloads** (`GET /v1/agent-workloads`).
- Standalone agents create an internal `AGENT_ANCHOR` service row for governance ownership without inflating conventional service counts.
- Supports immutable `agent_workload_version` manifests with SHA-256 digests (`CanonicalJson`) and independent activation (`activator != author`).
- Owns `customer_journey`, ordered `journey_step`s, `journey_capability` links (`SERVICE` or `AGENT` with `REQUIRED`/`OPTIONAL`/`UNKNOWN` participation criticality), and versioned `journey_impact_profile` revisions (`DRAFT -> SUBMITTED -> APPROVED/REJECTED`, enforcing `reviewer != author`).
- Owns `shared_resource` and `capability_asset` usage links (`MODEL`, `KNOWLEDGE`, `TOOL`, `MEMORY`, `IDENTITY`, `RUNTIME`).
