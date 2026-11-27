# UACRF Master Requirements & Compliance Traceability Matrix

> Honest engineering tracker mapping **Functional, Patent (`P1`–`P7`), Non-Functional, UI/UX, and Enterprise Regulatory Compliance** specifications against the current repository (`main`).
>
> **Status Legend:**
> - `IMPLEMENTED` — Implemented cleanly, backed by PostgreSQL/Neo4j/Kafka/S3/Temporal/OPA integration, REST/UI endpoints, and passing automated Testcontainers/MockMvc/Temporal tests.
> - `IMPLEMENTED / CONFIG_GATED_EXTERNAL` — Full SPI, security controls, and test verification implemented in repo; live SaaS/cloud API calls activate when enterprise cloud/LLM/KMS credentials or Helm/Terraform apply are supplied.
> - `PENDING / E4_ORG_AUDIT` — Organizational policies, external CPA/3PAO assessments, and live observation windows outside code artifacts (`Phase E4`).

---

## 1. Functional Requirements & Microservice Coverage (`S01`–`S14`)

| Req ID | Specification Area | Owning Module | Status | Implementation Evidence & Verification |
|---|---|---|---|---|
| **FR-IDN-001** | Tenant, Environment & Principal management (`HUMAN`/`SYSTEM`/`AGENT`) | `S01` (`identity-service`) | `IMPLEMENTED` | [IdentityAuthorityService.java](../../services/identity-service/src/main/java/io/uacrf/identity/service/IdentityAuthorityService.java) (`V1__init_identity.sql`, `V2__enable_rls.sql`, Testcontainers verified) |
| **FR-IDN-002** | Two-person independent grant approval & separation of duties | `S01` (`identity-service`) | `IMPLEMENTED` | Enforced in `IdentityAuthorityService` (`SELF_APPROVAL_PROHIBITED`, `SEPARATION_OF_DUTIES_VIOLATION`) |
| **FR-IDN-003** | Short-lived RS256 delegation exchange & OAuth2 Bearer JWT validation + CSP headers at edge | `S01`, `EDGE01` | `IMPLEMENTED` | [EdgeSecurityConfig.java](../../services/edge-gateway/src/main/java/io/uacrf/edge/EdgeSecurityConfig.java) + [EdgeJwtSecurityTest.java](../../services/edge-gateway/src/test/java/io/uacrf/edge/EdgeJwtSecurityTest.java) |
| **FR-CAT-001** | Conventional Services vs. Protected Customer Agent Workloads (`SINGLE_AGENT`/`MULTI_AGENT`) | `S02` (`catalog-service`) | `IMPLEMENTED` | Separate tables/filtering in `CatalogService.java` + [agents.html](../../services/experience-ui/src/main/resources/templates/agents.html) + `V2__enable_rls.sql` |
| **FR-JNY-001..003** | Customer Journeys, ordered steps, versioned Impact Profiles & distinct reviewer approval | `S02` (`catalog-service`) | `IMPLEMENTED` | `DRAFT -> SUBMITTED -> APPROVED` with owner/reviewer separation tested in `CatalogServiceIntegrationTest` |
| **FR-ING-001** | Cloud/OTel `TelemetryAdapter` SPI + `SAMPLE_LOCAL` isolated synthetic generator | `S03` (`discovery-service`) | `IMPLEMENTED` | [TelemetryAdapter.java](../../services/discovery-service/src/main/java/io/uacrf/discovery/adapter/TelemetryAdapter.java) & [SampleLocalScenarioGenerator.java](../../services/discovery-service/src/main/java/io/uacrf/discovery/adapter/SampleLocalScenarioGenerator.java) |
| **FR-ING-002** | Multi-cloud `AWS`/`GCP`/`AZURE`/`OCI`/`OTEL` collectors + pre-storage PII/PHI scrubber | `S03` (`discovery-service`) | `IMPLEMENTED / CONFIG_GATED_EXTERNAL` | [MultiCloudTelemetryAdapters.java](../../services/discovery-service/src/main/java/io/uacrf/discovery/adapter/MultiCloudTelemetryAdapters.java) + [PiiPhiScrubber.java](../../services/discovery-service/src/main/java/io/uacrf/discovery/service/PiiPhiScrubber.java) (`DiscoveryServiceIntegrationTest`) |
| **FR-EVD-001** | Deterministic Evidence Qualification, TTL freshness & revision binding | `S04` (`evidence-service`) | `IMPLEMENTED` | [QualificationEngine.java](../../services/evidence-service/src/main/java/io/uacrf/evidence/service/QualificationEngine.java) (`EvidenceServiceIntegrationTest`) |
| **FR-EVD-002** | **No-Synthetic-Fallback** invariant (`KNOWLEDGE_DOC`/`ADVISORY_DRAFT` rejected) | `S04` (`evidence-service`) | `IMPLEMENTED` | Returns `422 SYNTHETIC_FALLBACK_FORBIDDEN`; missing probes stay `UNKNOWN` |
| **FR-EVD-003** | Protected AI 6-aspect empirical dependency checks (`model_quality`, `subgroup_safety`, etc.) | `S04`, `UX01` | `IMPLEMENTED` | `evidence.ai_dependency_evidence` + [ai-dependencies.html](../../services/experience-ui/src/main/resources/templates/ai-dependencies.html) |
| **FR-EVD-004** | WORM immutable evidence archival in S3 Object Lock (`GOVERNANCE` mode) | `S04`, `docker-compose.yml` | `IMPLEMENTED` | [ObjectLockWormClient.java](../../services/evidence-service/src/main/java/io/uacrf/evidence/service/ObjectLockWormClient.java) verified against live S3 Object Lock container (`localstack/localstack:3.8`) |
| **FR-GPH-001..005** | Sole Neo4j writer, **58 Node Labels / 55 Rel Types**, prohibited-key guard | `uacrf-contracts`, `S06` | `IMPLEMENTED` | [Neo4jOntology.java](../../libs/uacrf-contracts/src/main/java/io/uacrf/contracts/graph/Neo4jOntology.java) & [Neo4jGraphGateway.java](../../services/graph-service/src/main/java/io/uacrf/graph/service/Neo4jGraphGateway.java) verified against live `neo4j:5.26` container |
| **FR-GPH-006** | `UNCERTAIN` link visibility, owner confirmation & non-causation warning | `S06`, `UX01` | `IMPLEMENTED` | [dependencies.html](../../services/experience-ui/src/main/resources/templates/dependencies.html) (Cytoscape.js) + `confirmEdge` endpoint |
| **FR-GPH-007** | Neo4j `PROJECTED` vs. `DRIFTED` anti-tamper reconciliation scanner | `S06` (`graph-service`) | `IMPLEMENTED` | [GraphDriftReconciler.java](../../services/graph-service/src/main/java/io/uacrf/graph/service/GraphDriftReconciler.java) + `GET /v1/graph/reconcile` (`GraphServiceIntegrationTest`) |
| **FR-ASS-001..003** | Read-only journey assessments, independent review (`SELF_REVIEW_FORBIDDEN`) & report issuance | `S07`, `UX01` | `IMPLEMENTED` | `AssessmentEngineService.java` + [assess.html](../../services/experience-ui/src/main/resources/templates/assess.html) + [assess-report.html](../../services/experience-ui/src/main/resources/templates/assess-report.html) |
| **FR-CTL-001..002** | Versioned SLI/SLO/RTO/RPO + Appendix I Core rules (`NORMAL..CRITICAL`, fail-closed `UNKNOWN`) | `S05` (`objectives-service`) | `IMPLEMENTED` | [CoreRuleEvaluator.java](../../services/objectives-service/src/main/java/io/uacrf/objectives/service/CoreRuleEvaluator.java) |
| **FR-POL-001..002** | Live OPA Rego 4-branch evaluator (`DENY`/`RECOMMEND`/`APPROVAL_REQUIRED`/`PREAUTHORIZED`) & two-person approval | `S10`, `policies/` | `IMPLEMENTED` | [PolicyServiceIntegrationTest.java](../../services/policy-service/src/test/java/io/uacrf/policy/PolicyServiceIntegrationTest.java) verified against live `openpolicyagent/opa:1.0.0` container |
| **FR-REC-001..003** | Temporal Java SDK durable recovery workflow, multi-cloud & K8s adapters, kill switch & compensation | `S11` (`recovery-service`) | `IMPLEMENTED` | [RecoveryWorkflowImpl.java](../../services/recovery-service/src/main/java/io/uacrf/recovery/temporal/RecoveryWorkflowImpl.java), [CloudTrafficFailoverAdapter.java](../../services/recovery-service/src/main/java/io/uacrf/recovery/adapter/CloudTrafficFailoverAdapter.java) & [TemporalRecoveryWorkflowTest.java](../../services/recovery-service/src/test/java/io/uacrf/recovery/TemporalRecoveryWorkflowTest.java) |
| **FR-LRN-001..002** | Closed-loop postmortem learning items blocking closure without verified artifact digest | `S12` (`knowledge-service`) | `IMPLEMENTED` | `409 VERIFICATION_REQUIRED` enforced in `KnowledgeGovernanceService.java` |
| **FR-EXP-001** | Controlled fault experiments with blast-radius cap & auto-abort SLO threshold | `S13` (`knowledge-service`) | `IMPLEMENTED` | `ABORTED_BY_GUARDRAIL` verified in `KnowledgeServiceIntegrationTest` |
| **FR-KNO-001..004** | Governed knowledge quarantine, independent review & SHA-256 citation digests | `S08` (`knowledge-service`) | `IMPLEMENTED` | Verified in `KnowledgeServiceIntegrationTest` |
| **FR-ADV-001..005** | Solution-Specific Agent Advisors, prompt-injection & SSRF firewall, PII scrubbing, budget caps & Critic Dissent | `S09` (`advisory-service`) | `IMPLEMENTED / CONFIG_GATED_EXTERNAL` | [EnterpriseLlmGateway.java](../../services/advisory-service/src/main/java/io/uacrf/advisory/service/EnterpriseLlmGateway.java) + [AdvisoryServiceIntegrationTest.java](../../services/advisory-service/src/test/java/io/uacrf/advisory/AdvisoryServiceIntegrationTest.java) |
| **FR-QNT-001** | Paper §10.5 Shadow Model ($r(t)$, $S(t)$, $RCI$, $ER$) & Ablation Benchmarks | `knowledge-service`, `UX01` | `IMPLEMENTED` | [QuantitativeShadowEngine.java](../../services/knowledge-service/src/main/java/io/uacrf/knowledge/service/QuantitativeShadowEngine.java) + [knowledge.html](../../services/experience-ui/src/main/resources/templates/knowledge.html) |
| **FR-REP-001..003** | `S14` Commercial Metering, Multi-Framework Crosswalk & Watermarked External Assurance Rooms | `reporting-service`, `EDGE01`, `UX01` | `IMPLEMENTED` | [ComplianceAssuranceService.java](../../services/reporting-service/src/main/java/io/uacrf/reporting/service/ComplianceAssuranceService.java), [assurance.html](../../services/experience-ui/src/main/resources/templates/assurance.html) & `ReportingServiceIntegrationTest` |

---

## 2. Core Patents & Cryptographic Guarantees (`P1`–`P7`)

| ID | Mechanism | Status | Verification / Remaining Gap |
|---|---|---|---|
| **P1** | RS256 JWS `DecisionEnvelope` sealing & tamper-detecting replay | `IMPLEMENTED` | Verified in `AssessmentServiceIntegrationTest`; FIPS 140-3 / KMS abstraction added via [FipsCryptoProfile.java](../../libs/uacrf-crypto/src/main/java/io/uacrf/crypto/FipsCryptoProfile.java) |
| **P2** | Pre-execution `MaterialGraphDeltaClassifier` invalidating widening/target changes | `IMPLEMENTED` | Verified (`409 MATERIAL_GRAPH_DELTA_INVALIDATED`) in `PolicyServiceIntegrationTest` |
| **P3** | Short-lived, audience-bound, single-use `BoundedRecoveryAuthorization` & nonce ledger | `IMPLEMENTED` | Enforced across `S10` & `S11` (`TARGET_SUBSTITUTION_FORBIDDEN`, `NONCE_REPLAY_DETECTED`) |
| **P4** | Deterministic evidence qualification (`QUALIFIED` / `PROVISIONAL` / `UNKNOWN` / `DISQUALIFIED`) | `IMPLEMENTED` | Verified in `EvidenceServiceIntegrationTest` |
| **P5** | Correlation-Domain Taxonomy (`REGION`/`IDP`/`KMS_KEY`/`DNS`/etc.) independence check | `IMPLEMENTED` | Verified (`NOT_INDEPENDENT`) in `AssessmentServiceIntegrationTest` |
| **P6** | Dependency-ordered 8-layer Cross-Layer Validation DAG with bounded compensation | `IMPLEMENTED` | Verified via Spring (`RecoveryServiceIntegrationTest`) & durable Temporal workflows (`TemporalRecoveryWorkflowTest`) |
| **P7** | Permutation-invariant `DeterministicArbiter` with non-suppressible Critic Dissent | `IMPLEMENTED` | Verified across forward/reverse permutations in `AdvisoryServiceIntegrationTest` |

---

## 3. Non-Functional Infrastructure & Security Architecture

| ID | Requirement | Status | Implementation Evidence |
|---|---|---|---|
| **NFR-OUTBOX** | Atomic PostgreSQL transactional outbox + live Kafka/Redpanda relay | `IMPLEMENTED` | [OutboxKafkaRelay.java](../../libs/uacrf-outbox/src/main/java/io/uacrf/outbox/OutboxKafkaRelay.java) verified against live `confluentinc/cp-kafka` + Postgres containers |
| **NFR-TENANT** | Multi-tenant + environment isolation enforced at DB kernel level (`FORCE ROW LEVEL SECURITY`) | `IMPLEMENTED` | `V2__enable_rls.sql` across all `12` DB services + [TenantRlsSession.java](../../libs/uacrf-outbox/src/main/java/io/uacrf/outbox/TenantRlsSession.java) + non-superuser RLS proof in `IdentityServiceIntegrationTest` |
| **NFR-UI-E2E** | Complete Thymeleaf + Bootstrap 5.3 + Cytoscape UI (`19` templates incl. `/assurance`) | `IMPLEMENTED` | `MockMvc` WebMVC verification across all portal routes in `ExperienceUiControllerTest` |
| **NFR-CRYPTO** | FIPS 140-3 key strength enforcement (`RSA >= 3072`) & Vault/Cloud KMS abstraction | `IMPLEMENTED` | [FipsCryptoProfile.java](../../libs/uacrf-crypto/src/main/java/io/uacrf/crypto/FipsCryptoProfile.java) (`CanonicalJsonAndSignerTest`) |
| **NFR-IAC** | Multi-stage non-root Dockerfiles, Helm K8s (`NetworkPolicy` + Istio `STRICT` mTLS), Multi-Cloud Terraform & PITR scripts | `IMPLEMENTED` | [Dockerfile.service](../../Dockerfile.service), [infra/helm/uacrf](../../infra/helm/uacrf/values.yaml), [infra/terraform/main.tf](../../infra/terraform/main.tf), [backup-and-pitr.sh](../../infra/scripts/backup-and-pitr.sh) |

---

## 4. Enterprise Regulatory & Compliance Standards Tracker

| Reg ID | Standard / Mandate | Technical Status | UACRF Technical Implementation | Phase E4 External Sign-Off Remaining |
|---|---|---|---|---|
| **REG-SOC2** | **SOC 2 Type I & Type II** (`CC1`–`CC9`) | `IMPLEMENTED` *(technical)* | PostgreSQL `FORCE RLS`, OAuth2/JWT + CSP, S3 WORM Object Lock, `S14` Assurance Rooms, `collect-soc2-evidence.sh`, SSDF CI gates | Corporate policies (IRP/HR/Vendor) & `3–6 mo` CPA observation window (`E4`) |
| **REG-ISO27001** | **ISO/IEC 27001 / 27017 / 27018** | `IMPLEMENTED` *(technical)* | Microservice & DB isolation, Istio mTLS + K8s `NetworkPolicy`, KMS encryption/FIPS, secret & vulnerability scanning | Formal ISMS audit Stage 1 & Stage 2 (`E4`) |
| **REG-ISO22301** | **ISO/IEC 22301 (BCM)** | `IMPLEMENTED` *(technical)* | Customer journeys, RTO/RPO, `P5` independence tests, `S13` drills, Temporal `S11` recovery validation, PITR backup scripts | Annual enterprise BCDR tabletop sign-off (`E4`) |
| **REG-GDPR** | **GDPR / CCPA-CPRA / ISO 27701** | `IMPLEMENTED` *(technical)* | Prohibited raw PII keys in Neo4j + `PiiPhiScrubber` pre-storage redaction (`S03`/`S09`) + sovereign multi-cloud Terraform regions | Organizational DPIA & DPA registration (`E4`) |
| **REG-DORA** | **EU DORA (Financial ICT Resilience)** | `IMPLEMENTED` *(technical)* | ICT topology (`S06`), path independence (`S07`), TLPT/fault drills (`S13`), Temporal recovery, live `S14` DORA crosswalk & Assurance Rooms | Financial-entity ICT register filing (`E4`) |
| **REG-PRA** | **UK PRA / FCA / FFIEC / NYDFS 23 NYCRR 500** | `IMPLEMENTED` *(technical)* | Journey impact tolerances (`S02`/`S05`), dual approvals (`S01`/`S10`), OAuth2 JWT + immutable WORM logs (`S04`/`S14`) | Annual executive compliance certification (`E4`) |
| **REG-PCI** | **PCI DSS v4.0** | `IMPLEMENTED / CONFIG_GATED_EXTERNAL` | `PiiPhiScrubber` PAN masking (`[REDACTED_PCI_PAN]`), default-deny `NetworkPolicy`, FIPS crypto, Trivy/Gitleaks CI gates | QSA ROC / ASV network scan on live CDE deployment (`E4`) |
| **REG-ISO42001** | **ISO/IEC 42001 (AIMS)** | `IMPLEMENTED` *(technical)* | AI 6-aspect empirical checks (`S04`), SSRF/PII-scrubbed LLM SPI (`S09`), blocking Critic Dissent, `S14` ISO 42001 crosswalk | Accredited AIMS certification audit (`E4`) |
| **REG-NIST-AI** | **NIST AI RMF 1.0 & EU AI Act** | `IMPLEMENTED` *(technical)* | Human-in-the-loop gates (`S10`/`S09`), prompt-injection firewall, deterministic P7 arbiter, non-fabrication guarantees | Conformity assessment filing (`E4`) |
| **REG-HIPAA** | **HIPAA / HITECH / HITRUST CSF** | `IMPLEMENTED` *(technical)* | `PiiPhiScrubber` pre-storage SSN/email/secret redaction (`S03`/`S09`), encrypted WORM S3 Object Lock (`S04`), strict TLS/mTLS | Executed cloud BAA & HITRUST r2/i1 assessment (`E4`) |
| **REG-FEDRAMP** | **FedRAMP (NIST 800-53 Rev 5) / FIPS 140-3** | `IMPLEMENTED` *(technical)* | [FipsCryptoProfile.java](../../libs/uacrf-crypto/src/main/java/io/uacrf/crypto/FipsCryptoProfile.java) (`RSA >= 3072`), OPA fail-closed (`S10`), `S14` FedRAMP crosswalk, CycloneDX SBOM | Agency ATO, SSP/OSCAL package & 3PAO audit (`E4`) |
| **REG-NIS2** | **EU NIS2 & NERC CIP** | `IMPLEMENTED` *(technical)* | Zero standing rights (`P3` single-use tokens), change gates (`S10`), Helm air-gappable non-root deployment | National authority reporting workflow (`E4`) |
| **REG-SSDF** | **NIST SP 800-218 (SSDF) & SLSA** | `IMPLEMENTED` | [.github/workflows/ci.yml](../../.github/workflows/ci.yml): Gitleaks + Trivy + CodeQL SAST + CycloneDX SBOM + Cosign attestation | Continuous branch protection enforcement in GitHub org (`E4`) |
