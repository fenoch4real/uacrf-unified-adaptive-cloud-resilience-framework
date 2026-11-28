# UACRF — Unified Adaptive Cloud Resilience Framework

## What UACRF Is

**UACRF** is an enterprise operational resilience platform for multi-cloud infrastructure (`GCP`, `AWS`, `Azure`, `OCI`, `Kubernetes`), critical customer journeys, and governed AI/agent workloads. It connects topology-aware dependency analysis, deterministic evidence qualification, declarative policy enforcement, bounded recovery orchestration, cross-layer post-action validation, closed-loop incident learning, and tamper-evident compliance reporting into a single microservice platform (`S01`–`S14`, `EDGE01`, and the `UX01` web portal) backed by PostgreSQL, Neo4j, WORM object storage, Open Policy Agent (OPA), and Temporal.

---

## Why UACRF Exists

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

## Published Paper

The foundational research behind UACRF introduces a unified engineering and governance model for cloud and AI-agent resilience. Authored by **Olufemi O. Fabiyi** and **Michael M. Yeboah**, the paper connects dependency-aware evidence qualification and failure-domain assessment with bounded recovery actions, independent post-action validation, and verified learning from incidents. Its guiding architectural boundary is simple and strict: **advice is not authority** — neither an AI/human recommendation nor a cloud provider's `200 OK` control-plane acknowledgment proves that recovery actually restored customer service.

**Citation:**
Fabiyi, O. O., & Yeboah, M. M. (2026). [*UACRF: A Unified Adaptive Framework
for Resilient AI-Cloud Systems Supporting Critical Digital Infrastructure*](https://nahdapublications.org/nid/nid/njbei.2026.0001).
*NJBEI Review*, 1.0, 1–29. Published September 18, 2026. NID: `nid/njbei.2026.0001`.

This repository translates that peer-reviewed framework into working microservices, formal compliance controls, multi-cloud SPIs, and an interactive resilience portal.

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
  --add-host=host.docker.internal:host-gateway \
  -v "$PWD":/workspace \
  -v "$HOME/.m2":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace \
  maven:3.9-eclipse-temurin-25 mvn -B verify
```

### 4. Multi-Cloud Sandbox Onboarding & Platform Reference
- Multi-cloud setup instructions for GCP, AWS, Azure, OCI, and OpenTelemetry: [Local and target infrastructure guide](docs/deployment/LOCAL_AND_TARGET_INFRA.md)
- Module map, product catalog SKUs, 131-requirement traceability rollup, and architecture index: [Platform reference](docs/Platform-reference.md)
