package io.uacrf.recovery.api;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RecoveryDtos {

    private RecoveryDtos() {
    }

    public record ValidationStepResult(
            String layer,
            String probeName,
            boolean mandatory,
            boolean passed,
            String detail
    ) {
    }

    public record KillSwitchDto(
            UUID tenantId,
            UUID environmentId,
            boolean active,
            String reason,
            String updatedBy,
            Instant updatedAt
    ) {
    }

    public record UpdateKillSwitchRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            boolean active,
            @NotBlank String reason,
            @NotBlank String updatedBy
    ) {
    }

    public record LiveExecutionPolicyDto(
            UUID tenantId,
            UUID environmentId,
            boolean tenantToggleEnabled,
            boolean serverConfigEnabled,
            boolean gcpAdapterEnabled,
            boolean awsAdapterEnabled,
            String effectiveMode,
            String reason,
            String updatedBy,
            Instant updatedAt
    ) {
    }

    public record UpdateLiveExecutionPolicyRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            boolean enabled,
            @NotBlank String reason,
            @NotBlank String updatedBy
    ) {
    }

    public record ExecuteRecoveryRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotNull BoundedRecoveryAuthorization authorization,
            @NotBlank String requestedTargetCanonicalId,
            @NotBlank String requestedTargetVersion,
            @NotBlank String adapterKey,
            @NotBlank String executedBy,
            boolean simulateProbeFailure
    ) {
    }

    public record RecoveryExecutionDto(
            UUID executionId,
            UUID tenantId,
            UUID environmentId,
            UUID authorizationId,
            String nonce,
            String serviceCanonicalId,
            String actionType,
            String adapterKey,
            String targetVersion,
            String workflowState,
            String adapterOutput,
            List<ValidationStepResult> validationSteps,
            boolean compensated,
            String executedBy,
            Instant startedAt,
            Instant completedAt
    ) {
    }
}
