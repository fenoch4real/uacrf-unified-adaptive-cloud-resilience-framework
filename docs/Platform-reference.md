# UACRF platform reference

This document holds the repository module map, commercial catalog and requirements traceability summary, multi-cloud inventory status, and architecture documentation index referenced from the root [readme](../README.md).

---

## Repository and module map

| Module / Directory                            | Port   | Ownership / Role                                                                                                                                                                                 |
|-----------------------------------------------|--------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `libs/uacrf-contracts`                        | —      | Shared enums, `CanonicalEvidenceEvent`, `BoundedRecoveryAuthorization`, 58-label/55-relationship `Neo4jOntology` validator                                                                       |
| `libs/uacrf-crypto`                           | —      | Deterministic RFC 8785 JSON canonicalization, SHA-256 digests, RS256 JWS `EnvelopeSigner`, FIPS 140-3 / KMS profile                                                                              |
| `libs/uacrf-outbox`                           | —      | Transactional outbox records, lease fencing, Kafka `OutboxKafkaRelay`, PostgreSQL `TenantRlsSession`                                                                                             |
| `services/identity-service` (`S01`)           | `8082` | Tenant/environment isolation, principals, independent two-person grants, 30s RS256 delegation tokens                                                                                             |
| `services/catalog-service` (`S02`)            | `8083` | Conventional services, Protected Agent Workloads, shared resources, customer journeys & impact profiles                                                                                          |
| `services/discovery-service` (`S03`)          | `8084` | Multi-cloud/OTel `TelemetryAdapter` SPI & `SAMPLE_LOCAL` fixture generator                                                                                                                       |
| `services/evidence-service` (`S04`)           | `8085` | Deterministic `QualificationEngine`, WORM evidence blobs, AI dependency checks, draft/final export bundles                                                                                       |
| `services/graph-service` (`S06`)              | `8086` | Sole Neo4j 5.26 writer, ontology enforcement, bounded traversals, owner edge confirmation, revision digests                                                                                      |
| `services/assessment-service` (`S07`)         | `8087` | Correlation-domain path certification (`P5`), journey resilience assessments, independent review, sealed `DecisionEnvelope`s (`P1`)                                                              |
| `services/objectives-service` (`S05`)         | `8088` | SLI/SLO/RTO/RPO objectives, Appendix I Core rule engine, continuous `RiskState` evaluations                                                                                                      |
| `services/policy-service` (`S10`)             | `8089` | OPA Rego evaluator, two-person recovery approvals, `P2` graph delta classifier, `P3` RS256 single-use authorizations                                                                             |
| `services/recovery-service` (`S11`)           | `8090` | Recovery orchestrator, allowlisted `ProviderActionAdapter` SPI (`P3`), toggle-gated `GcpLiveRecoveryGateway` (`DRY_RUN_SIMULATED` default), 8-layer `CrossLayerValidator` (`P6`), kill switch |
| `services/knowledge-service` (`S08/S12/S13`)  | `8091` | Governed knowledge base, closed-loop learning gate (`FR-LRN-002`), guarded fault experiments, Section 10.5 quantitative shadow model & benchmarks                                                 |
| `services/advisory-service` (`S09`)           | `8092` | Solution-specific agent advisors (`Evidence Assistant`, `Critic`), OpenAI/Bedrock/Azure/Vertex (`GcpVertexCredentialResolver` OAuth2/ADC)/OCI LLM SPI, agent runtime ledgers, `P7` arbitration   |
| `services/reporting-service` (`S14`)          | `8093` | Commercial asset metering, FinOps quotas, pilot locking, multi-framework crosswalk, watermarked External Assurance Rooms                                                                         |
| `services/edge-gateway` (`EDGE01`)            | `8080` | Public API gateway forwarding to backend owners, OAuth2 Bearer JWT + CSP headers, `Cache-Control: no-store`, concealing internal delegation routes                                               |
| `services/experience-ui` (`UX01`)             | `8081` | Spring Boot + Thymeleaf + Bootstrap 5.3 + Cytoscape.js web portal (`23` page templates incl. `/compute`, `/models`, `/orchestration`, `/assurance`, `/settings/admin-suite`)                     |
| `contracts/`, `infra/`, `policies/`           | —      | OpenAPI 3.1, AsyncAPI 3.0, Neo4j constraints/catalog, Helm + K8s/Istio mTLS, multi-cloud Terraform, PITR & SOC 2 scripts, OPA baseline Rego                                                      |

---

## Full technical approach requirements and product catalog SKUs (Rev 23)

Everything in `UACRF_Technical_Approach_v1.0_rev23` (`7` Commercial Package SKUs, `7` Metering Units, `4` Deployment Editions, `103` Functional Requirements, `28` Non-Functional Requirements, `14` Failure Scenarios `F01–F14`, `7` Core Patents `P1–P7`, and `13` Regulatory Mandates) is mapped 1-to-1 row-by-row in [Requirements traceability matrix](traceability/REQUIREMENTS_TRACEABILITY_MATRIX.md) and machine-readable [requirements_traceability.json](../contracts/traceability/requirements_traceability.json).

### Product and commercial catalog SKUs (Section 25 and Section 32)

| Catalog SKU & Package              | Commercial Offer                              | Code Status              | What Is Implemented                                                                                                                                        | What Is Left                                                                                  |
|------------------------------------|-----------------------------------------------|--------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `SKU-ASSESS` · Assess              | *UACRF Assess (Critical Journey Assessment)*  | `IMPLEMENTED`            | S02, S03, S04, S06, S07, `/journeys`, `/assess`, `/assess/{id}/report` verified via Maven                                                                  | Live cloud collector credentials (AWS / GCP / Azure / OCI) at customer onboarding             |
| `SKU-CONTROL` · Control            | *UACRF Control (Foundation Subscription)*     | `IMPLEMENTED`            | S05, S10, `/control`, Section 8 multi-layer rules, `ObjectiveImportService` conflict preview                                                               | Live OTel collector streaming soak run against customer production traffic                    |
| `SKU-RECOVER` · Recover            | *UACRF Recover (Add-On)*                      | `IMPLEMENTED`            | S10, S11, `BreakGlassService`, `MaterialGraphDeltaClassifier`, Kubernetes & multi-cloud adapters, toggle-gated `GcpLiveRecoveryGateway` (`/recover`)       | Live cloud IAM execution roles and live AWS FIS / Azure Chaos / GCP Fault Injection mutations |
| `SKU-EVIDENCE` · Evidence          | *UACRF Evidence (Add-On)*                     | `IMPLEMENTED`            | S04, S14, `ObjectLockWormClient`, `ComplianceAssuranceService`, `/assurance`, `collect-soc2-evidence.sh`                                                   | Live WORM S3/GCS/Blob/OCI Vault credentials and external CPA walkthrough in Phase E4          |
| `SKU-ADAPTIVE` · Adaptive          | *UACRF Adaptive Intelligence (Add-On)*        | `IMPLEMENTED`            | S08/S13 `QuantitativeShadowEngine`, `MlPromotionService`, S09 `EnterpriseLlmGateway` + `GcpVertexCredentialResolver` (Vertex OAuth2/ADC), `/advisory`      | Customer LLM keys / enabling Vertex AI API in GCP & offline calibration datasets              |
| `SKU-ENTERPRISE` · Enterprise      | *UACRF Enterprise Platform Suite*             | `IMPLEMENTED`            | `EDGE01` JWT/CSP, `V2__enable_rls.sql` across all 12 DBs, `FipsCryptoProfile`, Helm + Istio STRICT mTLS, Terraform, CI gates                               | Customer Kubernetes deployment, hardware HSM/KMS key binding & GitHub branch protection       |
| `SKU-SERVICES` · Managed Services  | *Services & Managed Operations*               | `PARTIAL / CONFIG_GATED` | Pilot locking, onboarding lead time & time-to-first-outcome metrics tracked in S14 (`CommercialFinOpsService`) + `/settings/admin-suite`                  | Organizational delivery workflows, CRM/ERP invoicing integration & support SLA contracts      |

### Metering SKUs (32.1) and deployment editions (32.2)

| SKU / Edition Identifier                    | Specification Definition                                      | Code Status              | What Is Left                                                            |
|---------------------------------------------|---------------------------------------------------------------|--------------------------|-------------------------------------------------------------------------|
| `MTR-SVC` · Protected service               | `PROTECTED_SERVICE_ASSET` count                               | `IMPLEMENTED`            | Automated nightly sync exporting S02 active services to billing         |
| `MTR-CON` · Provider connection             | Provider connection (`AWS`, `GCP`, `AZURE`, `OCI`, `OTEL`)    | `IMPLEMENTED`            | Live cloud billing-tag reconciliation across customer accounts          |
| `MTR-ASS` · Assessment unit                 | Journey resilience assessment unit                            | `IMPLEMENTED`            | Automated contract-amendment lock UI when assessment scope expands      |
| `MTR-WFL` · Recovery workflow               | Certified recovery workflow execution                         | `IMPLEMENTED`            | Live production billing invoice webhook trigger                         |
| `MTR-RET` · Evidence retention              | Evidence WORM retention volume / tier                         | `IMPLEMENTED`            | Scheduled S3 byte-size storage cron scanner                             |
| `MTR-ARM` · Assurance Room                  | External Assurance Room access grant                          | `IMPLEMENTED`            | External auditor email / magic-link notification SMTP delivery          |
| `MTR-INT` · Adaptive intelligence           | Advanced / Adaptive intelligence & token quota                | `IMPLEMENTED`            | Real-time provider LLM invoice reconciliation feed                      |
| `ED-SAAS-MT` · Multi-tenant SaaS            | Multi-tenant SaaS with PostgreSQL RLS isolation               | `IMPLEMENTED`            | Production cluster deployment using `infra/helm/uacrf`                  |
| `ED-SAAS-DED` · Dedicated SaaS              | Dedicated SaaS / isolated tenant stack                        | `IMPLEMENTED`            | Customer-dedicated DNS & ingress certificate provisioning               |
| `ED-CUST-CLOUD` · Customer cloud            | Customer cloud deployment (`GCP`, `AWS`, `Azure`, `OCI`)      | `IMPLEMENTED`            | Running `terraform apply` and `helm upgrade` in target customer account |
| `ED-REG-BOUNDARY` · Regulated boundary      | Separated regulated boundary (FIPS 140-3 / FedRAMP / air-gap) | `PARTIAL / CONFIG_GATED` | Phase E4 3PAO assessment, full SSP OSCAL package & hardware HSM FIPS    |

### Complete requirements rollup (103 FRs + 28 NFRs = 131 total requirements)

> Exact codebase accounting: `96/103` FRs `IMPLEMENTED` (`7` `PARTIAL / CONFIG_GATED`), `22/28` NFRs `IMPLEMENTED` (`5` `PARTIAL / CONFIG_GATED`, `1` `NOT_IMPLEMENTED / EXTERNAL_ORG`: `NFR-OPS-002`). See all `131` individual rows in [Requirements traceability matrix](traceability/REQUIREMENTS_TRACEABILITY_MATRIX.md).

| Requirement IDs (Count)           | Area / Section              | Status                                     | What Is Left                                                                                            |
|-----------------------------------|-----------------------------|--------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `FR-CAT-001 .. 003` (`3`)         | Catalog                     | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-GPH-001 .. 010` (`10`)        | Dependency graph            | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-ING-001 .. 004` (`4`)         | Telemetry                   | `IMPLEMENTED (3) / PARTIAL (1)`            | Multi-partition Kafka streaming window-join tuning under certified production load                      |
| `FR-RUL-001 .. 003` (`3`)         | Rules                       | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-RSK-001 .. 002` (`2`)         | Risk state                  | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-POL-001 .. 002` (`2`)         | Policy                      | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-APR-001 .. 002` (`2`)         | Approval                    | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-WFL-001 .. 002` (`2`)         | Workflow                    | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-VAL-001 .. 002` (`2`)         | Validation                  | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-EVD-001 .. 002` (`2`)         | Evidence                    | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-LRN-001 .. 002` (`2`)         | Learning                    | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-ONB-001 .. 002` (`2`)         | Onboarding                  | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-PRD-001 .. 010` (`10`)        | Product scope               | `IMPLEMENTED (6) / PARTIAL (4)`            | External CRM/ERP opportunity, RACI onboarding, QVR renewal, and margin ledger sync                      |
| `FR-TST-001 .. 002` (`2`)         | Testing                     | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-UI-001 .. 005` (`5`)          | Portal and API              | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-QNT-001 .. 002` (`2`)         | Quantitative                | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-ADP-001 .. 002` (`2`)         | Adaptive                    | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-AGT-001 .. 010` (`10`)        | Agent case context          | `IMPLEMENTED (9) / PARTIAL (1)`            | Automated CI benchmark harness executing curated adversarial/factuality golden LLM datasets             |
| `FR-IP-001 .. 007` (`7`)          | Decision reconstruction     | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-KNO-001 .. 006` (`6`)         | Knowledge                   | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-ORC-001 .. 006` (`6`)         | Agent runtime               | `IMPLEMENTED (5) / PARTIAL (1)`            | Automated regression suite re-running saved agent scenarios on prompt/model bump in CI                  |
| `FR-AWL-001 .. 012` (`12`)        | Protected agents            | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-JNY-001 .. 004` (`4`)         | Journeys                    | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `FR-DEP-001` (`1`)                | Deployment                  | `IMPLEMENTED`                              | Code complete; live cloud credentials / target wiring only at deploy time                               |
| `NFR-AVL-001 .. OPS-002` (`28`)   | Non-Functional (§23.4)      | `IMPLEMENTED (22) / PARTIAL (5) / ORG (1)` | Cluster 99.9% SLA, PITR restore drill, k6 load report, assistive AT audit & org owners/SLAs             |
| `F01 .. 14` (`14`)                | Failure scenarios (§App. F) | `IMPLEMENTED (14/14)`                      | Live chaos drills against customer AWS FIS / Azure Chaos / GCP Fault Injection accounts                 |
| `REG-SOC2 .. SSDF` (`13`)         | Regulatory mandates         | `SSDF (1) / TECH READY (12)`               | `Phase E4` corporate ISMS/IRP policies, BAAs & external CPA / 3PAO / registrar certification            |

### Cloud catalog and multi-cloud resource inventory (58 node labels, 20 resource families)

| Inventory Layer & Source Scope                | Coverage Scope                                                                                   | Code Status                       | What Is Implemented                                                                                                                                                  | What Is Left                                                                                |
|-----------------------------------------------|--------------------------------------------------------------------------------------------------|-----------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| Canonical Neo4j Inventory Ontology            | `58` Node Labels (`10` domain groups) + `55` Relationships (`Section 5.2.7` / `Appendix M`)      | `IMPLEMENTED`                     | Enforced in `Neo4jOntology`, `ontology_catalog.json`, `GraphServiceIntegrationTest`                                                                                  | Live continuous cloud topology refresh crons                                                |
| PostgreSQL Catalog & Compute Inventory (`S02`)| `service`, `agent_workload_profile`, `customer_journey`, `compute_placement`, `provider_resource_alias` | `IMPLEMENTED`                     | Flyway `V1`–`V3` + `ComputeAndAliasService` (`GCP`/`AWS`/`AZURE`/`OCI`/`K8S`) + `CatalogServiceIntegrationTest`                                                      | External CMDB / HR directory auto-sync                                                      |
| Multi-Cloud Resource Poll & Telemetry (`S03`) | All `20` infrastructure, storage, DB, queue, network, IAM & AI families across `AWS`, `Azure`, `GCP`, `OCI` | `GCP LIVE VERIFIED / OTHERS STANDBY` | `CloudInventoryPollerGateway`, `GcpCredentialProvider` & `GcpRuntimeTelemetryPoller` (verified live on `uacrf-sandbox-123`), `/settings/connections` UI, JUnit tests | Live `AWS` SigV4 / `OCI` HTTP signing / `Azure` Entra token exchange + CloudWatch / Monitor |

---

## Requirements tracking, enterprise compliance, and documentation

- Full requirements and implementation status tracker: [Requirements traceability matrix](traceability/REQUIREMENTS_TRACEABILITY_MATRIX.md)
- Updated technical approach and regulatory scope (Rev 23): [Technical approach and compliance spec](architecture/TECHNICAL_APPROACH_AND_COMPLIANCE_SPEC.md)
- Enterprise compliance and certification progression (E1–E4): [Enterprise compliance and roadmap](compliance/ENTERPRISE_COMPLIANCE_AND_ROADMAP.md)
- Architectural decision records (ADR-001 to ADR-005): [ADR-001](adr/ADR-001-polyglot-authority.md) · [ADR-002](adr/ADR-002-advice-not-authority.md) · [ADR-003](adr/ADR-003-multicloud-spi-isolation.md) · [ADR-004](adr/ADR-004-zero-trust-edge-and-rls.md) · [ADR-005](adr/ADR-005-common-controls-fips-assurance.md)
- Implemented class diagrams (CD-01 to CD-06): [Class diagrams](diagrams/CLASS_DIAGRAMS.md)
- End-to-end sequence and activity diagrams: [Sequence diagrams](diagrams/SEQUENCE_DIAGRAMS.md) | [Activity diagrams](diagrams/ACTIVITY_DIAGRAMS.md)
- System overview and target multi-cloud infrastructure: [System overview](architecture/SYSTEM_OVERVIEW.md) | [Local and target infrastructure](deployment/LOCAL_AND_TARGET_INFRA.md)
