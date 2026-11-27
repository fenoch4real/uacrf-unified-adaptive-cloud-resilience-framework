# UACRF Local & Enterprise Target Deployment Infrastructure

## 1. SSDF / SLSA DevSecOps Promotion & Supply-Chain Integrity Pipeline

```mermaid
flowchart LR
    Dev["Dev (SAMPLE_LOCAL Fixtures, Zero Prod Secrets)"] -->|PR + Review| CI["CI Build & Testcontainers (PG17 + Neo4j 5.26)"]
    CI --> Scan["SSDF Gates: CodeQL SAST + Gitleaks + Trivy CVE + CycloneDX SBOM"]
    Scan --> Sign["Cosign Image Signing + Digest Pinning"]
    Sign -->|Approved Change Ticket| Preprod["Preproduction Staging (Masked Data + S13 Fault Drills + Playwright E2E)"]
    Preprod -->|Dual-Approval Release Gate| Prod["Production Enterprise Cluster (RLS + KMS/FIPS + mTLS)"]
```

## 2. Zero-Trust Enterprise Network, Enclaves & Trust Boundaries

```mermaid
flowchart TB
    subgraph IdentityTrust["Identity & Cryptographic Trust Plane"]
        IDP["Keycloak / Corporate OIDC + MFA"]
        KMS["Vault Transit / HSM (FIPS 140-3 Crypto)"]
        CIAM["CI/CD Provenance & Policy Signing"]
    end

    subgraph ControlPlane["UACRF Control Plane (Private VPC + Istio mTLS Mesh)"]
        EDGE["EDGE01 JWT Gateway (:8080) & UX01 Portal (:8081)"]
        CoreSvc["Core Services (S01..S14 Spring Boot Pods + HPA/PDB)"]
        PG[("PostgreSQL 17 Multi-AZ (RLS + PITR Encrypted)")]
        KAFKA[("Kafka / Redpanda TLS Event Mesh")]
        NEO[("Neo4j 5.26 Enterprise Cluster")]
        S3[("S3 Object Lock / Azure Immutable Blob (WORM)")]
        OPA["OPA Bundle Server"]
        TEMP["Temporal Durable Cluster"]
    end

    subgraph RegulatedEnclave["Optional Regulated Workloads / Customer VPC"]
        Scrubber["PII / PHI Ingestion Scrubber + OTel Collector"]
        ReadID["Read-Only Discovery Identity (PrivateLink)"]
        ActionID["Allowlisted Recovery Identity (Single-Use P3 JWS + Nonce)"]
        Assurance["Watermarked External Auditor View (S14)"]
    end

    EDGE --> CoreSvc
    CoreSvc <--> IDP
    CoreSvc --> KMS
    CoreSvc --> PG
    CoreSvc <--> KAFKA
    CoreSvc --> NEO
    CoreSvc --> S3
    CoreSvc --> OPA
    CoreSvc --> TEMP
    Scrubber -->|Zero Raw PII/PHI/PAN| CoreSvc
    CoreSvc -->|Read-Only Discovery| ReadID
    CoreSvc -.->|Bounded & Nonce-Gated| ActionID
    Assurance -.->|Time-Bound Read-Only| CoreSvc
```

## 3. Compliance Controls Mapped to Infrastructure Layers
- **Network Segmentation & PCI CDE Isolation:** Standard telemetry runs in isolated VPCs; payment/CDE adapters deploy behind dedicated network policies and egress allowlists.
- **Data Residency & Erasure (GDPR / ISO 27701):** Regional Kubernetes deployments (`US`, `EU`) pin PostgreSQL, Neo4j, and S3 WORM buckets strictly in-region with tenant crypto-shredding keys managed in KMS.
- **Availability & Disaster Recovery (SOC 2 A1 / ISO 22301 / DORA):** Multi-AZ PostgreSQL with automated PITR backup/restore tests, Neo4j replication, Temporal crash recovery, and PodDisruptionBudgets (`PDB`).
