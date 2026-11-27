package io.uacrf.recovery.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;

import java.time.Duration;

public class RecoveryWorkflowImpl implements RecoveryWorkflow {

    private final RecoveryActivities activities = Workflow.newActivityStub(
            RecoveryActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build()
    );

    @Override
    public WorkflowOutcome executeBoundedRecovery(
            BoundedRecoveryAuthorization authorization,
            String adapterKey,
            String targetCanonicalId,
            String targetVersion,
            boolean simulateProbeFailure) {
        var action = activities.executeBoundedAction(authorization, adapterKey, targetCanonicalId, targetVersion);
        if (!action.succeeded()) {
            return new WorkflowOutcome("ESCALATED", false, action.summary(), java.util.List.of());
        }

        var validation = activities.runCrossLayerValidation(simulateProbeFailure);
        if (!validation.passed()) {
            var compensation = activities.compensateBoundedAction(authorization, adapterKey, targetCanonicalId, "baseline");
            String state = compensation.succeeded() ? "COMPENSATED" : "ESCALATED";
            return new WorkflowOutcome(state, compensation.succeeded(),
                    action.summary() + " | " + compensation.summary(), validation.steps());
        }
        return new WorkflowOutcome("SUCCEEDED", false, action.summary(), validation.steps());
    }
}
