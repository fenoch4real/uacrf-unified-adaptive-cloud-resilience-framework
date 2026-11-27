# UACRF System Architecture Overview (Milestone 0 Baseline)

## 1. Logical Microservice & Polyglot Authority Architecture

```mermaid
flowchart TB
    Portal["Bootstrap 5 + Thymeleaf + Cytoscape.js UI"] --> EDGE01["EDGE01: API Gateway"]
    EDGE01 --> UX01["UX01: Experience BFF"]
    EDGE01 --> S01["S01: Identity & Delegation"]
    EDGE01 --> S02["S02: Catalog & Customer Journeys"]
    EDGE01 --> S03["S03: Connectors & Telemetry Intake"]
    EDGE01 --> S04["S04: Evidence Qualification & Envelopes"]
    EDGE01 --> S05["S05: Objectives & Core Rules"]
    EDGE01 --> S06["S06: Topology & Graph Service"]
    EDGE01 --> S07["S07: Assessment Engine"]
    EDGE01 --> S10["S10: OPA Policy & Authority"]
    EDGE01 --> S11["S11: Temporal Recovery & Validation"]

    S01 & S02 & S03 & S04 & S05 & S07 & S10 & S11 --> PG[("PostgreSQL (Schema-per-Service + Outbox + RLS)")]
    S06 --> PG
    S06 -- "Sole Writer (Idempotent Projection)" --> NEO[("Neo4j (58 Labels / 55 Relationships)")]
    S04 --> MINIO[("MinIO / S3 Object Lock")]
    S10 --> OPA["Open Policy Agent"]
    S11 --> TEMP["Temporal"]
```

## 2. Polyglot Data Authority Boundary

```mermaid
flowchart LR
    PG["PostgreSQL (Authoritative Metadata, Catalog, Rules, Approvals, Outbox)"] -->|Atomic Outbox Event| Worker["Fenced Outbox Projector"]
    Worker -->|Idempotent MERGE + Revision Seal| NEO["Neo4j (Authoritative Resolved Topology & Witness Paths)"]
    NEO -->|GraphRevision + Subgraph Digest| Decisions["S07 Assess / S10 Policy / S04 Evidence"]
    PG -->|Immutable References| MINIO["MinIO / S3 (Signed DecisionEnvelopes & Raw Payloads)"]
```

## 3. Core Non-Overridable Invariants
1. **No Dual-Write**: Services commit business state and outbox records atomically in PostgreSQL; `S06` projects into Neo4j idempotently.
2. **Fail-Closed by Default**: Missing, expired, provisional, or contradicted evidence yields `UNKNOWN` or `DENY` for consequential operations.
3. **Separation of Duties**: No identity may propose and approve a high-risk action, nor qualify its own evidence.
4. **Canonical Ontology**: Every Neo4j node/edge must pass `Neo4jOntology` (58 node labels, 55 relationship types).

## 4. Enterprise Compliance & Traceability Extension
See [TECHNICAL_APPROACH_AND_COMPLIANCE_SPEC.md](TECHNICAL_APPROACH_AND_COMPLIANCE_SPEC.md), [ENTERPRISE_COMPLIANCE_AND_ROADMAP.md](../compliance/ENTERPRISE_COMPLIANCE_AND_ROADMAP.md), and [REQUIREMENTS_TRACEABILITY_MATRIX.md](../traceability/REQUIREMENTS_TRACEABILITY_MATRIX.md) for the expanded multi-standard Common Control Framework (**SOC 2, ISO 27001/22301/42001, GDPR, DORA, UK PRA, PCI DSS v4.0, HIPAA, FedRAMP/FIPS 140-3, EU AI Act, NIST AI RMF, SSDF**) and implementation tracking across `M0`–`M8` and `E1`–`E4`.
