# UACRF — Unified Adaptive Cloud Resilience Framework

**UACRF** is an enterprise operational resilience platform for multi-cloud infrastructure (`GCP`, `AWS`, `Azure`, `OCI`, `Kubernetes`), critical customer journeys, and governed AI/agent workloads. It unifies **Assess**, **Control**, **Recover**, **Evidence**, and **Learn** around one strict architectural principle—**advice is not authority**—so every resilience decision and recovery action is backed by qualified telemetry, dependency-graph path independence, policy approvals, cross-layer post-action validation, and tamper-evident compliance evidence.

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

The foundational research behind UACRF introduces a unified engineering and governance model for cloud and AI-agent resilience. Authored by **Olufemi O. Fabiyi** and **Michael M. Yeboah**, the paper connects dependency-aware evidence qualification and failure-domain assessment with bounded recovery actions, independent post-action validation, and verified learning from incidents. Its guiding architectural boundary is simple and strict: **advice is not authority** — neither an AI/human recommendation nor a cloud provider's `200 OK` control-plane acknowledgment proves that recovery actually restored customer service.

**Citation:**
Fabiyi, O. O., & Yeboah, M. M. (2026). [*UACRF: A Unified Adaptive Framework
for Resilient AI-Cloud Systems Supporting Critical Digital Infrastructure*](https://nahdapublications.org/nid/nid/njbei.2026.0001).
*NJBEI Review*, 1.0, 1–29. Published September 18, 2026. NID: `nid/njbei.2026.0001`.

This repository translates that peer-reviewed framework into working microservices, formal compliance controls, multi-cloud SPIs, and an interactive resilience portal.

---

## Repository & Module Map

| Module / Directory | Port | Ownership / Role |
|---|---|---|
| `libs/uacrf-contracts` | — | Shared enums, `CanonicalEvidenceEvent`, `BoundedRecoveryAuthorization`, 58-label/55-relationship `Neo4jOntology` validator |
| `libs/uacrf-crypto` | — | Deterministic RFC 8785 JSON canonicalization, SHA-256 digests, RS256 JWS `EnvelopeSigner`, FIPS 140-3 / KMS profile |
| `libs/uacrf-outbox` | — | Transactional outbox records, lease fencing, Kafka `OutboxKafkaRelay`, PostgreSQL `TenantRlsSession` |
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
| `services/advisory-service` (`S09`) | `8092` | Solution-specific agent advisors (`Evidence Assistant`, `Critic`), OpenAI/Bedrock/Azure/Vertex/OCI LLM SPI, agent runtime ledgers (`FR-AWL`/`FR-ORC`), `P7` arbitration |
| `services/reporting-service` (`S14`) | `8093` | Commercial asset metering, FinOps quotas, pilot locking, multi-framework crosswalk, watermarked External Assurance Rooms |
| `services/edge-gateway` (`EDGE01`) | `8080` | Public API gateway forwarding to backend owners, OAuth2 Bearer JWT + CSP headers, `Cache-Control: no-store`, concealing internal delegation routes |
| `services/experience-ui` (`UX01`) | `8081` | **Spring Boot + Thymeleaf + Bootstrap 5.3 + Cytoscape.js** web portal (`23` page templates incl. `/compute`, `/models`, `/orchestration`, `/assurance`, `/settings/admin-suite`) |
| `contracts/`, `infra/`, `policies/` | — | OpenAPI 3.1, AsyncAPI 3.0, Neo4j constraints/catalog, Helm + K8s/Istio mTLS, multi-cloud Terraform, PITR & SOC 2 scripts, OPA baseline Rego |

---

## Full Technical Approach Requirements & Product Catalog SKUs (`Rev 23`)

Everything in `UACRF_Technical_Approach_v1.0_rev23` (**`7`** Commercial Package SKUs, **`7`** Metering Units, **`4`** Deployment Editions, **`103`** Functional Requirements, **`28`** Non-Functional Requirements, **`14`** Failure Scenarios `F01–F14`, **`7`** Core Patents `P1–P7`, and **`13`** Regulatory Mandates) is mapped **1-to-1 row-by-row** in [REQUIREMENTS_TRACEABILITY_MATRIX.md](docs/traceability/REQUIREMENTS_TRACEABILITY_MATRIX.md) and machine-readable [requirements_traceability.json](contracts/traceability/requirements_traceability.json).

### Product & Commercial Catalog SKUs (`Section 25` & `Section 32`)

| Catalog SKU | Commercial Offer | Code Status | What Is Implemented | What Is Left |
|---|---|---|---|---|
| **SKU-ASSESS** | *UACRF Assess (Critical Journey Assessment)* | `IMPLEMENTED` | S02, S03, S04, S06, S07, /journeys, /assess, /assess/{id}/report verified via Maven... | Live cloud collector credentials (AWS Config / GCP Asset / Azure Resource Graph / OCI... |
| **SKU-CONTROL** | *UACRF Control (Foundation Subscription)* | `IMPLEMENTED` | S05, S10, /control, Section 8 multi-layer rules, ObjectiveImportService conflict preview,... | Live OTel collector streaming soak run against customer production traffic |
| **SKU-RECOVER** | *UACRF Recover (Add-On)* | `IMPLEMENTED` | S10, S11, BreakGlassService, MaterialGraphDeltaClassifier, KubernetesLocalRollbackAdapter,... | Live cloud IAM execution roles and live AWS FIS / Azure Chaos / GCP Fault Injection... |
| **SKU-EVIDENCE** | *UACRF Evidence (Add-On)* | `IMPLEMENTED` | S04, S14, ObjectLockWormClient, ComplianceAssuranceService, /assurance,... | Live WORM S3/GCS/Blob/OCI Vault credentials and external CPA walkthrough in Phase E4 |
| **SKU-ADAPTIVE** | *UACRF Adaptive Intelligence (Add-On)* | `IMPLEMENTED` | S08/S13 QuantitativeShadowEngine, MlPromotionService, S09 EnterpriseLlmGateway... | Customer LLM API keys (enabled=true) and offline/shadow calibration datasets per... |
| **SKU-ENTERPRISE** | *UACRF Enterprise Platform Suite* | `IMPLEMENTED` | EDGE01 JWT/CSP, V2__enable_rls.sql across all 12 DBs, FipsCryptoProfile,... | Customer Kubernetes cluster deployment, hardware HSM/KMS key binding & enterprise... |
| **SKU-SERVICES** | *Services & Managed Operations* | `PARTIAL / CONFIG_GATED` | Pilot locking, onboarding lead time & time-to-first-outcome metrics tracked in S14... | Organizational delivery team workflows, CRM/ERP invoicing integration & commercial... |

### Metering SKUs (`32.1`) & Deployment Editions (`32.2`)

| SKU / Edition ID | Specification Definition | Code Status | What Is Left |
|---|---|---|---|
| **MTR-SVC** | Protected service (PROTECTED_SERVICE_ASSET) | `IMPLEMENTED` | Automated nightly sync job exporting S02 active service counts to external billing |
| **MTR-CON** | Provider connection (AWS, GCP, AZURE, OCI, OTEL) | `IMPLEMENTED` | Live cloud billing-tag reconciliation across customer accounts |
| **MTR-ASS** | Assessment unit | `IMPLEMENTED` | Automated contract-amendment lock UI when assessment scope expands |
| **MTR-WFL** | Certified recovery workflow execution | `IMPLEMENTED` | Live production billing invoice webhook trigger |
| **MTR-RET** | Evidence WORM retention volume / tier | `IMPLEMENTED` | Scheduled S3 byte-size storage cron scanner |
| **MTR-ARM** | External Assurance Room access grant | `IMPLEMENTED` | External auditor email / magic-link notification SMTP delivery |
| **MTR-INT** | Advanced / Adaptive intelligence & token quota | `IMPLEMENTED` | Real-time provider LLM invoice reconciliation feed |
| **ED-SAAS-MT** | Multi-tenant SaaS with PostgreSQL RLS isolation | `IMPLEMENTED` | Production cluster deployment using infra/helm/uacrf |
| **ED-SAAS-DED** | Dedicated SaaS / isolated tenant stack | `IMPLEMENTED` | Customer-dedicated DNS & ingress certificate provisioning |
| **ED-CUST-CLOUD** | Customer cloud deployment (GCP, AWS, Azure, OCI) | `IMPLEMENTED` | Running terraform apply and helm upgrade in target customer account |
| **ED-REG-BOUNDARY** | Separated regulated boundary (FIPS 140-3 / FedRAMP / air-gapped) | `PARTIAL / CONFIG_GATED` | Phase E4 3PAO assessment, full SSP OSCAL package & hardware HSM FIPS validation |

### Complete Requirements Rollup (`103` FRs + `28` NFRs = `131` Total Requirements)

> Exact codebase accounting: **`96/103` FRs `IMPLEMENTED`** (`7` `PARTIAL / CONFIG_GATED`), **`22/28` NFRs `IMPLEMENTED`** (`5` `PARTIAL / CONFIG_GATED`, `1` `NOT_IMPLEMENTED / EXTERNAL_ORG`: `NFR-OPS-002`). See all **`131` individual rows** in [REQUIREMENTS_TRACEABILITY_MATRIX.md](docs/traceability/REQUIREMENTS_TRACEABILITY_MATRIX.md).

| Spec IDs (Count) | Area / Section | Status | What Is Left |
|---|---|---|---|
| **FR-CAT-001 .. FR-CAT-003** (`3`) | Catalog | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-GPH-001 .. FR-GPH-010** (`10`) | Dependency graph | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-ING-001 .. FR-ING-004** (`4`) | Telemetry | `IMPLEMENTED (3) / PARTIAL (1)` | Multi-partition Kafka streaming window-join tuning under certified production load |
| **FR-RUL-001 .. FR-RUL-003** (`3`) | Rules | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-RSK-001 .. FR-RSK-002** (`2`) | Risk state | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-POL-001 .. FR-POL-002** (`2`) | Policy | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-APR-001 .. FR-APR-002** (`2`) | Approval | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-WFL-001 .. FR-WFL-002** (`2`) | Workflow | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-VAL-001 .. FR-VAL-002** (`2`) | Validation | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-EVD-001 .. FR-EVD-002** (`2`) | Evidence | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-LRN-001 .. FR-LRN-002** (`2`) | Learning | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-ONB-001 .. FR-ONB-002** (`2`) | Onboarding | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-PRD-001 .. FR-PRD-010** (`10`) | Product scope | `IMPLEMENTED (6) / PARTIAL (4)` | External CRM (Salesforce/HubSpot) opportunity & disqualifier workflow sync; Full multi-stage onboarding RACI gate sign-off UI workflow & CRM sync; Automated QVR scheduling, renewal reminders & customer offboarding workflow; External ERP labor timesheet, partner cost & contracted margin P&L ledger |
| **FR-TST-001 .. FR-TST-002** (`2`) | Testing | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-UI-001 .. FR-UI-005** (`5`) | Portal and API | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-QNT-001 .. FR-QNT-002** (`2`) | Quantitative | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-ADP-001 .. FR-ADP-002** (`2`) | Adaptive | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-AGT-001 .. FR-AGT-010** (`10`) | Agent case context | `IMPLEMENTED (9) / PARTIAL (1)` | Automated CI benchmark harness executing curated adversarial/factuality golden LLM datasets |
| **FR-IP-001 .. FR-IP-007** (`7`) | Decision reconstruction | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-KNO-001 .. FR-KNO-006** (`6`) | Knowledge | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-ORC-001 .. FR-ORC-006** (`6`) | Agent Runtime | `IMPLEMENTED (5) / PARTIAL (1)` | Automated regression suite re-running saved agent scenarios on prompt/model bump in CI |
| **FR-AWL-001 .. FR-AWL-012** (`12`) | Protected Agents | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-JNY-001 .. FR-JNY-004** (`4`) | Journeys | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **FR-DEP-001** (`1`) | Deployment | `IMPLEMENTED` | Code complete; live cloud credentials / target wiring only at deploy time |
| **NFR-AVL-001 .. NFR-OPS-002** (`28`) | Non-Functional (`Section 23.4`) | `IMPLEMENTED (22) / PARTIAL (5) / ORG (1)` | Cluster 99.9% SLA telemetry (`NFR-AVL-001`), live PITR restore drill (`NFR-RPO-001`), k6 load report (`NFR-PRF-001..002`), assistive AT audit (`NFR-ACC-001`), & named org owners/SLAs (`NFR-OPS-002`) |
| **F01 .. F14** (`14`) | Failure Scenarios (`Appendix F`) | `IMPLEMENTED (14/14 rules & adapters)` | Live chaos drills against customer AWS FIS / Azure Chaos / GCP Fault Injection accounts |
| **REG-SOC2 .. REG-SSDF** (`13`) | Regulatory Mandates (`Appendix AC`) | `SSDF IMPLEMENTED (1) / TECH CONTROLS READY (12)` | `Phase E4` corporate ISMS/IRP policies, BAAs, & external CPA / 3PAO / registrar certification |

### Cloud Catalog & Multi-Cloud Resource Inventory (`58` Node Labels · `20` Resource Families)

| Inventory Layer | Coverage Scope | Code Status | What Is Implemented | What Is Left |
|---|---|---|---|---|
| **Canonical Neo4j Inventory Ontology** (`Section 5.2.7` / `Appendix M`) | `58` Node Labels (`10` domain groups) + `55` Relationships | `IMPLEMENTED` | Enforced in `Neo4jOntology`, `ontology_catalog.json`, `GraphServiceIntegrationTest` | Live continuous cloud topology refresh crons |
| **PostgreSQL Catalog & Compute Inventory** (`S02`) | `service`, `agent_workload_profile`, `customer_journey`, `compute_placement`, `provider_resource_alias` | `IMPLEMENTED` | Flyway `V1`–`V3` + `ComputeAndAliasService` (`GCP`/`AWS`/`AZURE`/`OCI`/`K8S`) + `CatalogServiceIntegrationTest` | External CMDB / HR directory auto-sync |
| **Multi-Cloud Resource Families & Asset Pollers** (`S03`, `TBL134`) | All **`20`** infrastructure, storage, DB, queue, network, IAM & AI resource families across `AWS`, `Azure`, `GCP`, `OCI` | `GCP LIVE VERIFIED / OTHERS STANDBY` | `CloudInventoryPollerGateway`, `GcpCredentialProvider` & `GcpRuntimeTelemetryPoller` (**verified live against `uacrf-sandbox-123` across Asset Inventory, Monitoring v3 & Cloud Logging v2**), `POST /v1/inventory/poll`, `POST /v1/telemetry/poll/gcp`, UX01 `/settings/connections` UI, `CloudInventoryPollerTests` | Live `AWS` SigV4 / `OCI` HTTP signing / `Azure` Entra token exchange + CloudWatch/Azure Monitor metric pollers |
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

### 4. Multi-Cloud Sandbox Connection & Onboarding Setup (`GCP`, `AWS`, `Azure`, `OCI`, `OTel`)
Follow the step-by-step **Console GUI, CLI/Terraform, and read-only IAM onboarding instructions for all 4 cloud providers + OpenTelemetry** in [**LOCAL_AND_TARGET_INFRA.md · Section 4**](docs/deployment/LOCAL_AND_TARGET_INFRA.md#4-multi-cloud-sandbox-connection--onboarding-guide-gcp-aws-azure-oci-otel).

---

## Requirements Tracking, Enterprise Compliance & Documentation

- **Full Requirements & Implementation Status Tracker:** [REQUIREMENTS_TRACEABILITY_MATRIX.md](docs/traceability/REQUIREMENTS_TRACEABILITY_MATRIX.md)
- **Updated Technical Approach & Regulatory Scope (`Rev 23`):** [TECHNICAL_APPROACH_AND_COMPLIANCE_SPEC.md](docs/architecture/TECHNICAL_APPROACH_AND_COMPLIANCE_SPEC.md)
- **Enterprise Compliance & Certification Progression (`E1`–`E4`):** [ENTERPRISE_COMPLIANCE_AND_ROADMAP.md](docs/compliance/ENTERPRISE_COMPLIANCE_AND_ROADMAP.md)
- **Architectural Decision Records (`ADR-001`–`ADR-005`):** [ADR-001](docs/adr/ADR-001-polyglot-authority.md) · [ADR-002](docs/adr/ADR-002-advice-not-authority.md) · [ADR-003](docs/adr/ADR-003-multicloud-spi-isolation.md) · [ADR-004](docs/adr/ADR-004-zero-trust-edge-and-rls.md) · [ADR-005](docs/adr/ADR-005-common-controls-fips-assurance.md)
- **Implemented Class Diagrams (`CD-01`–`CD-06`):** [CLASS_DIAGRAMS.md](docs/diagrams/CLASS_DIAGRAMS.md)
- **End-to-End Sequence & Activity Diagrams:** [SEQUENCE_DIAGRAMS.md](docs/diagrams/SEQUENCE_DIAGRAMS.md) | [ACTIVITY_DIAGRAMS.md](docs/diagrams/ACTIVITY_DIAGRAMS.md)
- **System Overview & Target Multi-Cloud Infrastructure:** [SYSTEM_OVERVIEW.md](docs/architecture/SYSTEM_OVERVIEW.md) | [LOCAL_AND_TARGET_INFRA.md](docs/deployment/LOCAL_AND_TARGET_INFRA.md)
