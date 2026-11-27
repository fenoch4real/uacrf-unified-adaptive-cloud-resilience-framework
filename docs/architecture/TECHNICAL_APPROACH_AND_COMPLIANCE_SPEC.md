# UACRF Technical Approach & Enterprise Regulatory Compliance Specification (Rev 23 Update)

> **Scope Extension:** Updates the UACRF Technical Approach & Product Specification (`v1.0 alignment rev 22`) to incorporate enterprise multi-framework regulatory compliance controls alongside the platform's core polyglot architecture (`S01`–`S14`, `P1`–`P7`).

---

## 1. Unified Common Control Framework (CCF)

Rather than engineering isolated compliance silos, UACRF adopts a single **Common Control Framework (CCF)** mapped across universal baseline standards, sector mandates, AI governance frameworks, and supply-chain integrity requirements:

| Adoption Wave | Standards & Frameworks | Primary Architectural Impact on UACRF |
|---|---|---|
| **Wave 1: Enterprise Baseline & Supply Chain** | **SOC 2 Type I & II** (`CC1`–`CC9`), **ISO/IEC 27001** (`27017`/`27018`), **ISO/IEC 22301**, **GDPR / CCPA-CPRA / ISO 27701**, **NIST SP 800-218 (SSDF)** & **SLSA** | OIDC/PKCE SSO + RBAC (`S01`), PostgreSQL Row-Level Security (RLS), WORM immutable logging (`S04`), SBOM + CVE gating + Cosign signed container images, EU/US data residency & tenant erasure |
| **Wave 2: Financial & Regulated AI** | **EU DORA**, **UK PRA/FCA Operational Resilience**, **US FFIEC / NYDFS 23 NYCRR 500**, **PCI DSS v4.0**, **ISO/IEC 42001**, **NIST AI RMF 1.0 + GenAI Profile**, **EU AI Act** | Journey impact tolerances (`S02`), `P5` ICT concentration/path independence (`S07`), fault resilience testing (`S13`), `P1` signed envelopes, Cardholder Data Environment segmentation + key rotation, 6-aspect AI evidence (`S04`), `S09` human oversight + Critic Dissent + kill switches |
| **Wave 3: Healthcare, Energy & Public Sector** | **HIPAA / HITECH + HITRUST CSF**, **FedRAMP (NIST SP 800-53 Rev 5) / StateRAMP**, **EU NIS2**, **NERC CIP** | Strict PHI/PII telemetry/prompt redaction (`S03`/`S06`/`S09`), BAA tenancy boundaries, **FIPS 140-3 validated cryptography** in `uacrf-crypto`/TLS/KMS, continuous monitoring (`ConMon`), zero standing privileged OT access (`P3`) |

---

## 2. How Core UACRF Capabilities Map Directly to Regulatory Mandates

1. **Access Control & Separation of Duties (`S01`, `S10` -> SOC 2 CC6, ISO 27001 A.5/A.8, PCI DSS, NYDFS):**
   - Two-person independent grant approvals, `SELF_APPROVAL_FORBIDDEN` enforcement, short-lived audience-bound RS256 delegation & recovery tokens (`P3`), and single-use nonce consumption eliminate standing privilege abuse.
2. **Operational Resilience & Concentration Risk (`S02`, `S06`, `S07`, `S13` -> EU DORA Ch. II/IV/V, UK PRA, ISO 22301):**
   - `CustomerJourney` + `JourneyImpactProfile` map directly to Critical Economic Functions / Important Business Services.
   - `PathIndependenceCertifier` (`P5`) detects single points of failure across `REGION`, `ZONE`, `CLUSTER`, `IDP`, `KMS_KEY`, `DNS`, `DATA_STORE`, `VENDOR`.
   - `S13` guarded fault injection verifies restoration within RTO/RPO impact tolerances.
3. **Evidence Integrity & Change Control (`S04`, `S10`, `S11`, `P1`, `P2`, `P6` -> SOC 2 CC7/CC8, Processing Integrity, DORA):**
   - `No-Synthetic-Fallback` prevents unverified claims; `P2` halts execution if topology drifted; `P6` validates 8 layers before completion; `P1` seals cryptographic chain-of-custody envelopes replayable independently.
4. **AI Trustworthiness & Oversight (`S04`, `S09`, `P7` -> ISO/IEC 42001, NIST AI RMF, EU AI Act):**
   - Strictly separates **Protected Customer AI Workloads** (requiring `endpoint_availability`, `latency_p95`, `model_quality`, `subgroup_safety`, `feature_freshness`, `fallback_capacity`) from **UACRF Solution-Specific Agent Advisors** (bounded cases, prompt-injection guard, citation locking, `BLOCKING_CONCERN` dissent preservation, advisory kill switch).
5. **Privacy & Data Minimization (`S03`, `S06`, `S09` -> GDPR, ISO 27701, HIPAA):**
   - Enforces `Neo4jOntology.validatePropertyKeys` blocking raw logs, unrestricted prompts, secrets, SSNs, and credit cards from ever entering graph storage; requires tenant-scoped KMS/Vault encryption and redaction pipelines before ingestion.

---

## 3. Mandatory Enterprise Architecture Additions Required for Production Sign-Off

1. **FIPS 140-3 & Envelope Key Management (`uacrf-crypto`, TLS, KMS):** Replace ephemeral JVM RSA keypairs with Cloud KMS / HSM / Vault Transit backed FIPS 140-3 providers and mTLS mesh (Istio/Linkerd) across all inter-service traffic.
2. **Database-Layer Tenant Isolation:** Enforce PostgreSQL Row-Level Security (`CREATE POLICY tenant_iso`) paired with `SET LOCAL app.tenant_id` in addition to application query filters.
3. **Async Kafka/Redpanda Outbox + Durable Temporal + Live OPA:** Replace synchronous HTTP relays and in-process mocks with durable partitioned transport, Temporal workflow clusters, and live signed OPA policy bundles.
4. **S3/MinIO WORM SDK & PII/PHI Scrubber Pipeline (`S03`/`S04`):** Connect S3 Object Lock Compliance/Governance mode natively and sanitize raw payloads prior to WORM archival.
5. **CI/CD Supply Chain (`SSDF`/`SLSA`) & `S14` Assurance Rooms:** Generate CycloneDX SBOMs, enforce Trivy CVE + Gitleaks secret policies, sign images with Cosign, and deploy `S14` watermarked Assurance Rooms for third-party auditors.
