package io.uacrf.contracts.graph;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Canonical Neo4j ontology enforcing the exact 58 domain node labels and 55 controlled relationship types
 * defined in the UACRF Graph Schema Reference (v1.0 alignment revision 7).
 */
public final class Neo4jOntology {

    private static final Set<String> PROHIBITED_PROPERTY_KEYS = Set.of(
            "secret", "secret_value", "password", "private_key", "raw_log_body",
            "unrestricted_prompt", "ssn", "credit_card"
    );

    private Neo4jOntology() {
    }

    public enum NodeLabel {
        // Evidence and tenancy (5)
        Tenant, Environment, EvidenceSource, EvidenceEvent, EvidenceQualification,
        // Infrastructure and location (10)
        CloudProvider, Account, Region, AvailabilityZone, FailureDomain,
        Cluster, ComputeNode, CloudResource, Network, DNSService,
        // Service and runtime (8)
        Service, ApplicationComponent, Deployment, API, Queue,
        Database, DataStore, ExternalDependency,
        // Identity ownership and control (7)
        Team, Identity, IdentityProvider, EncryptionKey, Control, Policy, SLO,
        // AI and data (6)
        AIModel, ModelVersion, ModelEndpoint, FeatureStore, Dataset, ModelFallback,
        // External context (2)
        ExternalProvider, Location,
        // Customer and business (6)
        CustomerJourney, JourneyStep, CustomerSegment, EssentialFunction, Impact, Outcome,
        // Recovery and action (7)
        Runbook, RecoveryPath, CandidateAction, Authorization, Execution, ValidationPlan, ValidationResult,
        // Decision provenance (2)
        GraphRevision, DecisionEnvelope,
        // Collaborative agents (5)
        AgentAnalysisCase, AgentAssignment, AgentProposal, AgentDissentRecord, ArbitrationRecord
    }

    public enum RelationshipType {
        // Evidence (6)
        EMITS, DESCRIBES_STATE_OF, QUALIFIED_BY, CORRELATED_WITH, SUPPORTED_BY_EVIDENCE, CONTRADICTED_BY,
        // Versioning (1)
        SUPERSEDES,
        // Runtime dependency (3)
        DEPENDS_ON, ROUTES_TO, CALLS,
        // Data dependency (2)
        READS_FROM, WRITES_TO,
        // Messaging (2)
        PUBLISHES_TO, CONSUMES_FROM,
        // Deployment (5)
        RUNS_ON, DEPLOYED_IN, LOCATED_IN, INSTANCE_OF, IMPLEMENTS,
        // Structure (1)
        PART_OF,
        // Failure domain (1)
        MEMBER_OF_FAILURE_DOMAIN,
        // Resilience (2)
        REPLICATES_TO, FALLBACK_TO,
        // AI (5)
        USES_MODEL, SERVES_MODEL, USES_FEATURES_FROM, TRAINED_FROM, USES_DATASET,
        // Security (3)
        USES_IDENTITY, USES_KEY, USES_DNS,
        // Governance (2)
        OWNED_BY, PROTECTED_BY,
        // External (1)
        PROVIDED_BY,
        // Customer (4)
        SUPPORTS_STEP, PART_OF_JOURNEY, ENABLES_FUNCTION, SERVES_SEGMENT,
        // Objective (1)
        GOVERNED_BY_SLO,
        // Impact (3)
        AFFECTS, CONTRIBUTES_TO, MEASURED_BY,
        // Propagation (1)
        FAILURE_PROPAGATES_TO,
        // Recovery (5)
        TARGETED_BY_ACTION, AUTHORIZED_BY, EXECUTES, USES_RUNBOOK, USES_RECOVERY_PATH,
        // Validation (1)
        VALIDATED_BY,
        // Provenance (2)
        BOUND_TO_REVISION, RECORDED_IN,
        // Agents (4)
        ASSIGNED_TO, PRODUCES_PROPOSAL, RAISES_DISSENT, ARBITRATED_BY
    }

    private static final Map<String, NodeLabel> LABELS_BY_NAME = Arrays.stream(NodeLabel.values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    private static final Map<String, RelationshipType> RELS_BY_NAME = Arrays.stream(RelationshipType.values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    public static NodeLabel requireNodeLabel(String rawLabel) {
        NodeLabel label = rawLabel == null ? null : LABELS_BY_NAME.get(rawLabel);
        if (label == null) {
            throw new IllegalArgumentException("Unsupported Neo4j node label outside canonical 58-label ontology: " + rawLabel);
        }
        return label;
    }

    public static RelationshipType requireRelationshipType(String rawType) {
        RelationshipType type = rawType == null ? null : RELS_BY_NAME.get(rawType);
        if (type == null) {
            throw new IllegalArgumentException("Unsupported Neo4j relationship type outside canonical 55-type vocabulary: " + rawType);
        }
        return type;
    }

    /**
     * Rejects prohibited sensitive/bulk property keys before graph persistence (FR-GPH-005, NFR-SEC-002).
     */
    public static void validatePropertyKeys(Set<String> propertyKeys) {
        if (propertyKeys == null) {
            return;
        }
        for (String key : propertyKeys) {
            if (key != null && PROHIBITED_PROPERTY_KEYS.contains(key.toLowerCase())) {
                throw new IllegalArgumentException("Prohibited sensitive/bulk property in graph node or edge: " + key);
            }
        }
    }
}
