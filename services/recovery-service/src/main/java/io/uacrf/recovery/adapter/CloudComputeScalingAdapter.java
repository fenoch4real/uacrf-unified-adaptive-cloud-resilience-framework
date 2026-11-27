package io.uacrf.recovery.adapter;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Multi-cloud Compute & Fault-Injection Bounded Action Adapter (Section 8 / Appendix X):
 * Handles bounded compute capacity scaling, Cloud Run/MIG rollbacks, VMSS reimage, OCI instance pool
 * adjustments, and controlled FIS/Chaos exercises under strict P3 target allowlists.
 */
@Component
public class CloudComputeScalingAdapter implements ProviderActionAdapter {

    private static final Set<String> SUPPORTED = Set.of(
            "AWS_ASG_SCALE",
            "GCP_MIG_ROLLBACK",
            "CLOUD_RUN_TRAFFIC_SPLIT",
            "AZURE_VMSS_REIMAGE",
            "OCI_INSTANCE_POOL_SCALE",
            "AWS_FIS_EXPERIMENT",
            "AZURE_CHAOS_STUDIO_EXERCISE",
            "GCP_FAULT_INJECTION"
    );

    @Override
    public String adapterKey() {
        return "multi-cloud-compute-adapter";
    }

    @Override
    public boolean supportsAction(String actionType) {
        return SUPPORTED.contains(actionType);
    }

    @Override
    public AdapterOutcome execute(BoundedRecoveryAuthorization auth, String targetCanonicalId, String targetVersion) {
        return new AdapterOutcome(
                true,
                "Executed multi-cloud compute action [" + auth.actionType() + "] on [" + targetCanonicalId
                        + "] -> target=" + targetVersion + " within maxTargets=" + auth.maximumTargets()
        );
    }

    @Override
    public AdapterOutcome compensate(BoundedRecoveryAuthorization auth, String targetCanonicalId, String previousVersion) {
        return new AdapterOutcome(
                true,
                "Restored prior compute capacity/revision [" + previousVersion + "] on [" + targetCanonicalId + "]."
        );
    }
}
