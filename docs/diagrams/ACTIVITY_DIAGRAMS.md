# UACRF Activity Diagrams (Decision Logic, Guardrails & Control Workflows)

> Activity diagrams detailing every deterministic gate, fail-closed branch, and closed-loop verification workflow implemented across `S01`–`S13`.

---

## AD-01: Unified UACRF Lifecycle Activity (`Assess -> Control -> Recover -> Evidence -> Learn`)
*End-to-end decision loop showing how telemetry moves from ingestion through topology projection, evidence qualification, SLO rules, policy gates, bounded recovery, and verified learning.*

```mermaid
flowchart TD
    Start([Ingest Telemetry & Topology]) --> Mode{"Telemetry Provenance?"}
    Mode -- "LIVE_ADAPTER" --> CollectLive["Collect AWS / GCP / Azure / OCI / OTel"]
    Mode -- "SAMPLE_LOCAL" --> CollectSample["Generate SAMPLE_ONLY Lab Scenario"]
    CollectLive --> Project["S06 Validate 58 Labels / 55 Rels & Project to Neo4j"]
    CollectSample --> Project
    Project --> Qualify["S04 Evidence Qualification Engine"]
    Qualify --> Assess["S07 Journey Assessment & P5 Path Independence"]
    Assess --> Control["S05 Continuous Core Rule Engine"]
    Control --> Risk{"RiskState"}
    Risk -- "NORMAL / UNKNOWN" --> Monitor["Continue Monitoring & Flag Gaps"]
    Risk -- "ELEVATED / CRITICAL" --> Proposal["S10 Create Reversible Recovery Proposal & Evaluate OPA"]
    Proposal --> Approval{"Independent Approval & P2 Delta Safe?"}
    Approval -- "No" --> Hold["Block Execution & Require Re-review"]
    Approval -- "Yes" --> Exec["S11 P3 Bounded Adapter Execution + P6 8-Layer Validation"]
    Exec --> Valid{"Mandatory Probes Passed?"}
    Valid -- "Yes" --> Learn["S12 Learning Closure Verified + Seal P1 Envelope"]
    Valid -- "No" --> Comp["Execute Bounded Compensation -> COMPENSATED"]
```

---

## AD-02: Deterministic Evidence Qualification & `No-Synthetic-Fallback` Activity (`S04`)
*Illustrates how `S04` rejects documentation or LLM summaries as proof of operational readiness, locks empirical evidence into WORM storage, and leaves gaps explicitly `UNKNOWN`.*

```mermaid
flowchart TD
    Submit([Submit Evidence Item]) --> KindCheck{"sourceKind is KNOWLEDGE_DOC, ADVISORY_DRAFT, RUNBOOK, or LLM_SUMMARY?"}
    KindCheck -- "Yes" --> Reject["Reject 422 SYNTHETIC_FALLBACK_FORBIDDEN"]
    Reject --> KeepUnknown["Keep Missing Probe UNKNOWN & Package MISSING_REQUIRED"]
    KindCheck -- "No" --> RevCheck{"Matches Active graphRevision?"}
    RevCheck -- "No" --> Stale["Mark DISQUALIFIED / STALE"]
    RevCheck -- "Yes" --> TtlCheck{"ttlSeconds > 0?"}
    TtlCheck -- "No" --> Expired["Mark DISQUALIFIED / EXPIRED"]
    TtlCheck -- "Yes" --> Canonical["Compute RFC 8785 Canonical SHA-256 Digest"]
    Canonical --> Worm["Persist WORM Object Blob (worm_locked = true)"]
    Worm --> UpdateItem["Mark Item QUALIFIED / CURRENT"]
    UpdateItem --> AllDone{"All Required Items QUALIFIED?"}
    AllDone -- "Yes" --> Complete["Package COMPLETENESS = COMPLETE / READINESS = QUALIFIED"]
    AllDone -- "No" --> Partial["Package Remains MISSING_REQUIRED / READINESS = UNKNOWN"]
```

---

## AD-03: Neo4j 58-Label Ontology Validation & Dependency Inspection (`S06`)
*Shows schema enforcement on every Neo4j write (`58` labels, `55` relationships, prohibited-key blocking) and how `UNCERTAIN` dependencies display dashed without implying causation.*

```mermaid
flowchart TD
    Ingest([Receive Topology Batch]) --> Loop{"Validate Each Edge"}
    Loop --> LabelCheck{"Source & Target in 58 Canonical NodeLabels?"}
    LabelCheck -- "No" --> Deny422["Fail Fast 422 ONTOLOGY_VIOLATION"]
    LabelCheck -- "Yes" --> RelCheck{"Relationship in 55 Canonical Types?"}
    RelCheck -- "No" --> Deny422
    RelCheck -- "Yes" --> PropCheck{"Any Prohibited Property Keys?"}
    PropCheck -- "Yes" --> Deny422
    PropCheck -- "No" --> Persist["Dual-Write Postgres + Neo4j MERGE & Publish SHA-256 Revision"]
    Persist --> QueryUI["UX01 Cytoscape Bounded BFS (depth 1..3)"]
    QueryUI --> Toggle{"Show uncertain links?"}
    Toggle -- "True" --> Dashed["Render CONFIRMED Solid + UNCERTAIN Dashed"]
    Toggle -- "False" --> Solid["Render CONFIRMED Only"]
    Dashed --> Notice["Show Mandatory Non-Causation Disclaimer"]
    Solid --> Notice
```

---

## AD-04: Correlation-Domain Path Certification (`P5`) & Report Issuance (`S07`)
*Checks whether primary and alternate failover paths share hidden single points of failure (`REGION`, `IDP`, `KMS_KEY`, `DNS`, `DATA_STORE`) and enforces dual independent human sign-off.*

```mermaid
flowchart TD
    PathStart([Inspect Primary vs Alternate Path Domains]) --> Empty{"Either Domain Map Empty?"}
    Empty -- "Yes" --> UnknownPath["Status = UNKNOWN"]
    Empty -- "No" --> Compare["Intersect REGION, ZONE, CLUSTER, IDP, KMS_KEY, DNS, DATA_STORE, VENDOR"]
    Compare --> Disjoint{"Zero Shared Domains?"}
    Disjoint -- "Yes" --> Cert["Status = CERTIFIED"]
    Disjoint -- "No" --> Critical{"Shared IDP, KMS_KEY, REGION, DNS, or DATA_STORE?"}
    Critical -- "Yes" --> NotIndep["Status = NOT_INDEPENDENT + Witness Paths"]
    Critical -- "No" --> CondCert["Status = CONDITIONALLY_CERTIFIED"]

    ReportStart([Independent Assessment Review]) --> Distinct{"Reviewer != Assessor AND Reviewer != Service Owner?"}
    Distinct -- "No" --> Forbid["Reject 403 SELF_REVIEW_FORBIDDEN"]
    Distinct -- "Yes" --> Both{"Technical & Independent Reviews APPROVED?"}
    Both -- "Yes" --> Issue["Mark Report ISSUED & Allow P1 RS256 Envelope Seal"]
    Both -- "No" --> Draft["Keep Report DRAFT_REVIEW_REQUIRED"]
```

---

## AD-05: Continuous Core Control Rule Evaluation State Machine (`S05`)
*Transitions service risk state cleanly across `UNKNOWN`, `CRITICAL`, `ELEVATED`, `GUARDED`, and `NORMAL` using multi-layer telemetry thresholds without failing open when data is stale.*

```mermaid
flowchart TD
    Eval([Evaluate Service Signals]) --> Telemetry{"Telemetry Fresh & SLI Present?"}
    Telemetry -- "No" --> StateUnknown["RiskState = UNKNOWN (Never Fail Open to NORMAL)"]
    Telemetry -- "Yes" --> Sec{"Security Alert Active OR SLI < 75%?"}
    Sec -- "Yes" --> StateCrit["RiskState = CRITICAL (SEC-AUTH-001 / APP-ERR-002)"]
    Sec -- "No" --> Elev{"SLI < 95% OR CPU > 85% OR AI Drift Flagged?"}
    Elev -- "Yes" --> StateElev["RiskState = ELEVATED (APP-ERR-001 / INF-SAT-001 / AI-SAF-001)"]
    Elev -- "No" --> Guard{"SLI < 99.5% Target?"}
    Guard -- "Yes" --> StateGuard["RiskState = GUARDED (APP-SLO-003)"]
    Guard -- "No" --> StateNorm["RiskState = NORMAL"]
```

---

## AD-06: Pre-Execution `MaterialGraphDelta` (`P2`) & Bounded Recovery Activity (`S10`, `S11`)
*Verifies kill switches, live topology deltas, token bounds, and single-use nonces prior to execution, then runs the 8-layer P6 validation DAG with automatic rollback compensation on probe failure.*

```mermaid
flowchart TD
    Req([Authorize & Execute Approved Proposal]) --> Kill{"Kill Switch Active?"}
    Kill -- "Yes" --> AbortKS["Block 409 KILL_SWITCH_ACTIVE"]
    Kill -- "No" --> Classify["P2 MaterialGraphDeltaClassifier"]
    Classify --> Delta{"Delta Classification"}
    Delta -- "WIDENING / TARGET_CHANGING / FALLBACK_CHANGING / INDETERMINATE" --> Inval["Abort 409 MATERIAL_GRAPH_DELTA_INVALIDATED"]
    Delta -- "IMMATERIAL / NARROWING" --> Bounds{"P3 Signature, Expiry, Audience, Target Allowlist Valid?"}
    Bounds -- "No" --> Block403["Reject 403 TARGET_SUBSTITUTION / AUDIENCE_MISMATCH"]
    Bounds -- "Yes" --> Nonce{"Atomic Single-Use Nonce Insert Succeeded?"}
    Nonce -- "No" --> BlockReplay["Reject 409 NONCE_REPLAY_DETECTED"]
    Nonce -- "Yes" --> RunAdapter["Run ProviderActionAdapter (e.g. KubernetesLocalRollbackAdapter)"]
    RunAdapter --> DAG["Run P6 8-Layer Validation DAG (Infra -> Journey Probe)"]
    DAG --> Mandatory{"All Mandatory Layers Passed?"}
    Mandatory -- "Yes" --> Done["Workflow State = SUCCEEDED"]
    Mandatory -- "No" --> Compensate["Run adapter.compensate() -> COMPENSATED"]
```

---

## AD-07: Controlled Experiment Guardrails & Learning Closure Activity (`S12`, `S13`)
*Runs bounded fault injection inside nonproduction guardrails, automatically aborts if SLOs breach thresholds, and prevents post-incident learning items from closing without verified remediation artifacts.*

```mermaid
flowchart TD
    Exp([Start Controlled Fault Experiment]) --> Cap["Enforce maxBlastRadius & abortSloThreshold"]
    Cap --> Inject["Inject Controlled Fault in Nonproduction"]
    Inject --> CheckSlo{"observedSli < abortSloThreshold?"}
    CheckSlo -- "Yes" --> Abort["Auto-Abort: ABORTED_BY_GUARDRAIL"]
    CheckSlo -- "No" --> Complete["Complete Safely: COMPLETED"]
    Complete --> Learning["Create Post-Incident LearningItem (S12)"]
    Abort --> Learning
    Learning --> CloseAttempt["Attempt to Close LearningItem"]
    CloseAttempt --> HasDigest{"Verified Rule / Runbook / Test SHA-256 Provided?"}
    HasDigest -- "No" --> BlockClose["Block Closure 409 VERIFICATION_REQUIRED (FR-LRN-002)"]
    HasDigest -- "Yes" --> Closed["Mark LearningItem CLOSED + Emit Outbox Event"]
```

---

## AD-08: Solution-Specific Agent Advisors (`S09`) & Quantitative Shadow Isolation
*Demonstrates how AI advisors obey kill switches, prompt-injection firewalls, token caps, and blocking Critic dissent arbitration alongside the non-authoritative Section 10.5 quantitative shadow benchmark.*

```mermaid
flowchart TD
    AdvStart([Request Core Agent Assistance]) --> Enabled{"Advisory Kill Switch Enabled?"}
    Enabled -- "No" --> FallbackCore["Return 409 ADVISORY_DISABLED (Human Core Flow Continues)"]
    Enabled -- "Yes" --> InjectScan{"Matches Prompt Injection Pattern?"}
    InjectScan -- "Yes" --> BlockInj["Block 422 PROMPT_INJECTION_BLOCKED"]
    InjectScan -- "No" --> Budget{"tokensUsed + est <= maxTokenBudget?"}
    Budget -- "No" --> BlockBudget["Reject 429 BUDGET_EXHAUSTED"]
    Budget -- "Yes" --> Draft["Generate Source-Backed Draft + Blocking Critic Dissent"]
    Draft --> Arb["P7 DeterministicArbiter Sorts Proposals Canonically"]
    Arb --> Preserve["Preserve All Dissenting Agent IDs (suppressedAnyDissent = false)"]
    Preserve --> Human["Human Reviews Draft (REJECT / KEEP / EDIT)"]
    Human --> GuardNote["Draft edits never clear dissent or qualify missing probes"]

    QuantStart([Paper Section 10.5 Quantitative Shadow Model]) --> Compute["Compute r(t+1), systemicRisk S(t), RCI(t), EffectiveResilience ER(t)"]
    Compute --> ShadowTag["Publish as SHADOW_NON_AUTHORITATIVE (Never overrides Core Safety Gates)"]
```


---

## Enterprise & Regulatory Compliance Activity Diagrams (`Phase E1–E4 Scope`)

## AD-09: PII/PHI/PAN Scrubbing, Data Residency Routing & WORM Retention (`REG-GDPR`, `REG-HIPAA`)
*Enforces regional residency matching, pre-storage redaction of sensitive personal/payment data, Neo4j prohibited-key checks, and immutable cloud Object Lock retention.*

```mermaid
flowchart TD
    Ingest([Raw Telemetry or Trace Span Received]) --> RegionCheck{"Matches Tenant Residency Region?"}
    RegionCheck -- "No" --> DropCross["Reject Cross-Region Egress (GDPR Residency)"]
    RegionCheck -- "Yes" --> Scrub["Run PII / PHI / PAN / Secret Scrubber"]
    Scrub --> GraphGuard{"Any Prohibited Key Remaining?"}
    GraphGuard -- "Yes" --> BlockGraph["Block Neo4j Write + Log Violation"]
    GraphGuard -- "No" --> StoreS3["Write Payload to In-Region S3 Object Lock (WORM)"]
    StoreS3 --> EraseCheck{"GDPR Deletion Request?"}
    EraseCheck -- "Yes" --> CryptoShred["Destroy Tenant KMS Data Key (Crypto-Shredding)"]
    EraseCheck -- "No" --> Retain["Retain Immutable Audit Window"]
```

---

## AD-10: Scheduled Neo4j Anti-Tamper Drift Reconciliation Activity (`FR-GPH-007`)
*Continuously compares live Neo4j subgraph checksums against authoritative PostgreSQL revisions, halting automated recovery whenever unauthorized graph drift is detected.*

```mermaid
flowchart TD
    Cron([Scheduled Reconciliation Tick in S06]) --> LoadPG["Load Active Subgraph Manifest Digest from PostgreSQL"]
    LoadPG --> QueryNeo["Query Live Node & Edge Digest from Neo4j Cluster"]
    QueryNeo --> Compare{"Digests Match?"}
    Compare -- "Yes" --> Projected["Mark Revision State = PROJECTED"]
    Compare -- "No" --> Drifted["Mark Revision State = DRIFTED & Emit Security Alert"]
    Drifted --> Freeze["Block P2 Graph Delta Approval & S11 Executions Until Re-Reconciled"]
```

---

## AD-11: CI/CD Supply-Chain Security & SSDF / SLSA Gate Activity (`REG-SSDF`, `REG-FEDRAMP`)
*Enforces automated secret scanning, CodeQL SAST, CycloneDX SBOM generation, Trivy vulnerability checks, and Cosign provenance attestation on every build.*

```mermaid
flowchart TD
    Push([Commit / PR to main]) --> Tests["Run Maven Reactor + PG17 & Neo4j 5.26 Testcontainers"]
    Tests --> Secrets["Run Gitleaks Secret Scan"]
    Secrets --> SAST["Run CodeQL Static Analysis"]
    SAST --> SBOM["Generate CycloneDX SBOM & Run Trivy Container CVE Scan"]
    SBOM --> HighCVE{"Critical or High Unpatched CVE Found?"}
    HighCVE -- "Yes" --> FailBuild["Block Pipeline & Open Remediation Ticket"]
    HighCVE -- "No" --> Cosign["Sign Container Image Digest with Cosign & Push Provenance"]
```

---

## AD-12: Multi-Framework Common Control Crosswalk & External Assurance Room Activity (`S14`)
*Assembles cryptographically verified evidence across UACRF services, discloses any missing items explicitly, and publishes time-bound watermarked auditor bundles.*

```mermaid
flowchart TD
    Request([Generate Customer / Auditor Compliance Pack]) --> Select["Select Crosswalk (SOC 2 / ISO 27001 / DORA / ISO 42001 / HIPAA / FedRAMP)"]
    Select --> Gather["Collect Immutable Artifacts from S01, S04, S07 (P1), S09 (P7), S12, CI SSDF"]
    Gather --> Verify{"All Mandatory Signatures & Freshness Valid?"}
    Verify -- "No" --> Disclose["Include Explicit Missing-Control Disclosures (No Synthetic Fallback)"]
    Verify -- "Yes" --> Watermark["Bind Read-Only Watermarked Time-Limited Assurance Room"]
    Disclose --> Watermark
    Watermark --> AuditLog["Record Immutable Auditor Access Trail"]
```


---

## AD-13: Four-Cloud Provider Onboarding, Workload Identity & Cross-Cloud Recovery Selection (`GCP`, `AWS`, `Azure`, `OCI`)
*Binds read-only federated identities across GCP, AWS, Azure, and OCI, monitors connector freshness, and validates failover independence across shared IdP, DNS, and KMS domains.*

```mermaid
flowchart TD
    Connect([Register Cloud Provider Connection in S03]) --> Provider{"Select Provider"}
    Provider -- "GCP" --> BindGCP["Bind Read-Only GCP Service Account & Asset Inventory API"]
    Provider -- "AWS" --> BindAWS["Bind Cross-Account Read-Only IAM Role (Config / CloudWatch)"]
    Provider -- "Azure" --> BindAZ["Bind Read-Only Entra Managed Identity (Resource Graph / Monitor)"]
    Provider -- "OCI" --> BindOCI["Bind Read-Only OCI IAM Dynamic Group / Policy (Search / APM / Cloud Guard)"]

    BindGCP & BindAWS & BindAZ & BindOCI --> Health{"Collection Fresh & Healthy?"}
    Health -- "No" --> Delayed["Flag Connector ATTENTION_NEEDED (Keep Missing Probes UNKNOWN)"]
    Health -- "Yes" --> Graph["Project Nodes & Correlation Domains to Neo4j (58 Labels)"]
    Graph --> PathCheck["Evaluate P5 Primary vs Alternate Cloud Recovery Path"]
    PathCheck --> SharedDom{"Alternate Cloud Shares IdP, DNS, or KMS Domain?"}
    SharedDom -- "Yes" --> NotIndep["Classify NOT_INDEPENDENT (Require Mitigation Plan)"]
    SharedDom -- "No" --> Certified["Certify Cross-Cloud Failover (e.g. GCP <-> AWS <-> OCI <-> Azure)"]
```
