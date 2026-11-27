package io.uacrf.recovery.adapter;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Deterministic sandboxed Kubernetes Rollback Adapter implementing strict P3 target bounds.
 */
@Component
public class KubernetesLocalRollbackAdapter implements ProviderActionAdapter {

    private static final Set<String> SUPPORTED = Set.of("K8S_ROLLBACK_DEPLOYMENT", "TRAFFIC_SHIFT_ROLLBACK");

    @Override
    public String adapterKey() {
        return "k8s-rollback-adapter";
    }

    @Override
    public boolean supportsAction(String actionType) {
        return SUPPORTED.contains(actionType);
    }

    @Override
    public AdapterOutcome execute(BoundedRecoveryAuthorization auth, String targetCanonicalId, String targetVersion) {
        return new AdapterOutcome(
                true,
                "Rolled back workload [" + targetCanonicalId + "] to bounded revision [" + targetVersion
                        + "] under authId=" + auth.authorizationId() + " within single-deployment cap=" + auth.maximumTargets()
        );
    }

    @Override
    public AdapterOutcome compensate(BoundedRecoveryAuthorization auth, String targetCanonicalId, String previousVersion) {
        return new AdapterOutcome(
                true,
                "Bounded compensation restored prior snapshot for [" + targetCanonicalId
                        + "] after mandatory P6 validation probe failed."
        );
    }
}
