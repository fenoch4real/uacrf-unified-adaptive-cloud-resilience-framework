package io.uacrf.reporting.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReportingDtos {

    private ReportingDtos() {
    }

    public record RecordMeteringRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String meterDimension,
            @NotBlank String cloudProvider,
            @Min(1) long quantity,
            @NotBlank String unit
    ) {
    }

    public record MeteringEventDto(
            UUID meterId,
            UUID tenantId,
            UUID environmentId,
            String meterDimension,
            String cloudProvider,
            long quantity,
            String unit,
            String eventDigest,
            Instant recordedAt
    ) {
    }

    public record ComplianceControlMapping(
            String controlId,
            String framework,
            String domain,
            String uacrfEnforcementMechanism,
            String technicalStatus,
            String evidenceSource
    ) {
    }

    public record CreateAssuranceRoomRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String auditorOrganization,
            @NotBlank String recipientEmail,
            @NotEmpty List<String> frameworks,
            @Min(1) int ttlHours,
            @NotBlank String createdBy
    ) {
    }

    public record CreatedAssuranceRoomResponse(
            UUID roomId,
            UUID tenantId,
            UUID environmentId,
            String auditorOrganization,
            List<String> frameworks,
            String watermarkText,
            String bundleSha256,
            String oneTimeAccessToken,
            String status,
            Instant expiresAt,
            Instant createdAt
    ) {
    }

    public record AssuranceRoomSummaryDto(
            UUID roomId,
            UUID tenantId,
            UUID environmentId,
            String auditorOrganization,
            String recipientEmail,
            List<String> frameworks,
            String watermarkText,
            String bundleSha256,
            String status,
            String createdBy,
            Instant expiresAt,
            Instant createdAt
    ) {
    }

    public record AssuranceEvidenceBundleDto(
            UUID roomId,
            String watermarkText,
            String auditorOrganization,
            List<String> frameworks,
            String bundleSha256,
            List<ComplianceControlMapping> controls,
            List<MeteringEventDto> meteringSnapshot,
            Instant generatedAt,
            Instant expiresAt
    ) {
    }
}
