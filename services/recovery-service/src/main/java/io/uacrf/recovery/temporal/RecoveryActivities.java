package io.uacrf.recovery.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;

import java.util.List;

@ActivityInterface
public interface RecoveryActivities {

    @ActivityMethod
    ActionResult executeBoundedAction(
            BoundedRecoveryAuthorization authorization,
            String adapterKey,
            String targetCanonicalId,
            String targetVersion
    );

    @ActivityMethod
    ValidationResult runCrossLayerValidation(boolean simulateProbeFailure);

    @ActivityMethod
    ActionResult compensateBoundedAction(
            BoundedRecoveryAuthorization authorization,
            String adapterKey,
            String targetCanonicalId,
            String previousVersion
    );

    record ActionResult(boolean succeeded, String summary) {
    }

    record ValidationResult(boolean passed, List<String> steps) {
    }
}
