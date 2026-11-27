package io.uacrf.recovery.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;

import java.util.List;

/**
 * Durable Temporal workflow contract orchestrating P3 bounded recovery action, P6 cross-layer validation,
 * and automatic bounded saga compensation upon probe failure.
 */
@WorkflowInterface
public interface RecoveryWorkflow {

    @WorkflowMethod
    WorkflowOutcome executeBoundedRecovery(
            BoundedRecoveryAuthorization authorization,
            String adapterKey,
            String targetCanonicalId,
            String targetVersion,
            boolean simulateProbeFailure
    );

    record WorkflowOutcome(
            String workflowState,
            boolean compensated,
            String summary,
            List<String> validationSteps
    ) {
    }
}
