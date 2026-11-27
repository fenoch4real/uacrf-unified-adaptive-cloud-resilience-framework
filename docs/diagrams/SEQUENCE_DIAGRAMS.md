# UACRF Sequence Diagrams (End-to-End Architecture & Subsystem Flows)

> Every sequence diagram below maps directly to the implemented microservices (`S01`–`S13`), `EDGE01` gateway routes, `UX01` Thymeleaf views, and cryptographic/safety invariants (`P1`–`P7`).

---

## SD-01: End-to-End UACRF Closed-Loop Resilience Flow (`S03` -> `S12`)
*Traces a full multi-service incident loop from telemetry ingestion and graph projection through SLO evaluation, OPA approval, bounded execution, 8-layer validation, and verified learning closure.*

```mermaid
sequenceDiagram
    actor Operator as SRE / Service Owner
    participant UX as UX01 Portal (:8081)
    participant EDGE as EDGE01 Gateway (:8080)
    participant S03 as S03 Discovery (:8084)
    participant S04 as S04 Evidence (:8085)
    participant S06 as S06 Graph / Neo4j (:8086)
    participant S05 as S05 Objectives/Rules (:8088)
    participant S10 as S10 OPA/Authority (:8089)
    participant S11 as S11 Recovery/Validation (:8090)
    participant S12 as S08/S12 Learning (:8091)

    Operator->>UX: Trigger telemetry intake / view service health
    UX->>EDGE: POST /v1/telemetry/sample-scenario (or live OTel event)
    EDGE->>S03: Forward with X-UACRF-Actor-Id
    S03->>S04: Emit normalized telemetry & observations
    S03->>S06: Project ontology-checked dependency edges to Neo4j 5.26
    S06-->>UX: Return graphRevision + Cytoscape topology (CONFIRMED & UNCERTAIN edges)

    S05->>S05: Evaluate Appendix I Core rules -> RiskState = ELEVATED
    Operator->>EDGE: Create RecoveryProposal (Rollback Checkout v43 -> v42)
    EDGE->>S10: POST /v1/policy/proposals -> OPA Rego = APPROVAL_REQUIRED
    Operator->>S10: Distinct approver calls POST /v1/policy/proposals/{id}/approve
    Operator->>S10: POST /v1/policy/proposals/{id}/authorizations (P2 Delta Check)
    S10-->>S11: Signed RS256 BoundedRecoveryAuthorization (single-use nonce)

    S11->>S11: Verify bounds, consume nonce, execute KubernetesLocalRollbackAdapter
    S11->>S11: Run P6 8-Layer Validation DAG (INFRA -> JOURNEY_PROBE)
    S11-->>S12: Execution SUCCEEDED -> require LearningItem verification closure
    S12->>S12: Close LearningItem only after verified SHA-256 artifact attached
```

---

## SD-02: Two-Person Grant Approval & Principal Isolation (`S01`)
*Shows strict separation of duties between principal creation, independent human approval, and atomic single-use membership application behind `EDGE01`.*

```mermaid
sequenceDiagram
    actor Admin as Tenant Admin
    actor Approver as Independent Approver
    actor Manager as Membership Manager
    participant UX as UX01 Customer Admin
    participant EDGE as EDGE01 Gateway
    participant S01 as S01 Identity Service (:8082)
    participant PG as PostgreSQL (uacrf_identity)

    Note over UX,EDGE: EDGE01 blocks /v1/delegations/exchange from external ingress (404)

    Admin->>UX: Admit principal (HUMAN / SYSTEM)
    UX->>EDGE: POST /v1/principals (Idempotency-Key)
    EDGE->>S01: Forward request
    S01->>PG: Insert principal (zero runtime memberships) + outbox
    S01-->>UX: 201 Created

    Approver->>UX: Grant approval decision for target principal
    UX->>EDGE: POST /v1/grants/decisions
    EDGE->>S01: Forward with X-UACRF-Actor-Id
    alt approverId == targetPrincipalId
        S01-->>UX: 403 SELF_APPROVAL_PROHIBITED
    else distinct HUMAN approver
        S01->>PG: Store single-use grant_decision with expiresAt
        S01-->>UX: 201 Created (grantDecisionId)
    end

    Manager->>UX: Apply approved membership
    UX->>EDGE: POST /v1/memberships
    EDGE->>S01: Forward request
    alt managerId == approverId OR managerId == recipientId
        S01-->>UX: 403 SEPARATION_OF_DUTIES_VIOLATION
    else independent manager & unexpired decision
        S01->>PG: Consume grant_decision + insert membership + audit + outbox
        S01-->>UX: 201 Created
    end
```

---

## SD-03: Telemetry Adapter SPI, `SAMPLE_LOCAL` Fixtures & `No-Synthetic-Fallback` Evidence Qualification (`S03`, `S04`)
*Contrasts isolated lab fixtures (`SAMPLE_LOCAL`) with empirical evidence qualification (`QualificationEngine`), blocking non-empirical substitutes and locking proof into WORM storage.*

```mermaid
sequenceDiagram
    actor Owner as Service Owner
    participant UX as UX01 Connections / Evidence
    participant EDGE as EDGE01 Gateway
    participant S03 as S03 Discovery (:8084)
    participant S04 as S04 Evidence (:8085)
    participant QE as QualificationEngine
    participant BLOB as WORM Object Store

    Owner->>UX: Load SAMPLE_LOCAL Multi-Cloud & AI Fixture
    UX->>EDGE: POST /v1/telemetry/sample-scenario
    EDGE->>S03: Forward
    S03->>S03: SampleLocalScenarioGenerator stamps SAMPLE_LOCAL / SAMPLE_ONLY
    S03-->>UX: 6 multi-cloud/AI observations + 12 ontology edges

    Owner->>UX: Submit missing evidence item on Evidence Package
    UX->>EDGE: POST /v1/evidence/packages/{id}/items
    EDGE->>S04: Forward with X-UACRF-Actor-Id
    S04->>QE: qualify(itemKey, sourceKind, graphRevision, ttlSeconds, payload)

    alt sourceKind in KNOWLEDGE_DOC, ADVISORY_DRAFT, RUNBOOK, LLM_SUMMARY
        QE-->>S04: Reject (No-Synthetic-Fallback invariant)
        S04-->>UX: 422 SYNTHETIC_FALLBACK_FORBIDDEN (Probe stays UNKNOWN)
    else graphRevision mismatch OR ttlSeconds <= 0
        QE-->>S04: Return DISQUALIFIED (STALE / EXPIRED)
        S04-->>UX: 200 OK (Package stays MISSING_REQUIRED)
    else Empirical evidence valid & matches revision
        QE-->>S04: Return QUALIFIED + RFC 8785 SHA-256 digest
        S04->>BLOB: Write immutable object_blob (worm_locked = true)
        S04->>S04: Recompute package completeness & emit outbox event
        S04-->>UX: 200 OK (Updated EvidencePackageDto)
    end
```

---

## SD-04: Sole Neo4j 5.26 Projection (`58` Labels / `55` Relationships) & Edge Confirmation (`S06`)
*Demonstrates schema validation (`Neo4jOntology`), atomic PostgreSQL revision tracking, Neo4j `MERGE` projection, and bounded dependency subgraph retrieval.*

```mermaid
sequenceDiagram
    participant Caller as S03 Discovery / Graph Client
    participant S06 as S06 Graph Service (:8086)
    participant ONT as Neo4jOntology Validator
    participant PG as PostgreSQL (uacrf_graph)
    participant NEO as Neo4j 5.26 Driver

    Caller->>S06: POST /v1/graph/project-edges
    S06->>ONT: Validate every nodeLabel (58), relationshipType (55), and propertyKeys
    alt Unknown label / relationship OR prohibited property (e.g. secret, password)
        ONT-->>S06: Throw IllegalArgumentException
        S06-->>Caller: 422 ONTOLOGY_VIOLATION
    else Valid canonical graph batch
        S06->>PG: Upsert projected_node & projected_edge + compute SHA-256 GraphRevision
        S06->>NEO: Execute Cypher MERGE bounded by tenantId, environmentId & revision
        S06->>PG: Insert integration.outbox_event (graph.revision.published)
        S06-->>Caller: 200 OK (GraphRevisionDto + digest)
    end

    Note over Caller,S06: Read path: UX01 Cytoscape Dependencies / AI Lineage views
    Caller->>S06: GET /v1/graph/dependencies?focusCanonicalId=...&depth=2&includeUncertain=true
    S06-->>Caller: Bounded subgraph + UNCERTAIN edges + NonCausationNotice
```

---

## SD-05: Journey Resilience Assessment, Path Independence (`P5`) & RS256 Envelope Replay (`P1`, `S07`)
*Captures cross-domain failover path checks (`P5`), independent reviewer separation, RS256 JWS envelope sealing (`P1`), and cryptographic tamper verification on replay.*

```mermaid
sequenceDiagram
    actor Owner as Technical Reviewer (Maya)
    actor Indep as Independent Reviewer (Aisha)
    participant UX as UX01 Assess / Report
    participant S07 as S07 Assessment Service (:8087)
    participant CERT as PathIndependenceCertifier (P5)
    participant SIGN as EnvelopeSigner (P1 / RS256)

    Owner->>UX: Check Correlation-Domain Independence for Checkout paths
    UX->>S07: POST /v1/path-certifications
    S07->>CERT: Compare REGION, ZONE, CLUSTER, IDP, KMS_KEY, DNS, DATA_STORE
    alt Shared IDP / KMS_KEY / REGION / DNS / DATA_STORE
        CERT-->>S07: NOT_INDEPENDENT + witness sharedDomains
    else Disjoint critical domains
        CERT-->>S07: CERTIFIED (or CONDITIONALLY_CERTIFIED)
    end

    Owner->>S07: POST /v1/assessments/{id}/reviews (TECHNICAL = APPROVED)
    Indep->>S07: POST /v1/assessments/{id}/reviews (INDEPENDENT = APPROVED)
    alt actorId == assessorId OR actorId == serviceOwnerId
        S07-->>Indep: 403 SELF_REVIEW_FORBIDDEN
    else distinct independent reviewer & both approved
        S07-->>S07: Update report_status = ISSUED
    end

    Indep->>S07: POST /v1/assessments/{id}/envelopes
    S07->>SIGN: Sign RFC 8785 manifest SHA-256 with RS256 (audience = uacrf-replay-verifier)
    S07-->>UX: DecisionEnvelopeDto (envelopeId, payloadSha256, jwsToken)

    UX->>S07: POST /v1/decision-replays
    S07->>SIGN: Verify RS256 JWS & compare manifest SHA-256
    alt Tampered digest or invalid signature
        S07-->>UX: TAMPER_DETECTED
    else Matching digest & signature
        S07-->>UX: VERIFIED
    end
```

---

## SD-06: Continuous Rules (`S05`), OPA Evaluation, `P2` Graph Delta & `P3` Bounded Authorization (`S10`)
*Details rule triggering (`S05`), live OPA Rego policy gating, two-person approval, `P2` graph-delta invalidation, and short-lived single-use `P3` token issuance.*

```mermaid
sequenceDiagram
    actor Proposer as SRE Proposer (Luis)
    actor Owner as Service Owner (Maya)
    participant S05 as S05 Objectives/Rules (:8088)
    participant S10 as S10 Policy Authority (:8089)
    participant OPA as OPA / PolicyEvaluator
    participant P2 as MaterialGraphDeltaClassifier (P2)
    participant SGN as EnvelopeSigner (P3)

    S05->>S05: Evaluate APP-ERR-001 / SEC-AUTH-001 / GOV-TEL-001
    Note right of S05: Stale or missing telemetry strictly produces UNKNOWN

    Proposer->>S10: POST /v1/policy/proposals (scope=1 deployment, reversible=true)
    S10->>OPA: Evaluate policies/baseline.rego
    OPA-->>S10: APPROVAL_REQUIRED (or DENY / RECOMMEND / PREAUTHORIZED)

    Owner->>S10: POST /v1/policy/proposals/{id}/approve
    alt actorId == proposerId
        S10-->>Owner: 403 SELF_APPROVAL_FORBIDDEN
    else distinct approver
        S10-->>Owner: approvalStatus = APPROVED
    end

    Owner->>S10: POST /v1/policy/proposals/{id}/authorizations
    S10->>P2: classify(proposalRev, liveRev, proposalSubgraph, liveSubgraph, blastRadius)
    alt WIDENING / TARGET_CHANGING / FALLBACK_CHANGING / INDETERMINATE
        P2-->>S10: Invalidate authorization
        S10-->>Owner: 409 MATERIAL_GRAPH_DELTA_INVALIDATED
    else IMMATERIAL or NARROWING
        S10->>SGN: Sign BoundedRecoveryAuthorization (RS256, audience, 5m TTL, unique nonce)
        S10-->>Owner: 201 Created (jwsToken + single-use nonce)
    end
```

---

## SD-07: Recovery Execution, Allowlisted Adapters (`P3`), `P6` Cross-Layer Validation & Compensation (`S11`)
*Shows pre-execution kill-switch and nonce checks before calling bounded cloud/Kubernetes adapters and running the 8-layer P6 validation DAG with automatic saga compensation.*

```mermaid
sequenceDiagram
    actor Operator as Operator
    participant EDGE as EDGE01 Gateway
    participant S11 as S11 Recovery Service (:8090)
    participant ADPT as KubernetesLocalRollbackAdapter (P3)
    participant DAG as CrossLayerValidator (P6)

    Operator->>EDGE: POST /v1/recovery/executions (BoundedRecoveryAuthorization)
    EDGE->>S11: Forward
    S11->>S11: Check Kill Switch, expiry, tenant/env, adapter audience & target allowlist

    alt Kill Switch ACTIVE
        S11-->>Operator: 409 KILL_SWITCH_ACTIVE
    else Target outside allowlist
        S11-->>Operator: 403 TARGET_SUBSTITUTION_FORBIDDEN
    else Consume single-use nonce fails
        S11-->>Operator: 409 NONCE_REPLAY_DETECTED
    else All P3 prechecks passed
        S11->>ADPT: execute(auth, targetCanonicalId, targetVersion)
        ADPT-->>S11: Workload rolled back to target version
        S11->>DAG: Run 8-layer DAG (INFRA -> PLT -> APP -> DATA -> SEC -> AI -> SLO -> JOURNEY_PROBE)
        alt Mandatory JOURNEY_PROBE passes
            S11-->>Operator: workflowState = SUCCEEDED
        else Mandatory probe fails
            S11->>ADPT: compensate(auth, targetCanonicalId, previousVersion)
            ADPT-->>S11: Restored prior snapshot
            S11-->>Operator: workflowState = COMPENSATED
        end
    end
```

---

## SD-08: Solution-Specific Agent Advisors (`S09`), Prompt-Injection Firewall & `P7` Arbitration
*Illustrates prompt-injection filtering, token budget caps, PII scrubbing across OpenAI/Bedrock/Azure/Vertex/OCI LLMs, and permutation-invariant Critic dissent preservation.*

```mermaid
sequenceDiagram
    actor Reviewer as Human Reviewer (Daniel)
    participant UX as UX01 Advisory UI
    participant S09 as S09 Advisory Service (:8092)
    participant ARB as DeterministicArbiter (P7)
    participant S08 as S08 Knowledge (:8091)

    Reviewer->>UX: Trigger bounded advisory run on case AR-104
    UX->>S09: POST /v1/advisory/runs
    S09->>S09: Verify Advisory Kill Switch + Token Budget + Injection Patterns

    alt Advisory disabled OR prompt injection matched OR budget exhausted
        S09-->>UX: 409 ADVISORY_DISABLED / 422 PROMPT_INJECTION_BLOCKED / 429 BUDGET_EXHAUSTED
    else Clean bounded query
        S09->>S08: Retrieve approved citations (OPS-2210, ARCH-1187)
        S09->>S09: Attach Critic Dissent (BLOCKING_CONCERN / UNRESOLVED)
        S09->>ARB: arbitrate(canonically sorted proposals)
        ARB-->>S09: Permutation-invariant outcome (humanReviewRequired=true, suppressedAnyDissent=false)
        S09-->>UX: AdvisoryFindingDto + Blocking Critic Dissent
    end

    Reviewer->>S09: POST /v1/advisory/findings/{id}/review (KEEP_FOR_REVIEW / EDIT_DRAFT / REJECT_DRAFT)
    Note over S09,UX: criticStatus stays UNRESOLVED — blocking dissent is never silently cleared
```


---

## Enterprise & Regulatory Compliance Sequence Diagrams (`Phase E1–E4 Scope`)

## SD-09: Enterprise Zero-Trust OIDC Login, Delegation Exchange & PostgreSQL RLS (`REG-SOC2`, `REG-ISO27001`, `REG-PRA`)
*Traces PKCE/MFA federation at `EDGE01`, internal 30-second RS256 delegation tokens, and PostgreSQL kernel Row-Level Security enforcement via `app.tenant_id`.*

```mermaid
sequenceDiagram
    actor User as Enterprise Operator
    participant IDP as Keycloak OIDC / MFA
    participant UX as UX01 Portal
    participant EDGE as EDGE01 JWT Resource Server
    participant S01 as S01 Identity & Delegation
    participant DB as PostgreSQL (RLS Enabled)

    User->>UX: Initiate login
    UX->>IDP: OIDC Authorization Code + PKCE + MFA challenge
    IDP-->>UX: Signed ID Token & Access JWT (tenant, env, roles)
    UX->>EDGE: Request with Bearer JWT + tenant context
    EDGE->>EDGE: Verify JWT signature & issuer against JWKS
    EDGE->>S01: POST /v1/delegations/exchange (Internal-Only)
    S01-->>EDGE: Short-lived (30s) audience-bound RS256 delegation JWT
    EDGE->>DB: Forward request -> SET LOCAL app.tenant_id = :tenant
    DB-->>EDGE: RLS policy restricts rows strictly to tenant/env
```

---

## SD-10: Async Kafka Outbox Relay, PII/PHI Scrubbing & S3 Object Lock WORM (`REG-GDPR`, `REG-HIPAA`, `REG-DORA`)
*Details atomic outbox publication to Kafka/Redpanda after pre-storage PII/PHI scrubbing, immutable S3 Object Lock persistence, and Neo4j reconciliation.*

```mermaid
sequenceDiagram
    participant Cloud as AWS / GCP / Azure / OCI / OTel Collector
    participant SCRUB as PII/PHI/PAN Scrubber
    participant S03 as S03 Discovery Service
    participant RELAY as Outbox Relay Worker -> Kafka/Redpanda
    participant S04 as S04 Evidence Service
    participant S3 as MinIO / S3 Object Lock (WORM)
    participant S06 as S06 Graph Projector & Drift Scanner

    Cloud->>SCRUB: Stream raw telemetry & spans
    SCRUB->>SCRUB: Redact SSN, Credit Card, PHI & Secrets before storage
    SCRUB->>S03: Normalized anonymized observation batch
    S03->>S03: Persist observation + integration.outbox_event (Atomic TX)
    RELAY->>S03: Poll / CDC unread outbox rows -> Publish CloudEvents to Kafka
    Kafka-->>S04: Consume telemetry event
    S04->>S3: PutObject (Object Lock COMPLIANCE mode + SHA-256)
    Kafka-->>S06: Consume topology edge event
    S06->>S06: Neo4j MERGE + verify checksum vs Postgres (PROJECTED vs DRIFTED)
```

---

## SD-11: Temporal Durable Recovery Worker, Live OPA Bundle, KMS/FIPS Signing & Live Cluster (`REG-FEDRAMP`, `REG-PCI`, `REG-NERC`)
*Connects live OPA Rego policy evaluation, FIPS 140-3 / KMS envelope signing, Temporal durable activity workers, and bounded rollback/compensation.*

```mermaid
sequenceDiagram
    actor Approver as Independent Owner
    participant S10 as S10 Policy Authority
    participant OPA as Live OPA Server (baseline.rego)
    participant KMS as Vault / HSM (FIPS 140-3)
    participant TEMP as Temporal Workflow Engine
    participant S11 as S11 Recovery Activity Worker
    participant K8S as Live Kubernetes / Cloud API

    Approver->>S10: Request authorization for approved rollback
    S10->>OPA: POST /v1/data/uacrf/authz/decision
    OPA-->>S10: APPROVAL_REQUIRED + policyHash
    S10->>KMS: Sign P3 BoundedRecoveryAuthorization via FIPS 140-3 key
    S10-->>TEMP: Start RecoveryExecutionWorkflow with single-use token
    TEMP->>S11: Dispatch bounded rollback activity (with heartbeat)
    S11->>K8S: Execute exact revision rollback on allowlisted target only
    TEMP->>S11: Run P6 8-Layer Validation DAG over stability window
    alt Mandatory layer fails
        TEMP->>S11: Trigger durable Compensation Activity -> COMPENSATED
    else All layers healthy
        TEMP-->>S10: Seal final outcome & notify S12 Learning
    end
```

---

## SD-12: External Auditor Assurance Room, Regulatory Crosswalk Export (`S14`) & SSDF Provenance
*Follows CI supply-chain provenance (`CycloneDX`, `Trivy`, `CodeQL`, `Cosign`) into `S14` time-bound watermarked auditor rooms and multi-framework compliance exports.*

```mermaid
sequenceDiagram
    actor Auditor as External CPA / Regulator
    participant CI as GitHub CI (SSDF / SLSA)
    participant S14 as S14 Reporting & Assurance Rooms
    participant S07 as S07 DecisionEnvelopes (P1)
    participant S04 as S04 WORM Evidence

    CI->>CI: Generate CycloneDX SBOM, Trivy CVE scan, CodeQL & Cosign sig
    CI->>S14: Register release provenance artifact

    Auditor->>S14: Access time-bound Watermarked Assurance Room (Read-Only)
    S14->>S07: Fetch sealed DecisionEnvelopes & P5 path proofs
    S14->>S04: Fetch WORM qualification bundles & AI 6-aspect evidence
    S14-->>Auditor: Watermarked SOC 2 (CC1-CC9) / DORA / ISO 42001 / HIPAA Crosswalk Pack
```


---

## SD-13: Four-Cloud Federated Discovery, Cross-Cloud Dependency Projection & Bounded Recovery (`GCP`, `AWS`, `Azure`, `OCI`)
*Demonstrates parallel read-only collection across GCP, AWS, Azure, and OCI, WORM storage, Neo4j failover path validation, and bounded multi-cloud action dispatch.*

```mermaid
sequenceDiagram
    participant GCP as GCP (Asset Inv + GKE)
    participant AWS as AWS (Config + EKS/ECS)
    participant AZ as Azure (Resource Graph + AKS)
    participant OCI as OCI (Resource Search + OKE + Vault)
    participant S03 as S03 Multi-Cloud Discovery SPI
    participant S04 as S04 Evidence + Cloud WORM Lock
    participant S06 as S06 Neo4j + P5 Certifier
    participant S11 as S11 Recovery Orchestrator (P3/P6)

    par Federated Read-Only OIDC Discovery
        S03->>GCP: Poll Cloud Asset Inventory & GKE Monitoring
        S03->>AWS: AssumeRole -> Poll AWS Config & CloudWatch
        S03->>AZ: Entra Token -> Poll Azure Resource Graph & Monitor
        S03->>OCI: Instance/Workload Principal -> Poll OCI Resource Search & APM
    end

    S03->>S03: Normalize events + scrub PII/PHI/secrets
    S03->>S04: Qualify operational & AI evidence + lock in WORM (S3 / GCS / Azure / OCI Retention)
    S03->>S06: Project cross-cloud edges (Service -> CloudResource -> Region -> Provider -> FailureDomain)
    S06->>S06: Validate P5 independence (ensure alternate path in OCI/AWS avoids failed GCP domain)

    Note over S11,OCI: Bounded recovery executes on target cloud only after S10 approval & P2 delta check
    S11->>GCP: (Example) Roll back defective GKE deployment OR shift traffic to healthy AWS/OCI endpoint
    S11->>S11: Run P6 8-layer validation DAG & cross-cloud synthetic journey probe
```
