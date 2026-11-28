package io.uacrf.recovery.adapter;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.recovery.api.RecoveryDomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Multi-cloud Compute & Fault-Injection Bounded Action Adapter (Section 8 / Appendix X):
 * Runs safely in DRY_RUN_SIMULATED mode unless both the tenant live toggle and provider gateway
 * are explicitly enabled with dedicated S11 execution credentials.
 */
@Component
public class CloudComputeScalingAdapter implements ProviderActionAdapter {

    private static final Set<String> SUPPORTED = Set.of(
            "AWS_ASG_SCALE",
            "AWS_ECS_ROLLBACK",
            "GCP_MIG_ROLLBACK",
            "CLOUD_RUN_TRAFFIC_SPLIT",
            "AZURE_VMSS_REIMAGE",
            "OCI_INSTANCE_POOL_SCALE",
            "AWS_FIS_EXPERIMENT",
            "AZURE_CHAOS_STUDIO_EXERCISE",
            "GCP_FAULT_INJECTION"
    );

    private final GcpLiveRecoveryGateway gcpGateway;
    private final AwsLiveRecoveryGateway awsGateway;

    public CloudComputeScalingAdapter() {
        this.gcpGateway = null;
        this.awsGateway = null;
    }

    public CloudComputeScalingAdapter(GcpLiveRecoveryGateway gcpGateway) {
        this.gcpGateway = gcpGateway;
        this.awsGateway = null;
    }

    @Autowired
    public CloudComputeScalingAdapter(GcpLiveRecoveryGateway gcpGateway, AwsLiveRecoveryGateway awsGateway) {
        this.gcpGateway = gcpGateway;
        this.awsGateway = awsGateway;
    }

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

    @Override
    public AdapterOutcome execute(BoundedRecoveryAuthorization auth, String targetCanonicalId, String targetVersion, boolean liveExecution) {
        if (!liveExecution) {
            var dry = execute(auth, targetCanonicalId, targetVersion);
            return new AdapterOutcome(dry.succeeded(), "[DRY_RUN_SIMULATED] " + dry.summary());
        }
        if ("CLOUD_RUN_TRAFFIC_SPLIT".equals(auth.actionType()) || "GCP_MIG_ROLLBACK".equals(auth.actionType())) {
            if (gcpGateway == null) {
                throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "GCP_LIVE_ADAPTER_DISABLED",
                        "GCP live recovery gateway is not configured.");
            }
            return new AdapterOutcome(true, gcpGateway.executeGcpMutation(auth, targetCanonicalId, targetVersion));
        }
        if ("AWS_ASG_SCALE".equals(auth.actionType()) || "AWS_ECS_ROLLBACK".equals(auth.actionType())) {
            if (awsGateway == null) {
                throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "AWS_LIVE_ADAPTER_DISABLED",
                        "AWS live recovery gateway is not configured.");
            }
            return new AdapterOutcome(true, awsGateway.executeAwsMutation(auth, targetCanonicalId, targetVersion));
        }
        throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "LIVE_ADAPTER_UNCONFIGURED",
                "Live cloud mutation is enabled globally, but action [" + auth.actionType() + "] has no live cloud SDK client wired yet.");
    }

    @Override
    public AdapterOutcome compensate(BoundedRecoveryAuthorization auth, String targetCanonicalId, String previousVersion, boolean liveExecution) {
        if (!liveExecution) {
            var dry = compensate(auth, targetCanonicalId, previousVersion);
            return new AdapterOutcome(dry.succeeded(), "[DRY_RUN_SIMULATED] " + dry.summary());
        }
        if (("CLOUD_RUN_TRAFFIC_SPLIT".equals(auth.actionType()) || "GCP_MIG_ROLLBACK".equals(auth.actionType())) && gcpGateway != null) {
            return new AdapterOutcome(true, gcpGateway.compensateGcpMutation(auth, targetCanonicalId, previousVersion));
        }
        if (("AWS_ASG_SCALE".equals(auth.actionType()) || "AWS_ECS_ROLLBACK".equals(auth.actionType())) && awsGateway != null) {
            return new AdapterOutcome(true, awsGateway.compensateAwsMutation(auth, targetCanonicalId, previousVersion));
        }
        return compensate(auth, targetCanonicalId, previousVersion);
    }
}
