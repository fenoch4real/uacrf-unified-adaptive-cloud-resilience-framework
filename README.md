# UACRF — Unified Adaptive Cloud Resilience Framework

> **Unified Cloud, Customer-Journey & AI-Agent Resilience Platform** implementing **Assess**, **Control**, **Recover**, **Evidence**, and **Learn** across heterogeneous cloud infrastructure (`GCP`, `AWS`, `Azure`, `Kubernetes`) and protected AI workloads.

---

## Why UACRF Exists (The Problem Statement)

Modern enterprise digital services span multiple cloud providers, distributed microservices, external third-party APIs, and autonomous AI/ML agents. During failures or rapid change, engineering and risk teams face five structural gaps:

1. **Fragmented, Non-Journey Visibility:** Infrastructure dashboards track isolated metrics (CPU, pods, HTTP 5xx) without proving which **customer journeys** are affected or whether alternate recovery paths actually avoid shared hidden failure domains (shared IdPs, KMS keys, DNS, regions, or third-party vendors).
2. **Advice Mistaken for Authority:** Incident responders, automated runbooks, and LLM copilots frequently treat unverified recommendations or retained deployment packages as proof of readiness or safety—triggering blind rollbacks that worsen blast radius.
3. **Provider Success != Customer Recovery:** A Kubernetes rollback or cloud failover often returns `200 OK` from the provider control plane while authentication, feature freshness, downstream dependencies, or end-to-end customer checkout transactions remain broken.
4. **Unregulated AI & Agent Dependencies:** Enterprises deploy predictive models and agent endpoints inside customer journeys alongside internal AI assistants, often with zero separation between customer-facing AI quality/safety evidence and internal advisory reasoning.
5. **Auditability & Regulatory Friction:** Regulated institutions struggle to produce tamper-evident, reproducible proof for **SOC 2 Type II, ISO 27001, ISO 22301, EU DORA, UK PRA/FCA, PCI DSS v4.0, HIPAA, FedRAMP, ISO 42001, and the EU AI Act** demonstrating that recovery decisions followed policy, separation of duties, and empirically verified validation.

---

## How UACRF Addresses It

UACRF enforces one non-negotiable architectural boundary: **Advice is Not Authority.**

- **Polyglot System of Record:** **PostgreSQL 17** owns authoritative transactional catalog, identity, separation-of-duties approvals, policy decisions, single-use execution nonces, and outbox state; **Neo4j 5.26** (`S06` sole writer) maintains the resolved multi-layer dependency topology across **`58` canonical node labels and `55` controlled relationship types**; immutable evidence payloads and signed decision manifests lock to **WORM storage**.
- **Deterministic Evidence & No-Synthetic-Fallback (`S04`):** Operational telemetry and dependency snapshots follow strict freshness TTLs and graph-revision bindings. Missing transaction probes stay explicitly **`UNKNOWN`**—runbooks, documentation, or LLM drafts can never fabricate operational readiness (`422 SYNTHETIC_FALLBACK_FORBIDDEN`).
- **Path Independence & Sealed Proof (`S07`, `P1`, `P5`):** Evaluates failover independence across the correlation-domain taxonomy (`REGION`, `ZONE`, `CLUSTER`, `IDP`, `KMS_KEY`, `DNS`, `DATA_STORE`, `VENDOR`) and seals assessments into **RS256 JWS `DecisionEnvelope`s** with canonical RFC 8785 SHA-256 digests.
- **Bounded, Policy-Gated Recovery & Cross-Layer Validation (`S05`, `S10`, `S11`, `P2`, `P3`, `P6`):** Continuous rule packs drive risk states; **OPA Rego** and two-person independent approvals govern proposals; pre-execution **`MaterialGraphDeltaClassifier`** blocks execution if topology widened; single-use, audience-bound tokens execute through allowlisted `ProviderActionAdapter`s; and an **8-layer Validation DAG** verifies infrastructure through synthetic customer-journey probes—triggering automatic compensation on mandatory failure.
- **Clear AI Workload vs. Advisory Agent Separation (`S02`, `S04`, `S09`, `P7`):** Protected customer AI dependencies require 6 separate empirical checks (availability, latency, quality, subgroup safety, feature freshness, fallback capacity). Solution-Specific Agent Advisors (`Evidence Assistant`, `Critic`) operate behind a prompt-injection firewall, token budgets, kill switches, and **blocking critic dissent preservation**.
- **Closed-Loop Learning & Quantitative Shadow Verification (`S12`, `S13`, Paper §10.5):** Incident learning items cannot close until verified rule/runbook/test artifacts are linked (`FR-LRN-002`), while the quantitative shadow model ($r(t)$, $S(t)$, $RCI$, $ER$) benchmarks resilience empirically without overriding deterministic safety gates.

---

## Target Outcomes

- **No Blind or Harmful Recoveries:** Eliminates unauthorized blast-radius expansion, stale-context actions, single-use token replays, and unverified rollback assumptions.
- **Journey-Verified Recovery Completion:** Confirms end-to-end business journeys actually work after remediation rather than trusting cloud provider acknowledgment alone.
- **Safe Adoption of AI in Operations & Customer Flows:** Enables bounded, inspectable, critic-checked AI assistance while enforcing empirical quality/safety gates on production AI dependencies.
- **Continuous Compliance-Ready Evidence:** Produces cryptographically verifiable evidence chains mapping directly to **SOC 2, ISO 27001/22301/42001, DORA, PCI DSS, HIPAA, NIST AI RMF, and FedRAMP** expectations.

---

## Published paper

The paper proposes a unified approach to cloud and AI-agent resilience,
connecting dependency-aware evidence and assessment with bounded actions,
independent recovery validation, and learning from outcomes. Its central
boundary is that advice is not authority: a recommendation does not authorize
recovery, and a provider-reported success does not prove that recovery worked.

The framework is described in:

Fabiyi, O. O., & Yeboah, M. M. (2026). [*UACRF: A Unified Adaptive Framework
for Resilient AI-Cloud Systems Supporting Critical Digital Infrastructure*](https://nahdapublications.org/nid/nid/njbei.2026.0001).
NJBEI Review, 1.0, 1–29. Published September 18, 2026. NID:
nid/njbei.2026.0001.

This repository develops the product design and implementation that build on
the paper's framework.

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
| `services/recovery-service` (`S11`) | `8090` | Recovery orchestrator, allowlisted `ProviderActionAdapter` SPI (`P3`), 8-layer `CrossLayerValidator` (`P6`), kill switch |
| `services/knowledge-service` (`S08`/`S12`/`S13`) | `8091` | Governed knowledge base, closed-loop learning gate (`FR-LRN-002`), guarded fault experiments, Section 10.5 quantitative shadow model & benchmarks |
| `services/advisory-service` (`S09`) | `8092` | Solution-specific agent advisors (`Evidence Assistant`, `Critic`), prompt-injection guard, `P7` permutation-invariant arbitration |
| `services/edge-gateway` (`EDGE01`) | `8080` | Public API gateway forwarding to backend owners, `Cache-Control: no-store`, concealing internal delegation routes |
| `services/experience-ui` (`UX01`) | `8081` | **Spring Boot + Thymeleaf + Bootstrap 5.3 + Cytoscape.js** web portal (`18` templates) |
| `contracts/` & `infra/` & `policies/` | — | OpenAPI 3.1, AsyncAPI 3.0, Neo4j constraints/catalog, traceability JSON, Keycloak realm, OPA baseline Rego, Postgres init |

---

## Quick Start

### 1. Start Core Backing Infrastructure
```bash
docker-compose up -d postgres neo4j minio keycloak opa temporal
```

### 2. Initialize Neo4j Canonical Constraints & Indexes
```bash
docker-compose exec -T neo4j cypher-shell -u neo4j -p uacrf_neo4j_dev < contracts/neo4j/01_constraints_and_indexes.cypher
```

### 3. Build & Run Full Verification Suite (PostgreSQL 17 + Neo4j 5.26 Testcontainers)
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

## Requirements Tracking, Enterprise Compliance & Documentation

- **Full Requirements & Implementation Status Tracker:** [REQUIREMENTS_TRACEABILITY_MATRIX.md](docs/traceability/REQUIREMENTS_TRACEABILITY_MATRIX.md) (`IMPLEMENTED`, `PARTIAL / LOCAL_SPI`, `PENDING / NOT_IMPLEMENTED`)
- **Updated Technical Approach & Regulatory Scope:** [TECHNICAL_APPROACH_AND_COMPLIANCE_SPEC.md](docs/architecture/TECHNICAL_APPROACH_AND_COMPLIANCE_SPEC.md)
- **Enterprise Production & Compliance Roadmap (`E1`–`E4`):** [ENTERPRISE_COMPLIANCE_AND_ROADMAP.md](docs/compliance/ENTERPRISE_COMPLIANCE_AND_ROADMAP.md)
- **End-to-End Sequence Diagrams (`SD-01`–`SD-08`):** [SEQUENCE_DIAGRAMS.md](docs/diagrams/SEQUENCE_DIAGRAMS.md)
- **End-to-End Activity Diagrams (`AD-01`–`AD-08`):** [ACTIVITY_DIAGRAMS.md](docs/diagrams/ACTIVITY_DIAGRAMS.md)
- **System Overview & Architecture Specs:** [SYSTEM_OVERVIEW.md](docs/architecture/SYSTEM_OVERVIEW.md) | [LOCAL_AND_TARGET_INFRA.md](docs/deployment/LOCAL_AND_TARGET_INFRA.md)
