# UACRF Implemented Solution Class Diagrams (`CD-01`–`CD-06`)

> Class architecture diagrams derived directly from the verified Java 25 / Spring Boot 4.1.2 codebase across shared libraries (`uacrf-contracts`, `uacrf-crypto`, `uacrf-outbox`) and all implemented platform components (`S01`–`S14`, `EDGE01`, `UX01`).

---

## CD-01: Shared Contracts, FIPS Cryptographic Integrity & Transactional Outbox
*Shows the core contract records, ontology validator, canonical hashing, FIPS 140-3 key compliance checks, and Postgres-to-Kafka outbox helper shared across all services.*

```mermaid
classDiagram
    direction LR
    class Neo4jOntology {
        <<utility>>
        +requireNodeLabel(label: String)
        +requireRelationshipType(type: String)
        +validatePropertyKeys(props: Map)
    }
    class CanonicalEvidenceEvent {
        <<record>>
        +UUID eventId
        +String provider
        +String subjectCanonicalId
        +String rawDigest
    }
    class BoundedRecoveryAuthorization {
        <<record>>
        +String authorizationId
        +String actionType
        +List~String~ targetCanonicalIds
        +int maximumTargets
        +int maximumTrafficPercentage
        +String audience
        +String nonce
        +Instant expiresAt
    }
    class CanonicalJson {
        <<utility>>
        +toJson(value: Object) String
        +digestObject(value: Object) String
    }
    class EnvelopeSigner {
        -RSAKey signingKey
        -Clock clock
        +sign(issuer, audience, subject, nonce, expiresAt, claims) String
        +verify(token, publicKey, expectedAudience) JWTClaimsSet
    }
    class FipsCryptoProfile {
        <<utility>>
        +int FIPS_MIN_RSA_KEY_BITS = 3072
        +generateKey(keyId, fipsMode) RSAKey
        +assertKeyCompliant(key, fipsMode)
    }
    class GcpOAuthAssertionSupport {
        <<utility>>
        +resolveCredentialsPath(explicit, env, home) Path
        +isUnsafeEgressHost(url) boolean
        +signServiceAccountAssertion(email, pem, scope, aud, iat) String
    }
    class AwsSigV4SignerSupport {
        <<utility>>
        +resolveCredentials(...) AwsCredentials
        +signRequest(method, uri, service, region, headers, body, creds, ts) Map
    }
    class KmsKeyProvider {
        <<interface>>
        +providerName() String
        +fipsValidated() boolean
        +resolveSigningKey(keyId) RSAKey
    }
    class VaultOrCloudKmsProvider {
        -String providerName
        -boolean fipsMode
        +resolveSigningKey(keyId) RSAKey
    }
    class OutboxKafkaRelay {
        +relayBatch(jdbc, producer, topic, limit) int
    }
    class TenantRlsSession {
        <<utility>>
        +bindTenant(jdbc, tenantId)
        +clear(jdbc)
    }

    KmsKeyProvider <|.. VaultOrCloudKmsProvider
    FipsCryptoProfile ..> KmsKeyProvider
    EnvelopeSigner ..> CanonicalJson
```

---

## CD-02: Identity Authority & Catalog / Customer Journey Domain (`S01`, `S02`)
*Illustrates `S01` identity/delegation controllers and `S02` service, agent workload, customer journey, compute placement, and provider alias components.*

```mermaid
classDiagram
    class IdentityController {
        +createPrincipal(request) PrincipalDto
        +submitGrantDecision(request) GrantDecisionDto
        +createMembership(request) MembershipDto
        +exchangeDelegationToken(request) DelegationTokenDto
    }
    class IdentityAuthorityService {
        -IdentityRepository repository
        -EnvelopeSigner signer
        +admitPrincipal(request) PrincipalDto
        +decideGrant(request) GrantDecisionDto
        +grantMembership(request) MembershipDto
        +exchangeDelegation(request) DelegationTokenDto
    }
    class IdentityRepository {
        -JdbcTemplate jdbc
        +insertPrincipal(...)
        +insertGrantDecision(...)
        +consumeGrantAndInsertMembership(...)
    }
    class CatalogController {
        +listServices(...)
        +listAgentWorkloads(...)
        +createCustomerJourney(...)
        +reviewImpactProfile(...)
    }
    class CatalogService {
        -CatalogRepository repository
        +registerService(...)
        +registerAgentWorkload(...)
        +activateAgentVersion(...)
        +submitAndReviewImpactProfile(...)
    }
    class CatalogRepository {
        -JdbcTemplate jdbc
        +insertService(...)
        +insertAgentWorkload(...)
        +insertCustomerJourney(...)
    }

    IdentityController --> IdentityAuthorityService
    IdentityAuthorityService --> IdentityRepository
    IdentityAuthorityService ..> EnvelopeSigner
    CatalogController --> CatalogService
    CatalogService --> CatalogRepository
```

---

## CD-03: Multi-Cloud Discovery, PII Scrubbing, Evidence Qualification & Knowledge Graph (`S03`, `S04`, `S06`)
*Captures read-only multi-cloud collectors, pre-storage PII/PHI scrubbing, S3 Object Lock evidence qualification, and Neo4j projection with drift reconciliation.*

```mermaid
classDiagram
    class TelemetryAdapter {
        <<interface>>
        +providerKey() String
        +telemetrySource() String
        +collectSnapshot(tenantId, envId, scenarioKey) AdapterBatch
    }
    class SampleLocalScenarioGenerator {
        +collectSnapshot(...) AdapterBatch
    }
    class AwsTelemetryAdapter
    class GcpTelemetryAdapter
    class AzureTelemetryAdapter
    class OciTelemetryAdapter
    class OtelReceiverAdapter
    class PiiPhiScrubber {
        +scrubText(input) ScrubResult
        +scrubDimensions(dimensions) Map
    }
    class DiscoveryIngestionService {
        -DiscoveryRepository repository
        -PiiPhiScrubber scrubber
        -CloudInventoryPollerGateway inventoryGateway
        -GcpRuntimeTelemetryPoller gcpRuntimePoller
        -Map~String, TelemetryAdapter~ adapters
        +ingestObservation(req) ObservationDto
        +pollCloudInventory(req) InventoryNormalizationResult
        +pollGcpRuntimeTelemetry(req) RuntimeTelemetryPollResult
        +loadSampleLocalScenario(req) SampleScenarioResult
        +listRegisteredAdapters() List
    }
    class CloudInventoryPollerGateway {
        -AwsCredentialProvider awsCredentialProvider
        -GcpCredentialProvider gcpCredentialProvider
        +pollAndNormalize(provider, scopeFilter) InventoryNormalizationResult
    }
    class AwsCredentialProvider {
        +resolveSigV4Credentials() AwsCredentials
    }
    class AwsRuntimeTelemetryPoller {
        -AwsCredentialProvider credentialProvider
        +poll(req) AwsRuntimeTelemetryPollResult
    }
    class GcpCredentialProvider {
        +resolveAccessToken() String
    }
    class GcpRuntimeTelemetryPoller {
        -GcpCredentialProvider credentialProvider
        +pollRuntimeTelemetry(projectId, filter, windowMinutes) RuntimePollOutcome
    }
    class EvidenceQualificationService {
        -EvidenceRepository repository
        -QualificationEngine engine
        -ObjectLockWormClient s3WormClient
        +submitEvidence(pkgId, req) EvidencePackageDto
        +exportPackage(pkgId) ExportBundleDto
    }
    class QualificationEngine {
        +qualify(itemKey, sourceKind, detail, expectedRevision, ttl) QualificationVerdict
    }
    class ObjectLockWormClient {
        -S3Client s3Client
        +storeWormObject(key, payloadJson, sha256Digest) boolean
    }
    class KnowledgeGraphService {
        -GraphRepository repository
        -Neo4jGraphGateway neo4jGateway
        -GraphDriftReconciler driftReconciler
        +projectEdges(req) ProjectionResult
        +queryBoundedNeighborhood(req) SubgraphDto
    }
    class Neo4jGraphGateway {
        +mergeNodeAndEdge(...)
        +readTopology(...)
    }
    class GraphDriftReconciler {
        +reconcile(tenantId, envId) DriftReport
    }

    TelemetryAdapter <|.. SampleLocalScenarioGenerator
    TelemetryAdapter <|.. AwsTelemetryAdapter
    TelemetryAdapter <|.. GcpTelemetryAdapter
    TelemetryAdapter <|.. AzureTelemetryAdapter
    TelemetryAdapter <|.. OciTelemetryAdapter
    TelemetryAdapter <|.. OtelReceiverAdapter
    DiscoveryIngestionService --> TelemetryAdapter
    DiscoveryIngestionService --> PiiPhiScrubber
    DiscoveryIngestionService --> CloudInventoryPollerGateway
    DiscoveryIngestionService --> GcpRuntimeTelemetryPoller
    CloudInventoryPollerGateway --> GcpCredentialProvider
    GcpRuntimeTelemetryPoller --> GcpCredentialProvider
    GcpRuntimeTelemetryPoller --> PiiPhiScrubber
    EvidenceQualificationService --> QualificationEngine
    EvidenceQualificationService --> ObjectLockWormClient
    KnowledgeGraphService --> Neo4jGraphGateway
    KnowledgeGraphService --> GraphDriftReconciler
    KnowledgeGraphService ..> Neo4jOntology
```

---

## CD-04: Objectives Rules, Path Independence & Sealed Decision Envelopes (`S05`, `S07`, `P1`, `P5`)
*Depicts continuous SLO rule evaluation, upstream conflict checks, correlation-domain path certification, and RS256 decision sealing.*

```mermaid
classDiagram
    class ObjectivesControlService {
        -ObjectivesRepository repository
        -CoreRuleEvaluator ruleEvaluator
        +createObjective(req) ObjectiveDto
        +evaluateRisk(req) RiskEvaluationDto
    }
    class CoreRuleEvaluator {
        +evaluate(sliValues, ttlSeconds, rulePacks) RiskOutcome
    }
    class AssessmentEngineService {
        -AssessmentRepository repository
        -PathIndependenceCertifier certifier
        -EnvelopeSigner signer
        +runAssessment(req) AssessmentDto
        +reviewAssessment(id, req) AssessmentDto
        +sealDecisionEnvelope(id) DecisionEnvelopeDto
        +replayEnvelope(req) ReplayResultDto
    }
    class PathIndependenceCertifier {
        +certify(primaryDomains, alternateDomains) PathCertificationOutcome
    }

    ObjectivesControlService --> CoreRuleEvaluator
    AssessmentEngineService --> PathIndependenceCertifier
    AssessmentEngineService ..> EnvelopeSigner
```

---

## CD-05: Policy Authority, Temporal Recovery Orchestrator & P6 Validation (`S10`, `S11`, `P2`, `P3`, `P6`)
*Maps OPA policy gating, break-glass escalation, `P2` graph delta checks, durable Temporal workflows, multi-cloud compute/traffic adapters, and 8-layer validation.*

```mermaid
classDiagram
    class PolicyAuthorityService {
        -PolicyRepository repository
        -PolicyEvaluator evaluator
        -MaterialGraphDeltaClassifier deltaClassifier
        -EnvelopeSigner signer
        +proposeRecovery(req) RecoveryProposalDto
        +approveProposal(id, req) RecoveryProposalDto
        +issueBoundedAuthorization(id, req) BoundedRecoveryAuthorization
    }
    class PolicyEvaluator {
        +evaluateOpaOrLocal(input) PolicyOutcome
    }
    class MaterialGraphDeltaClassifier {
        +classify(baselineDigest, currentDigest, wideningDetected) DeltaVerdict
    }
    class RecoveryWorkflow {
        <<interface>>
        +executeBoundedRecovery(auth, adapterKey, targetId, version, simulateFailure) WorkflowOutcome
    }
    class RecoveryWorkflowImpl {
        -RecoveryActivities activities
        +executeBoundedRecovery(...) WorkflowOutcome
    }
    class RecoveryActivities {
        <<interface>>
        +executeBoundedAction(...) ActionResult
        +runCrossLayerValidation(simulateFailure) ValidationResult
        +compensateBoundedAction(...) ActionResult
    }
    class RecoveryActivitiesImpl {
        -Map~String, ProviderActionAdapter~ adapters
        -CrossLayerValidator validator
    }
    class RecoveryOrchestrationService {
        -RecoveryRepository repository
        -CrossLayerValidator validator
        -Map~String, ProviderActionAdapter~ adapters
        +executeRecovery(req) RecoveryExecutionDto
    }
    class ProviderActionAdapter {
        <<interface>>
        +adapterKey() String
        +supportsAction(actionType) boolean
        +execute(auth, targetCanonicalId, targetVersion) AdapterOutcome
        +compensate(auth, targetCanonicalId, previousVersion) AdapterOutcome
    }
    class KubernetesLocalRollbackAdapter
    class CloudTrafficFailoverAdapter {
        -GcpLiveRecoveryGateway gcpGateway
    }
    class CloudComputeScalingAdapter {
        -GcpLiveRecoveryGateway gcpGateway
    }
    class GcpLiveRecoveryGateway {
        +isEnabled() boolean
        +executeGcpMutation(auth, targetCanonicalId, targetVersion) String
        +compensateGcpMutation(auth, targetCanonicalId, previousVersion) String
    }
    class AwsLiveRecoveryGateway {
        +isEnabled() boolean
        +executeAwsMutation(auth, targetCanonicalId, targetVersion) String
        +compensateAwsMutation(auth, targetCanonicalId, previousVersion) String
    }
    class CrossLayerValidator {
        +validateExecution(simulateProbeFailure) ValidationOutcome
    }

    PolicyAuthorityService --> PolicyEvaluator
    PolicyAuthorityService --> MaterialGraphDeltaClassifier
    PolicyAuthorityService ..> BoundedRecoveryAuthorization
    RecoveryWorkflow <|.. RecoveryWorkflowImpl
    RecoveryActivities <|.. RecoveryActivitiesImpl
    RecoveryWorkflowImpl --> RecoveryActivities
    RecoveryActivitiesImpl --> ProviderActionAdapter
    RecoveryActivitiesImpl --> CrossLayerValidator
    RecoveryOrchestrationService --> ProviderActionAdapter
    RecoveryOrchestrationService --> CrossLayerValidator
    RecoveryOrchestrationService --> GcpLiveRecoveryGateway
    RecoveryOrchestrationService --> AwsLiveRecoveryGateway
    ProviderActionAdapter <|.. KubernetesLocalRollbackAdapter
    ProviderActionAdapter <|.. CloudTrafficFailoverAdapter
    ProviderActionAdapter <|.. CloudComputeScalingAdapter
    CloudTrafficFailoverAdapter --> GcpLiveRecoveryGateway
    CloudTrafficFailoverAdapter --> AwsLiveRecoveryGateway
    CloudComputeScalingAdapter --> GcpLiveRecoveryGateway
    CloudComputeScalingAdapter --> AwsLiveRecoveryGateway
```

---

## CD-06: Knowledge, Agent Advisors, Commercial Reporting & Edge/UI (`S08`, `S09`, `S12`, `S13`, `S14`, `EDGE01`, `UX01`)
*Shows ML model promotion, quantitative benchmarking, multi-provider LLM advisors (`OpenAI`/`Bedrock`/`Azure`/`Vertex`/`OCI`), agent runtime ledgers, `S14` assurance/FinOps reporting, and edge/portal wiring.*

```mermaid
classDiagram
    class KnowledgeGovernanceService {
        -KnowledgeRepository repository
        +publishArticle(...)
        +closeLearningItem(id, req)
        +runFaultExperiment(req)
    }
    class QuantitativeShadowEngine {
        +simulateTrajectory(params) ShadowModelResult
    }
    class AdvisoryAgentService {
        -AdvisoryRepository repository
        -DeterministicArbiter arbiter
        -EnterpriseLlmGateway llmGateway
        +runAdvisory(req) AdvisoryFindingDto
        +reviewFinding(id, req) List
        +arbitrate(proposals) ArbitratedOutcome
    }
    class DeterministicArbiter {
        +arbitrate(proposals) ArbitratedOutcome
    }
    class EnterpriseLlmGateway {
        -GcpVertexCredentialResolver gcpVertexCredentialResolver
        +scrubOutboundPrompt(prompt) String
        +validateSafeEndpoint(url)
        +synthesize(queryPrompt, contextRef) SynthesisOutcome
    }
    class GcpVertexCredentialResolver {
        +resolveAccessToken(explicitToken) String
    }
    class OrchestrationRunService {
        +admitRun(req) OrchestrationRunDto
        +recordStepCheckpoint(runId, req) OrchestrationRunDto
        +cancelRun(runId, reason) OrchestrationRunDto
        +resumeFromCheckpoint(runId, req) OrchestrationRunDto
    }
    class ComplianceAssuranceService {
        -ReportingRepository repository
        +recordMetering(req) MeteringEventDto
        +getComplianceCrosswalk(framework) List
        +createAssuranceRoom(req) CreatedAssuranceRoomResponse
        +accessAssuranceBundle(roomId, token) AssuranceEvidenceBundleDto
    }
    class EdgeSecurityConfig {
        +securityFilterChain(http) SecurityFilterChain
    }
    class EdgeProxyController {
        +routeToIdentity(...)
        +routeToCatalog(...)
        +routeToDiscovery(...)
        +routeToEvidence(...)
        +routeToGraph(...)
        +routeToAssessment(...)
        +routeToObjectives(...)
        +routeToPolicy(...)
        +routeToRecovery(...)
        +routeToKnowledge(...)
        +routeToAdvisory(...)
        +routeToReporting(...)
    }
    class PortalController {
        -UacrfEdgeClient edge
        +overview(...)
        +journeys(...)
        +agents(...)
        +assess(...)
        +control(...)
        +recover(...)
        +evidence(...)
        +knowledge(...)
        +advisory(...)
        +assurance(...)
    }

    AdvisoryAgentService --> DeterministicArbiter
    AdvisoryAgentService --> EnterpriseLlmGateway
    EnterpriseLlmGateway --> GcpVertexCredentialResolver
    EdgeProxyController ..> OrchestrationRunService
    PortalController --> UacrfEdgeClient
    UacrfEdgeClient ..> EdgeProxyController
    EdgeSecurityConfig ..> EdgeProxyController
    EdgeProxyController ..> ComplianceAssuranceService
    EdgeProxyController ..> AdvisoryAgentService
    EdgeProxyController ..> KnowledgeGovernanceService
    KnowledgeGovernanceService ..> QuantitativeShadowEngine
```
