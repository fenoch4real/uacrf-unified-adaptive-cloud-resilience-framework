package io.uacrf.recovery.temporal;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.recovery.adapter.ProviderActionAdapter;
import io.uacrf.recovery.service.CrossLayerValidator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class RecoveryActivitiesImpl implements RecoveryActivities {

    private final Map<String, ProviderActionAdapter> adapters;
    private final CrossLayerValidator validator;

    public RecoveryActivitiesImpl(List<ProviderActionAdapter> adapterList, CrossLayerValidator validator) {
        this.adapters = adapterList.stream().collect(Collectors.toMap(
                ProviderActionAdapter::adapterKey, Function.identity(), (a, b) -> a));
        this.validator = validator;
    }

    @Override
    public ActionResult executeBoundedAction(
            BoundedRecoveryAuthorization authorization,
            String adapterKey,
            String targetCanonicalId,
            String targetVersion) {
        var adapter = adapters.get(adapterKey);
        if (adapter == null) {
            return new ActionResult(false, "Unknown adapter key: " + adapterKey);
        }
        var outcome = adapter.execute(authorization, targetCanonicalId, targetVersion);
        return new ActionResult(outcome.succeeded(), outcome.summary());
    }

    @Override
    public ValidationResult runCrossLayerValidation(boolean simulateProbeFailure) {
        var result = validator.validateExecution(simulateProbeFailure);
        List<String> stepSummaries = result.steps().stream()
                .map(s -> s.layer() + ":" + s.probeName() + "=" + s.passed())
                .toList();
        return new ValidationResult(result.passed(), stepSummaries);
    }

    @Override
    public ActionResult compensateBoundedAction(
            BoundedRecoveryAuthorization authorization,
            String adapterKey,
            String targetCanonicalId,
            String previousVersion) {
        var adapter = adapters.get(adapterKey);
        if (adapter == null) {
            return new ActionResult(false, "Compensation adapter missing: " + adapterKey);
        }
        var outcome = adapter.compensate(authorization, targetCanonicalId, previousVersion);
        return new ActionResult(outcome.succeeded(), outcome.summary());
    }
}
