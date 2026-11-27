# UACRF Master Requirements & Compliance Traceability Matrix

> Honest engineering tracker mapping **Functional, Patent (`P1`–`P7`), Non-Functional, UI/UX, and Enterprise Regulatory Compliance** specifications against the current repository (`HEAD` on `main`).
>
> **Status Legend:**
> - `IMPLEMENTED` — Implemented cleanly, backed by PostgreSQL/Neo4j persistence, REST/UI endpoints, and passing automated Testcontainers/MockMvc tests.
> - `PARTIAL / LOCAL_SPI` — Domain logic & SPI contract implemented and tested locally, while live external runtime/broker/cloud SDK/FIPS wiring remains pending (`Phase E1–E3`).
> - `PENDING / NOT_IMPLEMENTED` — Planned in Enterprise Phases `E1`–`E4`; not built yet.

---

## 1. Functional Requirements & Microservice Coverage (`S01`–`S14`)

| Req ID | Specification Area | Owning Module | Status | Implementation Evidence / Remaining Work |
|---|---|---|---|---|
| **FR-IDN-001** | Tenant, Environment & Principal management (`HUMAN`/`SYSTEM`/`AGENT`) | `S01` (`identity-service`) | `IMPLEMENTED` | [IdentityAuthorityService.java](../../services/identity-service/src/main/java/io/uacrf/identity/service/IdentityAuthorityService.java) (`V1__init_identity.sql`, Testcontainers verified) |
| **FR-IDN-002** | Two-person independent grant approval & separation of duties | `S01` (`identity-service`) | `IMPLEMENTED` | Enforced in `IdentityAuthorityService` (`SELF_APPROVAL_PROHIBITED`, `SEPARATION_OF_DUTIES_VIOLATION`) |
| **FR-IDN-003** | Short-lived RS256 delegation exchange & OIDC JWT validation at edge | `S01`, `EDGE01` | `PARTIAL / LOCAL_SPI` | `EDGE01` conceals `/v1/delegations/exchange` (`EdgeProxyController`); `EnvelopeSigner` built; Spring Security OAuth2 JWT + Keycloak wiring pending (`E1.1`) |
| **FR-CAT-001** | Conventional Services vs. Protected Customer Agent Workloads (`SINGLE_AGENT`/`MULTI_AGENT`) | `S02` (`catalog-service`) | `IMPLEMENTED` | Separate tables/filtering in `CatalogService.java` + [agents.html](../../services/experience-ui/src/main/resources/templates/agents.html) |
| **FR-JNY-001..003** | Customer Journeys, ordered steps, versioned Impact Profiles & distinct reviewer approval | `S02` (`catalog-service`) | `IMPLEMENTED` | `DRAFT -> SUBMITTED -> APPROVED` with owner/reviewer separation tested in `CatalogServiceIntegrationTest` |
| **FR-ING-001** | Cloud/OTel `TelemetryAdapter` SPI + `SAMPLE_LOCAL` isolated synthetic generator | `S03` (`discovery-service`) | `IMPLEMENTED` | [TelemetryAdapter.java](../../services/discovery-service/src/main/java/io/uacrf/discovery/adapter/TelemetryAdapter.java) & [SampleLocalScenarioGenerator.java](../../services/discovery-service/src/main/java/io/uacrf/discovery/adapter/SampleLocalScenarioGenerator.java) |
| **FR-ING-002** | Live AWS / GCP / Azure / OCI / K8s / OTel production collectors + PII scrubber | `S03` (`discovery-service`) | `PENDING / NOT_IMPLEMENTED` | SPI ready; live cloud SDK adapters & PII scrubber pipeline scheduled in `E2.2` |
| **FR-EVD-001** | Deterministic Evidence Qualification, TTL freshness & revision binding | `S04` (`evidence-service`) | `IMPLEMENTED` | [QualificationEngine.java](../../services/evidence-service/src/main/java/io/uacrf/evidence/service/QualificationEngine.java) (`EvidenceServiceIntegrationTest`) |
| **FR-EVD-002** | **No-Synthetic-Fallback** invariant (`KNOWLEDGE_DOC`/`ADVISORY_DRAFT` rejected) | `S04` (`evidence-service`) | `IMPLEMENTED` | Returns `422 SYNTHETIC_FALLBACK_FORBIDDEN`; missing probes stay `UNKNOWN` |
| **FR-EVD-003** | Protected AI 6-aspect empirical dependency checks (`model_quality`, `subgroup_safety`, etc.) | `S04`, `UX01` | `IMPLEMENTED` | `evidence.ai_dependency_evidence` + [ai-dependencies.html](../../services/experience-ui/src/main/resources/templates/ai-dependencies.html) |
| **FR-EVD-004** | WORM immutable evidence archival in S3 / MinIO Object Lock | `S04`, `docker-compose.yml` | `PARTIAL / LOCAL_SPI` | MinIO bucket `--with-lock` provisioned & immutable DB `object_blob` enforced; AWS S3 Object Lock SDK client in `S04` pending (`E1.3`) |
| **FR-GPH-001..005** | Sole Neo4j writer, **58 Node Labels / 55 Rel Types**, prohibited-key guard | `uacrf-contracts`, `S06` | `IMPLEMENTED` | [Neo4jOntology.java](../../libs/uacrf-contracts/src/main/java/io/uacrf/contracts/graph/Neo4jOntology.java) & [Neo4jGraphGateway.java](../../services/graph-service/src/main/java/io/uacrf/graph/service/Neo4jGraphGateway.java) verified against live `neo4j:5.26` container |
| **FR-GPH-006** | `UNCERTAIN` link visibility, owner confirmation & non-causation warning | `S06`, `UX01` | `IMPLEMENTED` | [dependencies.html](../../services/experience-ui/src/main/resources/templates/dependencies.html) (Cytoscape.js) + `confirmEdge` endpoint |
| **FR-GPH-007** | Scheduled Neo4j `PROJECTED` vs. `DRIFTED` anti-tamper reconciliation daemon | `S06` (`graph-service`) | `PENDING / NOT_IMPLEMENTED` | Revision digests sealed on write; background drift scanner pending (`E1.4`) |
| **FR-ASS-001..003** | Read-only journey assessments, independent review (`SELF_REVIEW_FORBIDDEN`) & report issuance | `S07`, `UX01` | `IMPLEMENTED` | `AssessmentEngineService.java` + [assess.html](../../services/experience-ui/src/main/resources/templates/assess.html) + [assess-report.html](../../services/experience-ui/src/main/resources/templates/assess-report.html) |
| **FR-CTL-001..002** | Versioned SLI/SLO/RTO/RPO + Appendix I Core rules (`NORMAL..CRITICAL`, fail-closed `UNKNOWN`) | `S05` (`objectives-service`) | `IMPLEMENTED` | [CoreRuleEvaluator.java](../../services/objectives-service/src/main/java/io/uacrf/objectives/service/CoreRuleEvaluator.java) |
| **FR-POL-001..002** | OPA Rego 4-branch evaluator (`DENY`/`RECOMMEND`/`APPROVAL_REQUIRED`/`PREAUTHORIZED`) & two-person approval | `S10`, `policies/` | `PARTIAL / LOCAL_SPI` | Mirrors [baseline.rego](../../policies/baseline.rego) with HTTP client ready; live sidecar enforcement flag defaults `false` pending `E1.3` |
| **FR-REC-001..003** | Recovery state machine, `ProviderActionAdapter` SPI, kill switch & compensation | `S11` (`recovery-service`) | `PARTIAL / LOCAL_SPI` | Synchronous orchestration + [KubernetesLocalRollbackAdapter.java](../../services/recovery-service/src/main/java/io/uacrf/recovery/adapter/KubernetesLocalRollbackAdapter.java) tested; Temporal Java SDK workers & live KinD/cloud adapters pending (`E2.1`, `E2.2`) |
| **FR-LRN-001..002** | Closed-loop postmortem learning items blocking closure without verified artifact digest | `S12` (`knowledge-service`) | `IMPLEMENTED` | `409 VERIFICATION_REQUIRED` enforced in `KnowledgeGovernanceService.java` |
| **FR-EXP-001** | Controlled fault experiments with blast-radius cap & auto-abort SLO threshold | `S13` (`knowledge-service`) | `IMPLEMENTED` | `ABORTED_BY_GUARDRAIL` verified in `KnowledgeServiceIntegrationTest` |
| **FR-KNO-001..004** | Governed knowledge quarantine, independent review & SHA-256 citation digests | `S08` (`knowledge-service`) | `IMPLEMENTED` | Verified in `KnowledgeServiceIntegrationTest` |
| **FR-ADV-001..005** | Solution-Specific Agent Advisors, prompt-injection firewall, budget caps, kill switch & Critic Dissent | `S09` (`advisory-service`) | `PARTIAL / LOCAL_SPI` | Full guardrails & UI ([advisory.html](../../services/experience-ui/src/main/resources/templates/advisory.html)) implemented; live enterprise LLM API connector pending (`E2.3`) |
| **FR-QNT-001** | Paper §10.5 Shadow Model ($r(t)$, $S(t)$, $RCI$, $ER$) & Ablation Benchmarks | `knowledge-service`, `UX01` | `IMPLEMENTED` | [QuantitativeShadowEngine.java](../../services/knowledge-service/src/main/java/io/uacrf/knowledge/service/QuantitativeShadowEngine.java) + [knowledge.html](../../services/experience-ui/src/main/resources/templates/knowledge.html) |
| **FR-REP-001..003** | `S14` Commercial Entitlements, Asset Metering & External Watermarked Assurance Rooms | `reporting-service` *(planned)* | `PENDING / NOT_IMPLEMENTED` | `uacrf_reporting` DB initialized in SQL; service implementation scheduled in `E2.4` |

---

## 2. Core Patents & Cryptographic Guarantees (`P1`–`P7`)

| ID | Mechanism | Status | Verification / Remaining Gap |
|---|---|---|---|
| **P1** | RS256 JWS `DecisionEnvelope` sealing & tamper-detecting replay | `IMPLEMENTED` *(ephemeral dev key)* | Verified in `AssessmentServiceIntegrationTest`; move signing key to KMS/Vault in `E3.2` |
| **P2** | Pre-execution `MaterialGraphDeltaClassifier` invalidating widening/target changes | `IMPLEMENTED` | Verified (`409 MATERIAL_GRAPH_DELTA_INVALIDATED`) in `PolicyServiceIntegrationTest` |
| **P3** | Short-lived, audience-bound, single-use `BoundedRecoveryAuthorization` & nonce ledger | `IMPLEMENTED` | Enforced across `S10` & `S11` (`TARGET_SUBSTITUTION_FORBIDDEN`, `NONCE_REPLAY_DETECTED`) |
| **P4** | Deterministic evidence qualification (`QUALIFIED` / `PROVISIONAL` / `UNKNOWN` / `DISQUALIFIED`) | `IMPLEMENTED` | Verified in `EvidenceServiceIntegrationTest` |
| **P5** | Correlation-Domain Taxonomy (`REGION`/`IDP`/`KMS_KEY`/`DNS`/etc.) independence check | `IMPLEMENTED` | Verified (`NOT_INDEPENDENT`) in `AssessmentServiceIntegrationTest` |
| **P6** | Dependency-ordered 8-layer Cross-Layer Validation DAG with bounded compensation | `IMPLEMENTED` *(simulated probes)* | Verified (`SUCCEEDED` vs. `COMPENSATED`) in `RecoveryServiceIntegrationTest`; live K8s probe wiring in `E2.2` |
| **P7** | Permutation-invariant `DeterministicArbiter` with non-suppressible Critic Dissent | `IMPLEMENTED` | Verified across forward/reverse permutations in `AdvisoryServiceIntegrationTest` |

---

## 3. Non-Functional Infrastructure & Security Architecture

| ID | Requirement | Status | Current State / Target Phase |
|---|---|---|---|
| **NFR-OUTBOX** | Atomic PostgreSQL transactional outbox across all services | `PARTIAL / LOCAL_SPI` | Outbox tables & writes exist in every service; Kafka/Redpanda broker + relay worker pending (`E1.2`) |
| **NFR-TENANT** | Multi-tenant + environment isolation | `PARTIAL / LOCAL_SPI` | Enforced on every repository query (`WHERE tenant_id = ? AND environment_id = ?`); PostgreSQL RLS SQL policies pending (`E1.1`) |
| **NFR-UI-E2E** | Complete Thymeleaf + Bootstrap 5.3 + Cytoscape UI + Playwright E2E | `PARTIAL / LOCAL_SPI` | All `18` UI templates + `MockMvc` WebMVC tests pass; full-stack Docker Compose Playwright E2E pending (`E1.4`) |
| **NFR-CRYPTO** | Production KMS / HSM key custody & Vault rotation | `PENDING / NOT_IMPLEMENTED` | Scheduled in `E3.2` |

---

## 4. Enterprise Regulatory & Compliance Standards Tracker

| Reg ID | Standard / Mandate | Status | What UACRF Provides Today | What Must Be Added for Compliance Sign-Off |
|---|---|---|---|---|
| **REG-SOC2** | **SOC 2 Type I & Type II** (`CC1`–`CC9`) | `PARTIAL / LOCAL_SPI` | Separation of duties (`S01`/`S10`), immutable evidence (`S04`/`S07`), fail-closed gates | Keycloak OIDC/JWT + PostgreSQL RLS (`E1`), SSDF CI gates (`E3`), corporate policies + `3–6 mo` CPA observation (`E4`) |
| **REG-ISO27001** | **ISO/IEC 27001 / 27017 / 27018** | `PARTIAL / LOCAL_SPI` | Microservice isolation, access approvals, audit tables, ontology secret blocking | ISMS documentation, mTLS mesh, KMS encryption, Vulnerability SLA automation (`E1`–`E4`) |
| **REG-ISO22301** | **ISO/IEC 22301 (BCM)** | `IMPLEMENTED` *(technical core)* | Customer journeys, RTO/RPO, `P5` independence tests, `S13` drills, `S11` recovery validation | Enterprise BIA exports & multi-region HA DR testing (`E2.4`, `E3.1`) |
| **REG-GDPR** | **GDPR / CCPA-CPRA / ISO 27701** | `PARTIAL / LOCAL_SPI` | Prohibited raw PII/secret properties in Neo4j graph (`Neo4jOntology`) | Data residency region pinning, tenant crypto-shredding/erasure API & PII ingestion scrubber (`E2.2`) |
| **REG-DORA** | **EU DORA (Financial ICT Resilience)** | `PARTIAL / LOCAL_SPI` | ICT topology (`S06`), concentration/path independence (`S07`), testing (`S13`), `P1` envelopes | Regulatory ICT incident reporting export templates (`S14` Assurance Rooms in `E2.4`) |
| **REG-PRA** | **UK PRA / FCA / FFIEC / NYDFS 23 NYCRR 500** | `PARTIAL / LOCAL_SPI` | Journey impact tolerances (`S02`/`S05`), immutable audit trails, dual approvals | NYDFS annual certification artifacts + OIDC MFA enforcement (`E1.1`, `E2.4`) |
| **REG-PCI** | **PCI DSS v4.0** | `PENDING / NOT_IMPLEMENTED` | No cardholder fields permitted in Neo4j ontology | CDE network segmentation, HSM key rotation, FIM/WAF & ASV vulnerability scans (`E3.1`–`E4`) |
| **REG-ISO42001** | **ISO/IEC 42001 (AIMS)** | `PARTIAL / LOCAL_SPI` | AI dependency 6-aspect checks (`S04`), Critic Dissent (`S09`), advisory kill switches | Formal AI Model Card registry, bias/subgroup continuous drift pipelines & AIMS audit (`E2.3`, `E4`) |
| **REG-NIST-AI** | **NIST AI RMF 1.0 & EU AI Act** | `PARTIAL / LOCAL_SPI` | Human-in-the-loop gates (`S10`/`S09`), adversarial prompt firewall, dissent preservation | EU AI Act risk classification logs & conformity assessment exports (`E2.4`, `E4`) |
| **REG-HIPAA** | **HIPAA / HITECH / HITRUST CSF** | `PENDING / NOT_IMPLEMENTED` | Access logs & tenant separation foundation | Mandatory PHI redaction pipeline prior to raw ingestion (`E2.2`), encrypted WORM S3, signed BAAs (`E4`) |
| **REG-FEDRAMP** | **FedRAMP (NIST 800-53 Rev 5) / FIPS 140-3** | `PENDING / NOT_IMPLEMENTED` | Baseline security architecture aligned to least privilege & auditability | **FIPS 140-3** validated JVM crypto/TLS modules (`E3.2`), OSCAL SSP, ConMon & 3PAO (`E4`) |
| **REG-NIS2** | **EU NIS2 & NERC CIP** | `PARTIAL / LOCAL_SPI` | Zero standing execution rights (`P3` single-use tokens), change control gates (`S10`) | Critical-infra air-gapped Helm deployment & SBOM/supply-chain enforcement (`E3.1`, `E3.3`) |
| **REG-SSDF** | **NIST SP 800-218 (SSDF) & SLSA** | `PENDING / NOT_IMPLEMENTED` | Baseline GitHub CI compilation/Testcontainers workflow | CycloneDX SBOM, Trivy CVE blocking, Gitleaks, CodeQL SAST & Cosign signing (`E3.3`) |
