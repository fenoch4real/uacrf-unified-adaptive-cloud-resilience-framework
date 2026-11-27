package io.uacrf.objectives.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ObjectivesDtos {

    private ObjectivesDtos() {
    }

    public record ObjectiveDto(
            UUID objectiveId,
            UUID tenantId,
            UUID environmentId,
            String serviceCanonicalId,
            String journeyName,
            String sliName,
            double sloTarget,
            String unit,
            int rtoMinutes,
            int rpoMinutes,
            String rulePackVersion
    ) {
    }

    public record TriggeredRuleDto(
            String ruleId,
            String layer,
            String severity,
            String detail
    ) {
    }

    public record TimelineEventDto(
            String time,
            String title,
            String detail
    ) {
    }

    public record RiskEvaluationDto(
            UUID evaluationId,
            UUID tenantId,
            UUID environmentId,
            String serviceCanonicalId,
            String serviceName,
            String journeyName,
            String ownerName,
            String riskState,
            double observedSliValue,
            String deltaVsBaseline,
            List<TriggeredRuleDto> triggeredRules,
            String explanationSummary,
            List<TimelineEventDto> timeline,
            Instant evaluatedAt
    ) {
    }

    public record EvaluateRiskRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String serviceCanonicalId,
            @NotBlank String serviceName,
            @NotBlank String journeyName,
            @NotBlank String ownerName,
            Double orderSuccessRatePct,
            Double cpuSaturationPct,
            Boolean securityAlertActive,
            Boolean aiSafetyDriftDetected,
            Boolean telemetryStale
    ) {
    }
}
