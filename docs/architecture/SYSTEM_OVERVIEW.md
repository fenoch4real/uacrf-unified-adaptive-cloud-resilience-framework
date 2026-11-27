# UACRF System Architecture Overview (Enterprise & Compliance Rev 23)

## 1. Enterprise Logical Microservice & Polyglot Authority Architecture

```mermaid
flowchart TB
    Portal["UX01: Thymeleaf + Bootstrap 5.3 + Cytoscape.js (:8081)"] --> EDGE["EDGE01: API Gateway + OIDC/JWT (:8080)"]
    Auditor["External Auditor / Regulator"] -->|Watermarked Assurance Link| S14["S14: Commercial Metering & Assurance Rooms"]

    EDGE --> S01["S01: Identity & Delegation (:8082)"]
    EDGE --> S02["S02: Catalog & Customer Journeys (:8083)"]
    EDGE --> S03["S03: Connectors, PII Scrubber & Telemetry Intake (:8084)"]
    EDGE --> S04["S04: Evidence Qualification & Envelopes (:8085)"]
    EDGE --> S05["S05: Objectives & Appendix I Rules (:8088)"]
    EDGE --> S06["S06: Topology & Graph Service (:8086)"]
    EDGE --> S07["S07: Assessment & P5/P1 Engine (:8087)"]
    EDGE --> S08["S08/S12/S13: Knowledge, Learning, Experiments & Quant (:8091)"]
    EDGE --> S09["S09: Solution-Specific Agent Advisors P7 (:8092)"]
    EDGE --> S10["S10: OPA Policy & P2/P3 Authority (:8089)"]
    EDGE --> S11["S11: Recovery Orchestrator P3/P6 (:8090)"]
    EDGE --> S14

    S01 & S02 & S03 & S04 & S05 & S07 & S08 & S09 & S10 & S11 & S14 --> PG[("PostgreSQL 17 (Schema-per-Service + RLS + Outbox)")]
    PG -->|Leased Outbox Relay| BUS[("Kafka / Redpanda Event Spine")]
    BUS --> S04 & S06 & S05 & S12

    S06 --> PG
    S06 -- "Sole Writer (58 Labels / 55 Rels + Drift Check)" --> NEO[("Neo4j 5.26 Cluster")]
    S04 & S08 & S14 --> OBJ[("MinIO / S3 Object Lock WORM")]
    S01 & EDGE <--> IDP["Keycloak OIDC / MFA Federation"]
    S01 & S07 & S10 & EDGE --> KMS["Vault / Cloud KMS (FIPS 140-3 Crypto Profile)"]
    S10 --> OPA["Open Policy Agent Cluster"]
    S11 --> TEMP["Temporal Durable Workflow Cluster"]
    S09 -.->|Bounded Citation-Locked API| LLM["Enterprise Private LLM Endpoint (Bedrock / Azure / Vertex)"]
    S03 --> Clouds["AWS / GCP / Azure / K8s / OTel + SAMPLE_LOCAL"]
```

## 2. Polyglot Data & Compliance Authority Boundaries

```mermaid
flowchart LR
    PG["PostgreSQL + RLS (Catalog, Rules, Approvals, Nonces, Outbox)"] -->|Atomic Outbox Event| Relay["Outbox Relay -> Kafka/Redpanda"]
    Relay -->|Idempotent MERGE + Revision Seal| NEO["Neo4j (58 Labels / 55 Rel Types, No PII/Secrets)"]
    NEO -->|GraphRevision + Subgraph Digest| Decisions["S07 Assess / S10 Policy / S06 Drift Scanner"]
    PG -->|WORM Object Lock Retention| S3["MinIO / S3 (P1 DecisionEnvelopes & Evidence Bundles)"]
    S14["S14 Crosswalk Engine (SOC2 / ISO / DORA / HIPAA / AI Act)"] --> S3
    S14 --> PG
```

## 3. Core Non-Overridable Invariants
1. **No Dual-Write**: Services commit business state and outbox records atomically in PostgreSQL; `S06` projects into Neo4j idempotently and verifies drift continuously.
2. **Fail-Closed by Default**: Missing, expired, provisional, drifted, or contradicted evidence yields `UNKNOWN`, `TAMPER_DETECTED`, or `DENY`.
3. **Separation of Duties**: No identity may propose and approve a high-risk action, approve own knowledge/impact profile, or conduct independent review on own assessment.
4. **Canonical Ontology & Data Minimization**: Every Neo4j node/edge must pass `Neo4jOntology` (`58` labels, `55` relationships) and prohibited-key checks (`secret`, `password`, `ssn`, `credit_card`, `raw_log_body`).
5. **Protected AI vs. Advisory Isolation**: Customer AI workloads require empirical quality/safety/fallback checks (`S04`); solution-specific advisors (`S09`) preserve blocking critic dissent and obey kill switches.

## 4. Enterprise Compliance & Traceability Extension
See [TECHNICAL_APPROACH_AND_COMPLIANCE_SPEC.md](TECHNICAL_APPROACH_AND_COMPLIANCE_SPEC.md), [ENTERPRISE_COMPLIANCE_AND_ROADMAP.md](../compliance/ENTERPRISE_COMPLIANCE_AND_ROADMAP.md), and [REQUIREMENTS_TRACEABILITY_MATRIX.md](../traceability/REQUIREMENTS_TRACEABILITY_MATRIX.md).
