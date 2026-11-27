# UACRF Technical Approach & Enterprise Regulatory Compliance Specification (Rev 23 Alignment)

> **Purpose:** Translates the end-to-end capabilities of `UACRF_Technical_Approach_v1.0_rev23` into clear architectural specifications, simple verifiable requirements, and a unified Common Control Framework spanning `S01`–`S14` and `P1`–`P7`.

---

## 1. End-to-End Platform Capabilities & Requirements Summary

The UACRF Technical Approach organizes resilience engineering and enterprise assurance into nine cohesive capability pillars. Every capability below maps directly to concrete microservices, automated tests, and regulatory controls.

1. **Identity, Segregation of Duties & Bounded Authority (`S01`, `S10`, `P3`):**
   - *Requirement:* Every user, workload, and agent identity is explicitly typed (`HUMAN`, `SYSTEM`, `AGENT`), zero runtime permissions are granted on initial admission, privilege grants require independent human two-person approval, and consequential execution uses short-lived, single-use, audience-bound RS256 envelopes (`BoundedRecoveryAuthorization`) backed by an atomic nonce ledger.
2. **Customer Journeys, Service Catalog & Protected Agent Workloads (`S02`, `FR-AWL-001..012`):**
   - *Requirement:* Conventional services and Protected Customer Agent Workloads (`SINGLE_AGENT`, `MULTI_AGENT`) are inventoried separately. Business-critical Customer Journeys bind ordered steps, capability criticality (`REQUIRED`, `OPTIONAL`, `UNKNOWN`), shared resources (`MODEL`, `KNOWLEDGE`, `TOOL`, `MEMORY`, `IDENTITY`, `RUNTIME`), and versioned `JourneyImpactProfile` revisions reviewed by an independent approver.
3. **Multi-Cloud Discovery, Privacy Scrubbing & WORM Evidence Qualification (`S03`, `S04`, `P4`):**
   - *Requirement:* Cloud and OpenTelemetry signals across `GCP`, `AWS`, `Azure`, `OCI`, and `OTel` ingest through the `TelemetryAdapter` SPI while stripping PII, PHI, PCI PANs, and secrets (`PiiPhiScrubber`) before storage. Deterministic `QualificationEngine` enforces freshness TTLs, graph-revision binding, 6-aspect empirical checks on customer AI models (`endpoint_availability`, `latency_p95`, `model_quality`, `subgroup_safety`, `feature_freshness`, `fallback_capacity`), immutable S3 Object Lock persistence, and **No-Synthetic-Fallback** (unverified operational probes remain `UNKNOWN`).
4. **Canonical Knowledge Graph & Anti-Tamper Projection (`S06`):**
   - *Requirement:* `S06` is the sole Neo4j writer, enforcing the strict `58`-node-label and `55`-relationship-type ontology (`Neo4jOntology`) plus prohibited-property blocking (never storing raw logs, SSNs, cards, or credentials). `UNCERTAIN` edges remain visible with explicit non-causation warnings, and `GraphDriftReconciler` continuously detects divergence between authoritative PostgreSQL revisions and Neo4j state.
5. **Journey Assessment, Path Independence & Decision Sealing (`S07`, `P1`, `P5`):**
   - *Requirement:* Read-only journey assessments verify primary vs. alternate failover independence across correlation domains (`REGION`, `ZONE`, `CLUSTER`, `IDP`, `KMS_KEY`, `DNS`, `DATA_STORE`, `VENDOR`), enforce independent review, seal results into canonical RFC 8785 SHA-256 + RS256 JWS `DecisionEnvelope`s, and verify historical replay integrity (`VERIFIED` vs. `TAMPER_DETECTED`).
6. **Continuous SLO Objectives, Policy Gate & Pre-Execution Delta Check (`S05`, `S10`, `P2`):**
   - *Requirement:* Versioned SLI/SLO/RTO/RPO rules evaluate resilience risk continuously and fail closed to `UNKNOWN` when telemetry is missing or stale. `S10` evaluates OPA Rego policies (`DENY`, `RECOMMEND`, `APPROVAL_REQUIRED`, `PREAUTHORIZED`), blocks self-approval, and runs `MaterialGraphDeltaClassifier` (`P2`) immediately before execution to invalidate widened or target-mutated proposals.
7. **Durable Recovery Orchestration, Validation DAG & Compensation (`S11`, `P6`):**
   - *Requirement:* Temporal workflows (`RecoveryWorkflow`) enforce kill-switch state, token expiry, tenant/audience match, target allowlists, and single-use nonce consumption before calling allowlisted `ProviderActionAdapter`s (`KubernetesLocalRollbackAdapter`, `CloudTrafficFailoverAdapter`). Completion requires passing all mandatory layers of the 8-layer Cross-Layer Validation DAG (through end-to-end synthetic customer-journey probes); failure triggers automatic bounded compensation.
8. **Governed Knowledge, Learning Closures, Fault Experiments & Advisory Dissent (`S08`, `S09`, `S12`, `S13`, `P7`):**
   - *Requirement:* Approved runbooks remain reference-only (`S08`); postmortem learning items cannot close without verified artifact digests (`S12`); chaos experiments halt automatically when SLO guardrails breach (`S13`); the Paper §10.5 quantitative shadow model ($r(t)$, $S(t)$, $RCI$, $ER$) benchmarks performance non-authoritatively; and Solution-Specific Agent Advisors (`S09`) run behind a prompt-injection and SSRF firewall, token budgets, kill switches, and permutation-invariant arbitration (`P7`) that never suppresses blocking Critic dissent.
9. **Commercial Metering, External Assurance Rooms & Multi-Cloud DevSecOps (`S14`, `uacrf-crypto`, `EDGE01`, `UX01`, CI/IaC):**
   - *Requirement:* `S14` (`reporting-service`) meters protected assets/evaluations, generates live compliance crosswalks, and serves time-bound watermarked External Assurance Rooms. Infrastructure deployment enforces non-root distroless containers, Kubernetes `NetworkPolicy`, Istio `STRICT` mTLS, multi-cloud Terraform KMS/WORM storage, FIPS 140-3 minimum key sizes, PITR snapshots, and automated SSDF CI gates (`Gitleaks`, `Trivy`, `CodeQL`, `CycloneDX SBOM`, `Cosign`).

---

## 2. Unified Common Control Framework (CCF)

Rather than building fragmented compliance silos, UACRF implements a unified **Common Control Framework (CCF)** mapped across three enterprise adoption waves:

| Adoption Wave | Standards & Frameworks | UACRF Technical Enforcement |
|---|---|---|
| **Wave 1: Enterprise Baseline & Supply Chain** | **SOC 2 Type I & II** (`CC1`–`CC9`), **ISO/IEC 27001** (`27017`/`27018`), **ISO/IEC 22301**, **GDPR / CCPA-CPRA / ISO 27701**, **NIST SP 800-218 (SSDF)** & **SLSA** | `EDGE01` OAuth2 JWT + CSP headers, PostgreSQL `FORCE ROW LEVEL SECURITY`, Kafka outbox relay, S3 Object Lock WORM storage, pre-storage PII scrubbing, CycloneDX SBOM, Trivy/Gitleaks/CodeQL gates, PITR scripts, and `S14` Assurance Rooms |
| **Wave 2: Financial & Regulated AI** | **EU DORA**, **UK PRA/FCA Operational Resilience**, **US FFIEC / NYDFS 23 NYCRR 500**, **PCI DSS v4.0**, **ISO/IEC 42001**, **NIST AI RMF 1.0 + GenAI Profile**, **EU AI Act** | `CustomerJourney` impact tolerances (`S02`), `P5` cross-cloud path independence across `AWS`/`GCP`/`Azure`/`OCI` (`S07`), fault drills (`S13`), `P1` signed envelopes, PCI PAN redaction + CDE segmentation, 6-aspect customer AI verification (`S04`), and `S09` human oversight + Critic dissent |
| **Wave 3: Healthcare, Energy & Public Sector** | **HIPAA / HITECH + HITRUST CSF**, **FedRAMP (NIST SP 800-53 Rev 5) / StateRAMP**, **EU NIS2**, **NERC CIP** | Pre-storage PHI/SSN redaction (`S03`/`S09`), sovereign multi-cloud Terraform regions, `FipsCryptoProfile` (`RSA >= 3072` + KMS abstraction), zero standing execution privilege (`P3`), and hardened non-root Helm/Istio mTLS deployments |

---

## 3. Product Suite Alignment (`Assess`, `Control`, `Recover`, `Evidence`)

| UACRF Product Pillar | Primary Services & UI Routes | Core Customer Outcome |
|---|---|---|
| **Assess** | `S02`, `S06`, `S07` (`/journeys`, `/agents`, `/dependencies`, `/ai-dependencies`, `/assess`) | Discovers true multi-cloud and AI blast radius, flags unconfirmed links without implying causation, certifies failover path independence (`P5`), and seals review-verified reports (`P1`). |
| **Control** | `S05`, `S10` (`/control`, `/settings/access`) | Continuously evaluates versioned SLO/RTO/RPO and Appendix I rules, enforces fail-closed `UNKNOWN` handling, runs live OPA Rego policy gates, and prevents stale-topology actions (`P2`). |
| **Recover** | `S11` (`/recover`) | Executes bounded, single-use Temporal workflows (`P3`) behind an instant kill switch and confirms restoration via the 8-layer P6 Validation DAG before declaring recovery complete. |
| **Evidence & Assurance** | `S04`, `S08`, `S09`, `S12`, `S13`, `S14` (`/evidence`, `/knowledge`, `/advisory`, `/assurance`) | Blocks synthetic fallback claims, locks empirical records into WORM storage, preserves advisory Critic dissent (`P7`), enforces verified learning closure, and exposes watermarked auditor Assurance Rooms. |
