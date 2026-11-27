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

    record AdapterOutcome(boolean succeeded, String summary) {
    }
}
