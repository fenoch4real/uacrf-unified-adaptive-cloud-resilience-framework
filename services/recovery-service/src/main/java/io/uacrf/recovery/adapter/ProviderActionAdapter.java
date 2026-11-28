package io.uacrf.recovery.adapter;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;

/**
 * Allowlisted Provider Action Adapter SPI (P3).
 * New cloud/runtime targets (AWS ECS, GCP Cloud Run, Azure Container Apps) implement this interface
 * without changing Temporal state orchestration or validation DAG logic.
 */
public interface ProviderActionAdapter {

    String adapterKey();

    boolean supportsAction(String actionType);

    AdapterOutcome execute(BoundedRecoveryAuthorization auth, String targetCanonicalId, String targetVersion);

    AdapterOutcome compensate(BoundedRecoveryAuthorization auth, String targetCanonicalId, String previousVersion);

    default AdapterOutcome execute(BoundedRecoveryAuthorization auth, String targetCanonicalId, String targetVersion, boolean liveExecution) {
        if (!liveExecution) {
            var dry = execute(auth, targetCanonicalId, targetVersion);
            return new AdapterOutcome(dry.succeeded(), "[DRY_RUN_SIMULATED] " + dry.summary());
        }
        return execute(auth, targetCanonicalId, targetVersion);
    }

    default AdapterOutcome compensate(BoundedRecoveryAuthorization auth, String targetCanonicalId, String previousVersion, boolean liveExecution) {
        if (!liveExecution) {
            var dry = compensate(auth, targetCanonicalId, previousVersion);
            return new AdapterOutcome(dry.succeeded(), "[DRY_RUN_SIMULATED] " + dry.summary());
        }
        return compensate(auth, targetCanonicalId, previousVersion);
    }

    record AdapterOutcome(boolean succeeded, String summary) {
    }
}
