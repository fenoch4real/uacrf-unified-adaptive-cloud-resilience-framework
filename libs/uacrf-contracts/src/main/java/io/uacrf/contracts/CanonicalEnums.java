package io.uacrf.contracts;

/**
 * Canonical enumerations shared across UACRF APIs, rules, graph projections, and evidence records.
 */
public final class CanonicalEnums {

    private CanonicalEnums() {
    }

    public enum RiskState {
        NORMAL, GUARDED, ELEVATED, CRITICAL, UNKNOWN
    }

    public enum EvidenceQualificationState {
        QUALIFIED, PROVISIONAL, UNKNOWN, DISQUALIFIED
    }

    public enum DependencyCriticality {
        MANDATORY, DEGRADABLE, OPTIONAL, UNKNOWN
    }

    public enum RecoveryPathStatus {
        CERTIFIED, CONDITIONALLY_CERTIFIED, NOT_INDEPENDENT, UNKNOWN
    }

    public enum ImpactStatus {
        OBSERVED, ESTIMATED, FORECAST, BOUNDED, UNKNOWN, INCOMPLETE
    }

    public enum PolicyDecisionOutcome {
        DENY, RECOMMEND, APPROVAL_REQUIRED, PREAUTHORIZED
    }

    public enum ProducerType {
        HUMAN, SYSTEM, AGENT
    }

    public enum WorkloadKind {
        SINGLE_AGENT, MULTI_AGENT
    }

    public enum GraphDeltaClassification {
        IMMATERIAL,
        NARROWING,
        WIDENING,
        TARGET_CHANGING,
        FALLBACK_CHANGING,
        VALIDATION_CHANGING,
        INDETERMINATE
    }
}
