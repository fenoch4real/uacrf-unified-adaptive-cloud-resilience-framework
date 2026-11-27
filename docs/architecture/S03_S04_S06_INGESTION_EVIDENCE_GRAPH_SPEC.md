# S03, S04, and S06 Technical Design Specification (Milestone 2)

## 1. Component Responsibilities & Authority Boundaries

| Service | Module | Port | Owned Stores | Exclusive Write Authority |
|---|---|---|---|---|
| **S03** Discovery & Telemetry Adapter | `services/discovery-service` | `8084` | `uacrf_discovery` (`discovery`, `integration`) | Connector health, normalized telemetry observations, discovered topology edges |
| **S04** Evidence Qualification & Storage | `services/evidence-service` | `8085` | `uacrf_evidence` (`evidence`, `integration`) + Object Lock store | Qualification verdicts, WORM object blobs, package completeness, AI aspect checks |
| **S06** Knowledge Graph Projection & Query | `services/graph-service` | `8086` | `uacrf_graph` (`graph`, `integration`) + **Neo4j 5.26** | **Sole Neo4j writer**, 58-label / 55-relationship ontology enforcement, revision digests |

## 2. Core Invariants Enforced

1. **Adapter SPI + Explicit Synthetic Isolation (`S03`)**:
   - External cloud/OTel adapters implement `TelemetryAdapter`.
   - Local simulation fixtures are isolated in `SampleLocalScenarioGenerator` and stamped with `telemetrySource = SAMPLE_LOCAL` and `provenanceClass = SAMPLE_ONLY`.
2. **No-Synthetic-Fallback (`S04`)**:
   - `QualificationEngine` rejects `KNOWLEDGE_DOC`, `ADVISORY_DRAFT`, `LLM_SUMMARY`, and `RUNBOOK` source kinds with HTTP `422 SYNTHETIC_FALLBACK_FORBIDDEN` when submitted as empirical probe or compatibility evidence.
   - Missing operational probes keep the package at `MISSING_REQUIRED` and recovery readiness at `UNKNOWN`.
3. **Sole Graph Writer & Non-Causation Discipline (`S06`)**:
   - Every node label and relationship type passes `Neo4jOntology.requireNodeLabel` (58 labels) and `Neo4jOntology.requireRelationshipType` (55 types) plus `validatePropertyKeys` before Cypher `MERGE` or Postgres persistence.
   - `UNCERTAIN` edges remain visible when `includeUncertain=true`, rendered dashed in Cytoscape.js with explicit notice: *"Dependency shows reliance. It does not prove the cause of this incident."*
4. **AI Workload vs Core Advisory Separation**:
   - Customer AI dependencies (`ModelEndpoint`, `ModelVersion`, `FeatureStore`, `ModelFallback`) require 6 distinct evidence aspects (`endpoint_availability`, `latency_p95`, `model_quality`, `subgroup_safety`, `feature_freshness`, `fallback_capacity`).
