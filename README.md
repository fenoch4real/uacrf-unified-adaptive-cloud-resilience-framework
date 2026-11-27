# UACRF — Unified Adaptive Cloud Resilience Framework

> **Enterprise Spring Boot + Neo4j + Thymeleaf implementation of the Unified Adaptive Cloud Resilience Framework (UACRF)** — spanning **Assess**, **Control**, **Recover**, and **Evidence**, aligned with the UACRF Academic Paper (*Fabiyi & Yeboah, NJBEI 2026*), Product & Technical Specification (`v1.0 rev 22`), and Complete Neo4j Graph Schema Reference (`58` canonical node labels, `55` controlled relationship types).

---

## Key Architectural Principles

1. **Polyglot Authoritative Storage (Zero Split-Brain)**
   - **PostgreSQL 17 (`foundation`, `catalog`, `discovery`, `evidence`, `graph`, `assessment`, `integration` schemas):** Authoritative transactional system of record, multi-tenant row scoping, separation-of-duties grant decisions, qualification state, nonces, audit trails, and transactional outbox.
   - **Neo4j 5.26 (`S06` Sole Writer):** Canonical dependency graph enforcing all **58 node labels** and **55 controlled relationship types**, immutable SHA-256 `GraphRevision` manifests, bounded Cypher BFS traversals, and AI workload lineage.
   - **MinIO / S3 Object Lock (WORM):** Content-addressed immutable evidence payloads and sealed RS256 JWS `ResilienceDecisionEnvelope` bundles.
2. **Deterministic Safety & No-Synthetic-Fallback**
   - Missing operational probes or unverified rollback compatibility stay strictly `MISSING` / `UNKNOWN`. Approved runbooks, RAG citations, or LLM drafts **cannot** qualify operational or AI readiness (`422 SYNTHETIC_FALLBACK_FORBIDDEN`).
   - Dependency graph edges preserve explicit state (`CONFIRMED`, `UNCERTAIN`, `EXCLUDED`) alongside the mandatory invariant: *"Dependency shows reliance. It does not prove the cause of this incident."*
3. **Clear Agent Separation (`Appendix X` vs `Appendix W / P7`)**
   - **Protected Customer Agent Workloads:** First-class workloads protected by UACRF (`ModelEndpoint`, `ModelVersion`, `FeatureStore`, `ModelFallback`) requiring separate 6-aspect empirical verification before traffic changes.
   - **UACRF Solution-Specific Agent Advisors:** Internal assistance roles (`Evidence Assistant`, `Critic Dissent`) strictly bounded by sealed cases, read-only revision bindings, and human sign-off (never mutating Neo4j or bypassing OPA/human approval).
4. **Synthetic Telemetry Isolation (`SAMPLE_LOCAL` vs `LIVE_ADAPTER`)**
   - Plug-and-play `TelemetryAdapter` SPI ships with `SampleLocalScenarioGenerator` (`telemetrySource = SAMPLE_LOCAL`, `provenanceClass = SAMPLE_ONLY`) so every workflow runs locally end-to-end without live production cloud credentials, while cleanly preventing sample data from masquerading as production telemetry.

---

## Repository & Module Map

| Module / Directory | Port | Ownership / Role |
|---|---|---|
| `libs/uacrf-contracts` | — | Shared enums, `CanonicalEvidenceEvent`, `BoundedRecoveryAuthorization`, 58-label/55-relationship `Neo4jOntology` validator |
| `libs/uacrf-crypto` | — | Deterministic RFC 8785 JSON canonicalization, SHA-256 digests, RS256 JWS `EnvelopeSigner` |
| `libs/uacrf-outbox` | — | Transactional outbox records, lease fencing policy, DDL template |
| `services/identity-service` (`S01`) | `8082` | Tenant/environment isolation, principals, independent two-person grants, 30s RS256 delegation tokens |
| `services/catalog-service` (`S02`) | `8083` | Conventional services, Protected Agent Workloads, shared resources, customer journeys & impact profiles |
| `services/discovery-service` (`S03`) | `8084` | Multi-cloud/OTel `TelemetryAdapter` SPI & `SAMPLE_LOCAL` fixture generator |
| `services/evidence-service` (`S04`) | `8085` | Deterministic `QualificationEngine`, WORM evidence blobs, AI dependency checks, draft/final export bundles |
| `services/graph-service` (`S06`) | `8086` | Sole Neo4j 5.26 writer, ontology enforcement, bounded traversals, owner edge confirmation, revision digests |
| `services/assessment-service` (`S07`) | `8087` | Correlation-domain path certification (`P5`), journey resilience assessments, independent review, sealed `DecisionEnvelope`s (`P1`) |
| `services/objectives-service` (`S05`) | `8088` | SLI/SLO/RTO/RPO objectives, Appendix I Core rule engine, continuous `RiskState` evaluations |
| `services/policy-service` (`S10`) | `8089` | OPA Rego evaluator, two-person recovery approvals, `P2` graph delta classifier, `P3` RS256 single-use authorizations |
| `services/recovery-service` (`S11`) | `8090` | Temporal recovery orchestrator, allowlisted `ProviderActionAdapter` SPI (`P3`), 8-layer `CrossLayerValidator` (`P6`), kill switch |
| `services/knowledge-service` (`S08`/`S12`/`S13`) | `8091` | Governed knowledge base, closed-loop learning gate (`FR-LRN-002`), guarded fault experiments, Section 10.5 quantitative shadow model & benchmarks |
| `services/advisory-service` (`S09`) | `8092` | Solution-specific agent advisors (`Evidence Assistant`, `Critic`), prompt-injection guard, `P7` permutation-invariant arbitration |
| `services/edge-gateway` (`EDGE01`) | `8080` | Public API gateway forwarding to backend owners, `Cache-Control: no-store`, concealing internal delegation routes |
| `services/experience-ui` (`UX01`) | `8081` | **Spring Boot + Thymeleaf + Bootstrap 5.3 + Cytoscape.js** web UI |
| `contracts/` | — | OpenAPI 3.1 specs, AsyncAPI 3.0 event schemas, Neo4j Cypher constraints & `ontology_catalog.json` |
| `docs/` | — | Architecture specs, ADRs, deployment topology, and Mermaid sequence/activity/architecture diagrams |
| `infra/` & `policies/` | — | Postgres 12-DB init, Keycloak realm, OTel Collector pipeline, OPA baseline Rego policy |

---

## Quick Start

### Prerequisites
- **Docker `29.x+` & Docker Compose** (JDK 25 / Maven optional locally — builds and full Testcontainers suites run inside `maven:3.9-eclipse-temurin-25` via Docker).

### 1. Start Core Backing Infrastructure
```bash
docker-compose up -d postgres neo4j minio keycloak opa temporal
```

### 2. Initialize Neo4j Canonical Constraints & Indexes
```bash
docker-compose exec -T neo4j cypher-shell -u neo4j -p uacrf_neo4j_pass < contracts/neo4j/01_constraints_and_indexes.cypher
```

### 3. Build & Run Full Verification Suite (PostgreSQL 17 + Neo4j 5.26 Testcontainers)
With local JDK 25 + Maven:
```bash
mvn -B verify
```
Or using Dockerized Maven (zero local Java installation needed):
```bash
docker run --rm \
  -v "$PWD":/workspace \
  -v "$HOME/.m2":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace \
  maven:3.9-eclipse-temurin-25 mvn -B verify
```

---

## Iterative Roadmap Status

| Milestone | Scope | Status |
|---|---|---|
| **M0** | Monorepo, shared crypto/contracts/outbox libs, Neo4j 58/55 schema, infra & ADRs | Complete |
| **M1** | `S01` Identity, `S02` Catalog/Journeys, `EDGE01` Gateway, `UX01` Bootstrap 5 portal | Complete |
| **M2** | `S03` Discovery & `SAMPLE_LOCAL`, `S04` WORM Evidence Engine, `S06` Neo4j Projector & Cytoscape UI | Complete |
| **M3** | `S07` Assess Engine, Correlation-Domain Certification (`P5`), Sealed `DecisionEnvelope` (`P1`), Report UI | Complete |
| **M4** | `S05` Continuous Rule Engine & `S10` OPA Policy / Bounded Recovery Authority (`P2`, `P3`) | Complete |
| **M5** | `S11` Temporal Recovery Orchestrator, Allowlisted Adapters & Cross-Layer Validation (`P6`) | Complete |
| **M6** | `S12` Learning Closures, `S13` Fault Experiments, Live Cloud Collectors & `S14` Assurance Rooms | Complete |
| **M7** | Paper Section 10.5 Quantitative Shadow Model ($r(t)$, $S(t)$, $RCI$, $ER$) & Ablation Benchmarks | Complete |
| **M8** | `S08` Governed Knowledge, `S09` Advisory Agents & Non-Majority Arbitration (`P7`), Adaptive ML | Complete |

---

## Documentation & Design Diagrams

- System Overview & Storage Authority: [SYSTEM_OVERVIEW.md](docs/architecture/SYSTEM_OVERVIEW.md)
- Infrastructure & Deployment Topology: [LOCAL_AND_TARGET_INFRA.md](docs/deployment/LOCAL_AND_TARGET_INFRA.md)
- Identity & Catalog Flows (Mermaid): [M1_IDENTITY_AND_JOURNEY_FLOWS.md](docs/flows/M1_IDENTITY_AND_JOURNEY_FLOWS.md)
- Telemetry, Qualification & Neo4j Flows (Mermaid): [M2_EVIDENCE_AND_GRAPH_FLOWS.md](docs/flows/M2_EVIDENCE_AND_GRAPH_FLOWS.md)
